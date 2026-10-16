package group12.dto;

import java.math.BigDecimal;

public record TradeVolumeDTO(
        Integer year,
        Long totalTrades,
        Long sharesTraded,
        BigDecimal totalValue
) {
}
