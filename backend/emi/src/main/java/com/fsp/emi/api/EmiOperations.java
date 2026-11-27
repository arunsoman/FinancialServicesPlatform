package com.fsp.emi.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operation catalog for the EMI service descriptor (FAPI2 guide §3, core
 * {@code OperationCatalog} semantics without importing core): inventories every
 * {@code @EmiOperation} handler at boot, validates it (unique ids, exactly one HTTP
 * method, /v1-prefixed business route) and publishes a digest-stable descriptor the
 * gateway fetches. A violation FAILS BOOT — the gateway would otherwise silently 404
 * a live endpoint.
 */
@Component
public class EmiOperations implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(EmiOperations.class);

    public record Entry(String operationId, String component, String method, String pathPattern,
                        String permission, String risk, String title) {}

    private final ApplicationContext context;
    private List<Entry> entries = List.of();
    private String digest = "";

    public EmiOperations(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        RequestMappingHandlerMapping mapping =
            context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        Map<String, Entry> byId = new LinkedHashMap<>();

        mapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            if (!(handlerMethod instanceof HandlerMethod hm)) return;
            String anyPath = info.getPatternValues().stream().findFirst().orElse("");
            EmiOperation op = hm.getMethodAnnotation(EmiOperation.class);
            if (op == null) op = hm.getBeanType().getAnnotation(EmiOperation.class);
            if (op == null) {
                if (anyPath.startsWith("/v1/")) {
                    throw new IllegalStateException("emi catalog violation: unclassified business route "
                        + anyPath + " on " + hm.getBeanType().getSimpleName() + " — annotate with @EmiOperation");
                }
                return;
            }
            Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                info.getMethodsCondition().getMethods();
            if (methods.size() != 1) {
                throw new IllegalStateException("emi catalog violation: operation %s must declare exactly one HTTP method"
                    .formatted(op.id()));
            }
            String path = info.getPatternValues().stream().sorted().findFirst().orElse("");
            if (!path.startsWith("/v1/")) {
                throw new IllegalStateException("emi catalog violation: business operation %s must live under /v1/ (got %s)"
                    .formatted(op.id(), path));
            }
            Entry e = new Entry(op.id(), op.component(), methods.iterator().next().name(), path,
                op.permission(), op.risk().name(), op.title());
            if (byId.putIfAbsent(op.id(), e) != null) {
                throw new IllegalStateException("emi catalog violation: duplicate operationId " + op.id());
            }
        });

        this.entries = byId.values().stream().sorted(Comparator.comparing(Entry::operationId)).toList();
        this.digest = sha256(entries);
        for (Entry e : entries) {
            log.info("emi_catalog_operation op={} method={} path={} risk={} permission={}",
                e.operationId(), e.method(), e.pathPattern(), e.risk(), e.permission());
        }
        log.info("emi_catalog_published operations={} digest={}", entries.size(), digest);
    }

    public List<Entry> entries() {
        return entries;
    }

    public String digest() {
        return digest;
    }

    private static String sha256(List<Entry> list) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Entry e : list) {
                md.update(("%s|%s|%s|%s|%s\n".formatted(e.operationId(), e.method(), e.pathPattern(),
                    e.permission(), e.risk())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("digest failure", e);
        }
    }
}
