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