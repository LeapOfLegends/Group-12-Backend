package group12.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PortfolioValueDTO(
        LocalDateTime date,
        BigDecimal accountBalance,
        BigDecimal holdingsValue,
        BigDecimal totalPortfolioValue
) {
}
