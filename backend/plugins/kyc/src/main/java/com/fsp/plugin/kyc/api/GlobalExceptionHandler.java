package com.fsp.plugin.kyc.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** RFC 7807 problem responses for the plugin (§9.3). */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiProblemException.class)
    public ResponseEntity<Map<String, Object>> handleApiProblem(ApiProblemException e) {
        log.warn("api_problem code={} status={} detail={}", e.getCode(), e.getStatus(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(e.toProblemDetails());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        String msg = e.getMessage();
        String code = msg != null && msg.contains(":") ? msg.split(":")[0] : "INVALID_REQUEST";
        String detail = msg != null && msg.contains(":") ? msg.substring(msg.indexOf(':') + 2) : msg;
        log.warn("api_problem code={} detail={}", code, detail);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", code);
        body.put("status", 400);
        body.put("detail", detail);
        body.put("instance", null);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(Exception e) {
        log.error("api_problem code=INTERNAL_ERROR detail={}", e.getMessage(), e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "INTERNAL_ERROR");
        body.put("status", 500);
        body.put("detail", "Internal server error");
        body.put("instance", null);
        return ResponseEntity.internalServerError().body(body);
    }
}
