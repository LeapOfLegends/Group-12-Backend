package group12.Controller;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import group12.Services.InstrumentHistoryService;
import group12.Services.InstrumentService;
import group12.dto.InstrumentHistoryDTO;
import group12.dto.InstrumentHistoryPointDTO;
import group12.exception.GlobalExceptionHandler;
import group12.security.JwtConfiguration;
import group12.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InstrumentController.class)
@ContextConfiguration(classes = InstrumentHistoryControllerTest.SecurityTestConfiguration.class)
@TestPropertySource(properties = "jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
class InstrumentHistoryControllerTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InstrumentService instrumentService;

    @MockBean
    private InstrumentHistoryService instrumentHistoryService;

    @Configuration(proxyBeanMethods = false)
    @Import({
            InstrumentController.class,
            GlobalExceptionHandler.class,
            SecurityConfig.class,
            JwtConfiguration.class
    })
    static class SecurityTestConfiguration {
    }

    @Test
    void serializesSuccessfulHistoryResponse() throws Exception {
        when(instrumentHistoryService.getHistory("AAPL", "1D"))
                .thenReturn(successfulResponse());

        mockMvc.perform(get("/instruments/AAPL/history")
                        .queryParam("range", "1D")
                        .header("Authorization", "Bearer " + token("CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId").value(12))
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.range").value("1D"))
                .andExpect(jsonPath("$.timeframe").value("5Min"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.availableFrom").value("2026-10-20T13:30:00Z"))
                .andExpect(jsonPath("$.availableTo").value("2026-10-20T13:35:00Z"))
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.points[0].timestamp")
                        .value("2026-10-20T13:30:00Z"))
                .andExpect(jsonPath("$.points[0].close").value(250.25));
    }

    @Test
    void retrievesHistoryByInstrumentId() throws Exception {
        when(instrumentHistoryService.getHistory(12L, "1D"))
                .thenReturn(successfulResponse());

        mockMvc.perform(get("/instruments/12/history")
                        .queryParam("range", "1D")
                        .header("Authorization", "Bearer " + token("CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId").value(12))
                .andExpect(jsonPath("$.symbol").value("AAPL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENT", "SUPER_ADMIN", "FINANCE", "OPERATIONS"})
    void approvedRolesCanReadHistory(String role) throws Exception {
        when(instrumentHistoryService.getHistory("AAPL", "1D"))
                .thenReturn(successfulResponse());

        mockMvc.perform(get("/instruments/AAPL/history")
                        .queryParam("range", "1D")
                        .header("Authorization", "Bearer " + token(role)))
                .andExpect(status().isOk());
    }

    @Test
    void historyRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/instruments/AAPL/history").queryParam("range", "1D"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingAndInvalidRangeAreBadRequests() throws Exception {
        when(instrumentHistoryService.getHistory("AAPL", "ALL"))
                .thenThrow(new IllegalArgumentException(
                        "Invalid range. Allowed values: 1D, 5D, 1M, 1Y, 5Y"
                ));
        String token = token("CLIENT");

        mockMvc.perform(get("/instruments/AAPL/history")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/instruments/AAPL/history")
                        .queryParam("range", "ALL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Invalid range. Allowed values: 1D, 5D, 1M, 1Y, 5Y"));
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 422, 503})
    void serviceStatusErrorsUseTheAgreedHttpContract(int statusCode) throws Exception {
        HttpStatus status = HttpStatus.valueOf(statusCode);
        when(instrumentHistoryService.getHistory("AAPL", "1D"))
                .thenThrow(new ResponseStatusException(status, "history error"));

        mockMvc.perform(get("/instruments/AAPL/history")
                        .queryParam("range", "1D")
                        .header("Authorization", "Bearer " + token("CLIENT")))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.message").value("history error"));
    }

    private InstrumentHistoryDTO successfulResponse() {
        OffsetDateTime first = OffsetDateTime.parse("2026-10-20T13:30:00Z");
        OffsetDateTime second = OffsetDateTime.parse("2026-10-20T13:35:00Z");
        return new InstrumentHistoryDTO(
                12L,
                "AAPL",
                "1D",
                "5Min",
                "USD",
                first,
                second,
                false,
                List.of(
                        new InstrumentHistoryPointDTO(first, new BigDecimal("250.25")),
                        new InstrumentHistoryPointDTO(second, new BigDecimal("250.80"))
                )
        );
    }

    private String token(String role) throws JOSEException {
        long issuedAt = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"123\",\"roles\":[\"" + role + "\"],"
                + "\"iat\":" + issuedAt + ",\"exp\":" + (issuedAt + 300) + "}";
        JWSObject jws = new JWSObject(
                new JWSHeader(JWSAlgorithm.HS256), new Payload(payload)
        );
        jws.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jws.serialize();
    }
}
