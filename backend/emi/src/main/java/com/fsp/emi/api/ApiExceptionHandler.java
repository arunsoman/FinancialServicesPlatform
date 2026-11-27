package com.fsp.emi.api;

import com.fsp.emi.common.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps the service-local {@link ApiException} to RFC 7807 problem+json (code stays stable). */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> problem(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", e.getStatus().value());
        body.put("code", e.getCode());
        body.put("title", e.getCode());
        body.put("detail", e.getMessage());
        return ResponseEntity.status(e.getStatus())
            .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(body);
    }
}
