package group12.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CashPositionDTO(
        Long clientId,
        String firstName,
        String lastName,
        String email,
        BigDecimal accountBalance,
        LocalDateTime createdAt
) {
}
