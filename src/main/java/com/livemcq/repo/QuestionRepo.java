package com.livemcq.repo;

import com.livemcq.domain.Question;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepo extends JpaRepository<Question, Long> {
    List<Question> findByExamIdOrderBySortNo(Long examId);
    long countByExamId(Long examId);
    void deleteByExamId(Long examId);
}
