package com.fsp.bff;

/** Wire DTOs for the BFF server-side session (records per AGENTS.md §2b). */
public final class BffDtos {

    private BffDtos() {}

    /** Mirrors core's AuthService.UserSummary; stored server-side in the session only. */
    public record SessionUser(String userId, String username, String fullName, String tenantId,
                              String tenantName, String tenantType, String tenantSubType,
                              String tenantStatus, boolean applicant, java.util.List<String> roles) {}

    public record LoginRequest(String username, String password) {}

    /** idleTimeoutSeconds: sign out after this much user inactivity; sessionEndsAt: absolute end (ISO-8601). */
    public record SessionResponse(boolean authenticated, SessionUser user, Integer idleTimeoutSeconds, String sessionEndsAt) {}

    public record CsrfResponse(String headerName, String token) {}
}
