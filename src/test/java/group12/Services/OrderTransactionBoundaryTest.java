package group12.Services;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderTransactionBoundaryTest {

    @Test
    void submissionAndAcceptanceRequireIndependentTransactions() throws Exception {
        Transactional submissionTransaction = OrderSubmissionService.class
                .getMethod("submit", group12.dto.CreateOrderRequest.class)
                .getAnnotation(Transactional.class);
        Transactional acceptanceTransaction = OrderAcceptanceService.class
                .getMethod("acceptSubmittedOrder", Long.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, submissionTransaction.propagation());
        assertEquals(Propagation.REQUIRES_NEW, acceptanceTransaction.propagation());
    }

    @Test
    void coordinatorDoesNotWrapSubmissionAndAcceptanceInOneTransaction() throws Exception {
        Transactional coordinatorTransaction = OrderService.class
                .getMethod("submitOrder", group12.dto.CreateOrderRequest.class)
                .getAnnotation(Transactional.class);

        assertNull(coordinatorTransaction);
    }
}
