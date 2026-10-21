package group12.Services;

import group12.Repository.ClientDashboardRepository;
import group12.dto.ClientDashboardDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClientDashboardService {

    private final ClientDashboardRepository dashboardRepository;
    private final ClientService clientService;

    public ClientDashboardDTO getDashboard(Long clientId) {
        clientService.getClientById(clientId);
        return new ClientDashboardDTO(
                dashboardRepository.findSummary(clientId),
                dashboardRepository.findPortfolioPerformance(clientId),
                dashboardRepository.findAssetAllocation(clientId),
                dashboardRepository.findCurrentHoldings(clientId),
                dashboardRepository.findRecentOrders(clientId)
        );
    }
}
