package group12.marketdata;

import java.time.OffsetDateTime;
import java.util.List;

public interface HistoricalMarketDataProvider {

    boolean supports(MarketDataRequest instrument);

    List<HistoricalBar> getHistoricalBars(
            MarketDataRequest instrument,
            HistoricalTimeframe timeframe,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    );
}
