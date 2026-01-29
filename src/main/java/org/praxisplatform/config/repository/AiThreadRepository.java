package org.praxisplatform.config.repository;

import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.AiThread;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiThreadRepository extends JpaRepository<AiThread, UUID> {
}
