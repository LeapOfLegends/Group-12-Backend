package group12.Services;

import group12.Entities.AdminEntity;
import group12.Repository.AdminDashboardRepository;
import group12.Repository.AdminRepository;
import group12.Repository.WarehouseRepository;
import group12.dto.AdminRoleCountDTO;
import group12.dto.CashPositionDTO;
import group12.dto.ClientActivityDTO;
import group12.dto.MarketDataHealthDTO;
import group12.dto.OrderLookupDTO;
import group12.dto.PlatformOverviewDTO;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AdminDashboardService {

    private final AdminDashboardRepository dashboardRepository;
    private final AdminRepository adminRepository;
    private final WarehouseRepository warehouseRepository;

    public AdminDashboardService(
            AdminDashboardRepository dashboardRepository,
            AdminRepository adminRepository,
            WarehouseRepository warehouseRepository
    ) {
        this.dashboardRepository = dashboardRepository;
        this.adminRepository = adminRepository;
        this.warehouseRepository = warehouseRepository;
    }

    public PlatformOverviewDTO getPlatformOverview() {
        return warehouseRepository.findPlatformOverview();
    }

    public List<AdminRoleCountDTO> getAdminCounts() {
        return adminRepository.findAll().stream()
                .collect(java.util.stream.Collectors.groupingBy(AdminEntity::getRole, java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .map(entry -> new AdminRoleCountDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

    public long getTotalAdmins() {
        return adminRepository.findAll().size();
    }

    public List<ClientActivityDTO> findClientActivity(String search) {
        return dashboardRepository.findClientActivity(search);
    }

    public Optional<OrderLookupDTO> findOrder(Long orderId) {
        return dashboardRepository.findOrder(orderId);
    }

    public MarketDataHealthDTO getMarketDataHealth() {
        return dashboardRepository.findMarketDataHealth();
    }

    public List<CashPositionDTO> findCashPositions(String search, String sort) {
        return dashboardRepository.findCashPositions(search, sort);
    }
}
