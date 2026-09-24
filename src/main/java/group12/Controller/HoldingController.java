package group12.Controller;

import org.springframework.web.bind.annotation.*;
import group12.Entities.HoldingEntity;
import group12.Services.HoldingService;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import java.util.List;


@RestController 
//@RequestMapping("api/holdings")
@RequestMapping("api/client/{clientId}/holdings")
@RequiredArgsConstructor 
public class HoldingController {

    private final HoldingService holdingService;

    @GetMapping
    public List<HoldingEntity> getHoldingsByClientId(
        @PathVariable Long clientId
    ) {
        return holdingService.getHoldingsByClientId(clientId);
    }

    @GetMapping("/{holdingId}")
    public ResponseEntity<HoldingEntity> getHolding(
        @PathVariable Long holdingId,
        @PathVariable Long clientId
    ) {
        HoldingEntity holding = holdingService.getHoldingByHoldingIdAndClientId(holdingId, clientId);

        return ResponseEntity.ok(holding);
    }

    @GetMapping("/instrument/{instrumentId}")
    public ResponseEntity<List<HoldingEntity>> getInstrumentHoldings(
        @PathVariable Long instrumentId,
        @PathVariable Long clientId
    ) {
        List<HoldingEntity> holdings = holdingService.getHoldingsByInstrumentIdAndClientId(instrumentId, clientId);

        return ResponseEntity.ok(holdings);
    }
    
}
