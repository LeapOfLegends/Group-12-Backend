package group12.dto;

import group12.Entities.OrderType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(

        // will remove once authentication is implemented
        @NotNull(message = "Client ID is required") Long clientId,

        @NotNull(message = "Instrument ID is required") Long instrumentId,
        @NotNull(message = "Order type is required") OrderType orderType,
        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be greater than zero") Integer quantity
) {
}