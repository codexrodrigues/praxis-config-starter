package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Item materializado de um catalogo semantico: context, node, edge, binding, alias, evidence ou governance.
 */
@Entity
@Table(
    name = "domain_catalog_item",
    uniqueConstraints = @UniqueConstraint(columnNames = {"release_id", "item_type", "item_key"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainCatalogItem {

  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "release_id", nullable = false)
  private DomainCatalogRelease release;

  @Column(name = "item_type", nullable = false, length = 32)
  private String itemType;

  @Column(name = "item_key", nullable = false, length = 512)
  private String itemKey;

  @Column(name = "context_key", length = 255)
  private String contextKey;

  @Column(name = "node_type", length = 64)
  private String nodeType;

  @Column(name = "binding_type", length = 64)
  private String bindingType;

  @Column(name = "edge_type", length = 64)
  private String edgeType;

  @Column(columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String payload;

  @Column(name = "searchable_text", columnDefinition = "TEXT")
  private String searchableText;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  public void onInsert() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
