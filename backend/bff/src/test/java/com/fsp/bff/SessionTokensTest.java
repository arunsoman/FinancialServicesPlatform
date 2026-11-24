package com.fsp.bff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Session token lifecycle (FAPI 2.0 BFF): renew while active, end at the absolute limit or when
 * the IdP refuses, revoke the refresh token whenever the session ends, never touch the browser.
 */
class SessionTokensTest {

    private static final Instant T0 = Instant.parse("2026-11-24T09:00:00Z");
    private final IdpClient idp = Mockito.mock(IdpClient.class);
    private final BffProperties props = new BffProperties();
    private MockHttpSession session;
    private MockHttpServletRequest request;

    private SessionTokens at(Instant now) {
        return new SessionTokens(idp, props, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static IdpClient.TokenResponse token(String access, String refresh) {
        return new IdpClient.TokenResponse(access, "DPoP", 900,
            new IdpClient.TokenClaims("u", "user", "User", "t", "T", "TENANT", List.of()), refresh);
    }

    @BeforeEach
    void signIn() {
        session = new MockHttpSession();
        request = new MockHttpServletRequest();
        request.setSession(session);
        session.setAttribute(BffSessionController.SESSION_DPOP_KEY, "{dpop-key}");
        at(T0).start(session, token("access-1", "refresh-1"));
    }

    @Test
    void signInSetsTheIdleLimitAndKeepsTokensOnTheServer() {
        assertThat(session.getMaxInactiveInterval()).isEqualTo(30 * 60);
        assertThat(session.getAttribute(SessionTokens.REFRESH)).isEqualTo("refresh-1");
        assertThat(session.getAttribute(BffSessionController.SESSION_TOKEN)).isEqualTo("access-1");
    }

    @Test
    void aFreshTokenIsUsedAsIs() {
        assertThat(at(T0.plusSeconds(600)).liveToken(request)).isEqualTo("access-1");
        verify(idp, never()).refresh(anyString(), anyString());
    }

    @Test
    void aTokenAboutToExpireIsRenewedWithTheSessionsDpopKey() {
        when(idp.refresh("refresh-1", "{dpop-key}")).thenReturn(token("access-2", null));
        SessionTokens tokens = at(T0.plusSeconds(870)); // 30 s left, skew is 60 s
        assertThat(tokens.liveToken(request)).isEqualTo("access-2");
        assertThat(session.getAttribute(BffSessionController.SESSION_TOKEN)).isEqualTo("access-2");
        assertThat(session.getAttribute(SessionTokens.REFRESH)).as("no rotation").isEqualTo("refresh-1");
        assertThat(session.getAttribute(SessionTokens.TOKEN_EXP)).isEqualTo(T0.plusSeconds(870 + 900).getEpochSecond());
    }

    @Test
    void aRefusedRenewalEndsTheSessionAndRevokes() {
        when(idp.refresh(anyString(), anyString())).thenReturn(null);
        assertThat(at(T0.plusSeconds(890)).liveToken(request)).isNull();
        assertThat(session.isInvalid()).isTrue();
        verify(idp).revoke("refresh-1", "refresh_refused");
    }

    @Test
    void anIdpBlipNeverEndsALiveSession() {
        when(idp.refresh(anyString(), anyString())).thenThrow(new IllegalStateException("idp down"));
        assertThat(at(T0.plusSeconds(870)).liveToken(request)).isEqualTo("access-1"); // still 30 s valid
        assertThat(session.isInvalid()).isFalse();
        verify(idp, never()).revoke(any(), any());
    }

    @Test
    void theAbsoluteLimitEndsTheSessionWhateverTheActivity() {
        assertThat(at(T0.plusSeconds(8 * 3600)).liveToken(request)).isNull();
        assertThat(session.isInvalid()).isTrue();
        verify(idp).revoke("refresh-1", "absolute_timeout");
        verify(idp, never()).refresh(anyString(), anyString());
    }

    @Test
    void logoutRevokesTheRefreshTokenThenDropsTheSession() {
        at(T0.plusSeconds(60)).end(session, "logout");
        verify(idp).revoke("refresh-1", "logout");
        assertThat(session.isInvalid()).isTrue();
    }
}
