package com.fsp.idp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: the hosted sign-in POST answers with a 302 to the client's redirect_uri, and
 * browsers enforce CSP form-action on that redirect hop. With form-action 'self' only, the
 * first "Sign in" did nothing (blocked hop, PAR already consumed) and the second showed
 * request_uri_invalid.
 */
class IdpHardeningCspTest {

    @Test
    void defaultPolicyOnlyAllowsSelfFormPosts() {
        assertThat(IdpHardeningFilter.csp()).contains("form-action 'self';");
    }

    @Test
    void parBoundPageAllowsExactlyTheRedirectUriOrigin() {
        String csp = IdpHardeningFilter.cspAllowingFormRedirectTo("http://localhost:5173/bff/auth/callback?x=1");
        assertThat(csp).contains("form-action 'self' http://localhost:5173;");
        assertThat(csp).doesNotContain("/bff/auth/callback");
        assertThat(csp).contains("frame-ancestors 'none'").contains("base-uri 'none'").contains("default-src 'self'");
    }

    @Test
    void defaultPortAndCaseAreNormalised() {
        assertThat(IdpHardeningFilter.originOf("HTTPS://App.Example.COM/cb")).isEqualTo("https://app.example.com");
    }

    @Test
    void anythingThatIsNotAPlainHttpOriginKeepsTheStrictPolicy() {
        for (String bad : new String[] {
                null, "", "/relative/cb", "javascript:alert(1)", "data:text/html,x", "ftp://h/cb",
                "https://user:pw@evil.example/cb", "https://*.example.com/cb", "not a uri; script-src *"}) {
            assertThat(IdpHardeningFilter.cspAllowingFormRedirectTo(bad))
                .as(String.valueOf(bad)).isEqualTo(IdpHardeningFilter.csp());
        }
    }
}
