package com.fsp.idp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Red-team regression (round 2 wave 10e): brute-force lockout on password grant. */
class LoginThrottleTest {

    @Test
    void failuresBelowThresholdAreRejectedNormally() {
        LoginThrottle t = new LoginThrottle();
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) t.failure("user@x");
        assertThatCode(() -> t.check("user@x")).doesNotThrowAnyException();
    }

    @Test
    void maxFailuresLocksUsername() {
        LoginThrottle t = new LoginThrottle();
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) t.failure("user@x");
        assertThatThrownBy(() -> t.check("user@x"))
            .isInstanceOf(IdpProblemException.class)
            .satisfies(e -> assertThatCodeIs(e, "LOGIN_LOCKED", 429));
        // even a single subsequent failure keeps the lock
        assertThatThrownBy(() -> t.check("USER@X")) // case-insensitive key
            .isInstanceOf(IdpProblemException.class)
            .satisfies(e -> assertThatCodeIs(e, "LOGIN_LOCKED", 429));
    }

    @Test
    void successClearsCounter() {
        LoginThrottle t = new LoginThrottle();
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) t.failure("user@x");
        t.success("user@x");
        assertThatCode(() -> t.check("user@x")).doesNotThrowAnyException();
    }

    @Test
    void differentUsernamesAreIsolated() {
        LoginThrottle t = new LoginThrottle();
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) t.failure("victim@x");
        assertThatCode(() -> t.check("other@x")).doesNotThrowAnyException();
    }

    private static void assertThatCodeIs(Throwable e, String code, int status) {
        var problem = ((IdpProblemException) e).problem();
        org.assertj.core.api.Assertions.assertThat(problem.code()).isEqualTo(code);
        org.assertj.core.api.Assertions.assertThat(problem.status()).isEqualTo(status);
    }
}
