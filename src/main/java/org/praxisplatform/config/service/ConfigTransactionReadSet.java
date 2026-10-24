package org.praxisplatform.config.service;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Positive immutable bytes only; never retains an authority context or admission decision. */
final class ConfigTransactionReadSet implements TransactionSynchronization {
  static final int MAX_ENTRIES = 128;
  static final long MAX_BYTES = 8L * 1024 * 1024;
  private final Object owner;
  private final ConnectionHolder holder;
  private final Connection connection;
  private final LinkedHashMap<Object, String> values = new LinkedHashMap<>(16, 0.75f, true);
  private long bytes;
  private boolean reusable = true;

  private ConfigTransactionReadSet(Object owner, ConnectionHolder holder) {
    this.owner = owner;
    this.holder = holder;
    this.connection = holder.getConnection();
  }

  static Optional<String> read(
      Object owner,
      ConfigJdbcTransactionGuard guard,
      Object key,
      Supplier<Optional<String>> loader) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      return loader.get();
    }
    ConfigTransactionReadSet readSet = admitted(owner, guard);
    guard.requireImmutableReadTransaction(readSet.holder, readSet.connection);
    readSet.reusable &= guard.mayReuseImmutableReads();
    String cached = readSet.reusable ? readSet.values.get(key) : null;
    if (cached != null) return Optional.of(cached);
    Optional<String> loaded = loader.get();
    guard.requireImmutableReadTransaction(readSet.holder, readSet.connection);
    readSet.reusable &= guard.mayReuseImmutableReads();
    if (loaded.isPresent()) readSet.retain(key, loaded.orElseThrow());
    return loaded;
  }

  /** SQL is executed only inside the admitted transaction; no caller-loaded map is accepted. */
  static int prefetch(Object owner, ConfigJdbcTransactionGuard guard,
      Supplier<Prefetched> loader) {
    ConfigTransactionReadSet readSet = admitted(owner, guard);
    guard.requireImmutableReadTransaction(readSet.holder, readSet.connection);
    readSet.reusable &= guard.mayReuseImmutableReads();
    Prefetched loaded = loader.get();
    guard.requireImmutableReadTransaction(readSet.holder, readSet.connection);
    readSet.reusable &= guard.mayReuseImmutableReads();
    long total = loaded.values().values().stream().mapToLong(ConfigTransactionReadSet::size).sum();
    if (loaded.consumed() < 1 || loaded.consumed() > MAX_ENTRIES
        || loaded.values().size() > loaded.consumed() || total > MAX_BYTES) {
      throw new IllegalStateException("Immutable evidence prefix exceeds its bounds.");
    }
    // The entire prefix fits: retaining it cannot evict an earlier entry of this prefix.
    loaded.values().forEach(readSet::retain);
    return loaded.consumed();
  }

  record Prefetched(int consumed, java.util.Map<Object, String> values) {
    Prefetched { values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
  }

  private static ConfigTransactionReadSet admitted(Object owner, ConfigJdbcTransactionGuard guard) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Immutable prefetch requires a Config transaction.");
    }
    for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof ConfigTransactionReadSet existing && existing.owner == owner) {
        return existing;
      }
    }
    // Spring suspends this list for REQUIRES_NEW; the proof is never a global resource.
    var created = new ConfigTransactionReadSet(owner, guard.admitImmutableReadTransaction());
    TransactionSynchronizationManager.registerSynchronization(created);
    return created;
  }

  private void retain(Object key, String value) {
    if (!reusable) return;
    long size = size(value);
    if (size > MAX_BYTES) return;
    String previous = values.remove(key);
    if (previous != null) bytes -= size(previous);
    while (!values.isEmpty() && (values.size() >= MAX_ENTRIES || bytes + size > MAX_BYTES)) {
      var iterator = values.entrySet().iterator();
      bytes -= size(iterator.next().getValue());
      iterator.remove();
    }
    values.put(key, value);
    bytes += size;
  }

  private static long size(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  @Override
  public void savepointRollback(Object savepoint) {
    // A savepoint rollback retains the synchronization list, but rows loaded since the
    // savepoint may no longer exist. This callback precedes the physical rollback;
    // later synchronizations can re-enter a read. Do not retain their pre-rollback
    // bytes, or reuse isolation proof, for the rest of the transaction.
    reusable = false;
    clear();
  }

  @Override
  public void afterCompletion(int status) {
    reusable = false;
    clear();
  }

  private void clear() {
    values.clear();
    bytes = 0;
  }
}
