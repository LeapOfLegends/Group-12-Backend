package group12.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PlatformMetricsDTO(
        String range,
        Instant from,
        Instant to,
        long totalOrders,
        long filledOrders,
        long pendingOrders,
        long failedRejectedOrders,
        BigDecimal totalDeposits,
        BigDecimal totalWithdrawals,
        List<TradingValue> tradingValues
) {
    public record TradingValue(String currency, BigDecimal value) {
    }
}