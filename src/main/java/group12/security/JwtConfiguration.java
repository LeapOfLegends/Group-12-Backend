package group12.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

/**
 * Configures JWT decoding and validation for the backend resource server.
 *
 * <p>Tokens are expected to be signed by the authentication service with HS256 and the same
 * shared secret configured here as {@code jwt.secret}. The default Spring validators check
 * standard timestamp claims; an additional validator requires {@code sub} to be a positive
 * client ID.</p>
 */
@Configuration
public class JwtConfiguration {

    /**
     * Creates the decoder used by Spring Security to validate incoming bearer tokens.
     *
     * @param secret shared HS256 signing secret, supplied through {@code JWT_SECRET}
     * @return a decoder restricted to HS256 with timestamp and subject validation
     * @throws IllegalArgumentException if the secret is shorter than the 32-byte HS256 minimum
     */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${jwt.secret}") String secret) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256 requires a key of at least 256 bits; fail at startup rather than accept a weak key.
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must be at least 32 bytes for HS256");
        }

        SecretKey signingKey = new SecretKeySpec(secretBytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        // The auth service places the client ID in the JWT subject; reject absent, malformed, or nonpositive IDs.
        OAuth2TokenValidator<Jwt> subjectValidator = jwt -> {
            try {
                if (Long.parseLong(jwt.getSubject()) > 0) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (NumberFormatException | NullPointerException ignored) {
                // Invalid subjects are rejected as invalid bearer tokens.
            }

            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "JWT subject must be a positive client ID", null)
            );
        };

        // Preserve Spring's standard issuer/time validation and add the application-specific subject check.
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(subjectValidator));
        return decoder;
    }
}
