package group12.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class JwtConfigurationTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final JwtDecoder decoder = new JwtConfiguration().jwtDecoder(SECRET);

    @Test
    void decodesValidHs256NestToken() throws JOSEException {
        Jwt jwt = decoder.decode(token(JWSAlgorithm.HS256, Instant.now().plusSeconds(300)));

        assertEquals("123", jwt.getSubject());
        assertEquals("client@example.com", jwt.getClaimAsString("email"));
    }

    @Test
    void rejectsTamperedSignature() throws JOSEException {
        String[] parts = token(JWSAlgorithm.HS256, Instant.now().plusSeconds(300)).split("\\.");
        char replacement = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        parts[2] = replacement + parts[2].substring(1);

        assertThrows(JwtException.class, () -> decoder.decode(String.join(".", parts)));
    }

    @Test
    void rejectsMalformedToken() {
        assertThrows(JwtException.class, () -> decoder.decode("not-a-jwt"));
    }

    @Test
    void rejectsExpiredToken() throws JOSEException {
        assertThrows(
                JwtException.class,
                () -> decoder.decode(token(JWSAlgorithm.HS256, Instant.now().minusSeconds(60)))
        );
    }

    @Test
    void rejectsHs512Token() throws JOSEException {
        assertThrows(
                JwtException.class,
                () -> decoder.decode(token(JWSAlgorithm.HS512, Instant.now().plusSeconds(300)))
        );
    }

    @Test
    void rejectsSecretShorterThanHs256Minimum() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JwtConfiguration().jwtDecoder("too-short")
        );
    }

    private String token(JWSAlgorithm algorithm, Instant expiration) throws JOSEException {
        long issuedAt = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"123\",\"email\":\"client@example.com\","
                + "\"iat\":" + issuedAt + ",\"exp\":" + expiration.getEpochSecond() + "}";
        JWSObject jws = new JWSObject(new JWSHeader(algorithm), new Payload(payload));
        jws.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jws.serialize();
    }
}