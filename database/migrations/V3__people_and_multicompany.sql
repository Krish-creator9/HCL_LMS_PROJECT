CREATE TABLE company_finance_profile (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  base_currency CHAR(3) NOT NULL,
  tax_profile VARCHAR(120),
  fiscal_year_start_month INT NOT NULL DEFAULT 1,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_finance_profile_company (tenant_id, company_id)
);

CREATE TABLE employee (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  employee_code VARCHAR(40) NOT NULL,
  first_name VARCHAR(100) NOT NULL,
  last_name VARCHAR(100) NOT NULL,
  email VARCHAR(180),
  department VARCHAR(100),
  pay_type VARCHAR(20) NOT NULL DEFAULT 'SALARY',
  pay_rate DECIMAL(15,2) NOT NULL DEFAULT 0,
  tax_withholding_rate DECIMAL(8,5) NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_employee_code (tenant_id, company_id, employee_code),
  KEY ix_employee_company (tenant_id, company_id, active)
);

CREATE TABLE payroll_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  period_start DATE NOT NULL,
  period_end DATE NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  gross_total DECIMAL(15,2) NOT NULL DEFAULT 0,
  withholding_total DECIMAL(15,2) NOT NULL DEFAULT 0,
  net_total DECIMAL(15,2) NOT NULL DEFAULT 0,
  created_by BIGINT,
  reviewed_by BIGINT,
  approved_by BIGINT,
  journal_entry_id BIGINT,
  approved_at TIMESTAMP NULL,
  posted_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY ix_payroll_company_period (tenant_id, company_id, period_start, period_end),
  KEY ix_payroll_status (tenant_id, company_id, status)
);

CREATE TABLE payroll_line (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  payroll_run_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  regular_hours DECIMAL(9,2) NOT NULL DEFAULT 0,
  overtime_hours DECIMAL(9,2) NOT NULL DEFAULT 0,
  gross_amount DECIMAL(15,2) NOT NULL DEFAULT 0,
  withholding_amount DECIMAL(15,2) NOT NULL DEFAULT 0,
  net_amount DECIMAL(15,2) NOT NULL DEFAULT 0,
  note VARCHAR(500),
  UNIQUE KEY uq_payroll_employee (tenant_id, payroll_run_id, employee_id),
  KEY ix_payroll_line_employee (tenant_id, company_id, employee_id)
);

CREATE TABLE payroll_audit_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  payroll_run_id BIGINT NOT NULL,
  actor_id BIGINT,
  event_type VARCHAR(40) NOT NULL,
  note VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY ix_payroll_audit (tenant_id, payroll_run_id, created_at)
);

CREATE TABLE people_audit_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  actor_id BIGINT,
  event_type VARCHAR(40) NOT NULL,
  entity_type VARCHAR(40) NOT NULL,
  record_id BIGINT,
  note VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY ix_people_audit_scope (tenant_id, company_id, created_at)
);

CREATE TABLE consolidated_report_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  requested_by BIGINT,
  base_currency CHAR(3) NOT NULL,
  report_type VARCHAR(40) NOT NULL,
  period_start DATE NOT NULL,
  period_end DATE NOT NULL,
  payload_json JSON NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY ix_consolidated_report (tenant_id, report_type, period_start, period_end)
);

CREATE TABLE finance_exchange_rate (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  from_currency CHAR(3) NOT NULL,
  to_currency CHAR(3) NOT NULL,
  effective_on DATE NOT NULL,
  rate DECIMAL(18,8) NOT NULL,
  created_by BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_exchange_rate (tenant_id, from_currency, to_currency, effective_on),
  KEY ix_exchange_lookup (tenant_id, from_currency, to_currency, effective_on)
);

CREATE TABLE payroll_gl_entry (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  payroll_run_id BIGINT NOT NULL,
  journal_date DATE NOT NULL,
  memo VARCHAR(255) NOT NULL,
  idempotency_key VARCHAR(160) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'POSTED',
  created_by BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_payroll_gl_run (tenant_id, company_id, payroll_run_id),
  UNIQUE KEY uq_payroll_gl_idempotency (tenant_id, company_id, idempotency_key)
);

CREATE TABLE payroll_gl_line (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  journal_id BIGINT NOT NULL,
  account_code VARCHAR(32) NOT NULL,
  account_name VARCHAR(120) NOT NULL,
  currency_code CHAR(3) NOT NULL,
  debit DECIMAL(15,2) NOT NULL DEFAULT 0,
  credit DECIMAL(15,2) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY ix_payroll_gl_journal (tenant_id, company_id, journal_id)
);
