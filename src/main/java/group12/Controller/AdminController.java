package group12.Controller;

import group12.Services.AdminService;

import group12.dto.AdminCreateDTO;
import group12.dto.AdminDTO;
import group12.dto.AdminUpdateDTO;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admins")
public class AdminController {

    private final AdminService adminService;


    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }


    // CREATE ADMIN
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDTO createAdmin(
            @Valid @RequestBody AdminCreateDTO request
    ) {

        return adminService.createAdmin(request);
    }


    // GET ALL ADMINS
    @GetMapping
    public List<AdminDTO> getAllAdmins() {

        return adminService.getAllAdmins();
    }


    // GET ONE ADMIN
    @GetMapping("/{adminId}")
    public AdminDTO getAdminById(
            @PathVariable Long adminId
    ) {

        return adminService.getAdminById(adminId);
    }


    // UPDATE ADMIN
    @PutMapping("/{adminId}")
    public AdminDTO updateAdmin(
            @PathVariable Long adminId,
            @Valid @RequestBody AdminUpdateDTO request
    ) {

        return adminService.updateAdmin(
                adminId,
                request
        );
    }


    // DELETE ADMIN
    @DeleteMapping("/{adminId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdmin(
            @PathVariable Long adminId
    ) {

        adminService.deleteAdmin(adminId);
    }
}