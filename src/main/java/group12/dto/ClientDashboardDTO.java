package group12.dto;

import java.util.List;

public record ClientDashboardDTO(
        ClientDashboardSummaryDTO summary,
        List<PortfolioValueDTO> portfolioPerformance,
        List<AssetAllocationDTO> assetAllocation,
        List<ClientHoldingDTO> currentHoldings,
        List<ClientOrderDTO> recentOrders
) {
}
