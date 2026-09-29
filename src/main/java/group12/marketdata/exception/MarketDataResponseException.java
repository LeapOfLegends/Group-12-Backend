package group12.marketdata.exception;

public class MarketDataResponseException extends MarketDataException {
    public MarketDataResponseException(String message) {
        super(message);
    }

    public MarketDataResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
