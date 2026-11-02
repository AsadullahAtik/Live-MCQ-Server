package com.livemcq.repo;

import com.livemcq.domain.Attempt;
import com.livemcq.domain.AttemptStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepo extends JpaRepository<Attempt, Long> {
    Optional<Attempt> findByExamIdAndUserId(Long examId, Long userId);
    List<Attempt> findByExamIdAndStatus(Long examId, AttemptStatus status);
    List<Attempt> findByUserId(Long userId);
    List<Attempt> findByStatusAndDeadlineBefore(AttemptStatus status, Instant t);
    long countByExamId(Long examId);
    void deleteByExamId(Long examId);
}
