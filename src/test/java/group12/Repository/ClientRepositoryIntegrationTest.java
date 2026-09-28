package group12.Repository;

import group12.Entities.ClientEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ClientRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("clients_test")
                    .withUsername("clients_test")
                    .withPassword("clients_test")
                    .withInitScript("order-test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long clientId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE orders, clients, instruments RESTART IDENTITY CASCADE"
        );
        clientId = insertClient();
    }

    @Test
    @Transactional
    void findByIdForUpdate_whenClientExists_mapsClient() {
        ClientEntity client = clientRepository.findByIdForUpdate(clientId);

        assertNotNull(client);
        assertEquals(clientId, client.getClientId());
        assertEquals("Ava", client.getFirstName());
        assertEquals("Martinez", client.getLastName());
        assertEquals("ava@example.com", client.getEmail());
        assertEquals(new BigDecimal("1000.0000000000000000"), client.getAccountBalance());
    }

    @Test
    void updateAccountBalance_updatesBalanceWithExpectedPrecision() {
        int rowsAffected = clientRepository.updateAccountBalance(
                clientId,
                new BigDecimal("123456789012345678901234.5678901234567890")
        );

        ClientEntity updatedClient = clientRepository.findById(clientId);
        assertEquals(1, rowsAffected);
        assertEquals(
                new BigDecimal("123456789012345678901234.5678901234567890"),
                updatedClient.getAccountBalance()
        );
    }

    @Test
    void updateAccountBalance_negativeValueIsRejected() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> clientRepository.updateAccountBalance(
                        clientId,
                        new BigDecimal("-0.0000000000000001")
                )
        );
    }

    @Test
    void updateAccountBalance_doesNotModifyUnrelatedFields() {
        ClientEntity originalClient = clientRepository.findById(clientId);

        int rowsAffected = clientRepository.updateAccountBalance(
                clientId,
                new BigDecimal("750.2500")
        );

        ClientEntity updatedClient = clientRepository.findById(clientId);
        assertEquals(1, rowsAffected);
        assertEquals(originalClient.getFirstName(), updatedClient.getFirstName());
        assertEquals(originalClient.getLastName(), updatedClient.getLastName());
        assertEquals(originalClient.getEmail(), updatedClient.getEmail());
        assertEquals(originalClient.getPasswordHash(), updatedClient.getPasswordHash());
        assertEquals(originalClient.getSsn(), updatedClient.getSsn());
        assertEquals(originalClient.getPhoneNumber(), updatedClient.getPhoneNumber());
    }

    @Test
    void updateAccountBalance_whenClientDoesNotExist_returnsZero() {
        int rowsAffected = clientRepository.updateAccountBalance(
                999L,
                new BigDecimal("10.0000")
        );

        assertEquals(0, rowsAffected);
    }

    private Long insertClient() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO clients (
                    first_name, last_name, email, password_hash, ssn,
                    phone_number, account_balance
                )
                VALUES (
                    'Ava', 'Martinez', 'ava@example.com', 'password-hash',
                    '123-45-6789', '555-123-4567', 1000.0000
                )
                RETURNING client_id
                """, Long.class);
    }
}
