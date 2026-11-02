package com.livemcq.repo;

import com.livemcq.domain.SchoolClass;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassRepo extends JpaRepository<SchoolClass, Long> {}
