package group12.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record InstrumentHistoryDTO(
        Long instrumentId,
        String symbol,
        String range,
        String timeframe,
        String currency,
        OffsetDateTime availableFrom,
        OffsetDateTime availableTo,
        boolean partial,
        List<InstrumentHistoryPointDTO> points
) {
}
