# OrderCraft — Codex Development Plan

**Purpose:** Use this file as the project brief and phase roadmap when building OrderCraft with Codex.  
**Canonical requirements:** [`OrderCraft_Project_Requirements.md`](./OrderCraft_Project_Requirements.md)  
**Project ID:** P_001  
**Status:** Ready to begin Phase 1

## Project brief

OrderCraft is a manufacturing order management system covering customer demand, production, material purchasing, inventory, shipment, invoicing, and payment. The expanded scope also includes multi-tenant/multi-company operation, financial accounting, payroll, capacity scheduling, mobile and shop-floor use, barcodes, machine/IoT data, and external integrations.

Use the requirements file as the detailed functional specification. This plan breaks that scope into five implementation phases. Build and complete one phase at a time, keeping the application runnable at the end of each phase.

### Required technology

- **Frontend:** Angular and TypeScript.
- **Backend:** Java 26 and Spring Boot.
- **Database:** MySQL 8.4 LTS baseline.
- **Build:** Maven with Maven Wrapper for the backend; Angular CLI and a committed package lockfile for the frontend.
- **API:** Versioned REST API under `/api/v1`; JSON; documented with OpenAPI.

Confirm the latest compatible stable patch versions when implementation starts and pin them in the project. Do not replace the requested stack without the user's approval.

### Domain and architecture decisions

- Each tenant represents an organization; each tenant may contain multiple companies. This resolves the overview's single-organization phrasing in the requirements against its expanded multi-tenant scope.
- Tenant and company context must be enforced in backend services, database access, exports, scheduled jobs, and integration processing. Never trust a tenant identifier supplied only by the client.
- Keep Angular and Spring Boot as separate application modules in one repository unless the existing repository or deployment needs justify a different structure.
- The browser/mobile clients call the backend API; they never connect directly to MySQL.
- Put business rules in backend services. Use DTOs at API boundaries, database migrations for schema changes, and auditable stock/financial ledgers.
- Build integrations behind adapters. Start with mocks or sandbox connections; add live providers only after the provider, jurisdiction, credentials, and applicable certification are known.
- Do not store raw payment-card details. Keep secrets out of source control and logs.

## Five development phases

### Phase 1 — Platform foundation and tenant security

**Outcome:** A clean, secure, runnable application foundation with tenant/company isolation and a basic administrative shell.

**Implement:**

- Repository structure, README, backend Maven Wrapper, Angular workspace, local configuration template, and development startup workflow.
- Java 26 / compatible Spring Boot baseline, Angular baseline, MySQL connection, schema migration framework, and health endpoints.
- Tenant and company model, tenant-scoped user/role assignments, authentication, authorization, and administrative user screens/API.
- Backend tenant context enforcement and a clear company context in the frontend.
- Shared API conventions, validation/error response handling, OpenAPI, logging, audit-event foundation, and navigation/layout.
- Seeded development data that is safe for local use and documented demo accounts.

**Exit criteria:**

- A new developer can follow the README to start the database, migrate the schema, run the API and UI, and sign in.
- Users can be assigned to tenants/companies and roles; an attempt to access another tenant's records is denied at the API.
- Database changes are versioned; health status, API documentation, and initial audit events are available.
- No committed production credentials or secrets exist.

### Phase 2 — Manufacturing operations and inventory

**Outcome:** The core manufacturing workflow from master data and customer order to production completion and available finished goods.

**Implement:**

- Customers, suppliers, products, components/materials, units, warehouses, and versioned BOMs.
- Customer orders, order lines, controlled status transitions, and immutable order/BOM snapshots on confirmation.
- Manufacturing orders, BOM demand calculation, shortage visibility, material reservation/release, and production reports.
- Inventory movement ledger, on-hand/reserved/available quantities, low-stock thresholds, and authorized corrections.
- Purchase orders, partial/full goods receipts, receiving history, and stock updates.
- Initial operational dashboards, searchable/filterable lists, and CSV export for core operations.

**Exit criteria:**

- A user can create and confirm an order, calculate correct BOM demand, see shortages, purchase and receive materials, record production, and inspect resulting stock movements.
- Reservations, consumption, receipts, and manufacturing status changes remain consistent if a request is retried.
- Negative stock, invalid status changes, and over-receipts are rejected or handled only through an auditable authorized adjustment.
- Prior BOM revisions and transaction history remain intact when master data changes.

### Phase 3 — Fulfillment, invoicing, and accounting core

**Outcome:** A complete internal order-to-cash flow and a reliable financial record before connecting live financial providers.

**Implement:**

- Partial/full shipments linked to customer order quantities and warehouse stock.
- Invoices generated from shipped quantities, preserved invoice-line snapshots, due dates, taxes/discount fields, and outstanding balances.
- Manual and offline payment records, partial payments, refunds/voids, and invoice status calculation.
- Configurable chart of accounts, accounting periods, posting rules, journals, balanced double-entry postings, and reversing/adjusting entries.
- Payment, tax/e-invoice, and bank-integration adapter contracts with mock/sandbox workflows and idempotent external transaction records.
- Bank statement import, match/review workflow, reconciliation history, and finance reports.
- Tenant/company-aware financial reports and access controls.

**Exit criteria:**

- Users can fulfill an order, invoice shipped quantities, apply partial/final payments, and view correct balances and statuses.
- Ledger postings balance and link back to source transactions; corrections do not rewrite posted history.
- Bank entries can be matched or left for review without silent data changes.
- Mock/sandbox payment and e-invoice flows record external references, errors, retries, and results without duplicate posting.

### Phase 4 — Connected factory and external integrations

**Outcome:** Production planning and execution are connected to capacity, devices, mobile workflows, and selected external systems.

**Implement:**

- Work centers, shifts, operation durations, capacity calendars, finite-capacity scheduling, conflict handling, and projected completion dates.
- Shop-floor terminal screens and agreed native mobile clients for assigned work, status, quantities, material scans, and offline-safe submissions.
- Barcode/QR creation and scanning for supported products, materials, locations, orders, and shipments.
- Machine/IoT adapter and event ingestion with device mapping, source timestamps, de-duplication, and event status.
- Configurable customer/supplier email templates and delivery tracking/retry.
- Shipping carrier adapter for supported rate/label/shipment/tracking workflows.
- ERP/CRM adapter with agreed field mappings, external IDs, sync status, conflict handling, and safe retry.
- Payment, bank, tax/e-invoice, and carrier live-provider connectors for providers selected by the user, using provider sandboxes and required certification.

**Exit criteria:**

- Planners can see capacity conflicts and forecast production dates; shop-floor users can scan and report work with an auditable result.
- Device events, carrier updates, and external syncs retain source, timestamp, status, and error/retry information.
- Retrying a webhook or sync does not duplicate business records or ledger postings.
- At least one mock/sandbox scenario is documented for each adapter; live connections pass the selected provider's sandbox/certification checks when credentials and provider details are available.

### Phase 5 — Payroll, multi-company close, and release readiness

**Outcome:** Complete the enterprise scope and prepare the system for a controlled deployment.

**Implement:**

- Employee records, pay periods, configurable payroll earnings/deductions, payroll review/approval, and summarized ledger posting.
- Company-level books, numbering, currency and tax configuration, authorized inter-company/consolidated reporting, and documented currency conversion/elimination rules.
- End-to-end operational, production, inventory, financial, payroll, and integration reports across authorized tenant/company scopes.
- Security and tenant-isolation review across APIs, exports, background jobs, audit events, logs, and integration callbacks.
- Operational documentation for deployment, environment configuration, database backup/restore, monitoring, retention, and recovery.
- Performance, accessibility, error handling, data migration, and release verification for the agreed deployment environment.

**Exit criteria:**

- A payroll run can be calculated using the selected jurisdiction configuration, reviewed, approved, and posted with an audit trail.
- Company-level and consolidated reports show correct values and are restricted by role and tenant/company access.
- Core workflows and integration retry/recovery behavior are verified in the deployment-like environment.
- Deployment, rollback/recovery, backup/restore, and administrator instructions are documented and usable.

## Cross-phase delivery rules

Apply these rules in each phase:

1. Read the current repository instructions (`AGENTS.md` and relevant project guidance) before editing. Inspect existing code and preserve compatible work.
2. Confirm the active phase and its exit criteria before making broad changes. Do not start later-phase features unless required to unblock the current phase or the user asks.
3. Implement complete vertical slices: database migration, backend rule/API, frontend flow, permissions, and documentation together where practical.
4. Keep the project runnable. Update local setup instructions and configuration examples when dependencies or commands change.
5. Preserve tenant isolation, auditability, and transaction integrity from the first implementation; do not defer them as final-phase cleanup.
6. Use provider interfaces and mock/sandbox implementations until provider details are selected. Do not invent credentials, jurisdictions, tax rules, or external API contracts.
7. Keep secrets out of source control. Validate access in the backend, even when the UI hides restricted actions.
8. Before calling a phase complete, compare the implementation with its exit criteria and record what is done, what remains, and any decisions needed.
9. Avoid unrequested destructive actions, production integrations, publishing, or deployment. Ask before any irreversible external action.
10. Treat `OrderCraft_Project_Requirements.md` as the detailed feature source of truth. If implementation reveals ambiguity or conflicting requirements, explain the conflict and choose the least surprising reversible behavior until the user decides.

## Codex handoff prompt

Use this plan as the project brief. First inspect the repository, its current branch/state, and all applicable `AGENTS.md` instructions. Read `OrderCraft_Project_Requirements.md`. Report which phase the repository is currently in based on the code, then work only on the next incomplete phase unless the user asks for a different scope. Complete a reviewable vertical slice, keep the app runnable, document setup changes, and summarize changed files, verification performed, remaining work, and decisions needed. Do not claim a phase is complete until its exit criteria are met.

## Progress tracker

- [ ] Phase 1 — Platform foundation and tenant security
- [ ] Phase 2 — Manufacturing operations and inventory
- [ ] Phase 3 — Fulfillment, invoicing, and accounting core
- [ ] Phase 4 — Connected factory and external integrations
- [ ] Phase 5 — Payroll, multi-company close, and release readiness

Update the tracker only when implementation evidence supports the status. Record in-progress work and decisions in the project README or a separate progress log.

## Decisions to capture before relevant work

- Hosting/deployment target, expected tenant/company/user scale, data residency, retention, and recovery objectives.
- Target mobile platforms and whether mobile is a separate native client or an approved cross-platform client.
- Payment gateway, bank feed/import format, tax jurisdiction, e-invoicing network, accounting standards, and payroll jurisdiction.
- ERP/CRM, shipping carrier, email provider, machine protocols, barcode symbology, and sandbox credentials.
- Currency, tax-inclusive/exclusive behavior, tax rounding, accounting periods, and consolidated reporting conversion/elimination rules.
