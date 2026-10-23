package group12.Controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import group12.Repository.PlatformMetricsRepository;
import group12.Repository.TransactionsRepository;
import group12.Services.AnalyticsService;
import group12.Services.PlatformMetricsService;
import group12.security.JwtConfiguration;
import group12.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AnalyticsController.class)
@ContextConfiguration(classes = PlatformMetricsSecurityTest.SecurityTestConfiguration.class)
@TestPropertySource(properties = "jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
class PlatformMetricsSecurityTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyticsService analyticsService;

    @MockitoBean
    private PlatformMetricsRepository repository;

    @MockitoBean
    private TransactionsRepository transactions;

    @Configuration(proxyBeanMethods = false)
    @Import({AnalyticsController.class, PlatformMetricsService.class, SecurityConfig.class, JwtConfiguration.class})
    static class SecurityTestConfiguration {
    }

    @Test
    void everyAdminRoleCanFilterWarehouseMetrics() throws Exception {
        when(transactions.getFundingTotal(eq("DEPOSIT"), nullable(java.time.OffsetDateTime.class), any(java.time.OffsetDateTime.class)))
            .thenReturn(new java.math.BigDecimal("1250.50"));
        when(transactions.getFundingTotal(eq("WITHDRAWAL"), nullable(java.time.OffsetDateTime.class), any(java.time.OffsetDateTime.class)))
            .thenReturn(new java.math.BigDecimal("250.25"));
        for (String role : List.of("SUPER_ADMIN", "OPERATIONS", "FINANCE")) {
            for (String range : List.of("week", "month", "year", "all")) {
                mockMvc.perform(get("/api/analytics/platform-metrics").param("range", range)
                                .header("Authorization", "Bearer " + token(role)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.range").value(range))
                        .andExpect(jsonPath("$.totalOrders").value(0))
                        .andExpect(jsonPath("$.totalDeposits").value(1250.5))
                        .andExpect(jsonPath("$.totalWithdrawals").value(250.25));
            }
        }
    }

    @Test
    void noRangeDefaultsToAllTime() throws Exception {
        mockMvc.perform(get("/api/analytics/platform-metrics").header("Authorization", "Bearer " + token("FINANCE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.range").value("all"));
    }

    @Test
    void invalidRangeReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/analytics/platform-metrics").param("range", "invalid")
                        .header("Authorization", "Bearer " + token("FINANCE")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }

    @Test
    void clientsAndAnonymousUsersCannotReadMetrics() throws Exception {
        mockMvc.perform(get("/api/analytics/platform-metrics").header("Authorization", "Bearer " + token("CLIENT")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/platform-metrics"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }

    private String token(String role) throws Exception {
        long issuedAt = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"789\",\"roles\":[\"" + role + "\"],\"iat\":" + issuedAt + ",\"exp\":" + (issuedAt + 300) + "}";
        var signedToken = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(payload));
        signedToken.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return signedToken.serialize();
    }
}