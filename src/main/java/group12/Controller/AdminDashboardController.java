package group12.Controller;

import group12.Services.AdminDashboardService;
import group12.dto.AdminRoleCountDTO;
import group12.dto.CashPositionDTO;
import group12.dto.ClientActivityDTO;
import group12.dto.MarketDataHealthDTO;
import group12.dto.OrderLookupDTO;
import group12.dto.PlatformOverviewDTO;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    public AdminDashboardController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/platform-overview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'FINANCE', 'OPERATIONS')")
    public PlatformOverviewDTO getPlatformOverview() {
        return dashboardService.getPlatformOverview();
    }

    @GetMapping("/admins/counts")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<AdminRoleCountDTO> getAdminCounts() {
        return dashboardService.getAdminCounts();
    }

    @GetMapping("/admins/total")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public long getTotalAdmins() {
        return dashboardService.getTotalAdmins();
    }

    @GetMapping("/clients")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATIONS')")
    public List<ClientActivityDTO> findClients(
            @RequestParam(required = false) String search
    ) {
        return dashboardService.findClientActivity(search);
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATIONS')")
    public ResponseEntity<OrderLookupDTO> findOrder(@PathVariable Long orderId) {
        return dashboardService.findOrder(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/market-data-health")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATIONS')")
    public MarketDataHealthDTO getMarketDataHealth() {
        return dashboardService.getMarketDataHealth();
    }

    @GetMapping("/cash-positions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'FINANCE')")
    public List<CashPositionDTO> findCashPositions(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "highest_balance") String sort
    ) {
        if (!List.of("highest_balance", "name", "newest").contains(sort)) {
            throw new ResponseStatusException(BAD_REQUEST, "sort must be highest_balance, name, or newest");
        }
        return dashboardService.findCashPositions(search, sort);
    }
}
