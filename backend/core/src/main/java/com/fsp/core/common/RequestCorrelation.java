package com.fsp.core.common;

import java.util.UUID;

/** Thread-local correlation context shared by audit/outbox/act-as. Set by ActorContextFilter. */
public final class RequestCorrelation {

    private static final ThreadLocal<String> CORRELATION = new ThreadLocal<>();
    private static final ThreadLocal<String> CLIENT_IP = new ThreadLocal<>();
    private static final ThreadLocal<String> CLIENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<Actor> ACTOR = new ThreadLocal<>();
    private static final ThreadLocal<UUID> HOME_TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ACT_SESSION_ID = new ThreadLocal<>();

    private RequestCorrelation() {}

    public static String currentCorrelation() { return CORRELATION.get(); }

    public static void setContext(String correlationId, String clientIp, String clientId) {
        CORRELATION.set(correlationId);
        CLIENT_IP.set(clientIp);
        CLIENT_ID.set(clientId);
    }

    public static void setActor(Actor actor) { ACTOR.set(actor); }

    public static Actor currentActor() { return ACTOR.get(); }

    public static String clientIp() { return CLIENT_IP.get(); }

    public static String clientId() { return CLIENT_ID.get(); }

    /** The person's home tenant (never the one they act as). Set by ActAsFilter while acting. */
    public static UUID homeTenantId() { return HOME_TENANT_ID.get(); }

    public static void setHomeTenantId(UUID homeTenantId) { HOME_TENANT_ID.set(homeTenantId); }

    /** The verified live act-as session this request runs under (ActAsFilter), else null. */
    public static String actSessionId() { return ACT_SESSION_ID.get(); }

    public static void setActSessionId(String sessionId) { ACT_SESSION_ID.set(sessionId); }

    public static void clear() {
        CORRELATION.remove();
        CLIENT_IP.remove();
        CLIENT_ID.remove();
        ACTOR.remove();
        HOME_TENANT_ID.remove();
        ACT_SESSION_ID.remove();
    }
}
