package com.fsp.plugin.inventory.api;

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
