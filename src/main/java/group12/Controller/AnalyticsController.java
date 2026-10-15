package group12.Controller;

import group12.Services.AnalyticsService;
import group12.dto.ClientCountDTO;
import group12.dto.MostTradedInstrumentDTO;
import group12.dto.OrderStatusDTO;
import group12.dto.PortfolioValueDTO;
import group12.dto.TopInstrumentPriceDTO;
import group12.dto.TradeVolumeDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/analytics")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'FINANCE', 'OPERATIONS')")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/clients/counts")
    public List<ClientCountDTO> getClientCounts() {
        return analyticsService.getClientCounts();
    }

    @GetMapping("/clients/{clientId}/portfolio")
    public List<PortfolioValueDTO> getClientPortfolio(@PathVariable Long clientId) {
        return analyticsService.getClientPortfolio(clientId);
    }

    @GetMapping("/instruments/most-traded")
    public List<MostTradedInstrumentDTO> getMostTradedInstruments() {
        return analyticsService.getMostTradedInstruments();
    }

    @GetMapping("/instruments/top-price")
    public List<TopInstrumentPriceDTO> getTopInstrumentsByPrice() {
        return analyticsService.getTopInstrumentsByPrice();
    }

    @GetMapping("/orders/status")
    public List<OrderStatusDTO> getOrderStatus() {
        return analyticsService.getOrderStatus();
    }

    @GetMapping("/trades/volume")
    public List<TradeVolumeDTO> getTradeVolume() {
        return analyticsService.getTradeVolume();
    }
}
