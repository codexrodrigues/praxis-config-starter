package org.praxisplatform.config.service;

import java.util.List;
import java.util.UUID;
import org.springframework.transaction.TransactionStatus;

/** Internal storage boundary. Production composition owns the Config JDBC implementation. */
interface UiLayoutMetadataCapturePersistence {
  void append(TransactionStatus status, UiLayoutLifecycleInvocation invocation, UUID draft,
      List<UiLayoutMetadataCapture> captures, UiLayoutMetadataCaptureAccess access);
  /** Returns exact persisted evidence after binding/integrity verification and current access checks
   * before and after the pure verifier. Every implementation owns these checks; callers must not
   * repeat the verifier or treat the returned evidence as authority for later boundaries. */
  UiLayoutMetadataCapture readVerified(UiLayoutLifecycleInvocation invocation, UiLayoutMetadataCapture.Binding expected,
      UiLayoutMetadataCaptureAccess access);
}
