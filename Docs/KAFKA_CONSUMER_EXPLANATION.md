# Kafka Consumer: Step-by-Step Explanation

## **Your Current Architecture**

```
┌──────────────────────────────────────────────────────────────┐
│                    CURRENT FLOW                              │
└──────────────────────────────────────────────────────────────┘

API Request: POST /api/orders
    │
    ▼
OrderService.submitOrder()
    ├─ 1️⃣ OrderSubmissionService.submit()
    │    └─ Creates OrderEntity in DB
    │
    ├─ 2️⃣ OrderAcceptanceService.acceptOrder()
    │    └─ Changes status to ACCEPTED
    │    └─ Updates DB
    │
    ├─ 3️⃣ Map OrderEntity → OrderAcceptedEvent
    │
    ├─ 4️⃣ orderProducer.publishOrderAccepted(event)
    │    └─ Publishes to Kafka topic "ORDER_ACCEPTED_TOPIC"
    │    └─ Message stored in Kafka
    │
    └─ 5️⃣ Return OrderEntity to client
       └─ Client gets response immediately
       └─ Event is in Kafka but NO ONE is listening yet!
```

---

## **What a Consumer Does**

A **consumer** listens to Kafka topics and reacts to events:

```
┌──────────────────────────────────────────────────────────────┐
│                  CONSUMER FLOW (NEW)                         │
└──────────────────────────────────────────────────────────────┘

Kafka Topic: ORDER_ACCEPTED_TOPIC
    │
    │ Contains: OrderAcceptedEvent {
    │   orderId: 123,
    │   clientId: 5,
    │   acceptedAt: 2026-10-05
    │ }
    │
    ▼
OrderEventListener (@Service)
    │
    ├─ @KafkaListener annotation
    ├─ Automatically subscribes to topic
    └─ Consumer group: "order-group"
       (Allows horizontal scaling)
    │
    ▼
handleOrderAccepted(OrderAcceptedEvent event)
    │
    ├─ Method called automatically
    ├─ Event passed as parameter
    └─ Now can process: log, execute, notify, etc.
```

---

## **Step-by-Step: What Happens When You Submit an Order**

### **Step 1️⃣: API Call**
```bash
POST /api/orders
{
  "clientId": 5,
  "instrumentId": 100,
  "orderType": "BUY",
  "quantity": 50
}
```

### **Step 2️⃣: OrderService Processes**
```java
OrderService.submitOrder(request) {
    // Creates order
    OrderEntity order = orderSubmissionService.submit(request);
    // Saves to database
    // Status: SUBMITTED
    
    // Accepts order
    OrderEntity acceptedOrder = orderAcceptanceService.acceptSubmittedOrder(orderId);
    // Updates database
    // Status: ACCEPTED
    
    // Creates event from order
    OrderAcceptedEvent event = new OrderAcceptedEvent(...);
    
    // PUBLISHES to Kafka
    orderProducer.publishOrderAccepted(event);
    // ↑ Event now in Kafka, waiting for consumers
    
    // Returns to client immediately
    return acceptedOrder;  // Client gets response in ~10-50ms
}
```

### **Step 3️⃣: Consumer Receives Event** (Async - happens in background)
```java
// In OrderEventListener (Consumer)
@KafkaListener(topics = "ORDER_ACCEPTED_TOPIC")
public void handleOrderAccepted(OrderAcceptedEvent event) {
    // This method is called AUTOMATICALLY when event arrives
    // Can happen milliseconds later, or seconds, doesn't matter
    
    log.info("Processing accepted order: {}", event.getOrderId());
    
    // Now you can:
    // - Execute the order
    // - Send notification
    // - Update holdings
    // - Log for audit
}
```

### **Step 4️⃣: Timeline Comparison**

**Without Kafka (Synchronous):**
```
0ms:   Client submits order
10ms:  DB saves order
20ms:  Risk check (if implemented)
100ms: Execution (if implemented)
150ms: Notification sent
500ms: Client gets response
       ❌ Client waits 500ms!
```

**With Kafka (Asynchronous):**
```
0ms:   Client submits order
10ms:  DB saves order
20ms:  Event published to Kafka
50ms:  Client gets response ✅
       │
       └─ Meanwhile, in background:
          100ms:  Consumer receives event
          110ms:  Consumer processes (execute, notify, etc.)
          ✓ Consumer can fail without affecting client
          ✓ Consumer can be added/removed without code changes
```

---

## **Do You NEED a Consumer?**

### ❌ **NO Consumer Needed If:**

```java
// Your current use case:
// 1. Order submitted
// 2. Order accepted
// 3. Return order to client
// That's it!

// Response:
{
  "orderId": 123,
  "status": "ACCEPTED",
  "clientId": 5,
  ...
}
// ✓ Everything is synchronous
// ✓ No downstream processing needed
```

**In this case:** The event is published but not consumed. It's harmless—just sits in Kafka.

---

### ✅ **YES, Add a Consumer If You Want:**

**Option 1: Auto-Execute After Accept**
```java
@KafkaListener(topics = "ORDER_ACCEPTED_TOPIC")
public void executeAcceptedOrder(OrderAcceptedEvent event) {
    orderExecutionService.execute(event.getOrderId());
    // Now order automatically executes after acceptance
}
```

**Option 2: Send Notification**
```java
@KafkaListener(topics = "ORDER_ACCEPTED_TOPIC")
public void notifyClient(OrderAcceptedEvent event) {
    emailService.send(event.getClientId(), "Your order was accepted");
    // Client gets notification asynchronously
}
```

**Option 3: Update Real-Time Dashboard**
```java
@KafkaListener(topics = "ORDER_ACCEPTED_TOPIC")
public void updateDashboard(OrderAcceptedEvent event) {
    websocketService.broadcast("order.accepted", event);
    // Real-time UI updates
}
```

**Option 4: Log for Analytics**
```java
@KafkaListener(topics = "ORDER_ACCEPTED_TOPIC")
public void logEvent(OrderAcceptedEvent event) {
    analyticsService.recordEvent("order.accepted", event);
    // Track metrics, trends, etc.
}
```

---

## **Benefits of Consumers**

| Benefit | Explanation |
|---------|-------------|
| **Decoupling** | OrderService doesn't need to know about execution, notifications, etc. |
| **Async Processing** | Heavy operations don't block order submission |
| **Scalability** | Multiple consumers can process same event independently |
| **Reliability** | If consumer fails, Kafka retries; doesn't affect order service |
| **Extensibility** | Add new consumer without changing order service |
| **Monitoring** | See which events are being consumed, lag, etc. |

---

## **Example: Add Execution Consumer**

If you want orders to **automatically execute after acceptance**, add this:

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionConsumer {
    
    private final OrderExecutionService executionService;
    
    @KafkaListener(topics = "ORDER_ACCEPTED_TOPIC", groupId = "execution-group")
    public void executeAcceptedOrder(OrderAcceptedEvent event) {
        try {
            log.info("🚀 Executing accepted order: {}", event.getOrderId());
            
            // Execute the order
            executionService.executeAcceptedOrder(event.getOrderId());
            
            log.info("✅ Order execution completed: {}", event.getOrderId());
            
        } catch (Exception e) {
            log.error("❌ Order execution failed: {}", event.getOrderId(), e);
            // In production: send to dead letter queue for retry
        }
    }
}
```

---

## **Summary**

| Aspect | Details |
|--------|---------|
| **Producer** | OrderService publishes OrderAcceptedEvent to Kafka ✅ (You have this) |
| **Consumer** | Listens to OrderAcceptedEvent and processes it 📥 (Optional) |
| **Do you need it?** | Only if you have downstream processing (execute, notify, etc.) |
| **Current state** | Events are published but not consumed—harmless |
| **Next step** | Decide what should happen after order is accepted |

If you just want to:
- ✅ Save order
- ✅ Return to client
- ✅ Log event in Kafka

**No consumer needed.**

If you want to:
- ✅ Auto-execute after accept
- ✅ Send notifications
- ✅ Update holdings/balance
- ✅ Real-time dashboard

**Add a consumer.**

---

## **Testing Without Consumer**

```bash
# 1. Start Kafka
docker-compose -f docker-compose-kafka.yml up -d

# 2. Run app
mvn clean package
java -jar target/group-12-backend-0.1.0.jar

# 3. Submit order
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"clientId": 5, "instrumentId": 100, "orderType": "BUY", "quantity": 50}'

# 4. App logs show:
# ✅ [CONSUMER] Received ORDER ACCEPTED event: orderId=1, clientId=5

# 5. Check Kafka topic (event is stored)
docker exec kafka-broker kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ORDER_ACCEPTED_TOPIC \
  --from-beginning
```
