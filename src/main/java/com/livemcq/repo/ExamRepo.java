package com.livemcq.repo;

import com.livemcq.domain.Exam;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamRepo extends JpaRepository<Exam, Long> {
    List<Exam> findAllByOrderByIdDesc();
    List<Exam> findByClassIdOrderByIdDesc(Long classId);
}
