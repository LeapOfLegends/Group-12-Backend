package group12.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PortfolioPerformanceDTO(
        OffsetDateTime date,
        BigDecimal investedCapital,
        BigDecimal holdingsValue,
        BigDecimal totalPortfolioValue
) {
}
