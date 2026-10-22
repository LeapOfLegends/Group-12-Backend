package group12.Controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import group12.Services.ClientDashboardService;
import group12.Services.ClientService;
import group12.dto.ClientDashboardDTO;
import group12.dto.ClientDashboardSummaryDTO;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class ClientDashboardEndpointTest {

    @Mock
    private ClientService clientService;

    @Mock
    private ClientDashboardService dashboardService;

    private ClientController controller;

    @BeforeEach
    void setUp() {
        controller = new ClientController(clientService, dashboardService);
    }

    @Test
    void dashboard_usesClientIdFromJwtSubject() {
        long clientId = 42L;
        ClientDashboardSummaryDTO summary = new ClientDashboardSummaryDTO(
                new BigDecimal("1000.00"),
                new BigDecimal("2500.00"),
                new BigDecimal("250.00"),
                new BigDecimal("11.11"),
                new BigDecimal("1000.00")
        );
        ClientDashboardDTO dashboard = new ClientDashboardDTO(
                summary,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        when(dashboardService.getDashboard(clientId)).thenReturn(dashboard);

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(String.valueOf(clientId))
                .claim("roles", List.of("CLIENT"))
                .build();

        ResponseEntity<ClientDashboardDTO> response =
                controller.getCurrentClientDashboard(jwt);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(dashboard, response.getBody());
        verify(dashboardService).getDashboard(clientId);
    }
}
