package group12.Services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import group12.Entities.ClientEntity;
import group12.Repository.ClientRepository;
import group12.dto.ClientDTO;
import group12.exception.ClientNotFoundException;
import group12.exception.ClientSubmissionException;
import group12.exception.DuplicateResourceException;

@ExtendWith(MockitoExtension.class)
class ClientServiceTest {

    @Mock
    private ClientRepository clientRepository;

    private ClientService clientService;

    @BeforeEach
    void setUp() {
        clientService = new ClientService(clientRepository);
    }

    @Test
    void getAllClients_returnsRepositoryResults() {
        List<ClientEntity> expectedClients = List.of(
                client(1L, "Ava", "Martinez", "ava@example.com"),
                client(2L, "Leo", "Brown", "leo@example.com")
        );
        when(clientRepository.findAll()).thenReturn(expectedClients);

        List<ClientEntity> result = clientService.getAllClients();

        assertSame(expectedClients, result);
        verify(clientRepository).findAll();
    }

    @Test
    void getClientById_whenClientExists_returnsClient() {
        ClientEntity expected = client(4L, "Ava", "Martinez", "ava@example.com");
        when(clientRepository.findById(4L)).thenReturn(expected);

        ClientEntity result = clientService.getClientById(4L);

        assertSame(expected, result);
        verify(clientRepository).findById(4L);
    }

    @Test
    void getClientById_whenClientDoesNotExist_throwsNotFoundException() {
        when(clientRepository.findById(99L)).thenReturn(null);

        ClientNotFoundException exception = assertThrows(
                ClientNotFoundException.class,
                () -> clientService.getClientById(99L)
        );

        assertEquals("Client not found with id: 99", exception.getMessage());
        verify(clientRepository).findById(99L);
    }

    @Test
    void getClientByEmail_whenClientExists_returnsClient() {
        ClientEntity expected = client(7L, "Ava", "Martinez", "ava@example.com");
        when(clientRepository.findByEmail("ava@example.com")).thenReturn(expected);

        ClientEntity result = clientService.getClientByEmail("ava@example.com");

        assertSame(expected, result);
        verify(clientRepository).findByEmail("ava@example.com");
    }

    @Test
    void getClientByEmail_whenClientDoesNotExist_throwsNotFoundException() {
        when(clientRepository.findByEmail("missing@example.com")).thenReturn(null);

        ClientNotFoundException exception = assertThrows(
                ClientNotFoundException.class,
                () -> clientService.getClientByEmail("missing@example.com")
        );

        assertEquals("Client not found with email", exception.getMessage());
        verify(clientRepository).findByEmail("missing@example.com");
    }

    @Test
    void toEntity_mapsDtoFieldsCorrectly() {
        ClientDTO dto = new ClientDTO();
        dto.setFirstName("Ava");
        dto.setLastName("Martinez");
        dto.setEmail("ava@example.com");
        dto.setPasswordHash("hashed-password");
        dto.setSsn("123-45-6789");
        dto.setPhoneNumber("555-123-4567");
        dto.setAccountBalance(new BigDecimal("250.00"));

        ClientEntity client = clientService.toEntity(dto);

        assertNotNull(client);
        assertEquals("Ava", client.getFirstName());
        assertEquals("Martinez", client.getLastName());
        assertEquals("ava@example.com", client.getEmail());
        assertEquals("hashed-password", client.getPasswordHash());
        assertEquals("123-45-6789", client.getSsn());
        assertEquals("555-123-4567", client.getPhoneNumber());
        assertEquals(new BigDecimal("250.00"), client.getAccountBalance());
    }

    @Test
    void createClient_whenDuplicateEmailOrSsn_throwsDuplicateResourceException() {
        ClientEntity client = client(1L, "Ava", "Martinez", "ava@example.com");
        client.setSsn("123-45-6789");
        when(clientRepository.countByEmail("ava@example.com")).thenReturn(1);
        when(clientRepository.countBySsn("123-45-6789")).thenReturn(1);

        DuplicateResourceException exception = assertThrows(
                DuplicateResourceException.class,
                () -> clientService.createClient(client)
        );

        assertEquals(2, exception.getMessages().size());
        assertTrue(exception.getMessages().contains("email: Email already in use"));
        assertTrue(exception.getMessages().contains("ssn: SSN already in use"));
        verify(clientRepository, never()).save(any(ClientEntity.class));
    }

    @Test
    void createClient_whenSaveFails_throwsClientSubmissionException() {
        ClientEntity newClient = client(null, "Ava", "Martinez", "ava@example.com");
        newClient.setSsn("123-45-6789");
        when(clientRepository.countByEmail("ava@example.com")).thenReturn(0);
        when(clientRepository.countBySsn("123-45-6789")).thenReturn(0);
        when(clientRepository.save(newClient)).thenReturn(0);

        assertThrows(
                ClientSubmissionException.class,
                () -> clientService.createClient(newClient)
        );
    }

    @Test
    void createClient_whenClientIsValid_savesClientAndReturnsTrue() {
        ClientEntity newClient = client(null, "Ava", "Martinez", "ava@example.com");
        newClient.setSsn("123-45-6789");
        when(clientRepository.countByEmail("ava@example.com")).thenReturn(0);
        when(clientRepository.countBySsn("123-45-6789")).thenReturn(0);
        when(clientRepository.save(newClient)).thenReturn(1);

        boolean result = clientService.createClient(newClient);

        assertTrue(result);
        verify(clientRepository).save(newClient);
    }

    @Test
    void updateClient_whenClientDoesNotExist_throwsNotFoundException() {
        ClientEntity client = client(5L, "Ava", "Martinez", "ava@example.com");
        when(clientRepository.findById(5L)).thenReturn(null);

        ClientNotFoundException exception = assertThrows(
                ClientNotFoundException.class,
                () -> clientService.updateClient(5L, client)
        );

        assertEquals("Client not found with id: 5", exception.getMessage());
    }

    @Test
    void updateClient_whenClientExists_updatesClient() {
        ClientEntity existing = client(5L, "Old", "Name", "old@example.com");
        existing.setSsn("111-22-3333");
        ClientEntity updated = client(5L, "Ava", "Martinez", "ava@example.com");
        updated.setSsn("123-45-6789");
        when(clientRepository.findById(5L)).thenReturn(existing);
        when(clientRepository.update(updated)).thenReturn(1);

        boolean result = clientService.updateClient(5L, updated);

        assertTrue(result);
        verify(clientRepository).update(updated);
    }

    @Test
    void deleteClient_whenClientDoesNotExist_throwsNotFoundException() {
        when(clientRepository.findById(9L)).thenReturn(null);

        ClientNotFoundException exception = assertThrows(
                ClientNotFoundException.class,
                () -> clientService.deleteClient(9L)
        );

        assertEquals("Client not found with id: 9", exception.getMessage());
    }

    @Test
    void deleteClient_whenClientExists_deletesClient() {
        ClientEntity existing = client(9L, "Ava", "Martinez", "ava@example.com");
        when(clientRepository.findById(9L)).thenReturn(existing);
        when(clientRepository.deleteById(9L)).thenReturn(1);

        boolean result = clientService.deleteClient(9L);

        assertTrue(result);
        verify(clientRepository).deleteById(9L);
    }

    private static ClientEntity client(Long clientId, String firstName, String lastName, String email) {
        ClientEntity client = new ClientEntity();
        client.setClientId(clientId);
        client.setFirstName(firstName);
        client.setLastName(lastName);
        client.setEmail(email);
        client.setPasswordHash("hash");
        client.setSsn("123-45-6789");
        client.setPhoneNumber("555-123-4567");
        client.setAccountBalance(new BigDecimal("100.00"));
        return client;
    }
}
