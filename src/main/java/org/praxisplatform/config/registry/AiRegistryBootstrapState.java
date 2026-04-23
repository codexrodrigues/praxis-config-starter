package org.praxisplatform.config.registry;

import java.time.Instant;
import lombok.Data;

@Data
public class AiRegistryBootstrapState {

    private boolean attempted;
    private boolean succeeded;
    private boolean skipped;
    private boolean fallbackUsed;

    private Instant attemptedAt;
    private Instant completedAt;

    private String requestedSnapshotLocation;
    private String resolvedSnapshotLocation;
    private String source;
    private String error;
    private String snapshotHash;
    private String previousSnapshotHash;
    private long snapshotComponentCount;
    private String skipReason;
}
