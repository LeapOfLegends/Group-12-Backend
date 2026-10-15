package group12.Services;

import group12.Repository.AnalyticsRepository;
import group12.dto.ClientCountDTO;
import group12.dto.MostTradedInstrumentDTO;
import group12.dto.OrderStatusDTO;
import group12.dto.PortfolioValueDTO;
import group12.dto.TopInstrumentPriceDTO;
import group12.dto.TradeVolumeDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final AnalyticsRepository analyticsRepository;

    public List<ClientCountDTO> getClientCounts() {
        return analyticsRepository.findClientCounts();
    }

    public List<PortfolioValueDTO> getClientPortfolio(Long clientId) {
        return analyticsRepository.findClientPortfolio(clientId);
    }

    public List<MostTradedInstrumentDTO> getMostTradedInstruments() {
        return analyticsRepository.findMostTradedInstruments();
    }

    public List<TopInstrumentPriceDTO> getTopInstrumentsByPrice() {
        return analyticsRepository.findTopInstrumentsByPrice();
    }

    public List<OrderStatusDTO> getOrderStatus() {
        return analyticsRepository.findOrderStatus();
    }

    public List<TradeVolumeDTO> getTradeVolume() {
        return analyticsRepository.findTradeVolume();
    }
}
