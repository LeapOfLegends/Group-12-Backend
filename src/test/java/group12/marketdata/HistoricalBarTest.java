package group12.marketdata;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HistoricalBarTest {

    @Test
    void normalizesBarStartToUtcWithoutChangingTheInstant() {
        HistoricalBar bar = new HistoricalBar(
                OffsetDateTime.parse("2026-10-20T09:30:00-04:00"),
                new BigDecimal("250.25"),
                true
        );

        assertEquals(OffsetDateTime.parse("2026-10-20T13:30:00Z"), bar.barStart());
        assertEquals(ZoneOffset.UTC, bar.barStart().getOffset());
    }

    @Test
    void rejectsZeroAndNegativePrices() {
        OffsetDateTime barStart = OffsetDateTime.parse("2026-10-20T13:30:00Z");

        assertThrows(
                IllegalArgumentException.class,
                () -> new HistoricalBar(barStart, BigDecimal.ZERO, true)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new HistoricalBar(barStart, new BigDecimal("-0.01"), true)
        );
    }

    @Test
    void historicalTimeframesMatchTheFrozenPersistenceValues() {
        assertEquals(
                List.of("5Min", "30Min", "1Day", "1Week", "1Month"),
                Arrays.stream(HistoricalTimeframe.values())
                        .map(HistoricalTimeframe::getDatabaseValue)
                        .toList()
        );
    }
}
