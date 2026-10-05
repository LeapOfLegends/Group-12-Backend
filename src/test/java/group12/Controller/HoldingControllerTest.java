package group12.Controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import group12.Entities.HoldingEntity;
import group12.Services.HoldingService;
import group12.dto.HoldingUpdateRequest;

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
        HoldingEntity holding = holding(10L, 1L, 25L, new BigDecimal("5.125"));
        when(holdingService.getHoldingByHoldingIdAndClientId(10L, 1L)).thenReturn(holding);

        ResponseEntity<HoldingEntity> response = holdingController.getHolding(10L, 1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10L, response.getBody().getHoldingId());
        assertEquals(25L, response.getBody().getInstrumentId());
        assertEquals(new BigDecimal("5.125"), response.getBody().getQuantity());
        verify(holdingService).getHoldingByHoldingIdAndClientId(10L, 1L);
    }

    @Test
    void getClientHoldings_shouldReturn200AndHoldings() {
        List<HoldingEntity> holdings = List.of(
            holding(10L, 1L, 25L, new BigDecimal("5.125")),
            holding(11L, 1L, 30L, new BigDecimal("2.25"))
        );
        when(holdingService.getHoldingsByClientId(1L)).thenReturn(holdings);

        List<HoldingEntity> response = holdingController.getHoldingsByClientId(1L);

        assertEquals(holdings, response);
        verify(holdingService).getHoldingsByClientId(1L);
    }

    @Test
    void getInstrumentHoldings_shouldReturn200AndHoldings() {
        HoldingEntity holding = holding(10L, 1L, 25L, new BigDecimal("5.125"));
        when(holdingService.getHoldingByInstrumentIdAndClientId(25L, 1L)).thenReturn(Optional.of(holding));

        ResponseEntity<Optional<HoldingEntity>> response = holdingController.getInstrumentHoldings(25L, 1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Optional.of(holding), response.getBody());
        verify(holdingService).getHoldingByInstrumentIdAndClientId(25L, 1L);
    }

    @Test
    void updateHolding_shouldReturn200AndUpdatedHolding() {
        HoldingEntity updated = holding(10L, 1L, 25L, new BigDecimal("8"));
        updated.setAverageCost(new BigDecimal("125.50"));
        HoldingUpdateRequest request = new HoldingUpdateRequest(new BigDecimal("8"), new BigDecimal("125.50"));
        when(holdingService.updateHolding(25L, 1L, new BigDecimal("8"), new BigDecimal("125.50"))).thenReturn(updated);


        ResponseEntity<HoldingEntity> response = holdingController.updateHolding(25L, 1L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(updated, response.getBody());
        verify(holdingService).updateHolding(25L, 1L, new BigDecimal("8"), new BigDecimal("125.50"));
    }

    @Test
    void deleteHolding_shouldReturn204WithoutBody() {
        ResponseEntity<Void> response = holdingController.deleteHolding(10L, 1L);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
        verify(holdingService).deleteHolding(10L, 1L);
    }

    private HoldingEntity holding(
            Long holdingId,
            Long clientId,
            Long instrumentId,
            BigDecimal quantity
    ) {
        HoldingEntity holding = new HoldingEntity();
        holding.setHoldingId(holdingId);
        holding.setClientId(clientId);
        holding.setInstrumentId(instrumentId);
        holding.setQuantity(quantity);
        holding.setAverageCost(new BigDecimal("100.00"));
        return holding;
    }

}
