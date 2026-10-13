package group12.Controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import group12.Entities.HoldingEntity;
import group12.Services.HoldingService;
import group12.security.JwtConfiguration;
import group12.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HoldingController.class)
@ContextConfiguration(classes = HoldingControllerSecurityTest.SecurityTestConfiguration.class)
@TestPropertySource(properties = "jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
class HoldingControllerSecurityTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HoldingService holdingService;

    @Configuration(proxyBeanMethods = false)
    @Import({HoldingController.class, SecurityConfig.class, JwtConfiguration.class})
    static class SecurityTestConfiguration {
    }

    @Test
    void clientSelfRoutesUseTheClientIdFromJwtSubject() throws Exception {
        when(holdingService.getHoldingsByClientId(123L)).thenReturn(List.of());
        when(holdingService.getHoldingByHoldingIdAndClientId(10L, 123L)).thenReturn(holding(10L, 123L, 25L));
        when(holdingService.getHoldingByInstrumentIdAndClientId(25L, 123L)).thenReturn(Optional.of(holding(10L, 123L, 25L)));
        String token = token("123", "CLIENT");

        mockMvc.perform(get("/api/clients/me/holdings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/clients/me/holdings/10").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/clients/me/holdings/instrument/25").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(holdingService).getHoldingsByClientId(123L);
        verify(holdingService).getHoldingByHoldingIdAndClientId(10L, 123L);
        verify(holdingService).getHoldingByInstrumentIdAndClientId(25L, 123L);
    }

    @Test
    void clientCannotReadAnotherClientsHoldings() throws Exception {
        mockMvc.perform(get("/api/clients/456/holdings")
                        .header("Authorization", "Bearer " + token("123", "CLIENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void financeAdminCanReadAnotherClientsHoldings() throws Exception {
        when(holdingService.getHoldingsByClientId(456L)).thenReturn(List.of());

        mockMvc.perform(get("/api/clients/456/holdings")
                        .header("Authorization", "Bearer " + token("789", "FINANCE")))
                .andExpect(status().isOk());

        verify(holdingService).getHoldingsByClientId(456L);
    }

    @Test
    void holdingRoutesRejectRequestsWithoutToken() throws Exception {
        mockMvc.perform(get("/api/clients/me/holdings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oldSingularClientRouteIsNoLongerMapped() throws Exception {
        mockMvc.perform(get("/api/client/me/holdings")
                        .header("Authorization", "Bearer " + token("123", "CLIENT")))
                .andExpect(status().isNotFound());
    }

    private String token(String subject, String role) throws JOSEException {
        long issuedAt = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"" + subject + "\",\"roles\":[\"" + role + "\"],"
                + "\"iat\":" + issuedAt + ",\"exp\":" + (issuedAt + 300) + "}";
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(payload));
        jws.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jws.serialize();
    }

    private HoldingEntity holding(Long holdingId, Long clientId, Long instrumentId) {
        HoldingEntity holding = new HoldingEntity();
        holding.setHoldingId(holdingId);
        holding.setClientId(clientId);
        holding.setInstrumentId(instrumentId);
        return holding;
    }
}
