# Leap Run 2 2026 - Capstone Project Group 12

A full-stack, production-grade enterprise trading platform.

## Team Members

- Zachary Disher
- Ethan Ma
- Toan (Thai) Nguyen
- Jennifer Ruiz
- Yatin Vajja

## Branching Strategy

We will be using the Gitflow-branching style. It allows for better structure, easier release versioning, and clear understanding. 

## PostgreSQL Setup and Dummy Data

The database files are:

- `CreateTable.sql` creates the tables and foreign-key relationships.
- `DummyData.sql` inserts sample instruments, users, accounts, orders, and holdings.

### Using the VS Code PostgreSQL Extension

This workspace uses the **PostgreSQL** extension (`cweijan.vscode-postgresql-client2`):

1. Open the PostgreSQL/database icon in the VS Code Activity Bar.
2. Choose **Add Connection** and enter the PostgreSQL host, port, database, username, and password. Use an existing database such as `postgres` for the initial connection.
3. Run `CREATE DATABASE capstone2026;` once from that connection, then create a new extension connection to the `capstone2026` database.
4. Open `CreateTable.sql`, select the `capstone2026` connection, and run the file with the extension's **Run Query** action.
5. Open `DummyData.sql` and run it after the tables have been created.
6. Run this query to verify the loaded sample records:

```sql
SELECT u.username, a.account_id, h.symbol, h.quantity
FROM users AS u
JOIN accounts AS a ON a.user_id = u.user_id
JOIN holdings AS h ON h.account_id = a.account_id
ORDER BY a.account_id, h.symbol;
```

The seed file uses a transaction and explicit IDs, so the foreign-key relationships are predictable. It also updates each `BIGSERIAL` sequence after inserting the sample rows.

### Using `psql` Instead

Create the database once while connected to an administrative database, then run the schema and seed files while connected to `capstone2026`:

```powershell
psql -h localhost -U your_username -d postgres -c "CREATE DATABASE capstone2026;"
psql -h localhost -U your_username -d capstone2026 -f CreateTable.sql
psql -h localhost -U your_username -d capstone2026 -f DummyData.sql
```
## Warehouse Platform Metrics

`GET /api/analytics/platform-metrics?range=all` reads `dw.table_records` in the
`data_warehouse` PostgreSQL database. `range` accepts `week`, `month`, `year`, or
`all` (default). Access is restricted to Super Admin, Operations, and Finance.

This endpoint uses an independent JDBC connection configured by:

- `WAREHOUSE_DATASOURCE_URL`: defaults to `jdbc:postgresql://localhost:15432/data_warehouse`.
- `WAREHOUSE_DATASOURCE_USERNAME`: defaults to `SPRING_DATASOURCE_USERNAME` or `postgres`.
- `WAREHOUSE_DATASOURCE_PASSWORD`: defaults to `SPRING_DATASOURCE_PASSWORD`.

Grant the warehouse user read access to schema `dw` and table `dw.table_records`.
Do not change `SPRING_DATASOURCE_URL` to the warehouse: client/trading operations
continue to use the transactional database. No database migration is required for
the metrics endpoint when the warehouse table already exists.

Ranges are rolling UTC windows ending at request time (seven days, one calendar
month, or one calendar year), with inclusive start and exclusive end. Order totals,
pending orders, and failed/rejected counts use `submitted_at`; filled counts and
trading values use `filled_at`. Trading values are separated by currency. Metrics
depend on ETL freshness, not on live trading state. Repeated snapshots are reduced
to the latest `created_at` per client/instrument/submission-timestamp key, with
lifecycle time and terminal status breaking ties. This follows the existing ETL
order key because the table has no `order_id`.

The same response includes `totalDeposits` and `totalWithdrawals` from
`capstone2026.transactions` through the existing transactional datasource, not the
warehouse connection. These sum absolute `amount` values for `transaction_type`
`DEPOSIT` and `WITHDRAWAL` respectively; BUY/SELL rows are excluded. They filter
`created_at` using the same half-open UTC range, return zero for empty periods,
and follow the application's USD account-cash convention. If either database is
unavailable, the endpoint returns 503 rather than presenting incomplete totals.

## Running Spring Boot for Development & Testing

This is the workflow to run the Spring Boot application with connection to the PostgreSQL database and Kafka

1. Open terminal #1 and tunnel to the PostgreSQL database:
```powershell
ssh -L 15432:localhost:5432 ec2-user@<yatin's-ip>
```
2. Open terminal #2 and tunnel to the Spring Boot application
```powershell
ssh -L 18081:localhost:8081 ec2-user@<yatin's-ip>
```
3. Within terminal #2, cd into the backend repo (if you're testing your own code, make sure you fetch and switch to your branch before running the bash script)

```bash
cd Group-12-Backend/
```
4. Run the bash script
```bash
./run-dev.sh
```

If you want to test the endpoints using Bruno/Postman, make sure that you use the port 18081 so it knows where the Spring Boot app is.