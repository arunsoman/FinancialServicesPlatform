package com.fsp.psp.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PspEventRepository extends JpaRepository<PspEvent, Long> {
}
