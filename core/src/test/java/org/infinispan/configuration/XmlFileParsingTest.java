package org.infinispan.configuration;

import static org.infinispan.test.TestingUtil.INFINISPAN_END_TAG;
import static org.infinispan.test.TestingUtil.INFINISPAN_START_TAG_NO_SCHEMA;
import static org.infinispan.test.TestingUtil.withCacheManager;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.infinispan.Version;
import org.infinispan.commons.CacheConfigurationException;
import org.infinispan.commons.equivalence.AnyEquivalence;
import org.infinispan.commons.executors.BlockingThreadPoolExecutorFactory;
import org.infinispan.commons.marshall.AdvancedExternalizer;
import org.infinispan.commons.marshall.jboss.GenericJBossMarshaller;
import org.infinispan.configuration.cache.AbstractStoreConfiguration;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ClusterLoaderConfiguration;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.InterceptorConfiguration;
import org.infinispan.configuration.cache.PersistenceConfiguration;
import org.infinispan.configuration.cache.SingleFileStoreConfiguration;
import org.infinispan.configuration.cache.StoreConfiguration;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.ShutdownHookBehavior;
import org.infinispan.configuration.cache.StorageType;
import org.infinispan.factories.threads.DefaultThreadFactory;
import org.infinispan.interceptors.FooInterceptor;
import org.infinispan.jmx.PerThreadMBeanServerLookup;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.marshall.AdvancedExternalizerTest;
import org.infinispan.marshall.TestObjectStreamMarshaller;
import org.infinispan.marshall.core.MarshalledEntry;
import org.infinispan.persistence.dummy.DummyInMemoryStoreConfiguration;
import org.infinispan.persistence.spi.CacheLoader;
import org.infinispan.persistence.spi.InitializationContext;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.infinispan.test.AbstractInfinispanTest;
import org.infinispan.test.CacheManagerCallable;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.TestingUtil.InfinispanStartTag;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.infinispan.test.tx.TestLookup;
import org.infinispan.transaction.LockingMode;
import org.infinispan.transaction.TransactionProtocol;
import org.infinispan.transaction.lookup.GenericTransactionManagerLookup;
import org.infinispan.util.concurrent.IsolationLevel;
import org.testng.annotations.Test;

@Test(groups = "unit", testName = "configuration.XmlFileParsingTest")
public class XmlFileParsingTest extends AbstractInfinispanTest {

   @Test(expectedExceptions=FileNotFoundException.class)
   public void testFailOnUnexpectedConfigurationFile() throws IOException {
      TestCacheManagerFactory.fromXml("does-not-exist.xml");
   }

   public void testNamedCacheFile() throws IOException {
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromXml("configs/named-cache-test.xml", true)) {
         @Override
         public void call() {
            assertNamedCacheFile(cm, false);
         }
      });
   }

   public void testNoNamedCaches() throws Exception {
      String config = InfinispanStartTag.LATEST +
            "<cache-container default-cache=\"default\">" +
            "   <transport cluster=\"demoCluster\"/>\n" +
            "   <replicated-cache name=\"default\">\n" +
            "   </replicated-cache>\n" +
            "</cache-container>" +
            TestingUtil.INFINISPAN_END_TAG;

      InputStream is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {

         @Override
         public void call() {
            GlobalConfiguration globalCfg = cm.getCacheManagerConfiguration();

            assertTrue(globalCfg.transport().transport() instanceof JGroupsTransport);
            assertEquals("demoCluster", globalCfg.transport().clusterName());

            Configuration cfg = cm.getDefaultCacheConfiguration();
            assertEquals(CacheMode.REPL_SYNC, cfg.clustering().cacheMode());
         }

      });

   }

   @Test(expectedExceptions=CacheConfigurationException.class)
   public void testDuplicateCacheNames() throws Exception {
      String config = InfinispanStartTag.LATEST +
            "<cache-container default-cache=\"duplicatename\">" +
            "   <transport cluster=\"demoCluster\"/>\n" +
            "   <distributed-cache name=\"duplicatename\">\n" +
            "   </distributed-cache>\n" +
            "   <distributed-cache name=\"duplicatename\">\n" +
            "   </distributed-cache>\n" +
            "</cache-container>" +
            TestingUtil.INFINISPAN_END_TAG;

      InputStream is = new ByteArrayInputStream(config.getBytes());
      EmbeddedCacheManager cacheManager = TestCacheManagerFactory.fromStream(is);
   }

   public void testNoSchemaWithStuff() throws IOException {
      String config = INFINISPAN_START_TAG_NO_SCHEMA +
            "<cache-container default-cache=\"default\">" +
            "   <local-cache name=\"default\">\n" +
            "        <locking concurrency-level=\"10000\" isolation=\"REPEATABLE_READ\" />\n" +
            "   </local-cache>\n" +
            "</cache-container>" +
            INFINISPAN_END_TAG;

      InputStream is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {

         @Override
         public void call() {
            Configuration cfg = cm.getDefaultCacheConfiguration();
            assertEquals(10000, cfg.locking().concurrencyLevel());
            assertEquals(IsolationLevel.REPEATABLE_READ, cfg.locking().isolationLevel());
         }

      });

   }

   public void testCompatibility() throws Exception {
      String config = InfinispanStartTag.LATEST +
            "<cache-container default-cache=\"default\">" +
            "   <local-cache name=\"default\">\n" +
            "   </local-cache>\n" +
            "</cache-container>" +
            TestingUtil.INFINISPAN_END_TAG;

      InputStream is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {
         @Override
         public void call() {
            Configuration cfg = cm.getDefaultCacheConfiguration();
            assertFalse(cfg.compatibility().enabled());
            assertNull(cfg.compatibility().marshaller());
         }
      });

      config = InfinispanStartTag.LATEST +
            "<cache-container default-cache=\"default\">" +
            "   <local-cache name=\"default\">\n" +
            "      <compatibility/>\n" +
            "   </local-cache>\n" +
            "</cache-container>" +
            TestingUtil.INFINISPAN_END_TAG;

      is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {
         @Override
         public void call() {
            Configuration cfg = cm.getDefaultCacheConfiguration();
            assertTrue(cfg.compatibility().enabled());
            assertNull(cfg.compatibility().marshaller());
         }
      });

      config = InfinispanStartTag.LATEST +
            "<cache-container default-cache=\"default\">" +
            "   <local-cache name=\"default\">\n" +
            "      <compatibility marshaller=\"org.infinispan.commons.marshall.jboss.GenericJBossMarshaller\"/>\n" +
            "   </local-cache>\n" +
            "</cache-container>" +
            TestingUtil.INFINISPAN_END_TAG;

      is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {
         @Override
         public void call() {
            Configuration cfg = cm.getDefaultCacheConfiguration();
            assertTrue(cfg.compatibility().enabled());
            assertTrue(cfg.compatibility().marshaller() instanceof GenericJBossMarshaller);
         }
      });
   }

   public void testOffHeap() throws Exception {
      String config = INFINISPAN_START_TAG_NO_SCHEMA +
            "<cache-container default-cache=\"default\">" +
            "   <local-cache name=\"default\">\n" +
            "      <memory>\n" +
            "        <off-heap/>\n" +
            "      </memory>\n" +
            "   </local-cache>\n" +
            "</cache-container>" +
            INFINISPAN_END_TAG;
      InputStream is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {
         @Override
         public void call() {
            Configuration cfg = cm.getDefaultCacheConfiguration();
            assertTrue(cfg.dataContainer().<byte[]>keyEquivalence() instanceof AnyEquivalence);
            assertTrue(cfg.dataContainer().valueEquivalence() instanceof AnyEquivalence);
            assertEquals(StorageType.OFF_HEAP, cfg.memory().storageType());
         }
      });

      config = INFINISPAN_START_TAG_NO_SCHEMA +
            "<cache-container default-cache=\"default\">" +
            "   <local-cache name=\"default\">\n" +
            "      <memory/>\n" +
            "   </local-cache>\n" +
            "</cache-container>" +
            INFINISPAN_END_TAG;
      is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {
         @Override
         public void call() {
            Configuration cfg = cm.getDefaultCacheConfiguration();
            assertTrue(cfg.dataContainer().<byte[]>keyEquivalence() instanceof AnyEquivalence);
            assertTrue(cfg.dataContainer().<byte[]>valueEquivalence() instanceof AnyEquivalence);
            assertEquals(StorageType.OBJECT, cfg.memory().storageType());
         }
      });

      config = INFINISPAN_START_TAG_NO_SCHEMA +
            "<cache-container default-cache=\"default\">" +
            "   <local-cache name=\"default\">\n" +
            "      <memory>\n" +
            "         <binary/>\n" +
            "      </memory>\n" +
            "   </local-cache>\n" +
            "</cache-container>" +
            INFINISPAN_END_TAG;
      is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {
         @Override
         public void call() {
            Configuration cfg = cm.getDefaultCacheConfiguration();
            assertTrue(cfg.dataContainer().<byte[]>keyEquivalence() instanceof AnyEquivalence);
            assertTrue(cfg.dataContainer().<byte[]>valueEquivalence() instanceof AnyEquivalence);
            assertEquals(StorageType.BINARY, cfg.memory().storageType());
         }
      });
   }

   public void testDummyInMemoryStore() throws IOException {
      String config = INFINISPAN_START_TAG_NO_SCHEMA +
            "<cache-container default-cache=\"default\">" +
            "   <local-cache name=\"default\">\n" +
            "<persistence >\n" +
               "<store class=\"org.infinispan.persistence.dummy.DummyInMemoryStore\" >\n" +
                  "<property name=\"storeName\">myStore</property>" +
               "</store >\n" +
            "</persistence >\n" +
            "   </local-cache>\n" +
            "</cache-container>" +
            INFINISPAN_END_TAG;

      InputStream is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {
         @Override
         public void call() {
            PersistenceConfiguration cfg = cm.getDefaultCacheConfiguration().persistence();
            StoreConfiguration config = cfg.stores().get(0);
            assertTrue(config instanceof DummyInMemoryStoreConfiguration);
            DummyInMemoryStoreConfiguration dummyInMemoryStoreConfiguration = (DummyInMemoryStoreConfiguration)config;
            assertEquals("myStore", dummyInMemoryStoreConfiguration.storeName());
         }
      });
   }

   public static class GenericLoader implements CacheLoader {

      @Override
      public void init(InitializationContext ctx) { }

      @Override
      public MarshalledEntry load(Object key) { return null; }

      @Override
      public boolean contains(Object key) { return false; }

      @Override
      public void start() { }

      @Override
      public void stop() { }
   }

   public void testStoreWithNoConfigureBy() throws IOException {
      String config = INFINISPAN_START_TAG_NO_SCHEMA +
            "<cache-container default-cache=\"default\">" +
            "   <local-cache name=\"default\">\n" +
            "<persistence >\n" +
               "<store class=\"org.infinispan.configuration.XmlFileParsingTest$GenericLoader\" preload=\"true\" >\n" +
                     "<property name=\"fetchPersistentState\">true</property>" +
               "</store >\n" +
            "</persistence >\n" +
            "   </local-cache>\n" +
            "</cache-container>" +
            INFINISPAN_END_TAG;

      InputStream is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {
         @Override
         public void call() {
            PersistenceConfiguration cfg = cm.getDefaultCacheConfiguration().persistence();
            StoreConfiguration config = cfg.stores().get(0);
            assertTrue(config instanceof AbstractStoreConfiguration);
            AbstractStoreConfiguration abstractStoreConfiguration = (AbstractStoreConfiguration)config;
            assertTrue(abstractStoreConfiguration.fetchPersistentState());
            assertTrue(abstractStoreConfiguration.preload());
         }
      });
   }

   public void testCustomTransport() throws IOException {
      String config = INFINISPAN_START_TAG_NO_SCHEMA +
            "<jgroups transport=\"org.infinispan.configuration.XmlFileParsingTest$CustomTransport\"/>\n" +
            "<cache-container default-cache=\"default\">\n" +
            "  <transport cluster=\"ispn-perf-test\"/>\n" +
            "  <distributed-cache name=\"default\"/>\n" +
            "</cache-container>" +
            INFINISPAN_END_TAG;

      InputStream is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {
         @Override
         public void call() {
            Transport transport = cm.getTransport();
            assertNotNull(transport);
            assertTrue(transport instanceof CustomTransport);
         }
      });
   }

   public void testNoDefaultCache() throws Exception {
      String config = InfinispanStartTag.LATEST +
            "<cache-container>" +
            "   <transport cluster=\"demoCluster\"/>\n" +
            "   <replicated-cache name=\"default\">\n" +
            "   </replicated-cache>\n" +
            "</cache-container>" +
            TestingUtil.INFINISPAN_END_TAG;

      InputStream is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {

         @Override
         public void call() {
            GlobalConfiguration globalCfg = cm.getCacheManagerConfiguration();
            assertFalse(globalCfg.defaultCacheName().isPresent());
            assertNull(cm.getDefaultCacheConfiguration());
            assertEquals(CacheMode.REPL_SYNC, cm.getCacheConfiguration("default").clustering().cacheMode());
         }

      });
   }

   @Test(expectedExceptions = CacheConfigurationException.class, expectedExceptionsMessageRegExp = "ISPN000432:.*")
   public void testNoDefaultCacheDeclaration() throws Exception {
      String config = InfinispanStartTag.LATEST +
            "<cache-container default-cache=\"non-existent\">" +
            "   <transport cluster=\"demoCluster\"/>\n" +
            "   <replicated-cache name=\"default\">\n" +
            "   </replicated-cache>\n" +
            "</cache-container>" +
            TestingUtil.INFINISPAN_END_TAG;

      InputStream is = new ByteArrayInputStream(config.getBytes());
      withCacheManager(new CacheManagerCallable(TestCacheManagerFactory.fromStream(is)) {

         @Override
         public void call() {
            // Do nothing
         }

      });
   }

   private void assertNamedCacheFile(EmbeddedCacheManager cm, boolean deprecated) {
      final GlobalConfiguration gc = cm.getCacheManagerConfiguration();

      BlockingThreadPoolExecutorFactory listenerThreadPool =
            cm.getCacheManagerConfiguration().listenerThreadPool().threadPoolFactory();
      assertEquals(5, listenerThreadPool.maxThreads());
      assertEquals(10000, listenerThreadPool.queueLength());
      DefaultThreadFactory listenerThreadFactory =
            cm.getCacheManagerConfiguration().listenerThreadPool().threadFactory();
      assertEquals("AsyncListenerThread", listenerThreadFactory.threadNamePattern());

      BlockingThreadPoolExecutorFactory persistenceThreadPool =
            cm.getCacheManagerConfiguration().persistenceThreadPool().threadPoolFactory();
      assertEquals(6, persistenceThreadPool.maxThreads());
      assertEquals(10001, persistenceThreadPool.queueLength());
      DefaultThreadFactory persistenceThreadFactory =
            cm.getCacheManagerConfiguration().persistenceThreadPool().threadFactory();
      assertEquals("PersistenceThread", persistenceThreadFactory.threadNamePattern());

      BlockingThreadPoolExecutorFactory asyncThreadPool =
            cm.getCacheManagerConfiguration().asyncThreadPool().threadPoolFactory();
      assertEquals(TestCacheManagerFactory.ASYNC_EXEC_MAX_THREADS, asyncThreadPool.maxThreads());
      assertEquals(TestCacheManagerFactory.ASYNC_EXEC_QUEUE_SIZE, asyncThreadPool.queueLength());
      assertEquals(TestCacheManagerFactory.KEEP_ALIVE, asyncThreadPool.keepAlive());

      BlockingThreadPoolExecutorFactory transportThreadPool =
            cm.getCacheManagerConfiguration().transport().transportThreadPool().threadPoolFactory();
      assertEquals(TestCacheManagerFactory.TRANSPORT_EXEC_MAX_THREADS, transportThreadPool.maxThreads());
      assertEquals(TestCacheManagerFactory.TRANSPORT_EXEC_QUEUE_SIZE, transportThreadPool.queueLength());
      assertEquals(TestCacheManagerFactory.KEEP_ALIVE, transportThreadPool.keepAlive());

      BlockingThreadPoolExecutorFactory remoteCommandThreadPool =
            cm.getCacheManagerConfiguration().transport().remoteCommandThreadPool().threadPoolFactory();
      assertEquals(TestCacheManagerFactory.REMOTE_EXEC_MAX_THREADS, remoteCommandThreadPool.maxThreads());
      assertEquals(TestCacheManagerFactory.REMOTE_EXEC_QUEUE_SIZE, remoteCommandThreadPool.queueLength());
      assertEquals(TestCacheManagerFactory.KEEP_ALIVE, remoteCommandThreadPool.keepAlive());

      BlockingThreadPoolExecutorFactory stateTransferThreadPool =
            cm.getCacheManagerConfiguration().stateTransferThreadPool().threadPoolFactory();
      assertEquals(TestCacheManagerFactory.STATE_TRANSFER_EXEC_MAX_THREADS, stateTransferThreadPool.maxThreads());
      assertEquals(TestCacheManagerFactory.STATE_TRANSFER_EXEC_QUEUE_SIZE, stateTransferThreadPool.queueLength());
      assertEquals(TestCacheManagerFactory.KEEP_ALIVE, stateTransferThreadPool.keepAlive());

      DefaultThreadFactory evictionThreadFactory =
            cm.getCacheManagerConfiguration().expirationThreadPool().threadFactory();
      assertEquals("ExpirationThread", evictionThreadFactory.threadNamePattern());

      assertTrue(gc.transport().transport() instanceof JGroupsTransport);
      assertEquals("infinispan-cluster", gc.transport().clusterName());
      // Should be "Jalapeno" but it's overriden by the test cache manager factory
      assertTrue(gc.transport().nodeName().contains("Node"));
      assertEquals(50000, gc.transport().distributedSyncTimeout());

      assertEquals(ShutdownHookBehavior.REGISTER, gc.shutdown().hookBehavior());

      assertTrue(gc.serialization().marshaller() instanceof TestObjectStreamMarshaller);
      assertEquals(Version.getVersionShort("1.0"), gc.serialization().version());
      final Map<Integer, AdvancedExternalizer<?>> externalizers = gc.serialization().advancedExternalizers();
      assertEquals(3, externalizers.size());
      assertTrue(externalizers.get(1234) instanceof AdvancedExternalizerTest.IdViaConfigObj.Externalizer);
      assertTrue(externalizers.get(5678) instanceof AdvancedExternalizerTest.IdViaAnnotationObj.Externalizer);
      assertTrue(externalizers.get(3456) instanceof AdvancedExternalizerTest.IdViaBothObj.Externalizer);

      Configuration defaultCfg = cm.getDefaultCacheConfiguration();

      assertEquals(1000, defaultCfg.locking().lockAcquisitionTimeout());
      assertEquals(100, defaultCfg.locking().concurrencyLevel());
      assertEquals(IsolationLevel.READ_COMMITTED, defaultCfg.locking().isolationLevel());
      if (!deprecated) {
         assertReaperAndTimeoutInfo(defaultCfg);
      }


      Configuration c = cm.getCacheConfiguration("transactional");
      assertTrue(!c.clustering().cacheMode().isClustered());
      assertTrue(c.transaction().transactionManagerLookup() instanceof GenericTransactionManagerLookup);
      if (!deprecated) {
         assertReaperAndTimeoutInfo(defaultCfg);
      }

      c = cm.getCacheConfiguration("transactional2");
      assertTrue(c.transaction().transactionManagerLookup() instanceof TestLookup);
      assertEquals(10000, c.transaction().cacheStopTimeout());
      assertEquals(LockingMode.PESSIMISTIC, c.transaction().lockingMode());
      assertTrue(!c.transaction().autoCommit());

      c = cm.getCacheConfiguration("transactional3");

      if (!deprecated) {
         assertEquals(TransactionProtocol.TOTAL_ORDER, c.transaction().transactionProtocol());
      }

      c = cm.getCacheConfiguration("syncInval");

      assertEquals(CacheMode.INVALIDATION_SYNC, c.clustering().cacheMode());
      assertTrue(c.clustering().stateTransfer().awaitInitialTransfer());
      assertEquals(15000, c.clustering().remoteTimeout());

      c = cm.getCacheConfiguration("asyncInval");

      assertEquals(CacheMode.INVALIDATION_ASYNC, c.clustering().cacheMode());
      assertEquals(15000, c.clustering().remoteTimeout());

      c = cm.getCacheConfiguration("syncRepl");

      assertEquals(CacheMode.REPL_SYNC, c.clustering().cacheMode());
      assertTrue(!c.clustering().stateTransfer().fetchInMemoryState());
      assertTrue(c.clustering().stateTransfer().awaitInitialTransfer());
      assertEquals(15000, c.clustering().remoteTimeout());

      c = cm.getCacheConfiguration("asyncRepl");

      assertEquals(CacheMode.REPL_ASYNC, c.clustering().cacheMode());
      assertTrue(!c.clustering().stateTransfer().fetchInMemoryState());
      assertTrue(c.clustering().stateTransfer().awaitInitialTransfer());

      c = cm.getCacheConfiguration("txSyncRepl");

      assertTrue(c.transaction().transactionManagerLookup() instanceof GenericTransactionManagerLookup);
      assertEquals(CacheMode.REPL_SYNC, c.clustering().cacheMode());
      assertTrue(!c.clustering().stateTransfer().fetchInMemoryState());
      assertTrue(c.clustering().stateTransfer().awaitInitialTransfer());
      assertEquals(15000, c.clustering().remoteTimeout());

      c = cm.getCacheConfiguration("overriding");

      assertEquals(CacheMode.LOCAL, c.clustering().cacheMode());
      assertEquals(20000, c.locking().lockAcquisitionTimeout());
      assertEquals(1000, c.locking().concurrencyLevel());
      assertEquals(IsolationLevel.REPEATABLE_READ, c.locking().isolationLevel());
      assertTrue(!c.storeAsBinary().enabled());

      c = cm.getCacheConfiguration("storeAsBinary");
      assertTrue(c.storeAsBinary().enabled());

      c = cm.getCacheConfiguration("withFileStore");
      assertTrue(c.persistence().preload());
      assertTrue(!c.persistence().passivation());
      assertEquals(1, c.persistence().stores().size());

      SingleFileStoreConfiguration loaderCfg = (SingleFileStoreConfiguration) c.persistence().stores().get(0);

      assertTrue(loaderCfg.fetchPersistentState());
      assertTrue(loaderCfg.ignoreModifications());
      assertTrue(loaderCfg.purgeOnStartup());
      assertEquals("/tmp/FileCacheStore-Location", loaderCfg.location());
      assertEquals(5, loaderCfg.async().threadPoolSize());
      assertTrue(loaderCfg.async().enabled());
      assertEquals(700, loaderCfg.async().modificationQueueSize());

      c = cm.getCacheConfiguration("withClusterLoader");
      assertEquals(1, c.persistence().stores().size());
      ClusterLoaderConfiguration clusterLoaderCfg = (ClusterLoaderConfiguration) c.persistence().stores().get(0);
      assertEquals(15000, clusterLoaderCfg.remoteCallTimeout());

      c = cm.getCacheConfiguration("withLoaderDefaults");
      loaderCfg = (SingleFileStoreConfiguration) c.persistence().stores().get(0);
      assertEquals("/tmp/Another-FileCacheStore-Location", loaderCfg.location());

      c = cm.getCacheConfiguration("withouthJmxEnabled");
      assertTrue(!c.jmxStatistics().enabled());
      assertTrue(gc.globalJmxStatistics().enabled());
      assertTrue(gc.globalJmxStatistics().allowDuplicateDomains());
      assertEquals("funky_domain", gc.globalJmxStatistics().domain());
      assertTrue(gc.globalJmxStatistics().mbeanServerLookup() instanceof PerThreadMBeanServerLookup);

      c = cm.getCacheConfiguration("dist");
      assertEquals(CacheMode.DIST_SYNC, c.clustering().cacheMode());
      assertEquals(600000, c.clustering().l1().lifespan());
      if (deprecated) assertEquals(120000, c.clustering().hash().rehashRpcTimeout());
      assertEquals(120000, c.clustering().stateTransfer().timeout());
      assertEquals(1200, c.clustering().l1().cleanupTaskFrequency());
      assertEquals(null, c.clustering().hash().consistentHash()); // this is just an override.
      assertEquals(3, c.clustering().hash().numOwners());
      assertTrue(c.clustering().l1().enabled());

      c = cm.getCacheConfiguration("dist_with_capacity_factors");
      assertEquals(CacheMode.DIST_SYNC, c.clustering().cacheMode());
      assertEquals(600000, c.clustering().l1().lifespan());
      if (deprecated) assertEquals(120000, c.clustering().hash().rehashRpcTimeout());
      assertEquals(120000, c.clustering().stateTransfer().timeout());
      assertEquals(null, c.clustering().hash().consistentHash()); // this is just an override.
      assertEquals(3, c.clustering().hash().numOwners());
      assertTrue(c.clustering().l1().enabled());
      assertEquals(0.0f, c.clustering().hash().capacityFactor());
      if (!deprecated) assertEquals(1000, c.clustering().hash().numSegments());

      c = cm.getCacheConfiguration("groups");
      assertTrue(c.clustering().hash().groups().enabled());
      assertEquals(1, c.clustering().hash().groups().groupers().size());
      assertEquals(String.class, c.clustering().hash().groups().groupers().get(0).getKeyType());

      c = cm.getCacheConfiguration("chunkSize");
      assertTrue(c.clustering().stateTransfer().fetchInMemoryState());
      assertEquals(120000, c.clustering().stateTransfer().timeout());
      assertEquals(1000, c.clustering().stateTransfer().chunkSize());

      c = cm.getCacheConfiguration("cacheWithCustomInterceptors");
      assertTrue(!c.customInterceptors().interceptors().isEmpty());
      assertEquals(6, c.customInterceptors().interceptors().size());
      for(InterceptorConfiguration i : c.customInterceptors().interceptors()) {
         if (i.asyncInterceptor() instanceof FooInterceptor) {
            assertEquals(i.properties().getProperty("foo"), "bar");
         }
      }

      c = cm.getCacheConfiguration("evictionCache");
      assertEquals(5000, c.memory().size());
      assertEquals(60000, c.expiration().lifespan());
      assertEquals(1000, c.expiration().maxIdle());
      assertEquals(500, c.expiration().wakeUpInterval());

      c = cm.getCacheConfiguration("withDeadlockDetection");
      assertFalse(c.deadlockDetection().enabled());
      assertEquals(-1, c.deadlockDetection().spinDuration());
      assertEquals(CacheMode.DIST_SYNC, c.clustering().cacheMode());

      c = cm.getCacheConfiguration("storeKeyValueBinary");
      assertTrue(c.storeAsBinary().enabled());
      assertTrue(c.storeAsBinary().storeKeysAsBinary());
      assertTrue(c.storeAsBinary().storeValuesAsBinary());
   }

   private void assertReaperAndTimeoutInfo(Configuration defaultCfg) {
      assertEquals(123, defaultCfg.transaction().reaperWakeUpInterval());
      assertEquals(3123, defaultCfg.transaction().completedTxTimeout());
   }

   public static class CustomTransport extends JGroupsTransport {

   }
}
