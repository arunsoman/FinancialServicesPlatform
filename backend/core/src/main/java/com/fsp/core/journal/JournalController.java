package com.fsp.core.journal;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import com.fsp.core.common.RequestCorrelation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Journal entry surfaces (PRD §6 / guide §4A bounded list). */
@Slf4j
@RestController
public class JournalController {

    private final JournalService journalService;

    public JournalController(JournalService journalService) {
        this.journalService = journalService;
    }

    @PostMapping("/v1/tenants/{tenantId}/journal-entries")
    @FspOperation(id = "journal.entry.create", permission = "je.create", component = "journal",
        actors = {Actor.ActorType.PERSON, Actor.ActorType.DELEGATED},
        risk = FspOperation.OperationRisk.WRITE, title = "Create journal entry draft")
    public JournalDtos.EntryView create(jakarta.servlet.http.HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                        @RequestBody JournalDtos.CreateEntryRequest body) {
        log.info("api_je_create tenantId={}", tenantId);
        return journalService.createDraft(tenantId, body, actor(request));
    }

    /** Bounded list (guide §4A): status filter validated against the closed state enum, size 1–100. */
    @GetMapping("/v1/tenants/{tenantId}/journal-entries")
    @FspOperation(id = "journal.entry.search", permission = "je.view", component = "journal",
        risk = FspOperation.OperationRisk.READ, title = "Search journal entries")
    public List<JournalDtos.EntryView> list(jakarta.servlet.http.HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                            @RequestParam(value = "status", required = false) String status,
                                            @RequestParam(value = "size", required = false, defaultValue = "25") Integer size) {
        if (size == null || size < 1 || size > 100) throw ApiException.badRequest("BOUNDS", "size must be 1-100");
        if (status != null && !JournalEntry.Status.VALID.contains(status)) {
            throw ApiException.badRequest("JE_STATUS", "Unknown status '" + status + "'");
        }
        List<String> statuses = status == null ? List.of() : List.of(status);
        return journalService.list(tenantId, statuses, actor(request)).stream().limit(size).toList();
    }

    @GetMapping("/v1/tenants/{tenantId}/journal-entries/uncertain")
    @FspOperation(id = "journal.entry.uncertain", permission = "je.view", component = "journal",
        risk = FspOperation.OperationRisk.READ, title = "Uncertain postings queue")
    public List<JournalDtos.EntryView> uncertain(jakarta.servlet.http.HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        return journalService.uncertain(tenantId, actor(request));
    }

    @GetMapping("/v1/tenants/{tenantId}/journal-entries/{entryId}")
    @FspOperation(id = "journal.entry.get", permission = "je.view", component = "journal",
        risk = FspOperation.OperationRisk.READ, title = "Journal entry detail")
    public JournalDtos.EntryView detail(jakarta.servlet.http.HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                        @PathVariable("entryId") UUID entryId) {
        return journalService.get(tenantId, entryId, actor(request));
    }

    @PostMapping("/v1/tenants/{tenantId}/journal-entries/{entryId}/submit-for-approval")
    @FspOperation(id = "journal.entry.submit", permission = "je.create", component = "journal",
        actors = {Actor.ActorType.PERSON, Actor.ActorType.DELEGATED},
        risk = FspOperation.OperationRisk.WRITE, title = "Submit JE for approval")
    public JournalDtos.EntryView submit(jakarta.servlet.http.HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                        @PathVariable("entryId") UUID entryId) {
        Actor actor = actor(request);
        log.info("api_je_submit tenantId={} entryId={} maker={}", tenantId, entryId, actor.personId());
        return journalService.submitForApproval(tenantId, entryId, actor);
    }

    @PostMapping("/v1/tenants/{tenantId}/journal-entries/{entryId}/approve")
    @FspOperation(id = "journal.entry.approve", permission = "je.approve", component = "journal",
        actors = {Actor.ActorType.PERSON, Actor.ActorType.DELEGATED},
        risk = FspOperation.OperationRisk.FOUR_EYES, title = "Approve and post JE")
    public JournalDtos.EntryView approve(jakarta.servlet.http.HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                         @PathVariable("entryId") UUID entryId) {
        Actor actor = actor(request);
        log.info("api_je_approve tenantId={} entryId={} checker={}", tenantId, entryId, actor.personId());
        return journalService.approveAndPost(tenantId, entryId, actor);
    }

    @PostMapping("/v1/tenants/{tenantId}/journal-entries/{entryId}/check-status")
    @FspOperation(id = "journal.entry.check_status", permission = "je.approve", component = "journal",
        actors = {Actor.ActorType.PERSON, Actor.ActorType.DELEGATED},
        risk = FspOperation.OperationRisk.FOUR_EYES, title = "Resolve uncertain commit")
    public JournalDtos.EntryView checkStatus(jakarta.servlet.http.HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                             @PathVariable("entryId") UUID entryId) {
        Actor actor = actor(request);
        log.info("api_je_check_status tenantId={} entryId={} actor={}", tenantId, entryId, actor.personId());
        return journalService.checkStatus(tenantId, entryId, actor);
    }

    /** Posting intents — single-actor immediate posting (plans/inventory-plugin-plan.md D3).
     *  Same money invariants as the four-eyes pipeline; only the maker/checker split is waived,
     *  so the DELEGATED (on-behalf) plane is the intended consumer, e.g. the inventory plugin
     *  posting a voucher-dispense JE. */
    @PostMapping("/v1/tenants/{tenantId}/journal/intents")
    @FspOperation(id = "journal.entry.post_intent", permission = "je.post.immediate", component = "journal",
        actors = {Actor.ActorType.PERSON, Actor.ActorType.DELEGATED},
        risk = FspOperation.OperationRisk.WRITE, title = "Post journal intent immediately")
    public JournalDtos.EntryView postIntent(jakarta.servlet.http.HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                            @RequestBody JournalDtos.CreateEntryRequest body) {
        log.info("api_je_post_intent tenantId={}", tenantId);
        return journalService.postIntent(tenantId, body, actor(request));
    }

    private static Actor actor(jakarta.servlet.http.HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        if (RequestCorrelation.currentCorrelation() == null) {
            log.warn("request_without_correlation actor={}", actor.personId());
        }
        return actor;
    }
}
