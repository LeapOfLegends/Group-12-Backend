package group12.Controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import group12.dto.ClientDTO;
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

    @GetMapping
    public List<ClientEntity> getAllClients() {
        return clientService.getAllClients();
    }

    @GetMapping("/{clientId}")
    public ResponseEntity<ClientEntity> getClientById(@PathVariable Long clientId) {
        ClientEntity client = clientService.getClientById(clientId);
        return ResponseEntity.ok(client);
    }

    //TODO: maybe rename this?
    @GetMapping("/{clientId}/balance")
    public ResponseEntity<AccountBalanceDTO> getAccountBalance(@PathVariable Long clientId) {
        return ResponseEntity.ok(clientService.getAccountBalance(clientId));
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<ClientEntity> getClientByEmail(@PathVariable String email) {
        ClientEntity client = clientService.getClientByEmail(email);
        return ResponseEntity.ok(client);
    }

    @PostMapping
    public ResponseEntity<ClientEntity> createClient(@Valid @RequestBody ClientDTO clientDTO) {
        ClientEntity client = clientService.toEntity(clientDTO);
        clientService.createClient(client); // will throw ClientSubmissionException on failure
        return ResponseEntity.status(HttpStatus.CREATED).body(client);
    }

    @PutMapping("/{clientId}")
    public ResponseEntity<ClientEntity> updateClient(@PathVariable Long clientId, @Valid @RequestBody ClientUpdateDTO clientDTO) {
        ClientEntity client = clientService.toEntity(clientDTO);
        boolean updated = clientService.updateClient(clientId, client);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return ResponseEntity.ok(client);
    }

    @PostMapping("/{clientId}/deposit")
    public ResponseEntity<AccountBalanceDTO> deposit(
            @PathVariable Long clientId,
            @Valid @RequestBody MoneyMovementDTO request
    ) {
        return ResponseEntity.ok(clientService.deposit(clientId, request.getAmount()));
    }

    @PostMapping("/{clientId}/withdraw")
    public ResponseEntity<AccountBalanceDTO> withdraw(
            @PathVariable Long clientId,
            @Valid @RequestBody MoneyMovementDTO request
    ) {
        return ResponseEntity.ok(clientService.withdraw(clientId, request.getAmount()));
    }

    @DeleteMapping("/{clientId}")
    public ResponseEntity<Void> deleteClient(@PathVariable Long clientId) {
        boolean deleted = clientService.deleteClient(clientId);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return ResponseEntity.noContent().build();
    }
}
