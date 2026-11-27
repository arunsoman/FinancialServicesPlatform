package com.fsp.plugin.kyc.api;

import com.fsp.plugin.kyc.service.KycOrganizationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** kyc.org.list / kyc.org.get — organizations API (§12.7). */
@Slf4j
@RestController
@RequestMapping("/v1/plugins/kyc")
public class KycOrganizationController {

    private final KycOrganizationService orgService;

    public KycOrganizationController(KycOrganizationService orgService) {
        this.orgService = orgService;
    }

    /** kyc.org.list — GET /organizations */
    @GetMapping("/organizations")
    public KycDtos.OrganizationListResponse list(
            jakarta.servlet.http.HttpServletRequest request,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sort", defaultValue = "createdAt,desc") String sort) {
        var actor = com.fsp.plugin.kyc.security.ActorAssertionFilter.actor(request);
        return orgService.list(actor.tenantId(), page, size, q, status, sort, actor.assertion());
    }

    /** kyc.org.get — GET /organizations/{tenantId} */
    @GetMapping("/organizations/{tenantId}")
    public KycDtos.OrganizationRow get(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        var actor = com.fsp.plugin.kyc.security.ActorAssertionFilter.actor(request);
        return orgService.get(tenantId, actor.assertion());
    }
}
