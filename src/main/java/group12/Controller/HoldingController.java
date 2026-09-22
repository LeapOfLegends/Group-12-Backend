package group12.Controller;

import org.springframework.web.bind.annotation.*;
import group12.Entities.HoldingEntity;
import group12.Services.HoldingService;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import java.util.List;


@RestController 
@RequestMapping("api/holdings")
@RequiredArgsConstructor 
public class HoldingController {

    private final HoldingService holdingService;

    @GetMapping("/{holdingId}")
    public ResponseEntity<HoldingEntity> getHolding(
        @PathVariable("holdingId") Long holdingId
    ) {
        HoldingEntity holding = holdingService.getHoldingByHoldingId(holdingId);

        return ResponseEntity.ok(holding);
    }

    @GetMapping("/client/{clientId}")
    public ResponseEntity<List<HoldingEntity>> getClientHoldings(
        @PathVariable("clientId") Long clientId
    ) {
        List<HoldingEntity> holdings = holdingService.getHoldingsByClientId(clientId);

        return ResponseEntity.ok(holdings);
    }

    @GetMapping("/instrument/{instrumentId}")
    public ResponseEntity<List<HoldingEntity>> getInstrumentHoldings(
        @PathVariable("instrumentId") Long instrumentId
    ) {
        List<HoldingEntity> holdings = holdingService.getHoldingsByInstrumentId(instrumentId);

        return ResponseEntity.ok(holdings);
    }
    
}
