package group12.Services;

import group12.Entities.AdminEntity;
import group12.Repository.AdminRepository;

import group12.dto.AdminCreateDTO;
import group12.dto.AdminDTO;
import group12.dto.AdminUpdateDTO;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

@Service
public class AdminService {

    private final AdminRepository adminRepository;

    public AdminService(AdminRepository adminRepository) {
        this.adminRepository = adminRepository;
    }


    // CREATE
    public AdminDTO createAdmin(AdminCreateDTO request) {

        adminRepository.findByEmail(request.getEmail())
                .ifPresent(existingAdmin -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "An admin already exists with email: "
                                    + request.getEmail()
                    );
                });


        AdminEntity admin = new AdminEntity();

        admin.setFirstName(request.getFirstName());
        admin.setLastName(request.getLastName());
        admin.setEmail(request.getEmail());

        admin.setPasswordHash(
                hashPassword(request.getPassword())
        );


        adminRepository.save(admin);


        AdminEntity savedAdmin =
                adminRepository.findById(admin.getAdminId())
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Admin could not be found after creation"
                                )
                        );


        return toDTO(savedAdmin);
    }


    // READ ALL
    public List<AdminDTO> getAllAdmins() {

        return adminRepository.findAll()
                .stream()
                .map(this::toDTO)
                .toList();
    }


    // READ ONE
    public AdminDTO getAdminById(Long adminId) {

        AdminEntity admin =
                adminRepository.findById(adminId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Admin not found with id: " + adminId
                                )
                        );

        return toDTO(admin);
    }


    // UPDATE
    public AdminDTO updateAdmin(
            Long adminId,
            AdminUpdateDTO request
    ) {

        AdminEntity admin =
                adminRepository.findById(adminId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Admin not found with id: " + adminId
                                )
                        );


        adminRepository.findByEmail(request.getEmail())
                .ifPresent(existingAdmin -> {

                    if (!existingAdmin.getAdminId().equals(adminId)) {

                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "An admin already exists with email: "
                                        + request.getEmail()
                        );
                    }
                });


        admin.setFirstName(request.getFirstName());
        admin.setLastName(request.getLastName());
        admin.setEmail(request.getEmail());


        adminRepository.update(admin);


        return toDTO(admin);
    }


    // DELETE
    public void deleteAdmin(Long adminId) {

        adminRepository.findById(adminId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Admin not found with id: " + adminId
                        )
                );


        adminRepository.deleteById(adminId);
    }


        private String hashPassword(String password) {
                byte[] salt = new byte[16];
                new SecureRandom().nextBytes(salt);

                try {
                        PBEKeySpec keySpec = new PBEKeySpec(
                                        password.toCharArray(), salt, 65536, 256
                        );
                        byte[] hash = SecretKeyFactory
                                        .getInstance("PBKDF2WithHmacSHA256")
                                        .generateSecret(keySpec)
                                        .getEncoded();

                        return Base64.getEncoder().encodeToString(salt) + ":"
                                        + Base64.getEncoder().encodeToString(hash);
                } catch (GeneralSecurityException exception) {
                        throw new IllegalStateException("Unable to hash password", exception);
                }
        }


        // CONVERT ENTITY -> DTO
    private AdminDTO toDTO(AdminEntity admin) {

        return new AdminDTO(
                admin.getAdminId(),
                admin.getFirstName(),
                admin.getLastName(),
                admin.getEmail(),
                admin.getCreatedAt()
        );
    }
}