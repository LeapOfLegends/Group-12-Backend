package group12.dto;

import group12.Entities.OrderType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotNull(message = "Instrument ID is required") Long instrumentId,
        @NotNull(message = "Order type is required") OrderType orderType,
        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be greater than zero") BigDecimal quantity
) {
}
