package com.fsp.psp.statement;

import com.fsp.psp.acquire.Acquisition;
import com.fsp.psp.acquire.AcquisitionRepository;
import com.fsp.psp.audit.PspEventRepository;
import com.fsp.psp.common.ApiException;
import com.fsp.psp.common.KeyLocks;
import com.fsp.psp.outbox.PspOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 5 — recon statement ingest tests (plan §11): hash-claim-once semantics, canonical
 * row-multiset hashing (order-insensitive), and mismatch taxonomy. Repos mocked at seams.
 */
class PspStatementServiceTest {

    StatementIngestRepository ingests;
    AcquisitionRepository acquisitions;
    PspEventRepository events;
    PspOutboxRepository outbox;
    StatementService service;

    @BeforeEach
    void setUp() {
        ingests = mock(StatementIngestRepository.class);
        acquisitions = mock(AcquisitionRepository.class);
        events = mock(PspEventRepository.class);
        outbox = mock(PspOutboxRepository.class);
        TransactionTemplate tx = new TransactionTemplate(new StubTxManager());
        service = new StatementService(ingests, acquisitions, events, outbox,
            new KeyLocks(), tx);
        when(ingests.findByFileHash(any())).thenReturn(Optional.empty());
        when(ingests.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outbox.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    Acquisition acq(String railRef, String state, long gross) {
        Acquisition a = new Acquisition();
        a.id = UUID.randomUUID();
        a.railRef = railRef;
        a.state = state;
        a.grossMinor = BigDecimal.valueOf(gross);
        a.currency = "NAD";
        return a;
    }

    @Test
    void matchedAndMismatchedAreClassified() {
        when(acquisitions.findAllByRailRef("demo-OK-1"))
            .thenReturn(List.of(acq("demo-OK-1", Acquisition.STATE_CAPTURED, 3000)));
        when(acquisitions.findAllByRailRef("demo-SETTLED-1"))
            .thenReturn(List.of(acq("demo-SETTLED-1", Acquisition.STATE_SETTLED, 1500)));
        when(acquisitions.findAllByRailRef("demo-NONE")).thenReturn(List.of());
        when(acquisitions.findAllByRailRef("demo-DRIFT"))
            .thenReturn(List.of(acq("demo-DRIFT", Acquisition.STATE_CREATED, 999)));

        var r = service.ingest("DEMO-BANK", "EOD-FILE",
            List.of(new StatementService.StatementRow("demo-OK-1", 3000),
                new StatementService.StatementRow("demo-SETTLED-1", 1500),
                new StatementService.StatementRow("demo-NONE", 100),
                new StatementService.StatementRow("demo-DRIFT", 999)));

        assertThat(r.replay()).isFalse();
        assertThat(r.rowsTotal()).isEqualTo(4);
        assertThat(r.rowsMatched()).isEqualTo(2);
        assertThat(r.rowsMismatched()).isEqualTo(2);
        List<String> reasons = r.mismatches().stream()
            .map(m -> (String) m.get("reason")).toList();
        assertThat(reasons).containsExactlyInAnyOrder("UNKNOWN_RAIL_REF", "AMOUNT_OR_STATE_DRIFT");
        verify(ingests).save(any());
        verify(outbox).save(any());
    }

    @Test
    void hashIsOrderInsensitiveAndReplayReturnsOriginalCounts() {
        when(acquisitions.findAllByRailRef("r-1"))
            .thenReturn(List.of(acq("r-1", Acquisition.STATE_CAPTURED, 1000)));
        var first = service.ingest("DEMO-BANK", "EOD-FILE",
            List.of(new StatementService.StatementRow("r-1", 1000),
                new StatementService.StatementRow("r-2", 2000)));

        // reorder rows + re-stub the found-acquisition to prove NO re-match happens on replay
        var replayIngest = mock(StatementIngest.class);
        replayIngest.id = first.id();
        replayIngest.connectorCode = first.connectorCode();
        replayIngest.source = first.source();
        replayIngest.fileHash = first.fileHash();
        replayIngest.rowsTotal = first.rowsTotal();
        replayIngest.rowsMatched = first.rowsMatched();
        replayIngest.rowsMismatched = first.rowsMismatched();
        when(ingests.findByFileHash(first.fileHash())).thenReturn(Optional.of(replayIngest));

        var second = service.ingest("DEMO-BANK", "EOD-FILE",
            List.of(new StatementService.StatementRow("r-2", 2000),
                new StatementService.StatementRow("r-1", 1000)));

        assertThat(second.fileHash()).isEqualTo(first.fileHash());
        assertThat(second.replay()).isTrue();
        assertThat(second.rowsMatched()).isEqualTo(first.rowsMatched());
        verify(acquisitions, never()).save(any());
        verify(ingests, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void duplicateRailRefIsAmbiguous() {
        when(acquisitions.findAllByRailRef("dup"))
            .thenReturn(List.of(acq("dup", "CAPTURED", 100), acq("dup", "CAPTURED", 100)));
        var r = service.ingest("DEMO-BANK", "EOD-FILE",
            List.of(new StatementService.StatementRow("dup", 100)));
        assertThat(r.rowsMismatched()).isEqualTo(1);
        assertThat(r.mismatches().get(0).get("reason")).isEqualTo("AMBIGUOUS_RAIL_REF");
    }

    @Test
    void invalidInputRejected() {
        assertThatThrownBy(() -> service.ingest(" ", "src", List.of()))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_INPUT_INVALID");
        assertThatThrownBy(() -> service.ingest("DEMO-BANK", " ", List.of()))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_INPUT_INVALID");
        assertThatThrownBy(() -> service.ingest("DEMO-BANK", "src", null))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_INPUT_INVALID");
        verify(ingests, never()).save(any());
    }

    @Test
    void oversizeStatementRejected() {
        List<StatementService.StatementRow> rows = java.util.stream.IntStream.range(0, 10_001)
            .mapToObj(i -> new StatementService.StatementRow("r" + i, 1)).toList();
        assertThatThrownBy(() -> service.ingest("DEMO-BANK", "src", rows))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_INPUT_INVALID");
    }

    static class StubTxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition)
            throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) { /* no-op */ }

        @Override
        public void rollback(TransactionStatus status) { /* no-op */ }
    }
}
