package group12.Controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import group12.Entities.ClientEntity;
import group12.Services.ClientService;
import group12.dto.ClientUpdateDTO;
import group12.dto.MoneyMovementDTO;
import group12.dto.AccountBalanceDTO;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientService clientService;

    public ClientController(ClientService clientService) {
        this.clientService = clientService;
    }

    /*
     * =========================================================================
     * CLIENT SELF-SERVICE ENDPOINTS
     *
     * These routes operate on the authenticated client's own record. The client
     * ID comes from the verified JWT subject rather than from a request path.
     * =========================================================================
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<ClientEntity> getCurrentClient(@AuthenticationPrincipal Jwt jwt) {
        ClientEntity client = clientService.getClientById(currentClientId(jwt));
        return ResponseEntity.ok(client);
    }

    @GetMapping("/me/balance")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<AccountBalanceDTO> getCurrentAccountBalance(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(clientService.getAccountBalance(currentClientId(jwt)));
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<ClientEntity> updateCurrentClient(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ClientUpdateDTO clientDTO
    ) {
        return updateClientDetails(currentClientId(jwt), clientDTO);
    }

    @PostMapping("/me/balance/transactions")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<AccountBalanceDTO> transactForCurrentClient(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MoneyMovementDTO request
    ) {
        return ResponseEntity.ok(clientService.transact(currentClientId(jwt), request));
    }

    /*
     * =========================================================================
     * ADMIN ENDPOINTS
     *
     * Operations manages client records, Finance handles balance operations,
     * and Super Admin has access to all admin operations, including deletion.
     * =========================================================================
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATIONS')")
    public List<ClientEntity> getAllClients() {
        return clientService.getAllClients();
    }

    @GetMapping("/{clientId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATIONS')")
    public ResponseEntity<ClientEntity> getClientById(@PathVariable Long clientId) {
        ClientEntity client = clientService.getClientById(clientId);
        return ResponseEntity.ok(client);
    }

    @GetMapping("/email/{email}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATIONS')")
    public ResponseEntity<ClientEntity> getClientByEmail(@PathVariable String email) {
        ClientEntity client = clientService.getClientByEmail(email);
        return ResponseEntity.ok(client);
    }

    @PutMapping("/{clientId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATIONS')")
    public ResponseEntity<ClientEntity> updateClient(
            @PathVariable Long clientId,
            @Valid @RequestBody ClientUpdateDTO clientDTO
    ) {
        return updateClientDetails(clientId, clientDTO);
    }

    @GetMapping("/{clientId}/balance")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'FINANCE')")
    public ResponseEntity<AccountBalanceDTO> getAccountBalance(@PathVariable Long clientId) {
        return ResponseEntity.ok(clientService.getAccountBalance(clientId));
    }

    @PostMapping("/{clientId}/balance/transactions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'FINANCE')")
    public ResponseEntity<AccountBalanceDTO> transact(
            @PathVariable Long clientId,
            @Valid @RequestBody MoneyMovementDTO request
    ) {
        return ResponseEntity.ok(clientService.transact(clientId, request));
    }

    @DeleteMapping("/{clientId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> deleteClient(@PathVariable Long clientId) {
        boolean deleted = clientService.deleteClient(clientId);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return ResponseEntity.noContent().build();
    }

    /*
     * =========================================================================
     * SHARED PRIVATE HELPERS
     * =========================================================================
     */

    /**
     * Applies profile updates for a client after the calling endpoint has
     * performed its role check. This helper is not exposed as an HTTP route.
     */
    private ResponseEntity<ClientEntity> updateClientDetails(Long clientId, ClientUpdateDTO clientDTO) {
        ClientEntity client = clientService.toEntity(clientDTO);
        boolean updated = clientService.updateClient(clientId, client);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return ResponseEntity.ok(client);
    }

    private Long currentClientId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
