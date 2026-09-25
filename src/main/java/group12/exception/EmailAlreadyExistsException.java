package group12.exception;

public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {

        super("An admin already exists with email: " + email);
    }
}