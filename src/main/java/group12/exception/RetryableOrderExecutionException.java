package group12.exception;

public class RetryableOrderExecutionException extends OrderLifecycleException {
    public RetryableOrderExecutionException(String message) {
        super(message);
    }
}
