package group12.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateTransactionRequest(
        @NotNull(message = "Client ID is required") Long clientId,
        Long orderId,
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.00", inclusive = false, message = "Amount must be greater than zero")
        BigDecimal amount,
        @NotNull(message = "Balance after is required")
        @DecimalMin(value = "0.00", message = "Balance after cannot be negative")
        BigDecimal balanceAfter
) {
}
