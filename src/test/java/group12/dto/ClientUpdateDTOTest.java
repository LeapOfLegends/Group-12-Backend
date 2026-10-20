package group12.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientUpdateDTOTest {

    private final Validator validator;

    ClientUpdateDTOTest() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void namesAcceptLettersAndCommonSeparators() {
        ClientUpdateDTO dto = new ClientUpdateDTO();
        dto.setFirstName("María-José");
        dto.setLastName("O’Connor Smith");

        assertTrue(validator.validate(dto).isEmpty());
    }

    @Test
    void namesRejectDigitsAndOtherPunctuation() {
        ClientUpdateDTO dto = new ClientUpdateDTO();
        dto.setFirstName("Ava2");
        dto.setLastName("Brown_!");

        var violations = validator.validate(dto);

        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("firstName")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("lastName")));
    }
}
