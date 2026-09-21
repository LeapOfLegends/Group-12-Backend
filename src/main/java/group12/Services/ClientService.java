package group12.Services;

import java.util.List;

import org.springframework.stereotype.Service;

import group12.Entities.ClientEntity;
import group12.Repository.ClientRepository;

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
