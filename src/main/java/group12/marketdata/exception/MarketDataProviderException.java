package group12.marketdata.exception;

public class MarketDataProviderException extends MarketDataException {
    public MarketDataProviderException(String message) {
        super(message);
    }

    public MarketDataProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
