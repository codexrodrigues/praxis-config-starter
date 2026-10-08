package org.praxisplatform.config.repository;

import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutReleaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UiLayoutReleaseEventRepository extends JpaRepository<UiLayoutReleaseEvent, UUID> {}
