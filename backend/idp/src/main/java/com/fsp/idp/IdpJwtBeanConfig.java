package com.fsp.idp;

import com.fsp.shared.JwtService;
import com.fsp.shared.PkiKeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One shared JwtService bean for the IdP (PAR client-assertion validation and
 * token issuance use the same RS256 key + issuer). Built from IdpProperties the
 * same way IdpTokenService resolves its signing key.
 */
@Configuration
public class IdpJwtBeanConfig {

    @Bean
    public JwtService jwtService(IdpProperties props) {
        JwtService jwt = new JwtService(resolveSigningKey(props.getSigningKeyPath()), props.getIssuer());
        if (!jwt.canSign()) {
            throw new IllegalStateException("idp_signing_key_missing run 'make pki' (idp-signing.pem)");
        }
        return jwt;
    }

    /** Signing key lookup via the shared relative-only PkiKeyResolver (launcher-cwd-independent). */
    private static java.nio.file.Path resolveSigningKey(String configured) {
        return PkiKeyResolver.resolve(configured, "idp-signing.pem");
    }
}