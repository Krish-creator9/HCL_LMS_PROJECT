# OrderCraft — Project Requirements

**Project ID:** P_001  
**Document version:** 1.0  
**Date:** 30 September 2026  
**Status:** Baseline requirements for implementation

## 1. Project overview

OrderCraft is a web-based Manufacturing Order Management System (OMS) for a single manufacturing organization. It supports the operational flow from customer order entry through production planning, bill-of-materials (BOM) consumption, purchasing, fulfillment, invoicing, and payment tracking.

This document translates the supplied project synopsis into implementable requirements. The synopsis permits alternative technology choices; this project selects the stack explicitly requested by the user: **Angular, Java 26, Spring Boot, MySQL, and Maven**.

### Goals

- Keep customer orders and their production, purchasing, shipping, and financial status in one system.
- Calculate required component quantities from product BOMs and order quantities.
- Identify material shortages and support purchase orders and receiving.
- Track manufacturing progress, finished goods, shipments, invoices, and incoming payments.
- Provide role-appropriate screens, searchable records, and operational reports.

### Success measures

- An authorized user can take a valid customer order through production, shipment, invoice, and payment recording without maintaining duplicate records outside the system.
- BOM demand and material shortage calculations are reproducible from the order quantity and the active BOM revision.
- Users can identify overdue orders, low stock, open purchase orders, unpaid invoices, and production bottlenecks from the application.

## 2. Scope

### Included in scope

- User authentication, role-based permissions, tenant/company administration, and basic user administration.
- Master data for customers, suppliers, products, raw materials/components, units, warehouses, and work centers.
- Product BOMs with revisions and effective/active version selection.
- Customer sales orders and line items, manufacturing orders, BOM demand, material reservations/consumption, production status, and finite-capacity scheduling.
- Inventory movements, low-stock visibility, purchase orders, and receipt of purchased materials.
- Shipment/fulfillment records, carrier integration, invoice generation, tax/e-invoice workflows, online payment processing, bank reconciliation, and payment tracking.
- Machine/IoT data integration, shop-floor terminal workflows, barcode scanning, and mobile-native apps for supported operations.
- Payroll and accounting general ledger workflows, multi-company consolidation, and tenant-isolated multi-tenant operation.
- Automatic supplier/customer email delivery and external ERP/CRM integrations.
- Search, filtering, pagination, dashboards, audit history, and exports for key lists/reports.
- Responsive web interface, documented REST API, database migrations, and local setup/deployment instructions.

Integrations are part of the system scope. Specific payment gateway, bank, tax authority, e-invoicing network, shipping carrier, email service, ERP/CRM, machine protocol, payroll rules, and accounting standards remain deployment configuration decisions and must be selected before live integration credentials or jurisdiction-specific behavior can be finalized.

## 3. Users and permissions

| Role | Main responsibilities | Access summary |
|---|---|---|
| Administrator | Configure users and system reference data | Full access within assigned tenant/company boundaries; cross-tenant operations are restricted to specifically authorized platform administrators |
| Sales | Maintain customers and enter/manage customer orders | Customer and order access; view fulfillment and invoice status |
| Production Planner | Release and plan manufacturing work | View orders and stock; create/update manufacturing orders and BOM demand |
| Production Operator | Report progress and material usage | Assigned manufacturing orders; record production steps, usage, and completion |
| Purchasing | Manage suppliers and replenishment | Supplier, shortage, purchase order, and goods receipt access |
| Inventory Clerk | Manage stock movements and shipment preparation | Inventory, reservations, receipts, and shipment records |
| Finance | Create invoices, record payments, reconcile bank activity, and manage accounting | Finance operations, ledger, reconciliation, tax, and e-invoice access within assigned companies |
| Payroll Administrator | Manage employee and payroll cycles | Employee/payroll records and approval workflows within assigned companies |
| Integration Administrator | Configure external connections and review sync failures | Integration credentials/configuration and operational logs; secrets remain masked |
| Tenant Administrator | Manage tenant/company settings and user assignments | Administrative access limited to assigned tenant/company boundaries |
| Read-only Manager | Review dashboards and reports | Read-only access to authorized operational and financial summaries |

Permissions must be enforced by the backend for every protected API operation, not only hidden in the UI. Users have one or more assigned roles. A user may not grant themselves permissions.

## 4. Core workflows

### 4.1 Customer order to cash

1. Sales creates an order for an active customer and adds one or more products, quantities, unit prices, and requested dates.
2. The system validates the order and saves it as **Draft**. Sales may edit or cancel a draft.
3. Sales confirms the order. The system assigns a unique order number, captures the applicable product/BOM revision, and marks it **Confirmed**.
4. A planner creates one or more manufacturing orders for the required quantities. The system calculates component demand from the selected BOM revisions and available stock and highlights shortages.
5. The planner reserves available components. Missing components may be covered by purchase orders and goods receipts. A manufacturing order cannot start until its configured material readiness rule is met; shortages are shown with an actionable explanation.
6. Production reports start, material consumption, good quantity, scrap quantity, and completion. The system prevents recording consumption beyond available stock and prevents good output beyond the manufacturing order quantity unless an authorized adjustment is recorded.
7. Completed goods are moved into finished-goods inventory. Inventory Clerk creates a shipment against the customer order, recording shipped quantities and shipment date. The system prevents shipment beyond the unshipped order quantity.
8. Finance creates an invoice for shipped quantities (partial invoicing is supported) and records one or more payments against it.
9. Order, shipment, invoice, and payment statuses update from their underlying quantities and balances. The order is **Closed** when it is fully shipped and its financial balance is settled, or remains visibly open otherwise.

### 4.2 Replenishment to inventory

1. Purchasing reviews a material shortage or low-stock alert and creates a purchase order for a supplier.
2. The purchase order can be saved as **Draft**, submitted as **Issued**, partially received, fully received, or **Cancelled** before receipt.
3. Inventory Clerk records a goods receipt against purchase-order lines, including received quantity and receipt date.
4. The system increases on-hand stock, records the inventory movement, and updates received and remaining quantities. A receipt cannot exceed the remaining purchase-order quantity without an authorized over-receipt adjustment.

### 4.3 BOM maintenance

1. An authorized user creates or edits a product BOM containing component quantities per finished unit and optional operation notes.
2. The system validates that a BOM has at least one component, no duplicate component lines, positive quantities, and no direct or indirect self-reference if assemblies can be components.
3. Publishing a new BOM creates a new revision and preserves prior revisions. Confirmed/manufacturing orders retain the revision they used.

### 4.4 Scheduling and shop-floor execution

1. The planner schedules manufacturing operations against work centers, shifts, and available capacity, and can see conflicts and projected completion dates.
2. Shop-floor staff use a supported desktop/tablet terminal or native mobile client to access assigned work, scan barcodes/QR codes, and record start, pause, completion, consumption, scrap, and quality notes.
3. Where supported, machine/IoT events are ingested through configured adapters, mapped to a work order/work center, and shown with source and timestamp. The system retains manual entry and correction with audit history.

### 4.5 Integrated finance and external systems

1. Issued invoices can be submitted through configured e-invoicing and tax-filing integrations, with submission status, external reference, response, retry, and correction history retained.
2. Customers can pay through the configured payment provider. The system records provider transaction references and reconciles callbacks/webhooks idempotently; users can still record approved offline payments.
3. Finance imports bank statements or uses a configured bank integration, matches entries to payments/invoices, and records unmatched entries for review without silently changing ledger records.
4. The system posts configured sales, purchases, inventory, tax, payroll, and payment transactions to the general ledger and supports company-level and consolidated reporting.
5. Configured ERP/CRM and shipping carrier connectors exchange agreed data, show sync status/errors, and support safe retry and duplicate detection. Supplier/customer emails are generated from templates and have delivery status and retry history.

### 4.6 Tenant and company operations

1. An administrator can create companies and tenants and assign users and roles within the permitted tenant/company boundary.
2. Business records are scoped to a tenant; users can access only the tenants and companies explicitly assigned to them.
3. A tenant with multiple companies can run company-level operations and authorized consolidated reporting, while preserving each company's books, currency, tax configuration, and audit trail.

## 5. Functional requirements

### Authentication and administration

- **FR-001:** Users can sign in and sign out. Passwords are stored using a modern adaptive one-way hash; plaintext passwords are never stored or logged.
- **FR-002:** Administrators can create, disable, and update user accounts and assign roles. Disabled users cannot authenticate.
- **FR-003:** The system records actor and timestamp for security-sensitive and business-critical changes.

### Master data and BOM

- **FR-010:** Authorized users can create, view, edit, deactivate, search, and filter customers, suppliers, products, components/materials, units, and warehouses.
- **FR-011:** Customer and supplier records include name, contact details, billing/shipping addresses, status, and notes as applicable.
- **FR-012:** Product and material records include unique code/SKU, description, unit of measure, active status, and applicable reorder threshold. Product records may include standard selling price; material records may include standard purchase cost.
- **FR-013:** Products support versioned BOMs. A BOM line identifies a component and required quantity per finished unit; BOM changes do not rewrite the history of previously confirmed orders.
- **FR-014:** Invalid, inactive, or incomplete records cannot be used for new transactions. Historical records remain viewable after master data is deactivated.
- **FR-015:** Each business record belongs to a tenant and, where applicable, a company. Tenant/company context is applied consistently to master data, transactions, search, reports, exports, and background processing.

### Sales and manufacturing

- **FR-020:** Users can create orders with unique human-readable order numbers, customer, order date, requested delivery date, currency, notes, lines, quantities, unit prices, and calculated subtotals/totals.
- **FR-021:** Order status transitions are controlled and validated. At minimum: Draft, Confirmed, In Production, Partially Fulfilled, Fulfilled, Closed, Cancelled. Invalid transitions are rejected with a clear message.
- **FR-022:** Users can create manufacturing orders linked to a customer order line or for approved stock replenishment. A manufacturing order stores product, planned quantity, BOM revision, target dates, and status.
- **FR-023:** The system calculates BOM demand as component quantity per unit × planned production quantity, with defined precision and rounding. It displays available, reserved, required, and shortage quantities.
- **FR-024:** Production users can report status, start/end timestamps, completed quantity, scrap quantity, material usage, and notes. Corrections are auditable.
- **FR-025:** Manufacturing status transitions are controlled: Planned, Ready, In Progress, Paused, Completed, Cancelled. Completion requires a recorded output quantity and material usage disposition.

### Inventory and procurement

- **FR-030:** The system tracks on-hand, reserved, and available quantity by item and warehouse. Available quantity is on-hand minus active reservations.
- **FR-031:** Every stock change is represented by an immutable inventory movement with item, warehouse, quantity, movement type, source reference, actor, and timestamp. Corrections use compensating movements, not silent edits.
- **FR-032:** Reservations are linked to manufacturing orders and released on cancellation or consumption. The system prevents stock from being reserved or consumed below zero unless an authorized adjustment workflow is used.
- **FR-033:** Purchasing users can create and manage supplier purchase orders with line quantities, unit costs, expected dates, and status.
- **FR-034:** Users can receive purchase-order lines fully or partially. Each receipt updates stock and the purchase-order received balance atomically.
- **FR-035:** Users can view low-stock items when available quantity is at or below the configured reorder threshold.

### Fulfillment and finance

- **FR-040:** Users can create shipments linked to an order, warehouse, shipment date, and line quantities. Partial shipments are supported.
- **FR-041:** Finance can generate invoices from shipped, not-yet-invoiced quantities. Invoice number is unique; invoice lines preserve description, quantity, unit price, tax/discount inputs, and totals at invoice time.
- **FR-042:** Invoice status is derived or consistently maintained as Draft, Issued, Partially Paid, Paid, Void, or Overdue. Overdue status uses invoice due date and outstanding balance.
- **FR-043:** Finance can record payments with invoice, amount, date, method/reference, and notes. Partial payments are supported; overpayment is rejected unless a documented credit workflow is added.
- **FR-044:** Monetary values use fixed-precision decimal storage and calculations; floating-point types are not used for currency.
- **FR-045:** Tax rate, tax-inclusive/exclusive behavior, default currency, and invoice numbering are configurable. Jurisdiction-specific filing and electronic invoice submission use the configured adapters and must retain acknowledgements, rejections, retries, and corrections.

### Scheduling, integrations, and expanded business operations

- **FR-060:** Planners can define work centers, shifts, operation durations, and capacity calendars; schedule manufacturing operations; and view capacity conflicts, queues, and projected completion dates. Rescheduling is auditable.
- **FR-061:** Shop-floor terminal and native mobile clients support secure sign-in and role-scoped assigned work, status reporting, quantity reporting, material scans, and offline-safe queuing where connectivity is interrupted. Conflicts are surfaced for resolution before commit.
- **FR-062:** Barcode/QR labels can be generated for supported products, materials, locations, orders, and shipments. Scans resolve the identifier and validate the requested action before changing data.
- **FR-063:** Machine/IoT integrations use configurable adapters and authenticated ingestion. Events retain source device, timestamp, unit, mapping, and processing status; duplicate and out-of-order events are handled safely.
- **FR-064:** The system can process online payments through a configurable payment-provider adapter, receive signed provider notifications, prevent duplicate posting, track refunds/voids, and store external transaction references. Sensitive card data is not stored by OrderCraft.
- **FR-065:** Finance can import bank statements or receive bank transaction feeds, match entries against recorded payments and invoices, mark unmatched/ambiguous items for review, and preserve reconciliation history.
- **FR-066:** Tax and e-invoicing integrations are adapter-based and configurable by jurisdiction. The system validates required fields, submits eligible invoices, stores acknowledgements/rejections and external IDs, and supports retries/corrections with an audit trail.
- **FR-067:** An accounting general ledger supports configurable chart of accounts, journals, posting rules, periods, and balanced double-entry postings for approved business transactions. Posted entries are corrected through reversing/adjusting entries.
- **FR-068:** Payroll supports employee records, pay periods, configurable earnings/deductions, payroll calculation review, approval, and posting summaries to the ledger. Statutory calculations require the configured jurisdiction's rules and are not inferred.
- **FR-069:** Multi-company operations maintain separate company settings, numbering, currency, tax configuration, and books. Authorized users can run consolidated reports with documented currency conversion and elimination rules.
- **FR-070:** Multi-tenant isolation is enforced in every backend query, write, export, background job, and integration. Tenant identity is established from authenticated context, never trusted solely from client-supplied IDs.
- **FR-071:** Configured ERP/CRM integrations synchronize agreed master and transaction records with field mappings, external IDs, sync status, conflict handling, retry, and duplicate protection.
- **FR-072:** Configured shipping-carrier integrations obtain rates/labels where available, submit shipment data, and track carrier status/events. Manual shipment entry remains available for unsupported carriers.
- **FR-073:** Supplier/customer emails use configurable templates and event triggers, record delivery attempts/status, and allow authorized retry. Sensitive information is excluded from message content unless explicitly configured.

### Search, dashboards, and reports

- **FR-050:** Lists support server-side pagination, sorting, and appropriate filters/search by identifier, status, customer/supplier, date range, and item.
- **FR-051:** The dashboard shows at least open customer orders, manufacturing orders by status, low-stock/shortage items, open purchase orders, shipments due, and unpaid/overdue invoices.
- **FR-052:** Users can view order-to-cash history from a customer order, including related manufacturing orders, receipts/materials, shipments, invoices, and payments.
- **FR-053:** Authorized users can export filtered order, inventory, purchasing, production, shipment, and invoice lists to CSV. Exports follow the same access permissions as the UI.
- **FR-054:** Dashboards and reports can be filtered by tenant/company and present separate-company and consolidated views only to authorized roles.

## 6. Data requirements

The relational model should include, at minimum, these entities and relationships:

- **Tenant, Company, User, Role, UserRole, UserTenantCompany** — tenant isolation, company books, account identity, and scoped permissions.
- **Customer, Supplier, Product, Material/Item, UnitOfMeasure, Warehouse** — active master data.
- **BOM, BOMRevision, BOMLine** — product structure and revision history.
- **SalesOrder, SalesOrderLine** — customer demand and order snapshots.
- **ManufacturingOrder, MaterialReservation, ProductionReport** — production planning and execution.
- **PurchaseOrder, PurchaseOrderLine, GoodsReceipt, GoodsReceiptLine** — replenishment and receiving.
- **InventoryMovement** — auditable stock ledger; on-hand balances must be consistent with movements.
- **Shipment, ShipmentLine** — fulfillment records.
- **Invoice, InvoiceLine, Payment** — receivables and payment history.
- **AuditEvent** — actor, tenant/company, action, entity, timestamp, and relevant before/after summary for selected business events.
- **WorkCenter, Shift, CapacityCalendar, OperationSchedule** — manufacturing resources and finite-capacity plans.
- **Machine, IoTEvent, ScanEvent, DeviceRegistration** — device mappings and traceable shop-floor/barcode input.
- **PaymentProviderTransaction, BankStatement, BankStatementLine, ReconciliationMatch** — online payment references and bank reconciliation.
- **TaxSubmission, EInvoiceSubmission, IntegrationConnection, ExternalIdMapping, SyncJob** — external system configuration and delivery status.
- **ChartOfAccounts, Journal, JournalLine, AccountingPeriod** — balanced company-level accounting records.
- **Employee, PayPeriod, PayrollRun, PayrollLine** — payroll calculations and approved posting summaries.
- **CarrierShipment, EmailMessage, EmailDeliveryAttempt** — carrier fulfillment and outbound communication history.

Use generated internal primary keys and unique business identifiers (order number, PO number, invoice number, SKU/code). Use foreign keys, appropriate indexes, non-null constraints, and decimal types sized for quantities and money. Store timestamps consistently in UTC and render them in the user's configured/local timezone. Define deletion behavior so records referenced by transactions are deactivated or archived rather than physically removed.

## 7. Technical and architecture requirements

- **Frontend:** Angular (target current supported major; baseline Angular 22), TypeScript, Angular CLI, responsive desktop-first interface with tablet support, and accessible form labels, keyboard navigation, and validation messages.
- **Backend:** Java 26, Spring Boot (baseline Spring Boot 4.1.1), Spring Web REST API, Spring Data JPA, Bean Validation, Spring Security, and Actuator health endpoints.
- **Build:** Maven wrapper and Maven project. Backend build must declare Java 26 and use pinned dependency/plugin versions or Spring Boot dependency management. Frontend dependencies must be locked.
- **Database:** MySQL 8.4 LTS baseline. Schema changes are versioned and applied through database migration scripts (Flyway or Liquibase); application startup must not silently alter production schemas.
- **API:** Versioned JSON REST API under `/api/v1`; consistent response/error shape, validation errors with field details, pagination conventions, and OpenAPI documentation. Expose integration endpoints through authenticated, authorized adapters and validate webhook signatures.
- **Structure:** Keep frontend and backend as clearly separated modules in one repository unless implementation needs justify otherwise. Business rules and transaction boundaries live in backend services; the Angular client must not connect directly to MySQL.
- **Configuration:** Secrets and environment-specific settings come from environment variables or a secrets manager, never committed credentials. Provide local development configuration with safe placeholder values.
- **Transactions:** Multi-step business operations that change related state (receipts, reservations/consumption, shipments, invoice issue/payment, payroll posting, and ledger posting) must be atomic and protect against duplicate/retried requests where applicable.
- **Integration architecture:** Use replaceable adapters for payment, banking, tax/e-invoicing, shipping, email, ERP/CRM, and machine/IoT connections. Persist delivery/sync state, external identifiers, retry policy, and error detail without leaking credentials.

### Version and compatibility baseline

At implementation start, pin the latest stable patch releases compatible with these major targets. The current Spring Boot system requirements document lists Spring Boot 4.1.1 as compatible through Java 26 and Maven 3.6.3 or later. Angular 22's published compatibility table specifies Node.js `^22.22.3`, `^24.15.0`, or `^26.0.0`, TypeScript `>=6.0.0 <6.1.0`, and RxJS `^6.5.3` or `^7.4.0`. MySQL 8.4 is the LTS baseline. Confirm versions again when implementation begins and commit lockfiles/wrapper configuration.

## 8. Quality attributes

- **NFR-001 Availability:** Application and database health are observable through health checks and structured logs.
- **NFR-002 Performance:** For a typical class-project deployment with up to 50 concurrent users and 100,000 transaction rows, common list/detail requests should complete within 2 seconds at the 95th percentile, excluding network latency and exports.
- **NFR-003 Security:** Enforce authentication and server-side authorization; validate all inputs; use parameterized persistence; protect against common web vulnerabilities; use HTTPS in deployed environments; avoid exposing secrets or sensitive data in logs. Tenant isolation must be verified at every access path. Protect payment, payroll, employee, and bank data with least-privilege access and encryption in transit and at rest where supported.
- **NFR-004 Data integrity:** Use database constraints and transactional service operations to prevent invalid state, duplicate business numbers, negative stock, and inconsistent financial balances.
- **NFR-005 Usability:** Provide consistent navigation, loading/empty/error states, confirmation for irreversible actions, and actionable validation feedback.
- **NFR-006 Accessibility:** Target WCAG 2.2 AA for core flows where practical, including keyboard operation, labels, contrast, and focus visibility.
- **NFR-007 Maintainability:** Organize backend by business domain, use DTOs rather than exposing persistence entities, centralize error handling, document setup and API behavior, and keep business rules covered by automated tests.
- **NFR-008 Backup/recovery:** Document MySQL backup and restore steps. A deployed instance must have a tested backup schedule appropriate to the project environment.
- **NFR-009 Auditability:** Preserve transaction history and log changes to quantities, statuses, invoice/payment records, payroll runs, ledger postings, integration submissions, and permissions with actor, tenant/company, and time.

## 9. Acceptance criteria

The first release is acceptable when all of the following are demonstrated in a clean local setup:

1. A developer can follow the README to configure prerequisites, start MySQL, apply migrations, run the Spring Boot API and Angular UI, and sign in with documented seeded development accounts.
2. An administrator can manage users and role assignments; unauthorized users receive a backend denial for restricted operations.
3. Sales can create and confirm an order; a planner can generate manufacturing demand from a BOM revision and see the correct component shortage calculation.
4. Purchasing can issue a PO and receive materials; inventory movements and available stock update exactly once, including when the UI retries a request.
5. Production can report output and material use; inventory and manufacturing status update consistently and users can inspect the history.
6. Inventory Clerk can partially or fully ship the order; shipped quantities cannot exceed order quantities.
7. Finance can issue an invoice for shipped items, record partial and final payments, and see the correct outstanding balance and status.
8. Dashboard, list filters, pagination, and CSV exports return data consistent with the underlying authorized records.
9. Core validations, permissions, status transitions, BOM calculations, stock updates, invoice totals, and payment balances have automated test coverage and run through the documented Maven/frontend commands.
10. API documentation, schema migrations, sample data, environment configuration guidance, and backup/restore instructions are included in the repository.
11. Work-center capacity scheduling shows conflicts and projected completion dates; shop-floor terminal/mobile flows can scan an item and submit an auditable production update.
12. Tenant/company isolation is demonstrated with users from separate tenants, including API, report, export, and background-job checks; authorized consolidated reports respect company access.
13. Payment, bank, tax/e-invoicing, carrier, email, ERP/CRM, and IoT integrations each have a documented adapter/configuration path, retry/error status, and sandbox or mock-based acceptance scenario. Live provider certification is completed for each provider selected for deployment.
14. A bank statement can be imported and matched to payments, with unmatched entries retained for finance review and reconciliation history available.
15. An approved invoice can be submitted through the configured tax/e-invoice adapter, and the response or rejection is visible with its external reference.
16. General-ledger posting creates balanced entries; payroll can be reviewed, approved, and posted using the selected jurisdiction configuration. Corrections are auditable.
17. Carrier status updates and customer/supplier email delivery attempts are visible, and failed deliveries can be retried without duplicate business transactions.
18. Native mobile clients support the specified role-scoped workflows and barcode scanning, including clear handling of offline or conflicting updates.

## 10. Key assumptions and decisions to confirm

- All capabilities listed in the expanded scope are included. Provider-specific live connections use replaceable adapters; each deployment must select and configure the relevant providers.
- The application supports multiple tenants and multiple companies per tenant. Each tenant's data is isolated; inter-company consolidation is permission-controlled.
- The first implementation should provide sandbox or mock adapters so workflows can be developed and demonstrated before provider credentials are available. Live integrations require provider selection, credentials, and any applicable sandbox certification.
- Exact tax jurisdiction, e-invoicing network, payroll rules, accounting standards, default currencies, tax rules, and rounding policy must be selected before financial acceptance testing. This document does not define jurisdiction-specific compliance rules.
- Payment card data is handled by the payment provider; OrderCraft stores references and status, not raw card credentials.
- Manufacturing scheduling includes finite work-center capacity. Detailed machine protocols, supported mobile platforms, and barcode label formats are finalized during implementation planning.
- Inventory uses a perpetual movement ledger with on-hand, reserved, and available quantities. Negative inventory is blocked by default.
- The initial deployment target, expected tenant/company/user counts, data residency, retention policy, recovery objectives, and licensing constraints for integrations should be agreed before production deployment.

## 11. Official technology references

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Angular version compatibility](https://angular.dev/reference/versions)
- [MySQL 8.4 release model](https://dev.mysql.com/doc/refman/8.4/en/mysql-releases.html)





