# Order Service — Architecture Reference

**Language:** Java 21 | **Framework:** Spring Boot 3.3 | **Database:** PostgreSQL 15

This document is injected as Layer 1 static context into every PARP LLM call.

---

## Service Responsibility

The Order Service owns the full lifecycle of checkout orders: creation, retrieval, status transitions, and cancellation. It is one of three checkout microservices (order, payment, inventory).

## Package Structure

```
com.checkout.order
├── controller/          REST layer — request validation, HTTP mapping
│   ├── dto/             Request/Response records (Java records, immutable)
│   └── OrderController  6 endpoints — see API section below
├── service/
│   └── OrderService     Business logic — transaction boundaries live here
├── repository/
│   └── OrderRepository  Spring Data JPA — extends JpaRepository<Order, UUID>
├── model/
│   ├── Order            JPA entity — maps to orders table
│   ├── OrderItem        JPA entity — maps to order_items table (child of Order)
│   └── OrderStatus      Enum: PENDING | CONFIRMED | CANCELLED | FULFILLED
└── exception/
    └── OrderNotFoundException  Runtime exception → HTTP 404
```

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/v1/orders` | Create a new order |
| `GET` | `/v1/orders/{id}` | Fetch order by ID |
| `GET` | `/v1/orders?customerId={id}&page=0&size=20` | List orders (paginated) |
| `PUT` | `/v1/orders/{id}/cancel` | Cancel an order |
| `GET` | `/v1/orders/status/{status}` | Orders by status |
| `GET` | `/actuator/health` | Spring health check |
| `GET` | `/actuator/metrics` | Micrometer metrics |

## Database Schema

```sql
orders (
  id           UUID PRIMARY KEY,
  customer_id  UUID NOT NULL,           -- missing index (known issue)
  status       VARCHAR(20) NOT NULL,    -- no index (known issue)
  total_amount DECIMAL(10,2) NOT NULL,
  created_at   TIMESTAMP NOT NULL,
  updated_at   TIMESTAMP NOT NULL
)

order_items (
  id         UUID PRIMARY KEY,
  order_id   UUID NOT NULL REFERENCES orders(id),
  product_id UUID NOT NULL,
  quantity   INTEGER NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL
)
```

## Known Reliability Issues (PARP targets)

| Issue | Location | Symptom |
|---|---|---|
| N+1 query in createOrder | `OrderService.createOrder()` lines 47–60 | p99 latency spikes with order item count |
| No caching on getOrder | `OrderService.getOrder()` | Unnecessary DB hits on high-read paths |
| Missing index on customer_id | DB schema, V1 migration | Slow pagination queries |
| Blocking payment notification | `OrderService.notifyPaymentServiceSync()` | Thread pool exhaustion under load |
| Full table scan on status filter | `OrderRepository.findByStatus()` | Slow status queries at scale |

## Testing

- **Framework:** JUnit 5 + Spring Boot Test
- **Integration tests:** Testcontainers (PostgreSQL 15-alpine)
- **Test class:** `OrderServiceTest` — covers all 5 service methods
- **Location:** `src/test/java/com/checkout/order/OrderServiceTest.java`
- **Run:** `mvn test`

## Coding Standards

- Controller methods are thin — business logic belongs in `OrderService`
- All DB operations go through `OrderRepository` — no native SQL in services
- Use Java records for DTOs
- Transactions: `@Transactional` on service methods; `readOnly=true` for reads
- Log at INFO for significant state changes, DEBUG for internal details
- Exception mapping: `OrderNotFoundException` → 404, `IllegalStateException` → 409
