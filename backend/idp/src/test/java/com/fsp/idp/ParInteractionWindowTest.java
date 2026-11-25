package com.fsp.idp;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Staging 2026-11-25: a person submitted the hosted sign-in 92 s after it opened; the 60 s PAR TTL
 * had run out and sign-in failed. An UNFETCHED request_uri still dies after 60 s (FAPI 2.0 / RFC
 * 9126); once the authorization endpoint fetched it, the person has the interaction window.
 */
class ParInteractionWindowTest {

    private final ParService par = new ParService(null, null);

    @SuppressWarnings("unchecked")
    private void put(String uri, Instant createdAt) throws Exception {
        var f = ParService.class.getDeclaredField("parStore");
        f.setAccessible(true);
        ((Map<String, ParService.ParRequestData>) f.get(par)).put(uri, new ParService.ParRequestData(
            "fsp-bff", "cc", "S256", "https://bff/cb", "openid", null, null, "st", createdAt));
    }

    @Test
    void aPersonOnTheSignInPageGetsMoreThanAMinuteToType() throws Exception {
        put("urn:a", Instant.now());
        assertNotNull(par.peekParRequest("urn:a"), "the authorization endpoint fetches it");
        put("urn:a", Instant.now().minusSeconds(92)); // the person typed for 92 s
        assertNotNull(par.consumeParRequest("urn:a"));
    }

    @Test
    void theInteractionWindowStillEnds() throws Exception {
        put("urn:b", Instant.now());
        par.peekParRequest("urn:b");
        put("urn:b", Instant.now().minusSeconds(ParService.INTERACTION_SECONDS + 5));
        assertNull(par.consumeParRequest("urn:b"));
    }

    @Test
    void anUnfetchedRequestUriStillExpiresAfterSixtySeconds() throws Exception {
        put("urn:c", Instant.now().minusSeconds(61));
        assertNull(par.peekParRequest("urn:c"));
    }

    @Test
    void stillSingleUse() throws Exception {
        put("urn:d", Instant.now());
        par.peekParRequest("urn:d");
        assertNotNull(par.consumeParRequest("urn:d"));
        assertNull(par.consumeParRequest("urn:d"));
        assertTrue(ParService.INTERACTION_SECONDS <= 600, "bounded: ten minutes at most");
    }
}
