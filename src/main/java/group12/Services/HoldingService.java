package group12.Services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import group12.exception.HoldingNotFoundException;
import group12.exception.HoldingArgumentInvalidException;
import group12.exception.ClientNotFoundException;
import group12.Entities.HoldingEntity;
import group12.Repository.HoldingRepository;
import group12.Repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;


@Slf4j
@Service
@RequiredArgsConstructor
public class HoldingService {

    private final HoldingRepository holdingRepository;
    private final ClientRepository clientRepository;

    public HoldingEntity getHoldingByHoldingIdAndClientId(Long holdingId, Long clientId) {

        if(clientRepository.findById(clientId) == null) {
            throw new ClientNotFoundException("Client not found");
        }

        HoldingEntity holding = holdingRepository.getHoldingByHoldingIdAndClientId(holdingId, clientId);

        if(holding == null){
            throw new HoldingNotFoundException("Holding not found");
        }
        return holding;
    }

    public List<HoldingEntity> getHoldingsByClientId(Long clientId) {
        
        //check if client exists
        if(clientRepository.findById(clientId) == null) {
            throw new ClientNotFoundException("Client not found");
        }

        List<HoldingEntity> holdings = holdingRepository.getHoldingsByClientId(clientId);

        if(holdings.isEmpty()) {
            throw new HoldingNotFoundException("No holdings found for this client");
        }
        
        return holdings;
    }

    public Optional<HoldingEntity> getHoldingByInstrumentIdAndClientId(Long instrumentId,  Long clientId) {

        Optional<HoldingEntity> holding = holdingRepository.getHoldingByInstrumentIdAndClientId(instrumentId, clientId);

        //check if client exists
        if(clientRepository.findById(clientId) == null) {
            throw new ClientNotFoundException("Client not found");
        }

        if(holding.isEmpty()) {
            throw new HoldingNotFoundException("Client does not hold this instrument");
        }
        // Check ID exists not implemented yet
        //
        // if(!instrumentRepository.exists(instrumentId)) {
        //     throw new ResponseStatusException(
        //             HttpStatus.NOT_FOUND,
        //             String.format("Instrument id: \"%s\" not found", instrumentId)
        //     );
        // }
        return holding;
    }

    public HoldingEntity getHoldingByInstrumentIdAndClientIdForUpdate(Long instrumentId,  Long clientId) {

        HoldingEntity holding = holdingRepository.getHoldingByInstrumentIdAndClientIdForUpdate(instrumentId, clientId);

        //check if client exists
        if(clientRepository.findById(clientId) == null) {
            throw new ClientNotFoundException("Client not found");
        }

        if(holding == null) {
            throw new HoldingNotFoundException("Client does not hold this instrument");
        }
        // Check ID exists not implemented yet
        //
        // if(!instrumentRepository.exists(instrumentId)) {
        //     throw new ResponseStatusException(
        //             HttpStatus.NOT_FOUND,
        //             String.format("Instrument id: \"%s\" not found", instrumentId)
        //     );
        // }
        return holding;
    }

    @Transactional
    public HoldingEntity updateHolding(Long instrumentId,  Long clientId, BigDecimal quantity, BigDecimal averageCost) {
        //update Holding entity
        HoldingEntity holding = getHoldingByInstrumentIdAndClientIdForUpdate(instrumentId, clientId);

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new HoldingArgumentInvalidException("Quantity must be greater than zero");
        }
        
        if (averageCost.compareTo(BigDecimal.ZERO) <= 0) {
            throw new HoldingArgumentInvalidException("Average cost must be greater than zero");
        }


        int updatedRows = holdingRepository.updateHolding(instrumentId, clientId, quantity, averageCost);
        if (updatedRows == 0) {
            throw new HoldingNotFoundException("Client does not hold this instrument");
        }

        holding.setQuantity(quantity);
        holding.setAverageCost(averageCost);
        return holding;
    }

    public HoldingEntity getHoldingByHoldingIdAndClientIdForDelete(Long holdingId, Long clientId) {

        if(clientRepository.findById(clientId) == null) {
            throw new ClientNotFoundException("Client not found");
        }

        HoldingEntity holding = holdingRepository.getHoldingForDeleteByIdAndClientId(holdingId, clientId);

        if(holding == null){
            throw new HoldingNotFoundException("Holding not found");
        }
        return holding;
    }

    public void deleteHolding(Long holdingId, Long clientId) {
        getHoldingByHoldingIdAndClientIdForDelete(holdingId, clientId);
        holdingRepository.deleteHoldingByHoldingIdAndClientId(holdingId, clientId);
    }

}
