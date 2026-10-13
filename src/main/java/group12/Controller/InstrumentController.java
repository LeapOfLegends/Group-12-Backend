package group12.Controller;

import group12.dto.InstrumentDTO;
import group12.Services.InstrumentService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/instruments")
public class InstrumentController {

    private final InstrumentService instrumentService;

    public InstrumentController(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    /*
     * =========================================================================
     * CLIENT AND ADMIN MARKET DATA ENDPOINTS
     *
     * Instruments are shared reference data used by clients to make investment
     * decisions and by admins to support client and account operations. Every
     * authenticated business role may read them; this controller has no write
     * endpoints.
     * =========================================================================
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENT', 'SUPER_ADMIN', 'FINANCE', 'OPERATIONS')")
    public ResponseEntity<List<InstrumentDTO>> getAllInstruments() {

        return ResponseEntity.ok(
                instrumentService.getAllInstruments()
        );
    }

    @GetMapping("/{instrumentId}")
    @PreAuthorize("hasAnyRole('CLIENT', 'SUPER_ADMIN', 'FINANCE', 'OPERATIONS')")
    public ResponseEntity<InstrumentDTO> getInstrument(
            @PathVariable Long instrumentId) {

        return ResponseEntity.ok(
                instrumentService.getInstrumentById(instrumentId)
        );
    }

    @GetMapping("/symbol/{symbol}")
    @PreAuthorize("hasAnyRole('CLIENT', 'SUPER_ADMIN', 'FINANCE', 'OPERATIONS')")
    public ResponseEntity<InstrumentDTO> getInstrumentBySymbol(
            @PathVariable String symbol) {

        return ResponseEntity.ok(
                instrumentService.getInstrumentBySymbol(symbol)
        );
    }
}
