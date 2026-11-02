package com.livemcq.repo;

import com.livemcq.domain.AppUser;
import com.livemcq.domain.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepo extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByRoll(String roll);
    boolean existsByRoll(String roll);
    List<AppUser> findByRoleOrderByRoll(Role role);
}
