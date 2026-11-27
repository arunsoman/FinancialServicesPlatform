package com.fsp.psp.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signed-descriptor publishing seam for the gateway route policy (FAPI2 guide §3.3, core
 * {@code /internal/ops/catalog} semantics; PSP copy of EMI's EmiOpsController): the
 * gateway derives PSP routes from THIS descriptor — never hand-maintained. Internal hop
 * only (credential-gated by {@link PspIdentityFilter}; not a business operation).
 */
@RestController
public class PspCatalogController {

    private final PspOperations catalog;

    public PspCatalogController(PspOperations catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/internal/ops/catalog")
    public Map<String, Object> descriptor() {
        List<PspOperations.Entry> entries = catalog.entries();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("component", "psp");
        out.put("digest", catalog.digest());
        out.put("count", entries.size());
        out.put("operations", entries);
        return out;
    }
}
