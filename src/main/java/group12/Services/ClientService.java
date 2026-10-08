package group12.Services;

import java.util.List;

import org.springframework.stereotype.Service;

import group12.Entities.ClientEntity;
import group12.Repository.ClientRepository;
import group12.dto.ClientDTO;
import group12.dto.ClientUpdateDTO;
import group12.dto.AccountBalanceDTO;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import group12.exception.ClientNotFoundException;
import group12.exception.ClientSubmissionException;

@Service
public class ClientService {

    private final ClientRepository clientRepository;

    public ClientService(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    public List<ClientEntity> getAllClients() {
        return clientRepository.findAll();
    }

    public ClientEntity getClientById(Long clientId) {
        ClientEntity client = clientRepository.findById(clientId);
        if (client == null) {
            throw new ClientNotFoundException("Client not found with id: " + clientId);
        }
        return client;
    }

    public ClientEntity getClientByEmail(String email) {
        ClientEntity client = clientRepository.findByEmail(email);
        if (client == null) {
            throw new ClientNotFoundException("Client not found with email");
        }
        return client;
    }

    public ClientEntity toEntity(ClientDTO clientDTO) {
        ClientEntity client = new ClientEntity();
        client.setFirstName(clientDTO.getFirstName());
        client.setLastName(clientDTO.getLastName());
        client.setEmail(clientDTO.getEmail());
        client.setPasswordHash(clientDTO.getPasswordHash());
        client.setSsn(clientDTO.getSsn());
        client.setPhoneNumber(clientDTO.getPhoneNumber());
        client.setAccountBalance(clientDTO.getAccountBalance());
        return client;
    }

    public AccountBalanceDTO getAccountBalance(Long clientId) {
        ClientEntity client = getClientById(clientId);
        return new AccountBalanceDTO(clientId, client.getAccountBalance());
    }

    public ClientEntity toEntity(ClientUpdateDTO clientDTO) {
        ClientEntity client = new ClientEntity();
        client.setFirstName(clientDTO.getFirstName());
        client.setLastName(clientDTO.getLastName());
        client.setEmail(clientDTO.getEmail());
        client.setPasswordHash(clientDTO.getPasswordHash());
        client.setSsn(clientDTO.getSsn());
        client.setPhoneNumber(clientDTO.getPhoneNumber());
        return client;
    }

    @Transactional
    public AccountBalanceDTO deposit(Long clientId, BigDecimal amount) {
        return changeBalance(clientId, amount, true);
    }

    @Transactional
    public AccountBalanceDTO withdraw(Long clientId, BigDecimal amount) {
        return changeBalance(clientId, amount, false);
    }

    private AccountBalanceDTO changeBalance(Long clientId, BigDecimal amount, boolean deposit) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        ClientEntity client = clientRepository.findByIdForUpdate(clientId);
        if (client == null) {
            throw new ClientNotFoundException("Client not found with id: " + clientId);
        }
        BigDecimal balance = client.getAccountBalance();
        if (balance == null) {
            throw new IllegalStateException("Client account balance is not set");
        }
        if (!deposit && balance.compareTo(amount) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient funds");
        }

        BigDecimal updatedBalance = deposit ? balance.add(amount) : balance.subtract(amount);
        if (clientRepository.updateAccountBalance(clientId, updatedBalance) != 1) {
            throw new IllegalStateException("Failed to update client account balance");
        }
        return new AccountBalanceDTO(clientId, updatedBalance);
    }

    public boolean createClient(ClientEntity client) {
        // check uniqueness for email and SSN using counts to avoid selectOne errors
        java.util.List<String> errors = new java.util.ArrayList<>();

        if (clientRepository.countByEmail(client.getEmail()) > 0) {
            errors.add("email: Email already in use");
        }

        if (clientRepository.countBySsn(client.getSsn()) > 0) {
            errors.add("ssn: SSN already in use");
        }

        if (!errors.isEmpty()) {
            throw new group12.exception.DuplicateResourceException(errors, client);
        }

        int result = clientRepository.save(client);
        if (result <= 0) {
            throw new ClientSubmissionException("Failed to create client");
        }
        return true;
    }

    public boolean updateClient(Long clientId, ClientEntity client) {
        ClientEntity existingClient = clientRepository.findById(clientId);
        if (existingClient == null) {
            throw new ClientNotFoundException("Client not found with id: " + clientId);
        }

        client.setClientId(clientId);
        return clientRepository.update(client) > 0;
    }

    public boolean deleteClient(Long clientId) {
        ClientEntity existingClient = clientRepository.findById(clientId);
        if (existingClient == null) {
            throw new ClientNotFoundException("Client not found with id: " + clientId);
        }

        return clientRepository.deleteById(clientId) > 0;
    }
}
