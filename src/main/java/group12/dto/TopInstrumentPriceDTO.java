package group12.dto;

import java.math.BigDecimal;

public record TopInstrumentPriceDTO(
        Long instrumentId,
        String symbol,
        BigDecimal price
) {
}
