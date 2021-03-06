/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.affinity.*;
import org.apache.ignite.cache.store.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.processors.cache.store.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.resources.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.*;
import org.apache.ignite.testframework.junits.common.*;
import org.apache.ignite.transactions.*;
import org.jetbrains.annotations.*;

import javax.cache.*;
import javax.cache.configuration.*;
import javax.cache.integration.*;
import java.util.*;

import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.*;
import static org.apache.ignite.internal.IgniteNodeAttributes.*;
import static org.apache.ignite.transactions.TransactionIsolation.*;

/**
 *
 */
public abstract class CacheStoreUsageMultinodeAbstractTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** */
    protected boolean client;

    /** */
    protected boolean cache;

    /** */
    protected boolean cacheStore;

    /** */
    protected boolean locStore;

    /** */
    protected boolean writeBehind;

    /** */
    protected boolean nearCache;

    /** */
    protected static Map<String, List<Cache.Entry<?, ?>>> writeMap;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setClientMode(client);

        ((TcpDiscoverySpi)cfg.getDiscoverySpi()).setIpFinder(IP_FINDER);

        if (cache)
            cfg.setCacheConfiguration(cacheConfiguration());

        return cfg;
    }

    /**
     * @return Cache configuration.
     */
    @SuppressWarnings("unchecked")
    protected CacheConfiguration cacheConfiguration() {
        CacheConfiguration ccfg = new CacheConfiguration();

        ccfg.setCacheMode(PARTITIONED);
        ccfg.setAtomicityMode(atomicityMode());
        ccfg.setBackups(1);
        ccfg.setWriteSynchronizationMode(FULL_SYNC);

        if (cacheStore) {
            if (writeBehind) {
                ccfg.setWriteBehindEnabled(true);
                ccfg.setWriteBehindFlushFrequency(100);
            }

            ccfg.setWriteThrough(true);

            ccfg.setCacheStoreFactory(locStore ? new TestLocalStoreFactory() : new TestStoreFactory());
        }

        if (nearCache)
            ccfg.setNearConfiguration(new NearCacheConfiguration());

        return ccfg;
    }

    /**
     * @return Cache atomicity mode.
     */
    protected abstract CacheAtomicityMode atomicityMode();

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        writeMap = new HashMap<>();
    }

    /**
     * @param clientStore {@code True} if store configured on client node.
     * @throws Exception If failed.
     */
    protected void checkStoreUpdate(boolean clientStore) throws Exception {
        Ignite client = grid(3);

        assertTrue(client.configuration().isClientMode());

        awaitPartitionMapExchange();

        IgniteCache<Object, Object> cache0 = ignite(0).cache(null);
        IgniteCache<Object, Object> cache1 = ignite(1).cache(null);
        IgniteCache<Object, Object> clientCache = client.cache(null);

        assertTrue(((IgniteCacheProxy)cache0).context().store().configured());
        assertEquals(clientStore, ((IgniteCacheProxy) clientCache).context().store().configured());

        List<TransactionConcurrency> tcList = new ArrayList<>();

        tcList.add(null);

        if (atomicityMode() == TRANSACTIONAL) {
            tcList.add(TransactionConcurrency.OPTIMISTIC);
            tcList.add(TransactionConcurrency.PESSIMISTIC);
        }

        log.info("Start test [atomicityMode=" + atomicityMode() +
            ", locStore=" + locStore +
            ", writeBehind=" + writeBehind +
            ", nearCache=" + nearCache +
            ", clientStore=" + clientStore + ']');

        for (TransactionConcurrency tc : tcList) {
            testStoreUpdate(cache0, primaryKey(cache0), tc);

            testStoreUpdate(cache0, backupKey(cache0), tc);

            testStoreUpdate(cache0, nearKey(cache0), tc);

            testStoreUpdate(cache0, primaryKey(cache1), tc);

            testStoreUpdate(clientCache, primaryKey(cache0), tc);

            testStoreUpdate(clientCache, primaryKey(cache1), tc);
        }
    }

    /**
     * @param cache Cache.
     * @param key Key.
     * @param tc Transaction concurrency mode.
     * @throws Exception If failed.
     */
    protected void testStoreUpdate(IgniteCache<Object, Object> cache,
       Object key,
       @Nullable TransactionConcurrency tc)
        throws Exception
    {
        boolean storeOnPrimary = atomicityMode() == ATOMIC || locStore || writeBehind;

        assertTrue(writeMap.isEmpty());

        Ignite ignite = cache.unwrap(Ignite.class);

        Affinity<Object> obj = ignite.affinity(cache.getName());

        ClusterNode node = obj.mapKeyToNode(key);

        assertNotNull(node);

        String expNode = storeOnPrimary ? (String)node.attribute(ATTR_GRID_NAME) : ignite.name();

        assertNotNull(expNode);

        log.info("Put [node=" + ignite.name() +
            ", key=" + key +
            ", primary=" + node.attribute(ATTR_GRID_NAME) +
            ", tx=" + tc +
            ", nearCache=" + (cache.getConfiguration(CacheConfiguration.class).getNearConfiguration() != null) +
            ", storeOnPrimary=" + storeOnPrimary + ']');

        Transaction tx = tc != null ? ignite.transactions().txStart(tc, REPEATABLE_READ) : null;

        cache.put(key, key);

        if (tx != null)
            tx.commit();

        boolean wait = GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override
            public boolean apply() {
                return writeMap.size() > 0;
            }
        }, 1000);

        assertTrue("Store is not updated", wait);

        assertEquals("Write on wrong node: " + writeMap, 1, writeMap.size());

        assertEquals(expNode, writeMap.keySet().iterator().next());

        writeMap.clear();
    }

    /**
     *
     */
    public static class TestStoreFactory implements Factory<CacheStore> {
        /** {@inheritDoc} */
        @Override public CacheStore create() {
            return new TestStore();
        }
    }

    /**
     *
     */
    public static class TestLocalStoreFactory implements Factory<CacheStore> {
        /** {@inheritDoc} */
        @Override public CacheStore create() {
            return new TestLocalStore();
        }
    }

    /**
     *
     */
    public static class TestStore extends CacheStoreAdapter<Object, Object> {
        /** */
        @IgniteInstanceResource
        private Ignite ignite;

        /** {@inheritDoc} */
        @SuppressWarnings("SynchronizeOnNonFinalField")
        @Override public void write(Cache.Entry<?, ?> entry) {
            synchronized (writeMap) {
                ignite.log().info("Write [node=" + ignite.name() + ", entry=" + entry + ']');

                String name = ignite.name();

                List<Cache.Entry<?, ?>> list = writeMap.get(name);

                if (list == null) {
                    list = new ArrayList<>();

                    writeMap.put(name, list);
                }

                list.add(entry);
            }
        }

        /** {@inheritDoc} */
        @Override public Object load(Object key) throws CacheLoaderException {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public void delete(Object key) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     *
     */
    @CacheLocalStore
    public static class TestLocalStore extends TestStore {
        /** {@inheritDoc} */
        @Override public void delete(Object key) {
            // No-op.
        }
    }
}
