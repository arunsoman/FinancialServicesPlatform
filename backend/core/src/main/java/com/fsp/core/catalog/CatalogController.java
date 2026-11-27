package com.fsp.core.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operation-catalog descriptor seam (FAPI2 guide §3.3): /internal is credential-gated by
 * ActorContextFilter. The gateway/BFF route policy is derived from THIS descriptor —
 * never hand-maintained (guide §6: "explicit route set generated from approved descriptors").
 */
@RestController
public class CatalogController {

    private final OperationCatalog catalog;

    public CatalogController(OperationCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/internal/ops/catalog")
    public Map<String, Object> descriptor() {
        List<OperationCatalog.Entry> entries = catalog.entries();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("digest", catalog.digest());
        out.put("count", entries.size());
        out.put("operations", entries);
        return out;
    }
}
