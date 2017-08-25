package org.infinispan.eviction.impl;

import org.infinispan.configuration.cache.StorageType;
import org.testng.annotations.Test;

/**
 * @author vjuranek
 * @since 9.2
 */
@Test(groups = {"functional", "smoke"}, testName = "eviction.OffHeapEvictionFunctionalTest")
public class OffHeapEvictionFunctionalTest extends BaseEvictionFunctionalTest {

   @Override
   protected StorageType getStorageType() {
      return StorageType.OFF_HEAP;
   }

}
