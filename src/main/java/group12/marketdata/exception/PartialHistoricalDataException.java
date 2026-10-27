package group12.marketdata.exception;

import group12.marketdata.HistoricalBar;

import java.util.List;

public class PartialHistoricalDataException extends MarketDataProviderException {

    private final List<HistoricalBar> bars;

    public PartialHistoricalDataException(
            String message,
            Throwable cause,
            List<HistoricalBar> bars
    ) {
        super(message, cause);
        this.bars = List.copyOf(bars);
    }

    public List<HistoricalBar> getBars() {
        return bars;
    }
}
