package group12.exception;

public class MarketCannotUpdateException extends RuntimeException{
    public MarketCannotUpdateException(String message) {
        super(message);
    }
}
