package group12.Services;

import group12.Entities.InstrumentEntity;
import group12.Repository.InstrumentRepository;
import group12.dto.InstrumentDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstrumentServiceTest {

    @Mock
    private InstrumentRepository instrumentRepository;

    @Test
    void getInstrumentBySymbol_preservesMarketSnapshotFields() {
        OffsetDateTime quoteAsOf = OffsetDateTime.parse("2026-09-28T10:00:00-05:00");
        OffsetDateTime lastTradeAsOf = OffsetDateTime.parse("2026-09-28T10:00:01-05:00");
        InstrumentEntity entity = new InstrumentEntity(
                1L,
                1L,
                "AAPL",
                "Apple Inc.",
                "Equity",
                "USD",
                true,
                new BigDecimal("255.12345678"),
                new BigDecimal("255.22345678"),
                new BigDecimal("255.17345678"),
                quoteAsOf,
                lastTradeAsOf
        );
        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(entity));
        InstrumentService service = new InstrumentService(instrumentRepository);

        InstrumentDTO result = service.getInstrumentBySymbol(" aapl ");

        assertEquals("AAPL", result.getSymbol());
        assertEquals(new BigDecimal("255.12345678"), result.getBidPrice());
        assertEquals(new BigDecimal("255.22345678"), result.getAskPrice());
        assertEquals(new BigDecimal("255.17345678"), result.getLastPrice());
        assertEquals(quoteAsOf, result.getQuoteAsOf());
        assertEquals(lastTradeAsOf, result.getLastTradeAsOf());
    }
}
