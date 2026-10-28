package group12.exception;

public class FinancialHistoryReconstructionException extends RuntimeException {

    public FinancialHistoryReconstructionException(Long clientId, String detail) {
        super("Cannot reconstruct financial history for client " + clientId + ": " + detail);
    }
}
