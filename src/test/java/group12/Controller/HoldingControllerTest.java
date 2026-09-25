package group12.Controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import group12.Entities.HoldingEntity;
import group12.Services.HoldingService;

public class HoldingControllerTest {

    private HoldingService holdingService;
    private HoldingController holdingController;

    @BeforeEach 
    void setUp() {
        holdingService = mock(HoldingService.class);
        holdingController = new HoldingController(holdingService);
    }

    @Test
    void getHolding_shouldReturn200AndHolding_whenHoldingExists() {
        HoldingEntity holding = holding(10L, 1L, 25L, 5);
        when(holdingService.getHoldingByHoldingIdAndClientId(10L, 1L)).thenReturn(holding);

        ResponseEntity<HoldingEntity> response = holdingController.getHolding(10L, 1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10L, response.getBody().getHoldingId());
        assertEquals(25L, response.getBody().getInstrumentId());
        assertEquals(5, response.getBody().getQuantity());
        verify(holdingService).getHoldingByHoldingIdAndClientId(10L, 1L);
    }

    @Test
    void getClientHoldings_shouldReturn200AndHoldings() {
        List<HoldingEntity> holdings = List.of(
            holding(10L, 1L, 25L, 5),
            holding(11L, 1L, 30L, 2)
        );
        when(holdingService.getHoldingsByClientId(1L)).thenReturn(holdings);

        List<HoldingEntity> response = holdingController.getHoldingsByClientId(1L);

        assertEquals(holdings, response);
        verify(holdingService).getHoldingsByClientId(1L);
    }

    @Test
    void getInstrumentHoldings_shouldReturn200AndHoldings() {
        List<HoldingEntity> holdings = List.of(holding(10L, 1L, 25L, 5));
        when(holdingService.getHoldingsByInstrumentIdAndClientId(25L, 1L)).thenReturn(holdings);

        ResponseEntity<List<HoldingEntity>> response = holdingController.getInstrumentHoldings(25L, 1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(holdings, response.getBody());
        verify(holdingService).getHoldingsByInstrumentIdAndClientId(25L, 1L);
    }

    private HoldingEntity holding(Long holdingId, Long clientId, Long instrumentId, Integer quantity) {
        HoldingEntity holding = new HoldingEntity();
        holding.setHoldingId(holdingId);
        holding.setClientId(clientId);
        holding.setInstrumentId(instrumentId);
        holding.setQuantity(quantity);
        holding.setAverageCost(new BigDecimal("100.00"));
        return holding;
    }

}
