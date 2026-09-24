package group12.exception;

public class AdminNotFoundException extends RuntimeException {

    public AdminNotFoundException(Long adminId) {

        super("Admin not found with id: " + adminId);
    }
}