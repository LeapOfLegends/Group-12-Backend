package group12.Controller;

import group12.Services.PortfolioPerformanceService;
import group12.dto.PortfolioPerformanceDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clients/me/portfolio")
public class PortfolioPerformanceController {

    private final PortfolioPerformanceService portfolioPerformanceService;

    public PortfolioPerformanceController(PortfolioPerformanceService portfolioPerformanceService) {
        this.portfolioPerformanceService = portfolioPerformanceService;
    }

    @GetMapping("/history")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<PortfolioPerformanceDTO> getHistory(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("range") String range
    ) {
        return ResponseEntity.ok(portfolioPerformanceService.getHistory(
                Long.valueOf(jwt.getSubject()), range
        ));
    }
}
