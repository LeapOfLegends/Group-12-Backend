package group12.dto;

import java.math.BigDecimal;

public record PlatformOverviewDTO(
        long registeredClients,
        long accountsWithCash,
        BigDecimal totalClientCash,
        long tradableInstruments
) {
}
