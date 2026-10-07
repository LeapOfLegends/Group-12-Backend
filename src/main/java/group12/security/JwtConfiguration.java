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

@Configuration
public class JwtConfiguration {

    @Bean
    public JwtDecoder jwtDecoder(@Value("${jwt.secret}") String secret) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must be at least 32 bytes for HS256");
        }

        SecretKey signingKey = new SecretKeySpec(secretBytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

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

        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(subjectValidator));
        return decoder;
    }
}