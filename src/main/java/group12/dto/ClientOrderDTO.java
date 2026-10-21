package group12.dto;

import group12.Entities.OrderStatus;
import group12.Entities.OrderType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ClientOrderDTO(
        Long orderId,
        Long instrumentId,
        String symbol,
        OrderType orderType,
        BigDecimal quantity,
        OrderStatus status,
        BigDecimal reservedCash,
        OffsetDateTime submittedAt,
        OffsetDateTime filledAt,
        BigDecimal executionPrice,
        BigDecimal tradeValue
) {
}
