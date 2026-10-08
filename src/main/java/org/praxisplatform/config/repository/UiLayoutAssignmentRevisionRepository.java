package org.praxisplatform.config.repository;

import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutAssignmentRevision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UiLayoutAssignmentRevisionRepository
    extends JpaRepository<UiLayoutAssignmentRevision, UUID> {}
