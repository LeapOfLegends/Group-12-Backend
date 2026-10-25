package group12.Controller;

import group12.Repository.InstrumentRepository;
import group12.Services.MarketTradingService;
import group12.dto.MarketAvailabilityDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class MarketAvailabilityController {
    private final InstrumentRepository instrumentRepository;
    private final MarketTradingService marketTradingService;

    @GetMapping("/instruments/{instrumentId}/market-status")
    @PreAuthorize("hasAnyRole('CLIENT', 'SUPER_ADMIN', 'FINANCE', 'OPERATIONS')")
    public ResponseEntity<MarketAvailabilityDTO> getMarketStatus(@PathVariable Long instrumentId) {
        var instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Instrument not found"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(marketTradingService.availability(instrument.getMarketCode()));
    }
}