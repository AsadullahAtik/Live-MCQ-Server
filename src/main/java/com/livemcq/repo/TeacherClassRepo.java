package com.livemcq.repo;

import com.livemcq.domain.TeacherClass;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeacherClassRepo extends JpaRepository<TeacherClass, Long> {
    List<TeacherClass> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
