package org.com.sharekhan.repository;

import org.com.sharekhan.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> lockForEnrollment(@org.springframework.data.repository.query.Param("id") Long id);
    boolean existsByUsernameIgnoreCase(String username);
    Optional<AppUser> findByUsername(String username);
}

