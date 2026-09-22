package group12.exception;

public class HoldingNotFoundException extends RuntimeException{
    public HoldingNotFoundException(String message) {
        super(message);
    }
}
