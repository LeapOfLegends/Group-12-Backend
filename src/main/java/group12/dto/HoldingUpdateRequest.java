package group12.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record HoldingUpdateRequest(
        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be greater than zero")
        Integer quantity,

        @NotNull(message = "Average cost is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "Average cost must be greater than zero")
        BigDecimal averageCost
) {
}
