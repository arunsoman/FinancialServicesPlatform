package com.fsp.core.plugin.registry;

import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.outbox.OutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Registering a new version of a plugin leaves exactly ONE registered version per code: the
 * gateway routes, declared approvals and grants are all read per code, so two REGISTERED rows
 * made the effective manifest depend on row order (kyc 1.0.0 vs 1.1.0).
 */
class PluginRegistrySupersedeTest {

    private static final String MANIFEST = "{\"client\":{\"jwks\":{\"keys\":[{\"kty\":\"EC\",\"x\":\"a\",\"y\":\"b\"}]},\"grants\":[]}}";
    private final PluginTypeRepository types = mock(PluginTypeRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PluginRegistryService service = new PluginRegistryService(types, mock(ApprovalService.class),
        mock(AuditService.class), mock(OutboxService.class), jdbc);
    private final Actor checker = new Actor(UUID.randomUUID(), "checker", UUID.randomUUID());

    private PluginType type(String version, String status) {
        return new PluginType(UUID.randomUUID(), "kyc", version, "http://localhost:8083", MANIFEST, "h", status, UUID.randomUUID());
    }

    @Test
    void approvingANewVersionDeactivatesTheOlderRegisteredOne() {
        PluginType old = type("1.0.0", "REGISTERED");
        PluginType neu = type("1.1.0", "PENDING_APPROVAL");
        when(types.findByCodeAndStatus("kyc", "PENDING_APPROVAL")).thenReturn(List.of(neu));
        when(types.findByCodeAndStatus("kyc", "REGISTERED")).thenReturn(List.of(old));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

        service.approve("kyc", "1.1.0", checker);

        assertEquals("REGISTERED", neu.status);
        assertEquals("DEACTIVATED", old.status);
    }

    @Test
    void approvesTheRequestedVersionNotTheFirstPendingRow() {
        PluginType other = type("2.0.0", "PENDING_APPROVAL");
        PluginType wanted = type("1.1.0", "PENDING_APPROVAL");
        when(types.findByCodeAndStatus("kyc", "PENDING_APPROVAL")).thenReturn(List.of(other, wanted));
        when(types.findByCodeAndStatus("kyc", "REGISTERED")).thenReturn(List.of());

        service.approve("kyc", "1.1.0", checker);

        assertEquals("REGISTERED", wanted.status);
        assertEquals("PENDING_APPROVAL", other.status);
        assertThrows(ApiException.class, () -> service.approve("kyc", "9.9.9", checker));
    }
}
