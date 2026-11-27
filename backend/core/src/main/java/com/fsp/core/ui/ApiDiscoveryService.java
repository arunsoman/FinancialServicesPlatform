package com.fsp.core.ui;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Discovers the live backend API surface from the REAL Spring handler mapping:
 * every {@code @RestController} handler carries {@code @FspOperation} (core boot fails
 * otherwise), so the inventory below is the complete set of method + path + signature
 * the system provides right now. Used by the widget workbench so LLM prompts carry the
 * full backend API catalog (paths + signatures) instead of guessing endpoints.
 */
@Service
public class ApiDiscoveryService {

    private final RequestMappingHandlerMapping mapping;

    public ApiDiscoveryService(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping) {
        this.mapping = mapping;
    }

    /**
     * All business endpoints visible to the authenticated actor. Mirrors what the gateway
     * route table is built from — nothing here is secret, and the call itself requires a
     * valid BFF session (authenticated-only, no specific permission).
     */
    public List<UiDtos.BackendApiDto> discover(HttpServletRequest request) {
        actor(request);
        List<UiDtos.BackendApiDto> out = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            if (!(handlerMethod instanceof HandlerMethod hm)) return;
            Class<?> beanType = hm.getBeanType();
            if (!beanType.isAnnotationPresent(RestController.class)) return;
            FspOperation op = hm.getMethodAnnotation(FspOperation.class);
            if (op == null) op = beanType.getAnnotation(FspOperation.class);
            if (op == null) return; // OperationCatalog guarantees /v1/ business routes are classified

            Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                info.getMethodsCondition() != null ? info.getMethodsCondition().getMethods() : Set.of();
            List<String> patterns = info.getPatternValues() != null ? new ArrayList<>(info.getPatternValues()) : List.of();
            for (org.springframework.web.bind.annotation.RequestMethod httpMethod : methods) {
                for (String pattern : patterns) {
                    out.add(toDto(pattern, httpMethod, hm, op));
                }
            }
        });
        out.sort(Comparator.comparing(UiDtos.BackendApiDto::httpMethod).thenComparing(UiDtos.BackendApiDto::path));
        return out;
    }

    private UiDtos.BackendApiDto toDto(String pattern, org.springframework.web.bind.annotation.RequestMethod httpMethod,
                                       HandlerMethod hm, FspOperation op) {
        List<UiDtos.BackendParamDto> params = new ArrayList<>();
        for (MethodParameter p : hm.getMethodParameters()) {
            params.add(parameterOf(p));
        }
        return new UiDtos.BackendApiDto(
            hm.getBeanType().getSimpleName(),
            hm.getMethod().getName(),
            httpMethod.name(),
            pattern,
            op.id(), op.permission(), op.component(), op.risk().name(), op.title(),
            params);
    }

    private UiDtos.BackendParamDto parameterOf(MethodParameter p) {
        Class<?> type = p.getParameterType();
        String location = "other";
        boolean required = true;
        if (p.hasParameterAnnotation(PathVariable.class)) {
            location = "path";
            required = p.getParameterAnnotation(PathVariable.class).required();
        } else if (p.hasParameterAnnotation(RequestParam.class)) {
            location = "query";
            required = p.getParameterAnnotation(RequestParam.class).required();
        } else if (p.hasParameterAnnotation(RequestBody.class)) {
            location = "body";
        } else if (p.hasParameterAnnotation(RequestHeader.class)) {
            location = "header";
        } else if (type == jakarta.servlet.http.HttpServletRequest.class
                   || type == jakarta.servlet.http.HttpSession.class) {
            location = "request";
            required = false;
        }
        String name = p.getParameterName() != null ? p.getParameterName() : "arg" + p.getParameterIndex();
        return new UiDtos.BackendParamDto(name, type.getSimpleName(), location, required);
    }

    private Actor actor(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        return actor;
    }
}
