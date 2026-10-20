package group12.marketdata;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

public record HistoricalBar(
        OffsetDateTime barStart,
        BigDecimal closePrice,
        boolean complete
) {
    public HistoricalBar {
        Objects.requireNonNull(barStart, "barStart must not be null");
        Objects.requireNonNull(closePrice, "closePrice must not be null");
        if (closePrice.signum() <= 0) {
            throw new IllegalArgumentException("closePrice must be positive");
        }
        barStart = barStart.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
