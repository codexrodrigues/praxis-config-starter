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
  static final int MAX_ENTRIES = 16;
  static final long MAX_BYTES = 8L * 1024 * 1024;
  private final Object owner;
  private final ConnectionHolder holder;
  private final Connection connection;
  private final LinkedHashMap<Object, String> values = new LinkedHashMap<>(16, 0.75f, true);
  private long bytes;

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
    ConnectionHolder currentHolder = guard.require();
    ConfigTransactionReadSet readSet = null;
    // Spring suspends this list with the transaction and restores it on resume. No global
    // registry or separately bound resource can leak into REQUIRES_NEW.
    for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof ConfigTransactionReadSet existing && existing.owner == owner) {
        readSet = existing;
        break;
      }
    }
    if (readSet == null) {
      readSet = new ConfigTransactionReadSet(owner, currentHolder);
      TransactionSynchronizationManager.registerSynchronization(readSet);
    }
    readSet.requireConnection(currentHolder);
    String cached = readSet.values.get(key);
    if (cached != null) return Optional.of(cached);
    Optional<String> loaded = loader.get();
    readSet.requireConnection(guard.require());
    if (loaded.isPresent()) readSet.retain(key, loaded.orElseThrow());
    return loaded;
  }

  private void requireConnection(ConnectionHolder currentHolder) {
    if (holder != currentHolder || connection != currentHolder.getConnection()) {
      throw new UiLayoutLifecycleException(
          UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
          "The immutable read set belongs to another Config transaction.");
    }
  }

  private void retain(Object key, String value) {
    long size = size(value);
    if (size > MAX_BYTES) return;
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
  public void afterCompletion(int status) {
    values.clear();
    bytes = 0;
  }
}
