package org.infinispan.client.hotrod.stress.near;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.NearCacheMode;
import org.infinispan.client.hotrod.test.InternalRemoteCacheManager;
import org.infinispan.client.hotrod.test.RemoteCacheManagerCallable;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.infinispan.client.hotrod.test.HotRodClientTestingUtil.killRemoteCacheManagers;
import static org.infinispan.client.hotrod.test.HotRodClientTestingUtil.withRemoteCacheManager;
import static org.infinispan.server.hotrod.test.HotRodTestingUtil.hotRodCacheConfiguration;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;

/**
 * Manual test, requires external Hot Rod server.
 */
@Test(groups = "manual", testName = "client.hotrod.stress.near.EagerNearCacheStressTest")
public class EagerNearCacheStressTest {

   static int NUM_CLIENTS = 3;
   static int NUM_THREADS_PER_CLIENT = 10;
   static AtomicInteger ID = new AtomicInteger();
   static ExecutorService EXEC = Executors.newCachedThreadPool();

   static final int NUM_OPERATIONS = 10_000_000;
   static final int NUM_KEYS_PRELOAD = 1_000;
   static final int KEY_RANGE = 1_000;
   static final Random R = new Random();

   @AfterClass
   public static void shutdownExecutor() {
      EXEC.shutdown();
   }

   EmbeddedCacheManager createCacheManager() {
      return TestCacheManagerFactory.createCacheManager(hotRodCacheConfiguration());
   }

//   HotRodServer createHotRodServer(EmbeddedCacheManager cm) {
//      return HotRodClientTestingUtil.startHotRodServer(cm);
//   }

   RemoteCacheManager getRemoteCacheManager(int port) {
      return getRemoteCacheManager(port, NearCacheMode.DISABLED, -1);
   }

   RemoteCacheManager getRemoteCacheManager(int port, NearCacheMode nearCacheMode, int maxEntries) {
      ConfigurationBuilder builder = new ConfigurationBuilder();
      builder.nearCache().mode(nearCacheMode).maxEntries(maxEntries);
      builder.addServer().host("127.0.0.1").port(port);
      return new InternalRemoteCacheManager(builder.build());
   }

   public void testLocalPreloadAndGetPut10to1() {
      runPreloadAndOps("l_PL_get", NearCacheMode.EAGER, -1, 0.90);
   }

   void runPreloadAndOps(String name, NearCacheMode nearCacheMode, int maxEntries, double getRatio) {
      EmbeddedCacheManager cm = createCacheManager();
      //HotRodServer server = createHotRodServer(cm);
      int port = 11222;
      preloadData(port);
      RemoteCacheManager[] remotecms = new RemoteCacheManager[NUM_CLIENTS];
      for (int i = 0; i < NUM_CLIENTS; i++)
         remotecms[i] = getRemoteCacheManager(port, nearCacheMode, maxEntries);
      try {
         ops(name, remotecms, getRatio);
      } finally {
         killRemoteCacheManagers(remotecms);
         //killServers(server);
         TestingUtil.killCacheManagers(cm);
      }
   }

   void ops(String testName, RemoteCacheManager[] remotecms, double getRatio) {
      CyclicBarrier barrier = new CyclicBarrier((NUM_CLIENTS * NUM_THREADS_PER_CLIENT) + 1);
      List<Future<Void>> futures = new ArrayList<>(NUM_CLIENTS * NUM_THREADS_PER_CLIENT);
      for (RemoteCacheManager remotecm : remotecms) {
         RemoteCache<Integer, String> remote = remotecm.getCache();
         for (int i = 0; i < NUM_THREADS_PER_CLIENT; i++) {
            Callable<Void> call = new Main(barrier, testName, remote, getRatio);
            futures.add(EXEC.submit(call));
         }
      }
      barrierAwait(barrier); // wait for all threads to be ready
      barrierAwait(barrier); // wait for all threads to finish

      for (Future<Void> f : futures)
         futureGet(f);
   }

   void preloadData(int port) {
      // Preload data
      withRemoteCacheManager(new RemoteCacheManagerCallable(getRemoteCacheManager(port)) {
         @Override
         public void call() {
            RemoteCache<Integer, String> remote = rcm.getCache();
            Map<Integer, String> map = new HashMap<>();
            for (int i = 0; i < NUM_KEYS_PRELOAD; ++i)
               map.put(i, TestingUtil.generateRandomString(512));
            remote.putAll(map);
         }
      });
   }

   static abstract class Runner implements Callable<Void> {

      final CyclicBarrier barrier;
      final RemoteCache<Integer, String> remote;
      final int threadId;
      final String testName;
      final double getRatio;

      Runner(CyclicBarrier barrier, String testName, RemoteCache<Integer, String> remote, double getRatio) {
         this.barrier = barrier;
         this.remote = remote;
         this.getRatio = getRatio;
         this.threadId = ID.incrementAndGet();
         this.testName = testName;
      }

      @Override
      public Void call() throws Exception {
         barrierAwait(barrier);
         try {
            run();
            return null;
         } finally {
            barrierAwait(barrier);
         }
      }

      abstract void run();
   }

   final static class Main extends Runner {

      Main(CyclicBarrier barrier, String testName, RemoteCache<Integer, String> remote, double getRatio) {
         super(barrier, testName, remote, getRatio);
      }

      @Override
      void run() {
         double maxGetKey = KEY_RANGE * getRatio;
         for (int i = 0; i < NUM_OPERATIONS; i++) {
            int key = R.nextInt(KEY_RANGE);
            if (key < maxGetKey) {
               String value = remote.get(key);
               assertNotNull(value);
            } else {
               String prev = remote.put(key, TestingUtil.generateRandomString(512));
               assertNull(prev);
            }
         }
      }
   }

   static int barrierAwait(CyclicBarrier barrier) {
      try {
         return barrier.await();
      } catch (InterruptedException | BrokenBarrierException e) {
         throw new AssertionError(e);
      }
   }

   <T> T futureGet(Future<T> future) {
      try {
         return future.get();
      } catch (InterruptedException | ExecutionException e) {
         throw new AssertionError(e);
      }
   }

}
