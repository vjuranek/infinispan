package org.infinispan.interceptors.impl;

import static org.infinispan.persistence.PersistenceUtil.internalMetadata;
import static org.infinispan.persistence.manager.PersistenceManager.AccessMode.BOTH;
import static org.infinispan.persistence.manager.PersistenceManager.AccessMode.PRIVATE;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.transaction.InvalidTransactionException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.infinispan.atomic.impl.AtomicHashMap;
import org.infinispan.commands.AbstractVisitor;
import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.functional.FunctionalCommand;
import org.infinispan.commands.functional.ReadWriteKeyCommand;
import org.infinispan.commands.functional.ReadWriteKeyValueCommand;
import org.infinispan.commands.functional.ReadWriteManyCommand;
import org.infinispan.commands.functional.ReadWriteManyEntriesCommand;
import org.infinispan.commands.functional.WriteOnlyKeyCommand;
import org.infinispan.commands.functional.WriteOnlyKeyValueCommand;
import org.infinispan.commands.functional.WriteOnlyManyCommand;
import org.infinispan.commands.functional.WriteOnlyManyEntriesCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.write.ApplyDeltaCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.DataWriteCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.commons.api.functional.Param;
import org.infinispan.commons.api.functional.Param.PersistenceMode;
import org.infinispan.commons.marshall.StreamingMarshaller;
import org.infinispan.configuration.cache.PersistenceConfiguration;
import org.infinispan.container.InternalEntryFactory;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.DeltaAwareCacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.InternalCacheValue;
import org.infinispan.container.versioning.EntryVersion;
import org.infinispan.container.versioning.EntryVersionsMap;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.FlagBitSets;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.jmx.annotations.MBean;
import org.infinispan.jmx.annotations.ManagedAttribute;
import org.infinispan.jmx.annotations.ManagedOperation;
import org.infinispan.jmx.annotations.MeasurementType;
import org.infinispan.marshall.core.MarshalledEntryImpl;
import org.infinispan.metadata.EmbeddedMetadata;
import org.infinispan.metadata.Metadata;
import org.infinispan.persistence.manager.PersistenceManager;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * Writes modifications back to the store on the way out: stores modifications back through the CacheLoader, either
 * after each method call (no TXs), or at TX commit.
 *
 * Only used for LOCAL and INVALIDATION caches.
 *
 * @author Bela Ban
 * @author Dan Berindei
 * @author Mircea Markus
 * @since 9.0
 */
@MBean(objectName = "CacheStore", description = "Component that handles storing of entries to a CacheStore from memory.")
public class CacheWriterInterceptor extends JmxStatsCommandInterceptor {
   private final boolean trace = getLog().isTraceEnabled();
   PersistenceConfiguration loaderConfig = null;
   final AtomicLong cacheStores = new AtomicLong(0);
   protected PersistenceManager persistenceManager;
   private InternalEntryFactory entryFactory;
   private TransactionManager transactionManager;
   private StreamingMarshaller marshaller;

   private static final Log log = LogFactory.getLog(CacheWriterInterceptor.class);

   protected Log getLog() {
      return log;
   }

   @Inject
   protected void init(PersistenceManager pm, InternalEntryFactory entryFactory, TransactionManager transactionManager,
                       StreamingMarshaller marshaller) {
      this.persistenceManager = pm;
      this.entryFactory = entryFactory;
      this.transactionManager = transactionManager;
      this.marshaller = marshaller;
   }

   @Start(priority = 15)
   protected void start() {
      this.setStatisticsEnabled(cacheConfiguration.jmxStatistics().enabled());
      loaderConfig = cacheConfiguration.persistence();
   }

   @Override
   public Object visitCommitCommand(TxInvocationContext ctx, CommitCommand command) throws Throwable {
      commitCommand(ctx);
      return invokeNext(ctx, command);
   }

   @Override
   public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      if (command.isOnePhaseCommit()) {
         commitCommand(ctx);
      }
      return invokeNext(ctx, command);
   }

   protected void commitCommand(TxInvocationContext ctx) throws Throwable {
      if (!ctx.getCacheTransaction().getAllModifications().isEmpty()) {
         // this is a commit call.
         GlobalTransaction tx = ctx.getGlobalTransaction();
         if (trace) getLog().tracef("Calling loader.commit() for transaction %s", tx);

         Transaction xaTx = null;
         try {
            xaTx = suspendRunningTx(ctx);
            store(ctx);
         } finally {
            resumeRunningTx(xaTx);
         }
      } else {
         if (trace) getLog().trace("Commit called with no modifications; ignoring.");
      }
   }

   private void resumeRunningTx(Transaction xaTx) throws InvalidTransactionException, SystemException {
      if (transactionManager != null && xaTx != null) {
         transactionManager.resume(xaTx);
      }
   }

   private Transaction suspendRunningTx(TxInvocationContext ctx) throws SystemException {
      Transaction xaTx = null;
      if (transactionManager != null) {
         xaTx = transactionManager.suspend();
         if (xaTx != null && !ctx.isOriginLocal())
            throw new IllegalStateException("It is only possible to be in the context of an JRA transaction in the local node.");
      }
      return xaTx;
   }

   @Override
   public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
      return invokeNextThenAccept(ctx, command, (rCtx, rCommand, rv) -> {
         RemoveCommand removeCommand = (RemoveCommand) rCommand;
         if (!isStoreEnabled(removeCommand) || rCtx.isInTxScope() || !removeCommand.isSuccessful()) return;
         if (!isProperWriter(rCtx, removeCommand, removeCommand.getKey())) return;

         Object key = removeCommand.getKey();
         boolean resp = persistenceManager.deleteFromAllStores(key, BOTH);
         if (trace)
            getLog().tracef("Removed entry under key %s and got response %s from CacheStore", key, resp);
      });
   }

   @Override
   public Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
      if (isStoreEnabled(command) && !ctx.isInTxScope())
         persistenceManager.clearAllStores(ctx.isOriginLocal() ? BOTH : PRIVATE);

      return invokeNext(ctx, command);
   }

   @Override
   public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
      return invokeNextThenAccept(ctx, command, (rCtx, rCommand, rv) -> {
         PutKeyValueCommand putKeyValueCommand = (PutKeyValueCommand) rCommand;
         if (!isStoreEnabled(putKeyValueCommand) || rCtx.isInTxScope() || !putKeyValueCommand.isSuccessful())
            return;
         if (!isProperWriter(rCtx, putKeyValueCommand, putKeyValueCommand.getKey()))
            return;

         Object key = putKeyValueCommand.getKey();
         storeEntry(rCtx, key, putKeyValueCommand);
         if (getStatisticsEnabled())
            cacheStores.incrementAndGet();
      });
   }

   @Override
   public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
      return invokeNextThenAccept(ctx, command, (rCtx, rCommand, rv) -> {
         ReplaceCommand replaceCommand = (ReplaceCommand) rCommand;
         if (!isStoreEnabled(replaceCommand) || rCtx.isInTxScope() || !replaceCommand.isSuccessful())
            return;
         if (!isProperWriter(rCtx, replaceCommand, replaceCommand.getKey()))
            return;

         Object key = replaceCommand.getKey();
         storeEntry(rCtx, key, replaceCommand);
         if (getStatisticsEnabled())
            cacheStores.incrementAndGet();
      });
   }

   @Override
   public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
      return invokeNextThenAccept(ctx, command, (rCtx, rCommand, rv) -> {
         PutMapCommand putMapCommand = (PutMapCommand) rCommand;
         if (!isStoreEnabled(putMapCommand) || rCtx.isInTxScope())
            return;

         Map<Object, Object> map = putMapCommand.getMap();
         for (Object key : map.keySet()) {
            if (isProperWriter(rCtx, putMapCommand, key)) {
               storeEntry(rCtx, key, putMapCommand);
            }
         }
         if (getStatisticsEnabled())
            cacheStores.getAndAdd(map.size());
      });
   }

   @Override
   public Object visitReadWriteKeyCommand(InvocationContext ctx, ReadWriteKeyCommand command)
         throws Throwable {
      return visitWriteCommand(ctx, command);
   }

   @Override
   public Object visitReadWriteKeyValueCommand(InvocationContext ctx, ReadWriteKeyValueCommand command)
         throws Throwable {
      return visitWriteCommand(ctx, command);
   }

   @Override
   public Object visitWriteOnlyKeyCommand(InvocationContext ctx, WriteOnlyKeyCommand command)
         throws Throwable {
      return visitWriteCommand(ctx, command);
   }

   @Override
   public Object visitWriteOnlyKeyValueCommand(InvocationContext ctx, WriteOnlyKeyValueCommand command)
         throws Throwable {
      return visitWriteCommand(ctx, command);
   }


   private <T extends DataWriteCommand & FunctionalCommand> Object visitWriteCommand(InvocationContext ctx,
         VisitableCommand command) throws Throwable {
      return invokeNextThenAccept(ctx, command, (rCtx, rCommand, rv) -> {
         T dataWriteCommand = (T) rCommand;
         if (!isStoreEnabled(dataWriteCommand) || rCtx.isInTxScope() || !dataWriteCommand.isSuccessful())
            return;
         if (!isProperWriter(rCtx, dataWriteCommand, dataWriteCommand.getKey()))
            return;

         Param<PersistenceMode> persistMode = dataWriteCommand.getParams().get(PersistenceMode.ID);
         switch (persistMode.get()) {
            case PERSIST:
               Object key = dataWriteCommand.getKey();
               CacheEntry entry = rCtx.lookupEntry(key);
               if (entry != null) {
                  if (entry.isRemoved()) {
                     boolean resp = persistenceManager.deleteFromAllStores(key, BOTH);
                     if (trace)
                        getLog().tracef("Removed entry under key %s and got response %s from CacheStore", key,
                              resp);
                  } else if (entry.isChanged()) {
                     storeEntry(rCtx, key, dataWriteCommand);
                  }
               }
               log.trace("Skipping cache store since entry was not found in context");
               break;
            case SKIP:
               log.trace("Skipping cache store since persistence mode parameter is SKIP");
         }
      });
   }

   @Override
   public Object visitWriteOnlyManyCommand(InvocationContext ctx, WriteOnlyManyCommand command)
         throws Throwable {
      return visitWriteManyCommand(ctx, command);
   }

   @Override
   public Object visitWriteOnlyManyEntriesCommand(InvocationContext ctx, WriteOnlyManyEntriesCommand command)
         throws Throwable {
      return visitWriteManyCommand(ctx, command);
   }

   @Override
   public Object visitReadWriteManyCommand(InvocationContext ctx, ReadWriteManyCommand command)
         throws Throwable {
      return visitWriteManyCommand(ctx, command);
   }

   @Override
   public Object visitReadWriteManyEntriesCommand(InvocationContext ctx, ReadWriteManyEntriesCommand command)
         throws Throwable {
      return visitWriteManyCommand(ctx, command);
   }

   private <T extends WriteCommand & FunctionalCommand> Object visitWriteManyCommand(InvocationContext ctx,
                                                                                                   WriteCommand command) throws Throwable {
      return invokeNextThenAccept(ctx, command, (rCtx, rCommand, rv) -> {
         T manyEntriesCommand = (T) rCommand;
         if (!isStoreEnabled(manyEntriesCommand) || rCtx.isInTxScope())
            return;

         Param<PersistenceMode> persistMode = manyEntriesCommand.getParams().get(PersistenceMode.ID);
         switch (persistMode.get()) {
            case PERSIST:
               int storedCount = 0;
               for (Object key : ((WriteCommand) rCommand).getAffectedKeys()) {
                  CacheEntry entry = rCtx.lookupEntry(key);
                  if (entry != null) {
                     if (entry.isRemoved()) {
                        boolean resp = persistenceManager.deleteFromAllStores(key, BOTH);
                        if (trace) getLog().tracef("Removed entry under key %s and got response %s from CacheStore", key, resp);
                     } else {
                        if (entry.isChanged() && isProperWriter(rCtx, manyEntriesCommand, key)) {
                           storeEntry(rCtx, key, manyEntriesCommand);
                           storedCount++;
                        }
                     }
                  }
               }

               if (getStatisticsEnabled())
                  cacheStores.getAndAdd(storedCount);
               break;
            case SKIP:
               log.trace("Skipping cache store since persistence mode parameter is SKIP");
         }
      });
   }

   protected final void store(TxInvocationContext ctx) throws Throwable {
      List<WriteCommand> modifications = ctx.getCacheTransaction().getAllModifications();
      if (modifications.isEmpty()) {
         if (trace) getLog().trace("Transaction has not logged any modifications!");
         return;
      }
      if (trace) getLog().tracef("Cache loader modification list: %s", modifications);


      Updater modsBuilder = new Updater(getStatisticsEnabled());
      for (WriteCommand cacheCommand : modifications) {
         if (isStoreEnabled(cacheCommand)) {
            cacheCommand.acceptVisitor(ctx, modsBuilder);
         }
      }
      if (getStatisticsEnabled() && modsBuilder.putCount > 0) {
         cacheStores.getAndAdd(modsBuilder.putCount);
      }
   }

   protected boolean isStoreEnabled(FlagAffectedCommand command) {
      if (command.hasAnyFlag(FlagBitSets.SKIP_CACHE_STORE)) {
         log.trace("Skipping cache store since the call contain a skip cache store flag");
         return false;
      }
      return true;
   }

   protected boolean isProperWriter(InvocationContext ctx, FlagAffectedCommand command, Object key) {
      return true;
   }

   public class Updater extends AbstractVisitor {

      protected final boolean generateStatistics;
      int putCount;

      public Updater(boolean generateStatistics) {
         this.generateStatistics = generateStatistics;
      }

      @Override
      public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
         return visitSingleStore(ctx, command, command.getKey());
      }

      @Override
      public Object visitApplyDeltaCommand(InvocationContext ctx, ApplyDeltaCommand command) throws Throwable {
         if (isProperWriter(ctx, command, command.getKey())) {
            if (generateStatistics) putCount++;
            CacheEntry entry = ctx.lookupEntry(command.getKey());
            // If the value is null, there is a subsequent remove operation in the transaction and we can ignore
            // this modification.
            if (entry.getValue() == null) {
               return null;
            }
            InternalCacheEntry ice;
            if (entry instanceof InternalCacheEntry) {
               ice = (InternalCacheEntry) entry;
            } else if (entry instanceof DeltaAwareCacheEntry) {
               AtomicHashMap<?,?> uncommittedChanges = ((DeltaAwareCacheEntry) entry).getUncommittedChages();
               ice = entryFactory.create(entry.getKey(), uncommittedChanges, entry.getMetadata(), entry.getLifespan(), entry.getMaxIdle());
            } else {
               ice = entryFactory.create(entry);
            }
            MarshalledEntryImpl marshalledEntry = new MarshalledEntryImpl(ice.getKey(), ice.getValue(), internalMetadata(ice), marshaller);
            persistenceManager.writeToAllNonTxStores(marshalledEntry, skipSharedStores(ctx, command.getKey(), command) ? PRIVATE : BOTH);
         }
         return null;
      }

      @Override
      public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
         return visitSingleStore(ctx, command, command.getKey());
      }

      @Override
      public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
         Map<Object, Object> map = command.getMap();
         for (Object key : map.keySet())
            visitSingleStore(ctx, command, key);
         return null;
      }

      @Override
      public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
         Object key = command.getKey();
         if (isProperWriter(ctx, command, key)) {
            persistenceManager.deleteFromAllStores(key, skipSharedStores(ctx, key, command) ? PRIVATE : BOTH);
         }
         return null;
      }

      @Override
      public Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
         persistenceManager.clearAllStores(ctx.isOriginLocal() ? PRIVATE : BOTH);
         return null;
      }

      protected Object visitSingleStore(InvocationContext ctx, FlagAffectedCommand command, Object key) throws Throwable {
         if (isProperWriter(ctx, command, key)) {
            if (generateStatistics) putCount++;
            InternalCacheValue sv = entryFactory.getValueFromCtxOrCreateNew(key, ctx);
            // TODO: we should merge all modifications and write each key only once
            // If the value in context is null, the transaction contains a subsequent remove,
            // so we'll ignore this modification
            if (sv.getValue() != null) {
               MarshalledEntryImpl me = new MarshalledEntryImpl(key, sv.getValue(), internalMetadata(sv), marshaller);
               persistenceManager.writeToAllNonTxStores(me, skipSharedStores(ctx, key, command) ? PRIVATE : BOTH);
            }
         }
         return null;
      }
   }

   @Override
   @ManagedOperation(
         description = "Resets statistics gathered by this component",
         displayName = "Reset statistics"
   )
   public void resetStatistics() {
      cacheStores.set(0);
   }

   @ManagedAttribute(
         description = "Number of writes to the store",
         displayName = "Number of writes to the store",
         measurementType = MeasurementType.TRENDSUP
   )
   public long getWritesToTheStores() {
      return cacheStores.get();
   }

   void storeEntry(InvocationContext ctx, Object key, FlagAffectedCommand command) {
      InternalCacheValue sv = getStoredValue(key, ctx);
      persistenceManager.writeToAllNonTxStores(new MarshalledEntryImpl(key, sv.getValue(), internalMetadata(sv), marshaller),
                                               skipSharedStores(ctx, key, command) ? PRIVATE : BOTH);
      if (trace) getLog().tracef("Stored entry %s under key %s", sv, key);
   }

   protected boolean skipSharedStores(InvocationContext ctx, Object key, FlagAffectedCommand command) {
      return !ctx.isOriginLocal() ||
            command.hasAnyFlag(FlagBitSets.SKIP_SHARED_CACHE_STORE);
   }

   InternalCacheValue getStoredValue(Object key, InvocationContext ctx) {
      CacheEntry entry = ctx.lookupEntry(key);
      if (entry instanceof InternalCacheEntry) {
         return ((InternalCacheEntry) entry).toInternalCacheValue();
      } else {
         if (ctx.isInTxScope()) {
            EntryVersionsMap updatedVersions =
                  ((TxInvocationContext) ctx).getCacheTransaction().getUpdatedEntryVersions();
            if (updatedVersions != null) {
               EntryVersion version = updatedVersions.get(entry.getKey());
               if (version != null) {
                  Metadata metadata = entry.getMetadata();
                  if (metadata == null) {
                     // If no metadata passed, assumed embedded metadata
                     metadata = new EmbeddedMetadata.Builder().lifespan(entry.getLifespan()).maxIdle(entry.getMaxIdle())
                                                              .version(version).build();
                     return entryFactory.create(entry.getKey(), entry.getValue(), metadata).toInternalCacheValue();
                  } else {
                     metadata = metadata.builder().version(version).build();
                     return entryFactory.create(entry.getKey(), entry.getValue(), metadata).toInternalCacheValue();
                  }
               }
            }
         }

         return entryFactory.create(entry).toInternalCacheValue();
      }
   }
}
