package group12.Controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import group12.Entities.ClientEntity;
import group12.Services.ClientService;
import group12.dto.ClientDTO;

class ClientControllerTest {

    private ClientService clientService;
    private ClientController clientController;

    @BeforeEach
    void setUp() {
        clientService = mock(ClientService.class);
        clientController = new ClientController(clientService);
    }

    @Test
    void createClient_shouldReturn201_whenPayloadIsValid() {
        ClientDTO request = new ClientDTO();
        request.setFirstName("Ava");
        request.setLastName("Martinez");
        request.setEmail("ava.martinez@example.com");
        request.setPasswordHash("hash");
        request.setSsn("123-45-6789");
        request.setPhoneNumber("555-123-4567");
        request.setAccountBalance(new BigDecimal("250.00"));

        ClientEntity entity = new ClientEntity();
        entity.setClientId(1L);
        entity.setFirstName("Ava");
        entity.setLastName("Martinez");
        entity.setEmail("ava.martinez@example.com");
        entity.setPasswordHash("hash");
        entity.setSsn("123-45-6789");
        entity.setPhoneNumber("555-123-4567");
        entity.setAccountBalance(new BigDecimal("250.00"));

        when(clientService.toEntity(any(ClientDTO.class))).thenReturn(entity);
        when(clientService.createClient(any(ClientEntity.class))).thenReturn(true);

        ResponseEntity<ClientEntity> response = clientController.createClient(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Ava", response.getBody().getFirstName());
    }

    @Test
    void getClientById_shouldReturn200_whenClientExists() {
        ClientEntity entity = new ClientEntity();
        entity.setClientId(1L);
        entity.setFirstName("Ava");

        when(clientService.getClientById(1L)).thenReturn(entity);

        ResponseEntity<ClientEntity> response = clientController.getClientById(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1L, response.getBody().getClientId());
    }

    @Test
    void getClientById_shouldThrowClientNotFound_whenClientDoesNotExist() {
        when(clientService.getClientById(99L)).thenThrow(new group12.exception.ClientNotFoundException("Client not found"));

        org.junit.jupiter.api.Assertions.assertThrows(group12.exception.ClientNotFoundException.class, () -> {
            clientController.getClientById(99L);
        });
    }
    @Test
    void updateClient_shouldReturn200_whenClientExists() {
        ClientDTO request = new ClientDTO();
        request.setFirstName("Ava");
        request.setLastName("Martinez");
        request.setEmail("ava.updated@example.com");
        request.setPasswordHash("new-hash");
        request.setSsn("123-45-6789");
        request.setPhoneNumber("555-987-6543");
        request.setAccountBalance(new BigDecimal("300.00"));

        ClientEntity existing = new ClientEntity();
        existing.setClientId(1L);
        existing.setFirstName("Old");

        ClientEntity updated = new ClientEntity();
        updated.setClientId(1L);
        updated.setFirstName("Ava");

        when(clientService.getClientById(1L)).thenReturn(existing);
        when(clientService.toEntity(any(ClientDTO.class))).thenReturn(updated);
        when(clientService.updateClient(1L, updated)).thenReturn(true);

        ResponseEntity<ClientEntity> response = clientController.updateClient(1L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void deleteClient_shouldReturn204_whenClientExists() {
        ClientEntity existing = new ClientEntity();
        existing.setClientId(1L);
        existing.setFirstName("Ava");

        when(clientService.getClientById(1L)).thenReturn(existing);
        when(clientService.deleteClient(1L)).thenReturn(true);

        ResponseEntity<Void> response = clientController.deleteClient(1L);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
}
