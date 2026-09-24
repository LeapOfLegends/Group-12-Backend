package group12.Services;

import java.util.List;

import org.springframework.stereotype.Service;

import group12.Entities.ClientEntity;
import group12.Repository.ClientRepository;
import group12.dto.ClientDTO;
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
            throw new ClientNotFoundException("Client not found with email: " + email);
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
