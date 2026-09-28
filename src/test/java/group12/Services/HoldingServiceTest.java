package group12.Services;

import group12.Entities.ClientEntity;
import group12.Entities.HoldingEntity;
import group12.Repository.ClientRepository;
import group12.Repository.HoldingRepository;
import group12.exception.ClientNotFoundException;
import group12.exception.HoldingNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HoldingServiceTest {

    private HoldingRepository holdingRepository;
    private ClientRepository clientRepository;
    private HoldingService holdingService;

    @BeforeEach
    void setUp() {
        holdingRepository = mock(HoldingRepository.class);
        clientRepository = mock(ClientRepository.class);
        holdingService = new HoldingService(holdingRepository, clientRepository);
    }

    @Test
    void getHoldingByHoldingIdAndClientIdForDelete_returnsHoldingWhenClientAndHoldingExist() {
        HoldingEntity expectedHolding = holding(10L, 1L);
        when(clientRepository.findById(1L)).thenReturn(new ClientEntity());
        when(holdingRepository.getHoldingForDeleteByIdAndClientId(10L, 1L))
                .thenReturn(expectedHolding);

        HoldingEntity result = holdingService.getHoldingByHoldingIdAndClientIdForDelete(10L, 1L);

        assertSame(expectedHolding, result);
        verify(clientRepository).findById(1L);
        verify(holdingRepository).getHoldingForDeleteByIdAndClientId(10L, 1L);
    }

    @Test
    void getHoldingByHoldingIdAndClientIdForDelete_throwsWhenClientDoesNotExist() {
        when(clientRepository.findById(1L)).thenReturn(null);

        assertThrows(
                ClientNotFoundException.class,
                () -> holdingService.getHoldingByHoldingIdAndClientIdForDelete(10L, 1L)
        );

        verify(holdingRepository, never()).getHoldingForDeleteByIdAndClientId(10L, 1L);
    }

    @Test
    void getHoldingByHoldingIdAndClientIdForDelete_throwsWhenHoldingDoesNotExist() {
        when(clientRepository.findById(1L)).thenReturn(new ClientEntity());
        when(holdingRepository.getHoldingForDeleteByIdAndClientId(10L, 1L)).thenReturn(null);

        assertThrows(
                HoldingNotFoundException.class,
                () -> holdingService.getHoldingByHoldingIdAndClientIdForDelete(10L, 1L)
        );

        verify(holdingRepository).getHoldingForDeleteByIdAndClientId(10L, 1L);
    }

    @Test
    void deleteHolding_checksHoldingThenDeletesIt() {
        when(clientRepository.findById(1L)).thenReturn(new ClientEntity());
        when(holdingRepository.getHoldingForDeleteByIdAndClientId(10L, 1L))
                .thenReturn(holding(10L, 1L));

        holdingService.deleteHolding(10L, 1L);

        verify(holdingRepository).getHoldingForDeleteByIdAndClientId(10L, 1L);
        verify(holdingRepository).deleteHoldingByHoldingIdAndClientId(10L, 1L);
    }

    private HoldingEntity holding(Long holdingId, Long clientId) {
        HoldingEntity holding = new HoldingEntity();
        holding.setHoldingId(holdingId);
        holding.setClientId(clientId);
        return holding;
    }
}
