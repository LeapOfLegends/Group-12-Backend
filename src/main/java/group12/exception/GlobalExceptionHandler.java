package group12.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return errorResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String message = exception.getReason() == null
                ? status.getReasonPhrase()
                : exception.getReason();

        return errorResponse(status, message, request);
    }

    @ExceptionHandler(ClientNotFoundException.class)
    public ResponseEntity<ApiError> handleClientNotFound(
            ClientNotFoundException exception,
            HttpServletRequest request
    ) {
        return errorResponse(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(HoldingNotFoundException.class)
    public ResponseEntity<ApiError> handleHoldingNotFound(
            HoldingNotFoundException exception,
            HttpServletRequest request
    ) {
        return errorResponse(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ApiError> handleOrderNotFound(
            OrderNotFoundException exception,
            HttpServletRequest request
    ) {
        return errorResponse(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(OrderSubmissionException.class)
    public ResponseEntity<ApiError> handleOrderSubmission(
            OrderSubmissionException exception,
            HttpServletRequest request
    ) {
        return errorResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(ClientSubmissionException.class)
    public ResponseEntity<ApiError> handleClientSubmission(
            ClientSubmissionException exception,
            HttpServletRequest request
    ) {
        return errorResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiError> handleDuplicateResource(
            DuplicateResourceException exception,
            HttpServletRequest request
    ) {
        String message = exception.getMessages().stream()
                .collect(Collectors.joining("; "));

        return errorResponse(HttpStatus.CONFLICT, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return errorResponse(
                HttpStatus.BAD_REQUEST,
                unreadableMessage(exception),
                request
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult()
                .getFieldError()
                .getDefaultMessage();

        return errorResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    private ResponseEntity<ApiError> errorResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        ApiError error = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(error);
    }

    private String unreadableMessage(HttpMessageNotReadableException exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof InvalidFormatException invalidFormat
                    && invalidFormat.getTargetType().isEnum()) {
                String field = invalidFormat.getPath().stream()
                        .map(JsonMappingException.Reference::getFieldName)
                        .filter(name -> name != null && !name.isBlank())
                        .reduce((first, second) -> second)
                        .orElse("request field");
                String allowedValues = Arrays.stream(
                                invalidFormat.getTargetType().getEnumConstants()
                        )
                        .map(Object::toString)
                        .collect(Collectors.joining(", "));

                return "Invalid value '" + invalidFormat.getValue()
                        + "' for " + field
                        + ". Allowed values: " + allowedValues;
            }

            cause = cause.getCause();
        }

        return "Request body is malformed or unreadable";
    }
}