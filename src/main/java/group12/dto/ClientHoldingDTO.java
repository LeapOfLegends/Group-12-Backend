package group12.dto;

import java.math.BigDecimal;

public record ClientHoldingDTO(
        Long instrumentId,
        String symbol,
        String instrumentName,
        String assetClass,
        BigDecimal quantity,
        BigDecimal averageCost,
        BigDecimal currentPrice,
        BigDecimal marketValue,
        BigDecimal unrealizedReturn,
        BigDecimal unrealizedReturnPercent
) {
}
