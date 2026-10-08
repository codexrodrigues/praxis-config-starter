package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Fixed contribution of one exact target to an immutable aggregate release. */
@Entity
@Table(name = "ui_layout_release_member")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiLayoutReleaseMember {
  @Id private UUID id;
  @Column(name = "release_id", nullable = false) private UUID releaseId;
  @Column(name = "member_order", nullable = false) private int memberOrder;
  @Column(name = "component_type", nullable = false, length = 64) private String componentType;
  @Column(name = "component_id", nullable = false, length = 255) private String componentId;
  @Column(name = "assignment_revision_id", nullable = false) private UUID assignmentRevisionId;
  @Column(name = "content_revision_id", nullable = false) private UUID contentRevisionId;
  @Column(name = "contribution_key", nullable = false, length = 255) private String contributionKey;
}
