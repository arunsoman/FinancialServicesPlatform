package com.fsp.psp.connector;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BankConnectorRepository extends JpaRepository<BankConnector, UUID> {

    Optional<BankConnector> findByBankCode(String bankCode);
}
