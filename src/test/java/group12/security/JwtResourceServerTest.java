package group12.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import group12.HelloController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HelloController.class)
@ContextConfiguration(classes = JwtResourceServerTest.SecurityTestConfiguration.class)
@TestPropertySource(properties = "jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
class JwtResourceServerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @Configuration(proxyBeanMethods = false)
    @Import({HelloController.class, SecurityConfig.class, JwtConfiguration.class})
    static class SecurityTestConfiguration {
    }

    @Test
    void protectedEndpointRejectsMissingBearerToken() throws Exception {
        mockMvc.perform(get("/hello"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRejectsMalformedBearerToken() throws Exception {
        mockMvc.perform(get("/hello").header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointAcceptsValidHs256BearerToken() throws Exception {
        mockMvc.perform(get("/hello").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello, World!"));
    }

    private String token() throws JOSEException {
        long issuedAt = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"123\",\"email\":\"client@example.com\","
                + "\"iat\":" + issuedAt + ",\"exp\":" + (issuedAt + 300) + "}";
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(payload));
        jws.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jws.serialize();
    }
}
