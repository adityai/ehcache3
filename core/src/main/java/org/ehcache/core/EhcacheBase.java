/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ehcache.core;

import org.ehcache.Cache;
import org.ehcache.Status;
import org.ehcache.config.CacheRuntimeConfiguration;
import org.ehcache.core.events.CacheEventDispatcher;
import org.ehcache.core.internal.resilience.LoggingRobustResilienceStrategy;
import org.ehcache.core.internal.resilience.RecoveryCache;
import org.ehcache.core.internal.resilience.ResilienceStrategy;
import org.ehcache.core.spi.LifeCycled;
import org.ehcache.core.spi.store.Store;
import org.ehcache.core.spi.store.Store.ValueHolder;
import org.ehcache.core.spi.store.StoreAccessException;
import org.ehcache.core.statistics.BulkOps;
import org.ehcache.core.statistics.CacheOperationOutcomes.ClearOutcome;
import org.ehcache.core.statistics.CacheOperationOutcomes.ConditionalRemoveOutcome;
import org.ehcache.core.statistics.CacheOperationOutcomes.GetAllOutcome;
import org.ehcache.core.statistics.CacheOperationOutcomes.GetOutcome;
import org.ehcache.core.statistics.CacheOperationOutcomes.PutAllOutcome;
import org.ehcache.core.statistics.CacheOperationOutcomes.PutIfAbsentOutcome;
import org.ehcache.core.statistics.CacheOperationOutcomes.PutOutcome;
import org.ehcache.core.statistics.CacheOperationOutcomes.RemoveAllOutcome;
import org.ehcache.core.statistics.CacheOperationOutcomes.RemoveOutcome;
import org.ehcache.core.statistics.CacheOperationOutcomes.ReplaceOutcome;
import org.ehcache.expiry.ExpiryPolicy;
import org.ehcache.spi.loaderwriter.BulkCacheLoadingException;
import org.ehcache.spi.loaderwriter.CacheWritingException;
import org.slf4j.Logger;
import org.terracotta.statistics.StatisticsManager;
import org.terracotta.statistics.observer.OperationObserver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import static org.ehcache.core.exceptions.ExceptionFactory.newCacheLoadingException;
import static org.terracotta.statistics.StatisticBuilder.operation;

/**
 * Base implementation of the {@link Cache} interface that is common to all Ehcache implementation
 */
public abstract class EhcacheBase<K, V> implements InternalCache<K, V> {

  protected final Logger logger;

  protected final StatusTransitioner statusTransitioner;

  protected final Store<K, V> store;
  protected final ResilienceStrategy<K, V> resilienceStrategy;
  protected final EhcacheRuntimeConfiguration<K, V> runtimeConfiguration;

  protected final OperationObserver<GetOutcome> getObserver = operation(GetOutcome.class).named("get").of(this).tag("cache").build();
  protected final OperationObserver<GetAllOutcome> getAllObserver = operation(GetAllOutcome.class).named("getAll").of(this).tag("cache").build();
  protected final OperationObserver<PutOutcome> putObserver = operation(PutOutcome.class).named("put").of(this).tag("cache").build();
  protected final OperationObserver<PutAllOutcome> putAllObserver = operation(PutAllOutcome.class).named("putAll").of(this).tag("cache").build();
  protected final OperationObserver<RemoveOutcome> removeObserver = operation(RemoveOutcome.class).named("remove").of(this).tag("cache").build();
  protected final OperationObserver<RemoveAllOutcome> removeAllObserver = operation(RemoveAllOutcome.class).named("removeAll").of(this).tag("cache").build();
  protected final OperationObserver<ConditionalRemoveOutcome> conditionalRemoveObserver = operation(ConditionalRemoveOutcome.class).named("conditionalRemove").of(this).tag("cache").build();
  protected final OperationObserver<PutIfAbsentOutcome> putIfAbsentObserver = operation(PutIfAbsentOutcome.class).named("putIfAbsent").of(this).tag("cache").build();
  protected final OperationObserver<ReplaceOutcome> replaceObserver = operation(ReplaceOutcome.class).named("replace").of(this).tag("cache").build();
  protected final OperationObserver<ClearOutcome> clearObserver = operation(ClearOutcome.class).named("clear").of(this).tag("cache").build();

  protected final Map<BulkOps, LongAdder> bulkMethodEntries = new EnumMap<>(BulkOps.class);

  /**
   * Creates a new {@code EhcacheBase} based on the provided parameters.
   *
   * @param runtimeConfiguration the cache configuration
   * @param store the store to use
   * @param eventDispatcher the event dispatcher
   * @param logger the logger
   */
  EhcacheBase(EhcacheRuntimeConfiguration<K, V> runtimeConfiguration, Store<K, V> store,
          CacheEventDispatcher<K, V> eventDispatcher, Logger logger, StatusTransitioner statusTransitioner) {
    this.store = store;
    runtimeConfiguration.addCacheConfigurationListener(store.getConfigurationChangeListeners());
    StatisticsManager.associate(store).withParent(this);

    if (store instanceof RecoveryCache) {
      this.resilienceStrategy = new LoggingRobustResilienceStrategy<>(castToRecoveryCache(store));
    } else {
      this.resilienceStrategy = new LoggingRobustResilienceStrategy<>(recoveryCache(store));
    }

    this.runtimeConfiguration = runtimeConfiguration;
    runtimeConfiguration.addCacheConfigurationListener(eventDispatcher.getConfigurationChangeListeners());

    this.logger = logger;
    this.statusTransitioner = statusTransitioner;
    for (BulkOps bulkOp : BulkOps.values()) {
      bulkMethodEntries.put(bulkOp, new LongAdder());
    }
  }

  @SuppressWarnings("unchecked")
  private RecoveryCache<K> castToRecoveryCache(Store<K, V> store) {
    return (RecoveryCache<K>) store;
  }

  private static <K> RecoveryCache<K> recoveryCache(final Store<K, ?> store) {
    return new RecoveryCache<K>() {

      @Override
      public void obliterate() throws StoreAccessException {
        store.clear();
      }

      @Override
      public void obliterate(K key) throws StoreAccessException {
        store.remove(key);
      }

      @Override
      public void obliterate(Iterable<? extends K> keys) throws StoreAccessException {
        for (K key : keys) {
          obliterate(key);
        }
      }
    };
  }

  protected V getNoLoader(K key) {
    getObserver.begin();
    statusTransitioner.checkAvailable();
    checkNonNull(key);

    try {
      final Store.ValueHolder<V> valueHolder = store.get(key);

      // Check for expiry first
      if (valueHolder == null) {
        getObserver.end(GetOutcome.MISS);
        return null;
      } else {
        getObserver.end(GetOutcome.HIT);
        return valueHolder.get();
      }
    } catch (StoreAccessException e) {
      try {
        return resilienceStrategy.getFailure(key, e);
      } finally {
        getObserver.end(GetOutcome.FAILURE);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean containsKey(final K key) {
    statusTransitioner.checkAvailable();
    checkNonNull(key);
    try {
      return store.containsKey(key);
    } catch (StoreAccessException e) {
      return resilienceStrategy.containsKeyFailure(key, e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void remove(K key) throws CacheWritingException {
    removeInternal(key); // ignore return value;
  }

  protected abstract boolean removeInternal(final K key);

  /**
   * {@inheritDoc}
   */
  @Override
  public void clear() {
    clearObserver.begin();
    statusTransitioner.checkAvailable();
    try {
      store.clear();
      clearObserver.end(ClearOutcome.SUCCESS);
    } catch (StoreAccessException e) {
      clearObserver.end(ClearOutcome.FAILURE);
      resilienceStrategy.clearFailure(e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Iterator<Entry<K, V>> iterator() {
    statusTransitioner.checkAvailable();
    return new CacheEntryIterator(false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<K, V> getAll(Set<? extends K> keys) throws BulkCacheLoadingException {
    return getAllInternal(keys, true);
  }

  protected abstract Map<K,V> getAllInternal(Set<? extends K> keys, boolean b);


  protected boolean newValueAlreadyExpired(K key, V oldValue, V newValue) {
    return newValueAlreadyExpired(logger, runtimeConfiguration.getExpiryPolicy(), key, oldValue, newValue);
  }

  protected static <K, V> boolean newValueAlreadyExpired(Logger logger, ExpiryPolicy<? super K, ? super V> expiry, K key, V oldValue, V newValue) {
    if (newValue == null) {
      return false;
    }

    Duration duration;
    try {
      if (oldValue == null) {
        duration = expiry.getExpiryForCreation(key, newValue);
      } else {
        duration = expiry.getExpiryForUpdate(key, () -> oldValue, newValue);
      }
    } catch (RuntimeException re) {
      logger.error("Expiry computation caused an exception - Expiry duration will be 0 ", re);
      return true;
    }

    return Duration.ZERO.equals(duration);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CacheRuntimeConfiguration<K, V> getRuntimeConfiguration() {
    return runtimeConfiguration;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void init() {
    statusTransitioner.init().succeeded();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() {
    statusTransitioner.close().succeeded();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Status getStatus() {
    return statusTransitioner.currentStatus();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addHook(LifeCycled hook) {
    statusTransitioner.addHook(hook);
  }

  void removeHook(LifeCycled hook) {
    statusTransitioner.removeHook(hook);
  }

  protected void addBulkMethodEntriesCount(BulkOps op, long count) {
    bulkMethodEntries.get(op).add(count);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<BulkOps, LongAdder> getBulkMethodEntries() {
    return bulkMethodEntries;
  }

  protected static void checkNonNull(Object thing) {
    Objects.requireNonNull(thing);
  }

  protected static void checkNonNull(Object... things) {
    for (Object thing : things) {
      checkNonNull(thing);
    }
  }

  protected void checkNonNullContent(Collection<?> collectionOfThings) {
    checkNonNull(collectionOfThings);
    for (Object thing : collectionOfThings) {
      checkNonNull(thing);
    }
  }

  protected abstract class Jsr107CacheBase implements Jsr107Cache<K, V> {

    @Override
    public void loadAll(Set<? extends K> keys, boolean replaceExistingValues, Function<Iterable<? extends K>, Map<K, V>> loadFunction) {
      if(keys.isEmpty()) {
        return ;
      }
      if (replaceExistingValues) {
        loadAllReplace(keys, loadFunction);
      } else {
        loadAllAbsent(keys, loadFunction);
      }
    }

    @Override
    public Iterator<Entry<K, V>> specIterator() {
      return new SpecIterator<>(this, store);
    }

    @Override
    public V getNoLoader(K key) {
      return EhcacheBase.this.getNoLoader(key);
    }

    @Override
    public Map<K, V> getAll(Set<? extends K> keys) {
      return getAllInternal(keys, false);
    }

    private void loadAllAbsent(Set<? extends K> keys, final Function<Iterable<? extends K>, Map<K, V>> loadFunction) {
      try {
        store.bulkComputeIfAbsent(keys, absentKeys -> cacheLoaderWriterLoadAllForKeys(absentKeys, loadFunction).entrySet());
      } catch (StoreAccessException e) {
        throw newCacheLoadingException(e);
      }
    }

    Map<K, V> cacheLoaderWriterLoadAllForKeys(Iterable<? extends K> keys, Function<Iterable<? extends K>, Map<K, V>> loadFunction) {
      try {
        Map<? super K, ? extends V> loaded = loadFunction.apply(keys);

        // put into a new map since we can't assume the 107 cache loader returns things ordered, or necessarily with all the desired keys
        Map<K, V> rv = new LinkedHashMap<>();
        for (K key : keys) {
          rv.put(key, loaded.get(key));
        }
        return rv;
      } catch (Exception e) {
        throw newCacheLoadingException(e);
      }
    }

    private void loadAllReplace(Set<? extends K> keys, final Function<Iterable<? extends K>, Map<K, V>> loadFunction) {
      try {
        store.bulkCompute(keys, entries -> {
          Collection<K> keys1 = new ArrayList<>();
          for (Map.Entry<? extends K, ? extends V> entry : entries) {
            keys1.add(entry.getKey());
          }
          return cacheLoaderWriterLoadAllForKeys(keys1, loadFunction).entrySet();
        });
      } catch (StoreAccessException e) {
        throw newCacheLoadingException(e);
      }
    }

    @Override
    public boolean remove(K key) {
      return EhcacheBase.this.removeInternal(key);
    }

    @Override
    public void removeAll() {
      Store.Iterator<Entry<K, ValueHolder<V>>> iterator = store.iterator();
      while (iterator.hasNext()) {
        try {
          Entry<K, ValueHolder<V>> next = iterator.next();
          remove(next.getKey());
        } catch (StoreAccessException cae) {
          // skip
        }
      }
    }

  }

  private class CacheEntryIterator implements Iterator<Entry<K, V>> {

    private final Store.Iterator<Entry<K, ValueHolder<V>>> iterator;
    private final boolean quiet;
    private Cache.Entry<K, ValueHolder<V>> current;
    private Cache.Entry<K, ValueHolder<V>> next;
    private StoreAccessException nextException;

    public CacheEntryIterator(boolean quiet) {
      this.quiet = quiet;
      this.iterator = store.iterator();
      advance();
    }

    private void advance() {
      try {
        while (iterator.hasNext()) {
          next = iterator.next();
          if (getNoLoader(next.getKey()) != null) {
            return;
          }
        }
        next = null;
      } catch (RuntimeException re) {
        nextException = new StoreAccessException(re);
        next = null;
      } catch (StoreAccessException cae) {
        nextException = cae;
        next = null;
      }
    }

    @Override
    public boolean hasNext() {
      statusTransitioner.checkAvailable();
      return nextException != null || next != null;
    }

    @Override
    public Entry<K, V> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }

      if (!quiet) getObserver.begin();
      if (nextException == null) {
        if (!quiet) getObserver.end(GetOutcome.HIT);
        current = next;
        advance();
        return new ValueHolderBasedEntry<>(current);
      } else {
        if (!quiet) getObserver.end(GetOutcome.FAILURE);
        StoreAccessException cae = nextException;
        nextException = null;
        return resilienceStrategy.iteratorFailure(cae);
      }
    }

    @Override
    public void remove() {
      statusTransitioner.checkAvailable();
      if (current == null) {
        throw new IllegalStateException("No current element");
      }
      EhcacheBase.this.remove(current.getKey(), current.getValue().get());
      current = null;
    }
  }

  private static class ValueHolderBasedEntry<K, V> implements Cache.Entry<K, V> {
    private final Cache.Entry<K, ValueHolder<V>> storeEntry;

    ValueHolderBasedEntry(Cache.Entry<K, ValueHolder<V>> storeEntry) {
      this.storeEntry = storeEntry;
    }

    @Override
    public K getKey() {
      return storeEntry.getKey();
    }

    @Override
    public V getValue() {
      return storeEntry.getValue().get();
    }

  }
}

