package group12.Controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import group12.Entities.ClientEntity;
import group12.Services.ClientDashboardService;
import group12.Services.ClientService;
import group12.security.JwtConfiguration;
import group12.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ClientController.class)
@ContextConfiguration(classes = ClientControllerSecurityTest.SecurityTestConfiguration.class)
@TestPropertySource(properties = "jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
class ClientControllerSecurityTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClientService clientService;

    @MockBean
    private ClientDashboardService clientDashboardService;

    @Configuration(proxyBeanMethods = false)
    @Import({ClientController.class, SecurityConfig.class, JwtConfiguration.class})
    static class SecurityTestConfiguration {
    }

    @Test
    void financeAdminCanReadClientCashPositions() throws Exception {
        ClientEntity client = new ClientEntity();
        client.setClientId(456L);
        when(clientService.getAllClients()).thenReturn(List.of(client));

        mockMvc.perform(get("/api/clients").header("Authorization", "Bearer " + token("FINANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientId").value(456));

        verify(clientService).getAllClients();
    }

    @Test
    void otherAdminRolesRetainClientListAccess() throws Exception {
        when(clientService.getAllClients()).thenReturn(List.of());

        for (String role : List.of("SUPER_ADMIN", "OPERATIONS")) {
            mockMvc.perform(get("/api/clients").header("Authorization", "Bearer " + token(role)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void clientCannotReadPlatformClientList() throws Exception {
        mockMvc.perform(get("/api/clients").header("Authorization", "Bearer " + token("CLIENT")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clientService);
    }

    @Test
    void financeAdminCannotEditClientProfiles() throws Exception {
        mockMvc.perform(patch("/api/clients/456")
                        .header("Authorization", "Bearer " + token("FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Changed\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clientService);
    }

    @Test
    void clientListRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/clients"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(clientService);
    }

    private String token(String role) throws JOSEException {
        long issuedAt = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"789\",\"roles\":[\"" + role + "\"],"
                + "\"iat\":" + issuedAt + ",\"exp\":" + (issuedAt + 300) + "}";
        JWSObject signedToken = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(payload));
        signedToken.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return signedToken.serialize();
    }
}