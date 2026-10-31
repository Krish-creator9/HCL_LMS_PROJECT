# HCL Career Shaper — Full Stack Development (Java)
# OrderCraft — Manufacturing Order Management System (OMS)

OrderCraft is an enterprise-grade multi-tenant Manufacturing Operations and Order Management System built with Angular and Spring Boot. It streamlines the full operational lifecycle from sales order entry, bill-of-materials (BOM) demand calculation, production scheduling, shop-floor execution, and inventory movements to fulfillment, automated invoicing, and general ledger journal posting.

**GitHub Repository:** [https://github.com/Krish-creator9/HCL_LMS_PROJECT](https://github.com/Krish-creator9/HCL_LMS_PROJECT)

---

## Project Structure

This project follows the standard modular structure required for HCL Career Shaper LMS evaluations:

```text
OrderCraft/
├── backend/                  # Java 26 & Spring Boot 4.1.1 REST API (Maven)
│   ├── .mvn/wrapper/         # Maven Wrapper configuration
│   ├── src/main/java/        # Business logic, controllers, security & adapters
│   ├── src/main/resources/   # Application YAML & Flyway migrations
│   ├── mvnw / mvnw.cmd       # Cross-platform Maven executable
│   └── pom.xml               # Pinned backend dependencies
├── frontend/                 # Angular 22 & TypeScript Client Application
│   ├── src/
│   │   ├── app/              # Standard Angular components & state management
│   │   │   ├── app.component.ts
│   │   │   ├── app.component.html
│   │   │   ├── app.component.css
│   │   │   ├── app.config.ts
│   │   │   └── app.routes.ts
│   │   ├── index.html        # Single-page application entry point
│   │   ├── main.ts           # Application bootstrapping
│   │   └── styles.css        # Global CSS & theme utilities
│   ├── public/               # Static assets & favicon
│   ├── angular.json          # Angular CLI workspace configuration
│   ├── package.json          # Frontend dependencies & scripts
│   └── tsconfig.json         # TypeScript configuration
├── database/                 # Dedicated database scripts for LMS evaluators
│   ├── migrations/           # Versioned Flyway SQL migration files (V1 - V4)
│   └── README.md             # Database credentials & setup guide
├── docs/                     # Comprehensive architecture and functional specs
│   ├── DEVELOPMENT_AND_API_GUIDE.md
│   ├── ORDERCRAFT_CODEX_DEVELOPMENT_PLAN.md
│   ├── OrderCraft_Project_Requirements.md
│   └── RELEASE_RUNBOOK.md
├── compose.yaml              # Docker Compose service definition for MySQL 8.4 LTS
├── PROJECT_TREE.txt          # Detailed repository tree snapshot
└── README.md                 # Primary project documentation & quick start guide
```

---

## Technology Stack

- **Frontend:** Angular 22.2.0 / TypeScript with Bootstrap 5.3.8, Node.js 22/24+
- **Backend:** Java 26 / Spring Boot 4.1.1
- **Database:** MySQL 8.4 LTS with Flyway automated migrations
- **Security:** JWT (JSON Web Token) with Tenant & Company scope enforcement
- **API Standard:** Versioned REST API at `/api/v1`, OpenAPI / Swagger specification

---

## DevOps & 1-Command Deployment (with Instant Public Link)

You can launch the entire stack (Database + Backend + Frontend + Live Public HTTPS Link) in a single command using Docker Compose:

```bash
docker compose up --build -d
```
- **Local Access:** Open `http://localhost` (or `http://localhost:4200`)
- **Public URL for Any Device (Phone/PC):** Run `docker compose logs tunnel` to get your free instant public HTTPS link (powered by Cloudflare Tunnel).
- **Architecture & Cloud Guide:** See [`docs/DEVOPS_DEPLOYMENT_GUIDE.md`](docs/DEVOPS_DEPLOYMENT_GUIDE.md).

---

## Alternative: Manual Local Startup (Development Mode)

### Prerequisites
- Docker Desktop (or local MySQL 8.0/8.4 running on port 3306)
- Java Development Kit (JDK 21 or 26)
- Node.js (v22 or v24+) & npm

### 1. Database Setup
From the `OrderCraft/` root directory, launch the database container:
```bash
docker compose up -d db
```
*(The Spring Boot backend automatically executes all Flyway migrations on startup).*

### 2. Start the Backend API
In a new terminal window:
```bash
cd backend
# Windows:
.\mvnw.cmd spring-boot:run
# macOS / Linux:
./mvnw spring-boot:run
```
The REST API will start listening at: `http://localhost:8081`

### 3. Start the Frontend Application
In another terminal window:
```bash
cd frontend
npm install
npm start
```
The web portal will open at: `http://localhost:4200`

---

## Demo & Evaluation Credentials

For testing and evaluation on the LMS portal, the following pre-seeded demo accounts are available:

| Role | Email | Password | Assigned Company |
|---|---|---|---|
| **Administrator** | `admin@ordercraft.local` | `OrderCraft2026!` | Chicago Plant 01 (CHI-01) |
| **Custom Registration** | Register on `/` | Any password | Selected on signup |

---

## Core API Endpoints

- **Authentication:** `POST /api/v1/auth/login`, `POST /api/v1/auth/register`, `POST /api/v1/auth/forgot-password`
- **Dashboard:** `GET /api/v1/dashboard`
- **Sales & Orders:** `GET|POST /api/v1/orders`, `POST /api/v1/orders/{id}/confirm`
- **Manufacturing:** `GET /api/v1/work-orders`, `POST /api/v1/work-orders/{id}/report`
- **Inventory & Receipts:** `GET /api/v1/inventory`, `POST /api/v1/inventory/adjustments`, `POST /api/v1/purchase-orders/{id}/receive`
- **Fulfillment & Billing:** `GET|POST /api/v1/shipments`, `GET|POST /api/v1/invoices`, `POST /api/v1/invoices/{id}/pay`
- **Factory & Shop Floor:** `GET /api/v1/factory/work-centers`, `POST /api/v1/factory/scan`
- **Payroll & Books:** `GET|POST /api/v1/payroll/runs`, `GET /api/v1/reports/consolidated`
- **Database Explorer:** `GET /api/v1/database/status`, `GET /api/v1/database/tables`
