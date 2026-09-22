package group12.Services;

import java.util.List;

import org.springframework.stereotype.Service;

import group12.Entities.ClientEntity;
import group12.Repository.ClientRepository;
import group12.dto.ClientDTO;

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
        return clientRepository.findById(clientId);
    }

    public ClientEntity getClientByEmail(String email) {
        return clientRepository.findByEmail(email);
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
        return clientRepository.save(client) > 0;
    }

    public boolean updateClient(Long clientId, ClientEntity client) {
        ClientEntity existingClient = clientRepository.findById(clientId);
        if (existingClient == null) {
            return false;
        }

        client.setClientId(clientId);
        return clientRepository.update(client) > 0;
    }

    public boolean deleteClient(Long clientId) {
        ClientEntity existingClient = clientRepository.findById(clientId);
        if (existingClient == null) {
            return false;
        }

        return clientRepository.deleteById(clientId) > 0;
    }
}
