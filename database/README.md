# OrderCraft — Database Schema & Migrations

This folder contains the complete relational database schema and migration scripts for the OrderCraft Manufacturing Order Management System.

## Database Information
- **RDBMS:** MySQL 8.0 / 8.4 LTS
- **Default Database Name:** `ordercraft`
- **Default Port:** `3306`
- **Application User:** `ordercraft_app`
- **Application Password:** `ordercraft_pass`

## Migration Structure
OrderCraft uses Flyway for automated, version-controlled database migrations located under `migrations/`:
- `V1__platform_and_operations_schema.sql`: Core multi-tenant platform, user accounts, master data, products, BOMs, sales orders, manufacturing orders, purchase orders, and inventory movements.
- `V2__fulfillment_finance_factory.sql`: Shipments, invoicing, payment records, general ledger accounts, accounting periods, journal entries, shop floor work centers, schedules, barcodes, and external adapter logging.
- `V3__people_and_multicompany.sql`: Multi-company finance profiles, exchange rates, employee records, payroll runs, line items, and journal posting.
- `V4__auth_security_and_password_reset.sql`: User profile avatar support, Google OAuth linkage, and secure password-reset tokens.

## Running Database with Docker
To spin up the isolated MySQL container:
```bash
docker compose up -d db
```
The Spring Boot backend (`backend/`) automatically runs all Flyway migrations on application startup.

## Manual Import (Optional)
If you wish to import the database manually into MySQL Workbench, phpMyAdmin, or MySQL CLI:
```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS ordercraft CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u root -p ordercraft < migrations/V1__platform_and_operations_schema.sql
mysql -u root -p ordercraft < migrations/V2__fulfillment_finance_factory.sql
mysql -u root -p ordercraft < migrations/V3__people_and_multicompany.sql
mysql -u root -p ordercraft < migrations/V4__auth_security_and_password_reset.sql
```
