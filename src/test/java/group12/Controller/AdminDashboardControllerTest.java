package group12.Controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import group12.Services.AdminDashboardService;
import group12.dto.CashPositionDTO;
import group12.dto.ClientActivityDTO;
import group12.dto.MarketDataHealthDTO;
import group12.dto.OrderLookupDTO;
import group12.dto.PlatformOverviewDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AdminDashboardControllerTest {

    @Mock
    private AdminDashboardService dashboardService;

    private AdminDashboardController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminDashboardController(dashboardService);
    }

    @Test
    void platformOverview_returnsWarehouseMetrics() {
        PlatformOverviewDTO overview = new PlatformOverviewDTO(
                10, 8, new BigDecimal("125000.00"), 17
        );
        when(dashboardService.getPlatformOverview()).thenReturn(overview);

        PlatformOverviewDTO response = controller.getPlatformOverview();

        assertEquals(overview, response);
        verify(dashboardService).getPlatformOverview();
    }

    @Test
    void clientLookup_forwardsSearchTerm() {
        List<ClientActivityDTO> clients = List.of(new ClientActivityDTO(
                53L, "Test", "Admin", "test.superadmin@admin.com",
                new BigDecimal("1000.00"), 2, null
        ));
        when(dashboardService.findClientActivity("test.superadmin@admin.com"))
                .thenReturn(clients);

        List<ClientActivityDTO> response = controller.findClients("test.superadmin@admin.com");

        assertEquals(clients, response);
        verify(dashboardService).findClientActivity("test.superadmin@admin.com");
    }

    @Test
    void orderLookup_returnsNotFoundWhenServiceHasNoOrder() {
        when(dashboardService.findOrder(999L)).thenReturn(Optional.empty());

        ResponseEntity<OrderLookupDTO> response = controller.findOrder(999L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(dashboardService).findOrder(999L);
    }

    @Test
    void marketDataHealth_returnsAllStatusCounts() {
        MarketDataHealthDTO health = new MarketDataHealthDTO(20, 12, 4, 2, 2);
        when(dashboardService.getMarketDataHealth()).thenReturn(health);

        MarketDataHealthDTO response = controller.getMarketDataHealth();

        assertEquals(health, response);
        verify(dashboardService).getMarketDataHealth();
    }

    @Test
    void cashPositions_forwardsSearchAndSort() {
        List<CashPositionDTO> positions = List.of(new CashPositionDTO(
                53L, "Test", "Admin", "test.superadmin@admin.com",
                new BigDecimal("1000.00"), LocalDateTime.now()
        ));
        when(dashboardService.findCashPositions("test", "name")).thenReturn(positions);

        List<CashPositionDTO> response = controller.findCashPositions("test", "name");

        assertEquals(positions, response);
        verify(dashboardService).findCashPositions("test", "name");
    }

    @Test
    void cashPositions_rejectsUnknownSort() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.findCashPositions(null, "balance")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }
}
