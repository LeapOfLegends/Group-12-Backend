package group12.dto;

import java.time.LocalDateTime;

public class AdminDTO {

    private Long adminId;
    private String firstName;
    private String lastName;
    private String email;
    private LocalDateTime createdAt;


    public AdminDTO(
            Long adminId,
            String firstName,
            String lastName,
            String email,
            LocalDateTime createdAt) {

        this.adminId = adminId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.createdAt = createdAt;
    }


    public Long getAdminId() {
        return adminId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}