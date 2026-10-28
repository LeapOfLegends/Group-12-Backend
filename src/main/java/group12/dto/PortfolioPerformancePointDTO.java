package group12.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PortfolioPerformancePointDTO(
        OffsetDateTime timestamp,
        BigDecimal cashValue,
        BigDecimal holdingsValue,
        BigDecimal totalValue,
        BigDecimal cumulativeReturnPercent,
        String valuationStatus
) {
}
