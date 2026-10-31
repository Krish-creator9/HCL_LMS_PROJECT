ALTER TABLE order_line ADD COLUMN unit_price DECIMAL(14,2) NOT NULL DEFAULT 0;
ALTER TABLE order_line ADD COLUMN currency_code CHAR(3) NOT NULL DEFAULT 'USD';

CREATE TABLE shipment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL, status VARCHAR(24) NOT NULL, tracking_number VARCHAR(120),
  idempotency_key VARCHAR(120) NOT NULL, shipped_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_shipment_key(tenant_id,company_id,idempotency_key),
  KEY ix_shipment_order(tenant_id,company_id,order_id)
);
CREATE TABLE shipment_line (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, shipment_id BIGINT NOT NULL, order_line_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL, sku_snapshot VARCHAR(60) NOT NULL, name_snapshot VARCHAR(160) NOT NULL,
  quantity DECIMAL(14,3) NOT NULL, unit_price DECIMAL(14,2) NOT NULL, currency_code CHAR(3) NOT NULL
);
CREATE TABLE invoice (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  invoice_no VARCHAR(40) NOT NULL, shipment_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL, invoice_date DATE NOT NULL, due_date DATE NOT NULL, currency_code CHAR(3) NOT NULL,
  subtotal DECIMAL(14,2) NOT NULL, discount_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0, tax_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  total DECIMAL(14,2) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_invoice_no(tenant_id,company_id,invoice_no),
  UNIQUE KEY uq_invoice_shipment(tenant_id,company_id,shipment_id)
);
CREATE TABLE invoice_line (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, invoice_id BIGINT NOT NULL, shipment_line_id BIGINT NOT NULL,
  sku_snapshot VARCHAR(60) NOT NULL, description_snapshot VARCHAR(160) NOT NULL,
  quantity DECIMAL(14,3) NOT NULL, unit_price DECIMAL(14,2) NOT NULL,
  line_subtotal DECIMAL(14,2) NOT NULL, discount_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0, tax_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  line_total DECIMAL(14,2) NOT NULL, currency_code CHAR(3) NOT NULL
);
CREATE TABLE invoice_payment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  invoice_id BIGINT NOT NULL, event_type VARCHAR(16) NOT NULL, amount DECIMAL(14,2) NOT NULL,
  payment_method VARCHAR(32) NOT NULL, reference VARCHAR(160), reverses_payment_id BIGINT,
  idempotency_key VARCHAR(120) NOT NULL, recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_payment_key(tenant_id,company_id,idempotency_key),
  KEY ix_payment_invoice(tenant_id,company_id,invoice_id)
);
CREATE TABLE gl_account (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  account_code VARCHAR(24) NOT NULL, name VARCHAR(120) NOT NULL, account_type VARCHAR(24) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE, system_account BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE KEY uq_gl_account(tenant_id,company_id,account_code)
);
CREATE TABLE posting_rule (
  tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL, rule_code VARCHAR(32) NOT NULL,
  account_id BIGINT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY(tenant_id,company_id,rule_code)
);
CREATE TABLE finance_settings (
  tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL, currency_code CHAR(3) NOT NULL DEFAULT 'USD',
  default_tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0, tax_inclusive BOOLEAN NOT NULL DEFAULT FALSE,
  invoice_prefix VARCHAR(12) NOT NULL DEFAULT 'INV', payment_term_days INT NOT NULL DEFAULT 30,
  tax_jurisdiction VARCHAR(80), PRIMARY KEY(tenant_id,company_id)
);
CREATE TABLE accounting_period (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  period_name VARCHAR(32) NOT NULL, starts_on DATE NOT NULL, ends_on DATE NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  UNIQUE KEY uq_accounting_period(tenant_id,company_id,period_name)
);
CREATE TABLE journal_entry (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  journal_no VARCHAR(40) NOT NULL, entry_date DATE NOT NULL, memo VARCHAR(255) NOT NULL,
  source_type VARCHAR(32), source_id BIGINT, status VARCHAR(16) NOT NULL,
  reverses_journal_id BIGINT, idempotency_key VARCHAR(120) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_journal_no(tenant_id,company_id,journal_no),
  UNIQUE KEY uq_journal_key(tenant_id,company_id,idempotency_key),
  KEY ix_journal_source(tenant_id,company_id,source_type,source_id)
);
CREATE TABLE journal_line (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, journal_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
  memo VARCHAR(255), debit DECIMAL(14,2) NOT NULL DEFAULT 0, credit DECIMAL(14,2) NOT NULL DEFAULT 0,
  currency_code CHAR(3) NOT NULL
);
CREATE TABLE bank_statement (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  statement_ref VARCHAR(120) NOT NULL, currency_code CHAR(3) NOT NULL, imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_bank_statement(tenant_id,company_id,statement_ref)
);
CREATE TABLE bank_statement_line (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  statement_id BIGINT NOT NULL, external_ref VARCHAR(160) NOT NULL,
  transaction_date DATE NOT NULL, description VARCHAR(255), amount DECIMAL(14,2) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'REVIEW', matched_payment_id BIGINT,
  UNIQUE KEY uq_bank_line(statement_id,external_ref)
);
CREATE TABLE reconciliation_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  bank_line_id BIGINT NOT NULL, action VARCHAR(16) NOT NULL, payment_id BIGINT, note VARCHAR(255),
  actor_id BIGINT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE adapter_transaction (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  adapter_type VARCHAR(32) NOT NULL, source_type VARCHAR(32) NOT NULL, source_id BIGINT NOT NULL,
  external_ref VARCHAR(160), status VARCHAR(24) NOT NULL, request_json JSON, response_json JSON, error_text VARCHAR(500),
  retry_count INT NOT NULL DEFAULT 0, idempotency_key VARCHAR(120) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_adapter_key(tenant_id,company_id,adapter_type,idempotency_key),
  KEY ix_adapter_source(tenant_id,company_id,source_type,source_id)
);

CREATE TABLE work_center (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  code VARCHAR(40) NOT NULL, name VARCHAR(120) NOT NULL, capacity_minutes_per_day INT NOT NULL DEFAULT 480,
  minutes_per_unit INT NOT NULL DEFAULT 60, active BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uq_work_center(tenant_id,company_id,code)
);
CREATE TABLE work_shift (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  work_center_id BIGINT NOT NULL, day_of_week TINYINT NOT NULL, starts_at TIME NOT NULL, ends_at TIME NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE KEY uq_work_shift(work_center_id,day_of_week,starts_at)
);
CREATE TABLE work_schedule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  work_order_id BIGINT NOT NULL, work_center_id BIGINT NOT NULL, starts_at DATETIME NOT NULL,
  ends_at DATETIME NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
  idempotency_key VARCHAR(120) NOT NULL, created_by BIGINT,
  UNIQUE KEY uq_schedule_key(tenant_id,company_id,idempotency_key),
  KEY ix_schedule_capacity(tenant_id,company_id,work_center_id,starts_at,ends_at)
);
CREATE TABLE machine_device (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  device_code VARCHAR(64) NOT NULL, name VARCHAR(120) NOT NULL, work_center_id BIGINT,
  active BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE KEY uq_device(tenant_id,company_id,device_code)
);
CREATE TABLE machine_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  device_id BIGINT NOT NULL, source_event_id VARCHAR(160) NOT NULL, event_type VARCHAR(40) NOT NULL,
  source_timestamp DATETIME NOT NULL, received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  payload_json JSON NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
  retry_count INT NOT NULL DEFAULT 0, error_text VARCHAR(500),
  UNIQUE KEY uq_machine_event(tenant_id,company_id,device_id,source_event_id)
);
CREATE TABLE email_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  template_code VARCHAR(40) NOT NULL, subject_template VARCHAR(180) NOT NULL, body_template TEXT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE KEY uq_email_template(tenant_id,company_id,template_code)
);
CREATE TABLE email_delivery (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL, source_type VARCHAR(32) NOT NULL, source_id BIGINT NOT NULL,
  recipient VARCHAR(190) NOT NULL, subject_snapshot VARCHAR(180) NOT NULL, body_snapshot TEXT NOT NULL,
  status VARCHAR(16) NOT NULL, provider_ref VARCHAR(160), error_text VARCHAR(500), retry_count INT NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(120) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_email_delivery(tenant_id,company_id,idempotency_key)
);
CREATE TABLE carrier_shipment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  shipment_id BIGINT NOT NULL, adapter_code VARCHAR(32) NOT NULL, status VARCHAR(24) NOT NULL,
  external_ref VARCHAR(160), tracking_number VARCHAR(160), label_payload TEXT, request_json JSON, response_json JSON,
  idempotency_key VARCHAR(120) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_carrier_shipment(tenant_id,company_id,shipment_id,adapter_code),
  UNIQUE KEY uq_carrier_key(tenant_id,company_id,idempotency_key)
);
CREATE TABLE carrier_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  carrier_shipment_id BIGINT NOT NULL, source_event_id VARCHAR(160) NOT NULL, status VARCHAR(24) NOT NULL,
  source_timestamp DATETIME NOT NULL, processing_status VARCHAR(16) NOT NULL DEFAULT 'APPLIED',
  payload_json JSON NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_carrier_event(tenant_id,company_id,carrier_shipment_id,source_event_id)
);
CREATE TABLE erp_sync_job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
  adapter_code VARCHAR(32) NOT NULL, source_type VARCHAR(32) NOT NULL, source_id BIGINT NOT NULL,
  external_id VARCHAR(160), payload_json JSON NOT NULL, status VARCHAR(16) NOT NULL, error_text VARCHAR(500),
  retry_count INT NOT NULL DEFAULT 0, idempotency_key VARCHAR(120) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_erp_sync_key(tenant_id,company_id,adapter_code,idempotency_key),
  UNIQUE KEY uq_erp_external(tenant_id,company_id,adapter_code,external_id)
);
CREATE TABLE scan_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL, actor_id BIGINT,
  barcode_value VARCHAR(200) NOT NULL, entity_type VARCHAR(32), record_id BIGINT, action_type VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL, detail_json JSON, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY ix_scan_scope(tenant_id,company_id,created_at)
);
