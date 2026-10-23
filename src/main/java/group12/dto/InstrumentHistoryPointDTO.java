package group12.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record InstrumentHistoryPointDTO(
        OffsetDateTime timestamp,
        BigDecimal close
) {
}
