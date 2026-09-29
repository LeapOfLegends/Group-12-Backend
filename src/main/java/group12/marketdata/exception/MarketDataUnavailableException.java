package group12.marketdata.exception;

public class MarketDataUnavailableException extends MarketDataException {
    public MarketDataUnavailableException(String symbol) {
        super("No usable market data is available for instrument: " + symbol);
    }
}
