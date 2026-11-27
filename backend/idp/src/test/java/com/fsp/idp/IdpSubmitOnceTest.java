package com.fsp.idp;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression: every hosted form consumes a single-use artefact (PAR request_uri, MFA /
 * password-change ticket, activation token). A double-click on "Sign in" POSTed twice and the
 * browser rendered the SECOND answer (request_uri_invalid) although the first had succeeded.
 * Each hosted form page must load the same-origin submit-once guard.
 */
class IdpSubmitOnceTest {

    private final IdpController controller = new IdpController(
        mock(IdpTokenService.class), mock(ParService.class), mock(IdpAuthService.class),
        mock(IdpProperties.class), mock(JdbcTemplate.class), mock(PPersonRepository.class),
        mock(IdpActivationService.class));

    private String render(String name, Object... args) throws Exception {
        Method m = IdpController.class.getDeclaredMethod(name, String.class, String.class);
        m.setAccessible(true);
        return String.valueOf(m.invoke(controller, args));
    }

    private static void assertGuarded(String page) {
        assertThat(page).contains("<form");
        assertThat(page).contains(IdpController.SUBMIT_ONCE_TAG);
        assertThat(page.indexOf(IdpController.SUBMIT_ONCE_TAG)).isLessThan(page.indexOf("</head>"));
    }

    @Test
    void signInPageLoadsTheGuard() {
        assertGuarded(controller.loginPage("urn:ietf:params:oauth:request_uri:x", "fsp-bff",
            new MockHttpServletResponse()));
    }

    @Test
    void passwordChangeMfaAndActivationPagesLoadTheGuard() throws Exception {
        assertGuarded(render("changePage", "t", ""));
        assertGuarded(render("mfaPage", "t", ""));
        assertGuarded(render("activationForm", "tok", ""));
        assertGuarded(render("activationForm", null, ""));
    }

    @Test
    void guardScriptIsServedAsJavascriptAndBlocksARepeatSubmit() {
        var r = controller.submitOnceJs();
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getBody()).contains("addEventListener('submit'").contains("preventDefault()")
            .contains("data-submitted");
    }

    @Test
    void cspStillForbidsInlineScriptAndAllowsTheSameOriginFile() {
        // no script-src override → default-src 'self' governs scripts: the file loads, inline does not
        assertThat(IdpHardeningFilter.csp()).contains("default-src 'self'").doesNotContain("script-src");
        assertThat(IdpController.SUBMIT_ONCE_TAG).contains("src=\"/idp/submit-once.js\"");
    }
}
