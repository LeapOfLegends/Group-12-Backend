package group12.exception;

import java.util.List;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString(callSuper = true)
public class DuplicateResourceException extends RuntimeException {
    private final List<String> messages;
    private final Object submitted;

    public DuplicateResourceException(List<String> messages, Object submitted) {
        super("Duplicate resource fields");
        this.messages = messages;
        this.submitted = submitted;
    }
}