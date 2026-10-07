# Complete Testing Guide for Kafka Order Processing

## **YES - You Need Docker Compose to Start Kafka**

```bash
docker-compose -f docker-compose-kafka.yml up -d
```

This starts:
- Zookeeper (needed by Kafka)
- Kafka broker (listens on localhost:9092)

---

## **5-Step Testing Process**

### **Step 1️⃣: Start Kafka (Required)**

```bash
docker-compose -f docker-compose-kafka.yml up -d
```

**Verify it's running:**
```bash
docker ps
# You should see:
# kafka-broker (UP)
# kafka-zookeeper (UP)
```

---

### **Step 2️⃣: Build Your Application**

```bash
mvn clean package
```

This compiles your code and creates the JAR file.

**Expected output:**
```
BUILD SUCCESS
```

---

### **Step 3️⃣: Run Your Application**

```bash
java -jar target/group-12-backend-0.1.0.jar
```

**Expected logs (first 10 seconds):**
```
Started Application in X seconds
Tomcat started on port 8081
```

**If you see errors:**
```
Cannot connect to localhost:9092
→ Make sure Kafka is running (Step 1)
→ Check: docker ps
```

---

### **Step 4️⃣: Test by Submitting an Order**

Open a **new terminal** and run:

```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": 5,
    "instrumentId": 100,
    "orderType": "BUY",
    "quantity": 50
  }'
```

**Expected response:**
```json
{
  "orderId": 1,
  "clientId": 5,
  "instrumentId": 100,
  "orderType": "BUY",
  "quantity": 50,
  "status": "ACCEPTED"
}
```

---

### **Step 5️⃣: Check Logs in Application Terminal**

Go back to the terminal running your app and look for:

```
✅ [CONSUMER] Received ORDER ACCEPTED event: orderId=1, clientId=5
```

**This means:**
- ✅ Order was created
- ✅ Order was accepted
- ✅ Event was published to Kafka
- ✅ Consumer received the event
- ✅ Everything works!

---

## **What's Actually Happening (Behind the Scenes)**

```
Step 1: You run: curl -X POST http://localhost:8081/api/orders

Step 2: Server receives request
        OrderService.submitOrder() is called

Step 3: OrderService:
        - Calls orderSubmissionService.submit()
        - Calls orderAcceptanceService.acceptOrder()
        - Status changes to ACCEPTED
        - Saved to database

Step 4: OrderService creates event:
        OrderAcceptedEvent {
          orderId: 1,
          clientId: 5,
          acceptedAt: 2026-10-05 10:30:00
        }

Step 5: OrderProducer publishes to Kafka:
        orderProducer.publishOrderAccepted(event)
        
        Event goes to Kafka topic: "trading.orders.accepted"

Step 6: Client gets response immediately (50ms)
        {
          "orderId": 1,
          "status": "ACCEPTED",
          ...
        }

Step 7: Meanwhile, in background:
        OrderEventListener (consumer) is listening
        When event arrives in Kafka → handleOrderAccepted() called
        Logs: ✅ [CONSUMER] Received ORDER ACCEPTED event...
```

---

## **Verify Kafka Has the Event**

Open **another new terminal** and run:

```bash
docker exec kafka-broker kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic trading.orders.accepted \
  --from-beginning
```

**Expected output:**
```json
{"orderId":1,"clientId":5,"acceptedAt":"2026-10-05T10:30:00"}
```

This shows the event is stored in Kafka!

---

## **Testing Checklist**

```
□ Step 1: Start Kafka
  docker-compose -f docker-compose-kafka.yml up -d

□ Step 2: Build app
  mvn clean package

□ Step 3: Run app
  java -jar target/group-12-backend-0.1.0.jar

□ Step 4: Submit order
  curl -X POST http://localhost:8081/api/orders ...

□ Step 5: Check app logs
  Look for: ✅ [CONSUMER] Received ORDER ACCEPTED event

□ Step 6: Verify Kafka has event
  docker exec kafka-broker kafka-console-consumer ...
```

---

## **Troubleshooting**

### **Issue: "Cannot connect to localhost:9092"**

**Solution:**
```bash
# Check if Kafka is running
docker ps

# If not running:
docker-compose -f docker-compose-kafka.yml up -d

# Check logs
docker logs kafka-broker
```

---

### **Issue: No logs appearing when order submitted**

**Check 1: Is the app running?**
```bash
# Try to access the app
curl http://localhost:8081/health

# Should return 200 OK
```

**Check 2: Is Kafka running?**
```bash
docker ps | grep kafka
# Should show kafka-broker UP
```

**Check 3: Check app logs for errors**
```bash
# Look at application output terminal
# Should see "Started Application in X seconds"
```

---

### **Issue: Order submitted but no consumer log**

**This means:**
1. ✅ Order was created
2. ✅ Event was published
3. ❌ Consumer didn't receive it

**Debug steps:**
1. Check if topic was created:
```bash
docker exec kafka-broker kafka-topics \
  --bootstrap-server localhost:9092 \
  --list
```

Should show: `trading.orders.accepted`

2. Check if consumer group exists:
```bash
docker exec kafka-broker kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --list
```

Should show: `order-group`

---

## **Full Test Sequence (Copy & Paste)**

### **Terminal 1: Start Kafka**
```bash
cd /path/to/Group-12-Backend
docker-compose -f docker-compose-kafka.yml up -d
sleep 5  # Wait for Kafka to start
```

### **Terminal 2: Build & Run App**
```bash
cd /path/to/Group-12-Backend
mvn clean package
java -jar target/group-12-backend-0.1.0.jar
```

Watch logs - wait for: `Started Application`

### **Terminal 3: Submit Order**
```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": 5,
    "instrumentId": 100,
    "orderType": "BUY",
    "quantity": 50
  }'
```

### **Back to Terminal 2: Check Logs**
Look for:
```
✅ [CONSUMER] Received ORDER ACCEPTED event: orderId=1, clientId=5
```

### **Terminal 4 (Optional): Verify Kafka Event**
```bash
docker exec kafka-broker kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic trading.orders.accepted \
  --from-beginning
```

---

## **Stop Everything When Done**

```bash
# Stop app (in Terminal 2)
Ctrl + C

# Stop Kafka
docker-compose -f docker-compose-kafka.yml down
```

---

## **Summary**

| What | Command | Terminal |
|------|---------|----------|
| Start Kafka | `docker-compose -f docker-compose-kafka.yml up -d` | T1 |
| Build app | `mvn clean package` | T2 |
| Run app | `java -jar target/group-12-backend-0.1.0.jar` | T2 |
| Submit order | `curl -X POST http://localhost:8081/api/orders ...` | T3 |
| View logs | Look at Terminal 2 output | - |
| Verify Kafka | `docker exec kafka-broker kafka-console-consumer ...` | T4 |

**Expected result after submitting order:**
- ✅ Order created in database
- ✅ Order accepted (status = ACCEPTED)
- ✅ Event published to Kafka
- ✅ Consumer receives event and logs it
- ✅ Client gets response with order details
