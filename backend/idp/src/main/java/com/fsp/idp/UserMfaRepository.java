package com.fsp.idp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserMfaRepository extends JpaRepository<UserMfa, UUID> {
    Optional<UserMfa> findByPersonId(UUID personId);
}
