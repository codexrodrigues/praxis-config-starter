package org.praxisplatform.config.repository;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.praxisplatform.config.domain.AiThread;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiThreadRepository extends JpaRepository<AiThread, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AiThread t where t.threadId = :threadId")
    Optional<AiThread> findByThreadIdForUpdate(@Param("threadId") UUID threadId);
}
