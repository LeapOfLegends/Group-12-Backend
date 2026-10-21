package group12.dto;

import java.math.BigDecimal;

public record ClientDashboardSummaryDTO(
        BigDecimal accountCashBalance,
        BigDecimal totalPortfolioValue,
        BigDecimal totalReturn,
        BigDecimal totalReturnPercent,
        BigDecimal buyingPower
) {
}
