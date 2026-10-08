package org.praxisplatform.config.service;

import java.util.List;
import java.util.UUID;
import org.springframework.transaction.TransactionStatus;

/** Internal storage boundary. Production composition owns the Config JDBC implementation. */
interface UiLayoutMetadataCapturePersistence {
  void append(TransactionStatus status, UiLayoutLifecycleInvocation invocation, UUID draft,
      List<UiLayoutMetadataCapture> captures, UiLayoutMetadataCaptureAccess access);
  UiLayoutMetadataCapture read(UiLayoutLifecycleInvocation invocation, UiLayoutMetadataCapture.Binding expected,
      UiLayoutMetadataCaptureAccess access);
}
