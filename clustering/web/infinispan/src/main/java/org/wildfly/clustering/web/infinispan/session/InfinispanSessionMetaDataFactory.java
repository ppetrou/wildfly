/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.clustering.web.infinispan.session;

import org.infinispan.Cache;
import org.infinispan.context.Flag;
import org.wildfly.clustering.ee.infinispan.CacheEntryMutator;
import org.wildfly.clustering.ee.infinispan.Mutator;
import org.wildfly.clustering.infinispan.spi.distribution.Key;
import org.wildfly.clustering.web.session.ImmutableSessionMetaData;

/**
 * @author Paul Ferraro
 */
public class InfinispanSessionMetaDataFactory<L> implements SessionMetaDataFactory<InfinispanSessionMetaData<L>, L> {

    private final Cache<SessionCreationMetaDataKey, SessionCreationMetaDataEntry<L>> creationMetaDataCache;
    private final Cache<SessionCreationMetaDataKey, SessionCreationMetaDataEntry<L>> findCreationMetaDataCache;
    private final Cache<SessionAccessMetaDataKey, SessionAccessMetaData> accessMetaDataCache;
    private final boolean transactional;
    private final boolean lockOnWrite;

    @SuppressWarnings("unchecked")
    public InfinispanSessionMetaDataFactory(Cache<? extends Key<String>, ?> cache, boolean transactional, boolean lockOnRead, boolean lockOnWrite) {
        this.creationMetaDataCache = (Cache<SessionCreationMetaDataKey, SessionCreationMetaDataEntry<L>>) cache;
        this.findCreationMetaDataCache = lockOnRead ? this.creationMetaDataCache.getAdvancedCache().withFlags(Flag.FORCE_WRITE_LOCK) : this.creationMetaDataCache;
        this.accessMetaDataCache = (Cache<SessionAccessMetaDataKey, SessionAccessMetaData>) cache;
        this.transactional = transactional;
        this.lockOnWrite = lockOnWrite;
    }

    @Override
    public InfinispanSessionMetaData<L> createValue(String id, Void context) {
        SessionCreationMetaDataEntry<L> creationMetaDataEntry = this.creationMetaDataCache.getAdvancedCache().withFlags(Flag.FORCE_SYNCHRONOUS).computeIfAbsent(new SessionCreationMetaDataKey(id), key -> new SessionCreationMetaDataEntry<>(new SimpleSessionCreationMetaData()));
        SessionAccessMetaData accessMetaData = this.accessMetaDataCache.getAdvancedCache().withFlags(Flag.FORCE_SYNCHRONOUS).computeIfAbsent(new SessionAccessMetaDataKey(id), key -> new SimpleSessionAccessMetaData());
        return new InfinispanSessionMetaData<>(creationMetaDataEntry.getMetaData(), accessMetaData, creationMetaDataEntry.getLocalContext());
    }

    @Override
    public InfinispanSessionMetaData<L> findValue(String id) {
        return this.getValue(id, this.findCreationMetaDataCache);
    }

    @Override
    public InfinispanSessionMetaData<L> tryValue(String id) {
        return this.getValue(id, this.findCreationMetaDataCache.getAdvancedCache().withFlags(Flag.ZERO_LOCK_ACQUISITION_TIMEOUT, Flag.FAIL_SILENTLY));
    }

    private InfinispanSessionMetaData<L> getValue(String id, Cache<SessionCreationMetaDataKey, SessionCreationMetaDataEntry<L>> creationMetaDataCache) {
        SessionCreationMetaDataKey key = new SessionCreationMetaDataKey(id);
        SessionCreationMetaDataEntry<L> creationMetaDataEntry = creationMetaDataCache.get(key);
        if (creationMetaDataEntry != null) {
            SessionAccessMetaData accessMetaData = this.accessMetaDataCache.get(new SessionAccessMetaDataKey(id));
            if (accessMetaData != null) {
                return new InfinispanSessionMetaData<>(creationMetaDataEntry.getMetaData(), accessMetaData, creationMetaDataEntry.getLocalContext());
            }
            // Purge orphaned entry, making sure not to trigger cache listener
            creationMetaDataCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES, Flag.SKIP_LISTENER_NOTIFICATION).remove(key);
        }
        return null;
    }

    @Override
    public InvalidatableSessionMetaData createSessionMetaData(String id, InfinispanSessionMetaData<L> entry) {
        SessionCreationMetaDataKey creationMetaDataKey = new SessionCreationMetaDataKey(id);
        Mutator creationMutator = this.transactional && this.creationMetaDataCache.getAdvancedCache().getCacheEntry(creationMetaDataKey).isCreated() ? Mutator.PASSIVE : new CacheEntryMutator<>(this.creationMetaDataCache, creationMetaDataKey, new SessionCreationMetaDataEntry<>(entry.getCreationMetaData(), entry.getLocalContext()));
        SessionCreationMetaData creationMetaData = new MutableSessionCreationMetaData(entry.getCreationMetaData(), creationMutator);

        SessionAccessMetaDataKey accessMetaDataKey = new SessionAccessMetaDataKey(id);
        Mutator accessMutator = this.transactional && this.accessMetaDataCache.getAdvancedCache().getCacheEntry(accessMetaDataKey).isCreated() ? Mutator.PASSIVE : new CacheEntryMutator<>(this.accessMetaDataCache, accessMetaDataKey, entry.getAccessMetaData());
        SessionAccessMetaData accessMetaData = new MutableSessionAccessMetaData(entry.getAccessMetaData(), accessMutator);

        return new SimpleSessionMetaData(creationMetaData, accessMetaData);
    }

    @Override
    public ImmutableSessionMetaData createImmutableSessionMetaData(String id, InfinispanSessionMetaData<L> entry) {
        return new SimpleSessionMetaData(entry.getCreationMetaData(), entry.getAccessMetaData());
    }

    @Override
    public boolean remove(String id) {
        return this.remove(id, this.creationMetaDataCache);
    }

    @Override
    public boolean purge(String id) {
        return this.remove(id, this.creationMetaDataCache.getAdvancedCache().withFlags(Flag.SKIP_LISTENER_NOTIFICATION));
    }

    private boolean remove(String id, Cache<SessionCreationMetaDataKey, SessionCreationMetaDataEntry<L>> creationMetaDataCache) {
        SessionCreationMetaDataKey key = new SessionCreationMetaDataKey(id);
        if (this.lockOnWrite && creationMetaDataCache.getAdvancedCache().withFlags(Flag.ZERO_LOCK_ACQUISITION_TIMEOUT, Flag.FAIL_SILENTLY).lock(key)) {
            creationMetaDataCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES).remove(key);
            this.accessMetaDataCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES).remove(new SessionAccessMetaDataKey(id));
            return true;
        }
        return false;
    }

    @Override
    public void evict(String id) {
        this.creationMetaDataCache.evict(new SessionCreationMetaDataKey(id));
        this.accessMetaDataCache.evict(new SessionAccessMetaDataKey(id));
    }
}
