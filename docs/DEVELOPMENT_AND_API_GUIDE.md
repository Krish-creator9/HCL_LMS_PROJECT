# OrderCraft Development, MySQL, and Postman Guide

This guide applies to the `OrderCraft` folder containing `frontend/` and `backend/`.

## Project layout

- `frontend/src/app/app.ts` — Angular component state, API calls, navigation, and actions.
- `frontend/src/app/app.html` — dashboard and workspace templates. Use Bootstrap classes for layout and visual changes.
- `frontend/src/styles.css` — global styles and small project-specific overrides.
- `frontend/src/main.ts` and `frontend/src/app/app.config.ts` — app startup and Angular providers.
- `frontend/angular.json` — Angular build/serve configuration; Bootstrap CSS is loaded here.
- `frontend/proxy.conf.json` — routes browser `/api` requests to `http://127.0.0.1:8081` in development.
- `backend/src/main/java/com/ordercraft/api/` — Spring Boot controllers, auth, security, and tenant context.
- `backend/src/main/resources/application.yml` — server, MySQL, Flyway, JWT, and seed defaults.
- `backend/src/main/resources/db/migration/` — versioned Flyway schema and seed migrations (`V1`, `V2`, `V3`).
- `compose.yaml` — local MySQL 8.4 container definition.

## Make frontend changes

From PowerShell, open the project folder and start Angular:

```powershell
Set-Location 'C:\Users\krishna''s-Dell14Plus\Documents\Codex\2026-09-30\OrderCraft'
Set-Location .\frontend
npm ci        # Only needed if node_modules is missing or dependencies changed
npm start
```

Open `http://localhost:4200`. Angular watches the source and refreshes the page as you edit it.

- Change layout, labels, forms, and Bootstrap classes in `src/app/app.html`.
- Change component state, event handlers, and API request behavior in `src/app/app.ts`.
- Keep custom CSS minimal in `src/styles.css`; this project uses Bootstrap 5 utility and component classes.
- Add or change Angular dependencies in `package.json`, then refresh `package-lock.json` with `npm install`; keep both files in sync.
- Create a production bundle with `npm run build`; output goes to `dist/web/browser/`.

The API base in the Angular component is `/api/v1`. The Angular development proxy forwards that path to port `8081`; keep requests relative to `/api/v1` so local development uses the proxy.

## Make backend changes

Use Java 26 as configured in `backend/pom.xml`. The Maven wrapper is included, so a separately installed Maven is not required.

```powershell
Set-Location 'C:\Users\krishna''s-Dell14Plus\Documents\Codex\2026-09-30\OrderCraft\backend'
.\mvnw.cmd spring-boot:run
```

Useful files:

- `OperationsController.java` — orders, inventory, purchasing, work orders, dashboard, and related operations.
- `FulfillmentController.java` — shipments, invoices, payments, journals, and finance workflows.
- `FactoryController.java` — work centers, schedules, scanning, machine events, and integrations.
- `PeopleController.java` — employee, payroll, company, and reporting routes.
- `AuthController.java`, `TokenFilter.java`, `SecurityConfig.java` — login, bearer tokens, authorization, and CORS.
- `UserContext.java` — authenticated tenant, company, and role information.

This backend uses `JdbcTemplate` and SQL in its controllers. Keep SQL parameterized with `?` placeholders. Scope data reads and writes with both `tenant_id` and `company_id`, use `@Transactional` for multi-step writes, validate state changes, and record audit events for operational changes.

Build the API JAR with:

```powershell
.\mvnw.cmd -DskipTests package
```

The artifact is written under `target/`.

## Run MySQL and connect the API

**For the current Windows setup:** `docker` is not installed or available in PowerShell, while the `MySQL80` service is running on `localhost:3306`. Use Option B below for now. Do not start the Compose database with its default `3306:3306` mapping unless you first stop the existing service or change the host mapping.

### Option A: Docker Compose (recommended)

Run from the `OrderCraft` project root:

```powershell
docker compose up -d db
docker compose ps
```

The Compose defaults are:

| Setting | Value |
|---|---|
| Host / port | `localhost:3306` |
| Database | `ordercraft` |
| Username | `ordercraft` |
| Password | `ordercraft-local` |
| Root password | `root-local-only` |

Start the API in a second PowerShell window:

```powershell
Set-Location 'C:\Users\krishna''s-Dell14Plus\Documents\Codex\2026-09-30\OrderCraft\backend'
.\mvnw.cmd spring-boot:run
```

Flyway applies the migrations automatically when the API starts. You do not need to create tables by hand. If the demo seed is enabled and the database has no users yet, the application inserts the local demo company and sample manufacturing data.

You can connect MySQL Workbench using host `127.0.0.1`, port `3306`, user `ordercraft`, password `ordercraft-local`, and schema `ordercraft`.

If another MySQL service already occupies port 3306, change the Compose mapping to `"3307:3306"`, then apply it with `docker compose up -d --force-recreate db`. Start the API with this host-side port override:

```powershell
$env:ORDERCRAFT_DB_URL = 'jdbc:mysql://localhost:3307/ordercraft?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
Set-Location 'C:\Users\krishna''s-Dell14Plus\Documents\Codex\2026-09-30\OrderCraft\backend'
.\mvnw.cmd spring-boot:run
```

### Option B: Existing local MySQL

In MySQL Workbench, the screen shown in your screenshot is a **model** canvas. The `mydb` schema there is a diagram/model and is not the running MySQL server. To run the setup SQL:

1. Click the **Home** (house) icon at the upper left.
2. On the Workbench home page, open your local server connection (usually named `Local instance MySQL80`). If there is no connection tile, click the `+` next to **MySQL Connections** and create one with hostname `127.0.0.1`, port `3306`, and your MySQL administrator username; use **Test Connection**.
3. In the connected SQL Editor, open a new query tab with the SQL-plus toolbar button or `Ctrl+T`.
4. Paste the SQL below into the large query editor area, then click the lightning-bolt **Execute** button. Do not paste it into the model canvas.

Create a database and a local application user in MySQL Workbench or the MySQL client:

```sql
CREATE DATABASE IF NOT EXISTS ordercraft CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'ordercraft_app'@'localhost' IDENTIFIED BY 'choose-a-local-password';
GRANT ALL PRIVILEGES ON ordercraft.* TO 'ordercraft_app'@'localhost';
```

If `ordercraft_app` already exists with a different password, run `ALTER USER 'ordercraft_app'@'localhost' IDENTIFIED BY 'choose-a-local-password';` as your MySQL administrator before starting the API.

Set the connection values in the PowerShell window that will launch the API:

```powershell
$env:ORDERCRAFT_DB_URL = 'jdbc:mysql://localhost:3306/ordercraft?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:ORDERCRAFT_DB_USER = 'ordercraft_app'
$env:ORDERCRAFT_DB_PASSWORD = 'choose-a-local-password'
Set-Location 'C:\Users\krishna''s-Dell14Plus\Documents\Codex\2026-09-30\OrderCraft\backend'
.\mvnw.cmd spring-boot:run
```

The environment variables override the defaults in `src/main/resources/application.yml`. They apply only to that PowerShell window and its child process. Do not commit real passwords or JWT secrets. The Compose volume keeps its data when stopped with `docker compose down`; `docker compose down -v` removes that database volume and its data.

Important environment settings:

- `ORDERCRAFT_DB_URL`, `ORDERCRAFT_DB_USER`, `ORDERCRAFT_DB_PASSWORD` — JDBC connection.
- `PORT` — API port; defaults to `8081`.
- `ORDERCRAFT_JWT_SECRET` — signing key for login tokens; set a long random value outside local demo use.
- `ORDERCRAFT_TOKEN_HOURS` — token lifetime; defaults to 8 hours.
- `ORDERCRAFT_DEMO_SEED` — demo seed switch; defaults to `true`. Set to `false` when you do not want demo seed data on a fresh database.

Schema changes belong in a **new** migration such as `V4__add_new_feature.sql`; do not edit a migration that has already run on a database because Flyway validates migration checksums.

## Use the API with Postman

Postman should call the backend directly at `http://localhost:8081`; it does not use the Angular proxy. CORS restrictions apply to browsers, not to Postman.

### 1. Create a Postman environment

Add these variables:

| Variable | Initial value |
|---|---|
| `baseUrl` | `http://localhost:8081` |
| `token` | leave blank |
| `companyId` | leave blank |

### 2. Log in

Create a request with:

- Method: `POST`
- URL: `{{baseUrl}}/api/v1/auth/login`
- Header: `Content-Type: application/json`
- Body → raw → JSON:

```json
{
  "email": "admin@ordercraft.local",
  "password": "OrderCraft2026!"
}
```

The demo login works only when demo data is seeded. Add this script under the request's **Scripts → Post-response** tab to save the returned credentials in the selected environment:

```javascript
const result = pm.response.json();
pm.environment.set("token", result.token);
pm.environment.set("companyId", result.companies[0].id);
```

The response includes the bearer `token`, user, tenant, and assigned `companies`. Use the company ID returned by login rather than assuming a particular ID.

### 3. Send authenticated requests

For protected requests, use **Authorization → Bearer Token** with `{{token}}`. Also add:

```text
X-Company-Id: {{companyId}}
Content-Type: application/json
```

The API defaults to the user's first assigned company if `X-Company-Id` is omitted. A supplied company must be assigned to that user.

Useful first requests:

```text
GET {{baseUrl}}/actuator/health
GET {{baseUrl}}/api/v1/dashboard
GET {{baseUrl}}/api/v1/orders
GET {{baseUrl}}/api/v1/inventory
GET {{baseUrl}}/api/v1/work-orders
GET {{baseUrl}}/api/v1/shipments
GET {{baseUrl}}/api/v1/invoices
```

Example: create a draft sales order. Use customer and product IDs returned by `GET /api/v1/customers` and `GET /api/v1/products` for the active company.

```text
POST {{baseUrl}}/api/v1/orders
```

```json
{
  "customerId": 1,
  "productId": 1,
  "quantity": 2,
  "dueDate": "2026-12-15",
  "currencyCode": "USD",
  "unitPrice": 125.00
}
```

Other implemented route groups include `/api/v1/purchase-orders`, `/api/v1/inventory/adjustments`, `/api/v1/work-orders`, `/api/v1/finance/**`, `/api/v1/payroll/**`, work-center/schedule/shop-floor/scan routes, `/api/v1/shipments`, `/api/v1/invoices`, and `/api/v1/reports/**`. See the controller `@GetMapping` and `@PostMapping` declarations under `backend/src/main/java/com/ordercraft/api/` for the exact route and request shape. OpenAPI is served at `{{baseUrl}}/v3/api-docs`; Swagger UI is served at `{{baseUrl}}/swagger-ui.html`.

### Common errors

- `401 Unauthorized` — log in again, save the latest token, and check that the `X-Company-Id` belongs to that user's company list. Tokens expire after the configured lifetime.
- `403 Forbidden` — the login may be valid but the role does not have permission for that operation.
- Database connection failure on API startup — confirm the MySQL service is running, the port matches `ORDERCRAFT_DB_URL`, and the database username/password match.
- Browser-only CORS error — use the Angular dev server at port `4200` (which uses `frontend/proxy.conf.json`) or add the exact frontend origin in `SecurityConfig.java`. Postman does not need a CORS change.

The demo username/password above are for local development only. Change credentials and secrets before using real company data.
