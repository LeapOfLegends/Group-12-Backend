package group12.Controller;

import group12.Services.ClientService;
import group12.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClientControllerRequestTest {

    private ClientService clientService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clientService = mock(ClientService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ClientController(clientService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void patchClient_withSsn_returnsBadRequestAndDoesNotCallService() throws Exception {
        mockMvc.perform(patch("/api/clients/{clientId}", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ava","ssn":"123-45-6789"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("SSN cannot be changed through client profile updates"));

        verifyNoInteractions(clientService);
    }
}
