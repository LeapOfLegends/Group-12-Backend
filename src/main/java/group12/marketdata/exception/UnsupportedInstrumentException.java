package group12.marketdata.exception;

public class UnsupportedInstrumentException extends MarketDataException {
    public UnsupportedInstrumentException(String symbol) {
        super("Instrument is not supported by the configured US equity market-data feed: " + symbol);
    }
}
