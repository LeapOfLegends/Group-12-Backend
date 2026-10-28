package group12.Controller;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import group12.Services.PortfolioPerformanceService;
import group12.dto.PortfolioPerformanceDTO;
import group12.security.JwtConfiguration;
import group12.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PortfolioPerformanceController.class)
@ContextConfiguration(classes = PortfolioPerformanceControllerSecurityTest.Config.class)
@TestPropertySource(properties = "jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
class PortfolioPerformanceControllerSecurityTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired MockMvc mockMvc;
    @MockBean PortfolioPerformanceService service;

    @Configuration(proxyBeanMethods = false)
    @Import({PortfolioPerformanceController.class, SecurityConfig.class, JwtConfiguration.class})
    static class Config {
    }

    @Test
    void authenticatedClientCanOnlyRequestJwtSubjectsPortfolio() throws Exception {
        when(service.getHistory(789L, "1M")).thenReturn(new PortfolioPerformanceDTO(
                "1M", "1Day", "USD", null, null, false, "TWR", 0,
                List.of(), List.of()
        ));

        mockMvc.perform(get("/api/clients/me/portfolio/history")
                        .queryParam("range", "1M")
                        .header("Authorization", "Bearer " + token("CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("1M"));

        verify(service).getHistory(789L, "1M");
    }

    @Test
    void endpointRejectsUnauthenticatedAndAdministrativeCallers() throws Exception {
        mockMvc.perform(get("/api/clients/me/portfolio/history").queryParam("range", "1M"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/clients/me/portfolio/history")
                        .queryParam("range", "1M")
                        .header("Authorization", "Bearer " + token("SUPER_ADMIN")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    private String token(String role) throws JOSEException {
        long issuedAt = Instant.now().getEpochSecond();
        String body = "{\"sub\":\"789\",\"roles\":[\"" + role + "\"],"
                + "\"iat\":" + issuedAt + ",\"exp\":" + (issuedAt + 300) + "}";
        JWSObject token = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(body));
        token.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }
}
