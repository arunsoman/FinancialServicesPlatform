package com.fsp.psp.connector;

import com.fsp.psp.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Connector profile read/seal service. Only ENABLED connectors may carry live acquiring;
 * sandbox conformance is a prerequisite (plan §3.4.3).
 */
@Service
public class ConnectorService {

    private final BankConnectorRepository connectors;
    private final com.fsp.psp.common.KeyLocks locks;

    public ConnectorService(BankConnectorRepository connectors, com.fsp.psp.common.KeyLocks locks) {
        this.connectors = connectors;
        this.locks = locks;
    }

    public BankConnector byCode(String bankCode) {
        return connectors.findByBankCode(bankCode)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PSP_CONNECTOR_NOT_FOUND",
                "connector not found: " + bankCode));
    }

    public BankConnector enabledByCode(String bankCode) {
        BankConnector c = byCode(bankCode);
        if (!BankConnector.STATUS_ENABLED.equals(c.profileStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "PSP_CONNECTOR_NOT_ENABLED",
                "connector " + bankCode + " is " + c.profileStatus + ", not ENABLED");
        }
        return c;
    }

    /** Demo/sandbox registration: creates or upgrades a connector row (never downgrades). */
    @Transactional
    public BankConnector upsert(String bankCode, String bankName, String railFamily,
                                String transportChannel, String messageFormat,
                                String credentialEnvRef, String targetStatus) {
        return locks.withLock("connector", bankCode, () -> {
            var existing = connectors.findByBankCode(bankCode);
            if (existing.isPresent()) {
                BankConnector c = existing.get();
                if (rankOf(targetStatus) < rankOf(c.profileStatus)) {
                    throw new ApiException(HttpStatus.CONFLICT, "PSP_CONNECTOR_STATE",
                        "connector " + bankCode + " is already " + c.profileStatus);
                }
                c.profileStatus = targetStatus;
                c.updatedAt = Instant.now();
                return connectors.save(c);
            }
            BankConnector c = new BankConnector();
            c.id = UUID.randomUUID();
            c.bankCode = bankCode;
            c.bankName = bankName;
            c.railFamily = railFamily;
            c.transportChannel = transportChannel;
            c.messageFormat = messageFormat;
            c.endpointCredentialEnvRef = credentialEnvRef;
            c.profileStatus = targetStatus;
            c.createdAt = Instant.now();
            c.updatedAt = c.createdAt;
            return connectors.save(c);
        });
    }

    private static int rankOf(String s) {
        return switch (s) {
            case BankConnector.STATUS_ENABLED -> 2;
            case BankConnector.STATUS_SANDBOX_CONFORMED -> 1;
            default -> 0;
        };
    }
}
