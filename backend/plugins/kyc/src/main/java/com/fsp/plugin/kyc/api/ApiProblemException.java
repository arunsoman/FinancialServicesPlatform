package com.fsp.plugin.kyc.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Plugin RFC-7807 problem exception — same convention as core/common/ApiException. */
public class ApiProblemException extends RuntimeException {

    private final String code;
    private final int status;

    public ApiProblemException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() { return code; }
    public int getStatus() { return status; }

    public Map<String, Object> toProblemDetails() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", code);
        body.put("code", code);
        body.put("status", status);
        body.put("detail", getMessage());
        body.put("instance", null);
        return body;
    }

    public static ApiProblemException badRequest(String code, String message) {
        return new ApiProblemException(code, message, 400);
    }

    public static ApiProblemException forbidden(String code, String message) {
        return new ApiProblemException(code, message, 403);
    }

    public static ApiProblemException conflict(String code, String message) {
        return new ApiProblemException(code, message, 409);
    }

    public static ApiProblemException notFound(String code, String message) {
        return new ApiProblemException(code, message, 404);
    }
}
