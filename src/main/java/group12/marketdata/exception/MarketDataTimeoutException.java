package group12.marketdata.exception;

public class MarketDataTimeoutException extends MarketDataException {
    public MarketDataTimeoutException(Throwable cause) {
        super("Market-data provider request timed out", cause);
    }
}
