package group12.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for partially updating a client's profile.
 * Null or omitted fields are left unchanged. SSN and account balance are not
 * editable through profile updates.
 */
public class ClientUpdateDTO {

    @Pattern(regexp = "(?s).*\\S.*", message = "First name cannot be blank")
    private String firstName;

    @Pattern(regexp = "(?s).*\\S.*", message = "Last name cannot be blank")
    private String lastName;

    @Email(message = "Email must be a valid address")
    @Pattern(regexp = "(?s).*\\S.*", message = "Email cannot be blank")
    private String email;

    @Pattern(regexp = "(?s).*\\S.*", message = "Password hash cannot be blank")
    private String passwordHash;

    @Pattern(regexp = "^(?=.*\\S)(?:\\+?[0-9()\\-\\s]{7,20})$", message = "Phone number is invalid")
    private String phoneNumber;

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
}
