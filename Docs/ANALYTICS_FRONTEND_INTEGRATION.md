# Analytics API and Angular Integration

This document describes the analytics endpoints exposed by the Spring Boot backend and the quickest way to verify that an Angular frontend can consume them.

## Architecture

```text
PostgreSQL dw.table_records
        -> AnalyticsRepository (MyBatis)
        -> AnalyticsService
        -> AnalyticsController
        -> Angular HttpClient and charts
```

The backend owns the SQL and database credentials. Angular only calls the REST API.

## Start the backend

The backend listens on port `8081` by default.

```powershell
# Required by Spring Security's JWT decoder. Use the same value as the auth service.
$env:JWT_SECRET = '<at-least-32-character-shared-secret>'
mvn spring-boot:run
```

`JWT_SECRET` must be at least 32 UTF-8 bytes because the backend validates HS256
tokens. Set it in the same PowerShell session before starting Maven. The Maven
goal is `spring-boot:run` with a hyphen; `spring:boot:run` is invalid. Spring Boot
does not automatically load a `.env` file, so adding the variable there alone will
not make it available to Maven. Do not commit the real secret to the repository.

To check only whether the current PowerShell session has the variable, without
printing the secret:

```powershell
if ($env:JWT_SECRET) { $env:JWT_SECRET.Length } else { 'JWT_SECRET is not set' }
```

The database must already contain the warehouse schema and data from:

- `Database-Setup/createWarehouse.sql`
- `Database-Setup/seedWarehouseData.sql`

The Spring datasource environment variables must point to the database used by the backend:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/capstone2026
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=<password>
```

## Endpoints

All analytics endpoints require a valid JWT with one of these roles:

- `SUPER_ADMIN`
- `FINANCE`
- `OPERATIONS`

| Endpoint | Purpose |
|---|---|
| `GET /api/analytics/clients/counts` | Distinct clients grouped by creation year |
| `GET /api/analytics/clients/{clientId}/portfolio` | Portfolio snapshots for one client |
| `GET /api/analytics/instruments/most-traded` | Ten instruments with the most warehouse records |
| `GET /api/analytics/instruments/top-price` | Ten highest-priced equity instruments |
| `GET /api/analytics/orders/status` | Order counts grouped by status |
| `GET /api/analytics/trades/volume` | Filled trades grouped by fill year |

The trade-volume query uses `FILLED`, because `filled_at` represents completed trades. The top-price query groups by instrument before sorting, so it returns the globally highest-priced equities.

## Client home dashboard

The client home page uses OLTP data, not the warehouse analytics endpoints. The
authenticated client can request all home-page values with:

```text
GET /api/clients/me/dashboard
```

The bearer token must have the `CLIENT` role. The backend takes the client ID
from the JWT subject and queries `clients`, `transactions`, `holdings`,
`holding_history`, `instruments`, and `orders`.

The response shape is:

```typescript
export interface ClientDashboard {
  summary: {
    accountCashBalance: number;
    totalPortfolioValue: number;
    totalReturn: number;
    totalReturnPercent: number;
    buyingPower: number;
  };
  portfolioPerformance: PortfolioValue[];
  assetAllocation: {
    assetClass: string;
    marketValue: number;
    allocationPercent: number;
  }[];
  currentHoldings: {
    instrumentId: number;
    symbol: string;
    instrumentName: string;
    assetClass: string;
    quantity: number;
    averageCost: number;
    currentPrice: number;
    marketValue: number;
    unrealizedReturn: number;
    unrealizedReturnPercent: number;
  }[];
  recentOrders: {
    orderId: number;
    instrumentId: number;
    symbol: string;
    orderType: 'BUY' | 'SELL';
    quantity: number;
    status: 'SUBMITTED' | 'ACCEPTED' | 'REJECTED' | 'FAILED' | 'FILLED';
    reservedCash: number;
    submittedAt: string;
    filledAt: string | null;
    executionPrice: number | null;
    tradeValue: number | null;
  }[];
}
```

`totalPortfolioValue` is cash plus the current market value of holdings.
`totalReturn` is the current unrealized return against holding average cost.
`buyingPower` is cash less reserved cash for submitted or accepted buy orders.
Asset-allocation percentages describe the invested holdings portion of the
portfolio; cash is represented separately by `accountCashBalance`.

## Verify the backend first

The database ping is public and checks that the application can reach its configured database:

```powershell
curl.exe http://localhost:8081/db/ping
```

Then call an analytics endpoint with a bearer token issued by your authentication system:

```powershell
$token = '<paste-jwt-here>'
$headers = @{ Authorization = "Bearer $token" }
curl.exe -H "Authorization: Bearer $token" http://localhost:8081/api/analytics/clients/counts
curl.exe -H "Authorization: Bearer $token" http://localhost:8081/api/analytics/orders/status
curl.exe -H "Authorization: Bearer $token" http://localhost:8081/api/analytics/clients/1001/portfolio
```

Expected response shape for client counts:

```json
[
  {
    "year": 2024,
    "totalNewClients": 10
  }
]
```

Expected response shape for order status:

```json
[
  {
    "status": "FILLED",
    "totalOrders": 35
  }
]
```

A `401` means the token is missing, expired, or invalid. A `403` means the token is valid but does not contain an allowed role. A `500` usually means the warehouse schema or datasource configuration is unavailable.

## Angular setup

This repository does not contain an Angular application, so add the following to the Angular project.

Install the HTTP client and, if charts are needed, Chart.js:

```powershell
npm install chart.js ng2-charts
```

Configure the API URL in `src/environments/environment.ts`:

```typescript
export const environment = {
  production: false,
  apiUrl: 'http://localhost:8081'
};
```

Create `src/app/services/analytics.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../environments/environment';

export interface ClientCount {
  year: number;
  totalNewClients: number;
}

export interface PortfolioValue {
  date: string;
  accountBalance: number;
  holdingsValue: number;
  totalPortfolioValue: number;
}

export interface MostTradedInstrument {
  instrumentId: number;
  symbol: string;
  tradedOrders: number;
}

export interface TopInstrumentPrice {
  instrumentId: number;
  symbol: string;
  price: number;
}

export interface OrderStatus {
  status: string;
  totalOrders: number;
}

export interface TradeVolume {
  year: number;
  totalTrades: number;
  sharesTraded: number;
  totalValue: number;
}

@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/analytics`;

  getClientCounts() {
    return this.http.get<ClientCount[]>(`${this.baseUrl}/clients/counts`);
  }

  getClientPortfolio(clientId: number) {
    return this.http.get<PortfolioValue[]>(
      `${this.baseUrl}/clients/${clientId}/portfolio`
    );
  }

  getMostTradedInstruments() {
    return this.http.get<MostTradedInstrument[]>(
      `${this.baseUrl}/instruments/most-traded`
    );
  }

  getTopInstrumentPrices() {
    return this.http.get<TopInstrumentPrice[]>(
      `${this.baseUrl}/instruments/top-price`
    );
  }

  getOrderStatus() {
    return this.http.get<OrderStatus[]>(`${this.baseUrl}/orders/status`);
  }

  getTradeVolume() {
    return this.http.get<TradeVolume[]>(`${this.baseUrl}/trades/volume`);
  }
}
```

Register `HttpClient` in the Angular application if it is not already registered:

```typescript
import { provideHttpClient, withInterceptors } from '@angular/common/http';

export const appConfig: ApplicationConfig = {
  providers: [
    provideHttpClient(withInterceptors([authInterceptor]))
  ]
};
```

The `authInterceptor` must attach the same bearer token used by the rest of the Angular application:

```typescript
import { HttpInterceptorFn } from '@angular/common/http';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const token = localStorage.getItem('access_token');

  if (!token) {
    return next(request);
  }

  return next(request.clone({
    setHeaders: { Authorization: `Bearer ${token}` }
  }));
};
```

Use the service from a dashboard component:

```typescript
export class AnalyticsDashboardComponent implements OnInit {
  clientCounts: ClientCount[] = [];
  error = '';

  constructor(private readonly analytics: AnalyticsService) {}

  ngOnInit(): void {
    this.analytics.getClientCounts().subscribe({
      next: data => this.clientCounts = data,
      error: () => this.error = 'Analytics data could not be loaded.'
    });
  }
}
```

## Verify from the browser

1. Start the backend on `http://localhost:8081`.
2. Start Angular with `ng serve`, which normally uses `http://localhost:4200`.
3. Sign in with a user whose JWT contains `SUPER_ADMIN`, `FINANCE`, or `OPERATIONS`.
4. Open browser developer tools and inspect the Network tab.
5. Confirm the request to `/api/analytics/clients/counts` returns `200` and JSON.
6. Confirm the request includes `Authorization: Bearer ...`.

The backend allows `http://localhost:4200` for `GET` and preflight `OPTIONS` requests under `/api/**`.

## Troubleshooting

- `net::ERR_CONNECTION_REFUSED`: the Spring Boot process is not running or is using another port.
- `401 Unauthorized`: Angular did not send a token, or the token expired.
- `403 Forbidden`: the token lacks `SUPER_ADMIN`, `FINANCE`, or `OPERATIONS`.
- CORS error: Angular is not running at `http://localhost:4200`, or the browser is calling a different backend origin.
- `500 Internal Server Error`: verify `dw.table_records` exists and contains data, then check `logs/group-12-backend.log`.
- Empty arrays: the endpoint is working, but the warehouse has no matching records. Run the warehouse seed script and retry.
