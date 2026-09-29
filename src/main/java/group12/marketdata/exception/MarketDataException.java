package group12.marketdata.exception;

public abstract class MarketDataException extends RuntimeException {

    protected MarketDataException(String message) {
        super(message);
    }

    protected MarketDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
