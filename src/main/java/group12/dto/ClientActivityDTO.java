package group12.dto;

import java.time.OffsetDateTime;

public record ClientActivityDTO(
        Long clientId,
        String firstName,
        String lastName,
        String email,
        java.math.BigDecimal accountBalance,
        long totalOrders,
        OffsetDateTime lastOrderAt
) {
}
