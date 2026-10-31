package com.ordercraft.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PeopleController {
  private static final BigDecimal ZERO = new BigDecimal("0.00");
  private final JdbcTemplate db;

  public PeopleController(JdbcTemplate db) { this.db = db; }

  private long actorId(UserContext user) {
    String principal = SecurityContextHolder.getContext().getAuthentication() == null
      ? "" : SecurityContextHolder.getContext().getAuthentication().getName();
    try { return Long.parseLong(principal); } catch (RuntimeException ignored) { }
    Long id = db.query("SELECT id FROM user_account WHERE tenant_id=? AND email=?",
      rs -> rs.next() ? rs.getLong(1) : null, user.tenantId(), principal);
    if (id == null) throw ApiException.forbidden("The authenticated account could not be resolved");
    return id;
  }

  private void allow(UserContext user, String... roles) {
    if (!Set.of(roles).contains(user.role())) throw ApiException.forbidden("This action is not allowed for the active role");
  }

  private void peopleAudit(UserContext user, long companyId, String eventType, String entityType, Long recordId, String note) {
    db.update("INSERT INTO people_audit_event(tenant_id,company_id,actor_id,event_type,entity_type,record_id,note) VALUES(?,?,?,?,?,?,?)", user.tenantId(), companyId, actorId(user), eventType, entityType, recordId, note);
  }

  private long insert(String sql, Object... args) {
    db.update(sql, args);
    Long id = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    if (id == null) throw ApiException.conflict("Could not create the requested record");
    return id;
  }

  private String currency(Object raw) {
    String code = raw == null ? "" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
    if (!code.matches("[A-Z]{3}")) throw ApiException.bad("Currency must be a three-letter ISO code");
    return code;
  }

  private BigDecimal amount(Object value, String name) {
    try { return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP); }
    catch (RuntimeException e) { throw ApiException.bad(name + " must be a valid amount"); }
  }

  private LocalDate date(Object value, String name) {
    try { return LocalDate.parse(String.valueOf(value)); }
    catch (RuntimeException e) { throw ApiException.bad(name + " must be an ISO date"); }
  }

  private Map<String,Object> companyProfile(UserContext user, long companyId) {
    db.update("INSERT IGNORE INTO company_finance_profile(tenant_id,company_id,base_currency,fiscal_year_start_month) SELECT tenant_id,id,'USD',1 FROM company WHERE tenant_id=? AND id=?", user.tenantId(), companyId);
    return db.queryForMap("SELECT company_id companyId,base_currency baseCurrency,tax_profile taxProfile,fiscal_year_start_month fiscalYearStartMonth FROM company_finance_profile WHERE tenant_id=? AND company_id=?", user.tenantId(), companyId);
  }

  @GetMapping("/companies")
  public List<Map<String,Object>> companies(@AuthenticationPrincipal UserContext user) {
    long actor = actorId(user);
    return db.queryForList("SELECT c.id,c.code,c.name,a.role_name role,p.base_currency baseCurrency,p.tax_profile taxProfile FROM company c JOIN user_company_assignment a ON a.company_id=c.id LEFT JOIN company_finance_profile p ON p.company_id=c.id AND p.tenant_id=c.tenant_id WHERE c.tenant_id=? AND a.user_id=? ORDER BY c.name", user.tenantId(), actor);
  }

  public record CompanyRequest(String code, String name, String baseCurrency, String taxProfile, Integer fiscalYearStartMonth) {}

  @PostMapping("/admin/companies") @Transactional
  public Map<String,Object> createCompany(@AuthenticationPrincipal UserContext user, @RequestBody CompanyRequest request) {
    allow(user, "ADMIN");
    if (request.code() == null || request.code().isBlank() || request.name() == null || request.name().isBlank()) throw ApiException.bad("Company code and name are required");
    long actor = actorId(user);
    String code = request.code().trim().toUpperCase(Locale.ROOT);
    long id = insert("INSERT INTO company(tenant_id,code,name) VALUES(?,?,?)", user.tenantId(), code, request.name().trim());
    db.update("INSERT INTO user_company_assignment(user_id,company_id,role_name) VALUES(?,?,'ADMIN')", actor, id);
    String currency = request.baseCurrency() == null || request.baseCurrency().isBlank() ? "USD" : currency(request.baseCurrency());
    int fiscalStart = request.fiscalYearStartMonth() == null ? 1 : request.fiscalYearStartMonth();
    if (fiscalStart < 1 || fiscalStart > 12) throw ApiException.bad("Fiscal year start month must be between 1 and 12");
    db.update("INSERT INTO company_finance_profile(tenant_id,company_id,base_currency,tax_profile,fiscal_year_start_month) VALUES(?,?,?,?,?)", user.tenantId(), id, currency, request.taxProfile(), fiscalStart);
    peopleAudit(user, id, "COMPANY_CREATED", "company", id, "Company books initialized in " + currency);
    return Map.of("id", id, "code", code, "name", request.name().trim(), "baseCurrency", currency);
  }

  @GetMapping("/company/finance-profile")
  public Map<String,Object> financeProfile(@AuthenticationPrincipal UserContext user) {
    allow(user, "ADMIN", "MANAGER", "FINANCE", "READ_ONLY_MANAGER", "VIEWER");
    return companyProfile(user, user.companyId());
  }

  @PostMapping("/company/finance-profile") @Transactional
  public Map<String,Object> updateFinanceProfile(@AuthenticationPrincipal UserContext user, @RequestBody CompanyRequest request) {
    allow(user, "ADMIN", "FINANCE");
    String code = currency(request.baseCurrency());
    int fiscalStart = request.fiscalYearStartMonth() == null ? 1 : request.fiscalYearStartMonth();
    if (fiscalStart < 1 || fiscalStart > 12) throw ApiException.bad("Fiscal year start month must be between 1 and 12");
    companyProfile(user, user.companyId());
    db.update("UPDATE company_finance_profile SET base_currency=?,tax_profile=?,fiscal_year_start_month=? WHERE tenant_id=? AND company_id=?", code, request.taxProfile(), fiscalStart, user.tenantId(), user.companyId());
    db.update("UPDATE finance_settings SET currency_code=? WHERE tenant_id=? AND company_id=?", code, user.tenantId(), user.companyId());
    peopleAudit(user, user.companyId(), "COMPANY_FINANCE_PROFILE_UPDATED", "company_finance_profile", user.companyId(), "Currency=" + code + "; fiscal year start month=" + fiscalStart);
    return companyProfile(user, user.companyId());
  }

  public record EmployeeRequest(String employeeCode, String firstName, String lastName, String email,
      String department, String payType, BigDecimal payRate, BigDecimal taxWithholdingRate) {}

  @GetMapping("/payroll/employees")
  public List<Map<String,Object>> employees(@AuthenticationPrincipal UserContext user) {
    allow(user, "ADMIN", "MANAGER", "FINANCE", "READ_ONLY_MANAGER", "VIEWER");
    return db.queryForList("SELECT id,employee_code employeeCode,first_name firstName,last_name lastName,email,department,pay_type payType,pay_rate payRate,tax_withholding_rate taxWithholdingRate,active FROM employee WHERE tenant_id=? AND company_id=? ORDER BY last_name,first_name", user.tenantId(), user.companyId());
  }

  @PostMapping("/payroll/employees") @Transactional
  public Map<String,Object> createEmployee(@AuthenticationPrincipal UserContext user, @RequestBody EmployeeRequest request) {
    allow(user, "ADMIN", "MANAGER", "FINANCE");
    if (request.employeeCode() == null || request.employeeCode().isBlank() || request.firstName() == null || request.lastName() == null) throw ApiException.bad("Employee code and name are required");
    String type = request.payType() == null ? "SALARY" : request.payType().toUpperCase(Locale.ROOT);
    if (!Set.of("SALARY", "HOURLY").contains(type)) throw ApiException.bad("Pay type must be SALARY or HOURLY");
    BigDecimal rate = request.payRate() == null ? ZERO : request.payRate().setScale(2, RoundingMode.HALF_UP);
    BigDecimal withholding = request.taxWithholdingRate() == null ? BigDecimal.ZERO : request.taxWithholdingRate();
    if (rate.signum() < 0 || withholding.signum() < 0 || withholding.compareTo(new BigDecimal("100")) > 0) throw ApiException.bad("Pay rate and withholding must be non-negative; withholding cannot exceed 100 percent");
    long id = insert("INSERT INTO employee(tenant_id,company_id,employee_code,first_name,last_name,email,department,pay_type,pay_rate,tax_withholding_rate) VALUES(?,?,?,?,?,?,?,?,?,?)", user.tenantId(), user.companyId(), request.employeeCode().trim(), request.firstName().trim(), request.lastName().trim(), request.email(), request.department(), type, rate, withholding);
    peopleAudit(user, user.companyId(), "EMPLOYEE_CREATED", "employee", id, "Employee code=" + request.employeeCode().trim() + "; pay type=" + type);
    return Map.of("id", id, "employeeCode", request.employeeCode().trim(), "payType", type);
  }

  public record PayrollLineRequest(long employeeId, BigDecimal regularHours, BigDecimal overtimeHours, BigDecimal grossAmount, String note) {}
  public record PayrollRunRequest(LocalDate periodStart, LocalDate periodEnd, List<PayrollLineRequest> lines) {}

  @GetMapping("/payroll/runs")
  public List<Map<String,Object>> payrollRuns(@AuthenticationPrincipal UserContext user) {
    allow(user, "ADMIN", "MANAGER", "FINANCE", "READ_ONLY_MANAGER", "VIEWER");
    return db.queryForList("SELECT r.id,r.period_start periodStart,r.period_end periodEnd,r.status,r.gross_total grossTotal,r.withholding_total withholdingTotal,r.net_total netTotal,r.created_at createdAt,COUNT(l.id) employees FROM payroll_run r LEFT JOIN payroll_line l ON l.payroll_run_id=r.id AND l.tenant_id=r.tenant_id AND l.company_id=r.company_id WHERE r.tenant_id=? AND r.company_id=? GROUP BY r.id ORDER BY r.period_end DESC,r.id DESC", user.tenantId(), user.companyId());
  }

  @PostMapping("/payroll/runs") @Transactional
  public Map<String,Object> createPayrollRun(@AuthenticationPrincipal UserContext user, @RequestBody PayrollRunRequest request) {
    allow(user, "ADMIN", "MANAGER", "FINANCE");
    if (request.periodStart() == null || request.periodEnd() == null || request.periodEnd().isBefore(request.periodStart()) || request.lines() == null || request.lines().isEmpty()) throw ApiException.bad("A valid pay period and at least one employee line are required");
    long actor = actorId(user);
    long runId = insert("INSERT INTO payroll_run(tenant_id,company_id,period_start,period_end,status,created_by) VALUES(?,?,?,?,'DRAFT',?)", user.tenantId(), user.companyId(), request.periodStart(), request.periodEnd(), actor);
    BigDecimal grossTotal = ZERO, withholdingTotal = ZERO, netTotal = ZERO;
    for (PayrollLineRequest line : request.lines()) {
      Map<String,Object> employee = db.query("SELECT pay_type,pay_rate,tax_withholding_rate,active FROM employee WHERE id=? AND tenant_id=? AND company_id=?", rs -> rs.next() ? Map.of("payType", rs.getString("pay_type"), "payRate", rs.getBigDecimal("pay_rate"), "withholdingRate", rs.getBigDecimal("tax_withholding_rate"), "active", rs.getBoolean("active")) : null, line.employeeId(), user.tenantId(), user.companyId());
      if (employee == null || !Boolean.TRUE.equals(employee.get("active"))) throw ApiException.bad("Every payroll employee must be active in the selected company");
      BigDecimal regular = line.regularHours() == null ? BigDecimal.ZERO : line.regularHours();
      BigDecimal overtime = line.overtimeHours() == null ? BigDecimal.ZERO : line.overtimeHours();
      if (regular.signum() < 0 || overtime.signum() < 0) throw ApiException.bad("Hours cannot be negative");
      BigDecimal rate = (BigDecimal) employee.get("payRate");
      BigDecimal gross;
      if (line.grossAmount() != null) gross = line.grossAmount().setScale(2, RoundingMode.HALF_UP);
      else if ("HOURLY".equals(employee.get("payType"))) gross = regular.multiply(rate).add(overtime.multiply(rate).multiply(new BigDecimal("1.5"))).setScale(2, RoundingMode.HALF_UP);
      else gross = rate.setScale(2, RoundingMode.HALF_UP);
      if (gross.signum() < 0) throw ApiException.bad("Gross pay cannot be negative");
      BigDecimal withholding = gross.multiply((BigDecimal) employee.get("withholdingRate")).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
      BigDecimal net = gross.subtract(withholding);
      db.update("INSERT INTO payroll_line(tenant_id,company_id,payroll_run_id,employee_id,regular_hours,overtime_hours,gross_amount,withholding_amount,net_amount,note) VALUES(?,?,?,?,?,?,?,?,?,?)", user.tenantId(), user.companyId(), runId, line.employeeId(), regular, overtime, gross, withholding, net, line.note());
      grossTotal = grossTotal.add(gross); withholdingTotal = withholdingTotal.add(withholding); netTotal = netTotal.add(net);
    }
    db.update("UPDATE payroll_run SET gross_total=?,withholding_total=?,net_total=? WHERE id=? AND tenant_id=? AND company_id=?", grossTotal, withholdingTotal, netTotal, runId, user.tenantId(), user.companyId());
    payrollAudit(user, runId, "DRAFT_CREATED", "Manual payroll draft created");
    return Map.of("id", runId, "status", "DRAFT", "grossTotal", grossTotal, "withholdingTotal", withholdingTotal, "netTotal", netTotal);
  }

  @GetMapping("/payroll/runs/{id}")
  public Map<String,Object> payrollRun(@PathVariable long id, @AuthenticationPrincipal UserContext user) {
    allow(user, "ADMIN", "MANAGER", "FINANCE", "READ_ONLY_MANAGER", "VIEWER");
    Map<String,Object> run = db.query("SELECT id,period_start periodStart,period_end periodEnd,status,gross_total grossTotal,withholding_total withholdingTotal,net_total netTotal,created_by createdBy,reviewed_by reviewedBy,approved_by approvedBy,journal_entry_id journalEntryId FROM payroll_run WHERE id=? AND tenant_id=? AND company_id=?", rs -> rs.next() ? new HashMap<>(Map.of("id", rs.getLong("id"), "periodStart", rs.getDate("periodStart").toLocalDate(), "periodEnd", rs.getDate("periodEnd").toLocalDate(), "status", rs.getString("status"), "grossTotal", rs.getBigDecimal("grossTotal"), "withholdingTotal", rs.getBigDecimal("withholdingTotal"), "netTotal", rs.getBigDecimal("netTotal"))) : null, id, user.tenantId(), user.companyId());
    if (run == null) throw ApiException.bad("Payroll run was not found in the active company");
    run.put("lines", db.queryForList("SELECT l.employee_id employeeId,e.employee_code employeeCode,CONCAT(e.first_name,' ',e.last_name) employee,e.department,l.regular_hours regularHours,l.overtime_hours overtimeHours,l.gross_amount grossAmount,l.withholding_amount withholdingAmount,l.net_amount netAmount,l.note FROM payroll_line l JOIN employee e ON e.id=l.employee_id AND e.tenant_id=l.tenant_id AND e.company_id=l.company_id WHERE l.payroll_run_id=? AND l.tenant_id=? AND l.company_id=? ORDER BY e.last_name,e.first_name", id, user.tenantId(), user.companyId()));
    run.put("audit", db.queryForList("SELECT event_type eventType,note,created_at createdAt FROM payroll_audit_event WHERE payroll_run_id=? AND tenant_id=? AND company_id=? ORDER BY id", id, user.tenantId(), user.companyId()));
    return run;
  }

  private Map<String,Object> lockedRun(long id, UserContext user) {
    Map<String,Object> run = db.query("SELECT id,status,created_by createdBy,reviewed_by reviewedBy,approved_by approvedBy,gross_total grossTotal,withholding_total withholdingTotal,net_total netTotal,period_end periodEnd FROM payroll_run WHERE id=? AND tenant_id=? AND company_id=? FOR UPDATE", rs -> {
      if (!rs.next()) return null;
      Map<String,Object> row = new HashMap<>(); row.put("id", rs.getLong("id")); row.put("status", rs.getString("status"));
      row.put("createdBy", rs.getLong("createdBy")); row.put("reviewedBy", (Long)rs.getObject("reviewedBy")); row.put("approvedBy", (Long)rs.getObject("approvedBy"));
      row.put("grossTotal", rs.getBigDecimal("grossTotal")); row.put("withholdingTotal", rs.getBigDecimal("withholdingTotal"));
      row.put("netTotal", rs.getBigDecimal("netTotal")); row.put("periodEnd", rs.getDate("periodEnd").toLocalDate()); return row;
    }, id, user.tenantId(), user.companyId());
    if (run == null) throw ApiException.bad("Payroll run was not found in the active company");
    return run;
  }

  private void payrollAudit(UserContext user, long runId, String type, String note) {
    db.update("INSERT INTO payroll_audit_event(tenant_id,company_id,payroll_run_id,actor_id,event_type,note) VALUES(?,?,?,?,?,?)", user.tenantId(), user.companyId(), runId, actorId(user), type, note);
  }

  @PostMapping("/payroll/runs/{id}/review") @Transactional
  public Map<String,Object> reviewPayroll(@PathVariable long id, @AuthenticationPrincipal UserContext user, @RequestBody(required=false) Map<String,Object> body) {
    allow(user, "ADMIN", "MANAGER", "FINANCE"); long actor = actorId(user); Map<String,Object> run = lockedRun(id, user);
    if (!"DRAFT".equals(run.get("status"))) throw ApiException.conflict("Only a draft payroll run can be reviewed");
    if (((Number)run.get("createdBy")).longValue() == actor) throw ApiException.forbidden("The creator cannot review their own payroll run");
    String note = body == null ? "Reviewed" : String.valueOf(body.getOrDefault("note", "Reviewed"));
    db.update("UPDATE payroll_run SET status='REVIEWED',reviewed_by=? WHERE id=? AND tenant_id=? AND company_id=?", actor, id, user.tenantId(), user.companyId());
    payrollAudit(user, id, "REVIEWED", note); return Map.of("id", id, "status", "REVIEWED");
  }

  @PostMapping("/payroll/runs/{id}/approve") @Transactional
  public Map<String,Object> approvePayroll(@PathVariable long id, @AuthenticationPrincipal UserContext user, @RequestBody(required=false) Map<String,Object> body) {
    allow(user, "ADMIN", "FINANCE"); long actor = actorId(user); Map<String,Object> run = lockedRun(id, user);
    if (!"REVIEWED".equals(run.get("status"))) throw ApiException.conflict("Payroll must be reviewed before approval");
    if (((Number)run.get("createdBy")).longValue() == actor || ((Number)run.get("reviewedBy")).longValue() == actor) throw ApiException.forbidden("Approval must be completed by a user other than the creator and reviewer");
    db.update("UPDATE payroll_run SET status='APPROVED',approved_by=?,approved_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND company_id=?", actor, id, user.tenantId(), user.companyId());
    payrollAudit(user, id, "APPROVED", body == null ? "Approved" : String.valueOf(body.getOrDefault("note", "Approved"))); return Map.of("id", id, "status", "APPROVED");
  }

  @PostMapping("/payroll/runs/{id}/post") @Transactional
  public Map<String,Object> postPayroll(@PathVariable long id, @AuthenticationPrincipal UserContext user) {
    allow(user, "ADMIN", "FINANCE"); long actor = actorId(user); Map<String,Object> run = lockedRun(id, user);
    if ("POSTED".equals(run.get("status"))) return Map.of("id", id, "status", "POSTED", "idempotentReplay", true);
    if (!"APPROVED".equals(run.get("status"))) throw ApiException.conflict("Payroll must be approved before posting");
    if (((Number)run.get("createdBy")).longValue() == actor || ((Number)run.get("reviewedBy")).longValue() == actor || ((Number)run.get("approvedBy")).longValue() == actor) throw ApiException.forbidden("Payroll posting must be completed by a user other than the creator, reviewer, and approver");
    LocalDate journalDate = (LocalDate) run.get("periodEnd");
    String currencyCode = (String) companyProfile(user, user.companyId()).get("baseCurrency");
    long journalId = insert("INSERT INTO payroll_gl_entry(tenant_id,company_id,payroll_run_id,journal_date,memo,idempotency_key,created_by) VALUES(?,?,?,?,?,?,?)", user.tenantId(), user.companyId(), id, journalDate, "Payroll run " + id, "payroll:" + id, actor);
    BigDecimal gross = (BigDecimal)run.get("grossTotal"), withholding = (BigDecimal)run.get("withholdingTotal"), net = (BigDecimal)run.get("netTotal");
    addPayrollLine(user, journalId, "5000", "Wages expense", currencyCode, gross, ZERO);
    if (withholding.signum() > 0) addPayrollLine(user, journalId, "2100", "Withholding payable", currencyCode, ZERO, withholding);
    if (net.signum() > 0) addPayrollLine(user, journalId, "2200", "Payroll payable", currencyCode, ZERO, net);
    if (gross.compareTo(withholding.add(net)) != 0) throw ApiException.conflict("Payroll posting is not balanced");
    db.update("UPDATE payroll_run SET status='POSTED',journal_entry_id=?,posted_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND company_id=?", journalId, id, user.tenantId(), user.companyId());
    payrollAudit(user, id, "POSTED", "Posted balanced payroll journal " + journalId);
    return Map.of("id", id, "status", "POSTED", "journalEntryId", journalId, "currencyCode", currencyCode);
  }

  private void addPayrollLine(UserContext user, long journalId, String code, String name, String currencyCode, BigDecimal debit, BigDecimal credit) {
    db.update("INSERT INTO payroll_gl_line(tenant_id,company_id,journal_id,account_code,account_name,currency_code,debit,credit) VALUES(?,?,?,?,?,?,?,?)", user.tenantId(), user.companyId(), journalId, code, name, currencyCode, debit, credit);
  }

  @GetMapping("/payroll/runs/{id}/journal")
  public Map<String,Object> payrollJournal(@PathVariable long id, @AuthenticationPrincipal UserContext user) {
    allow(user, "ADMIN", "MANAGER", "FINANCE", "READ_ONLY_MANAGER");
    Map<String,Object> journal = db.query("SELECT id,journal_date journalDate,memo,status,created_by createdBy FROM payroll_gl_entry WHERE payroll_run_id=? AND tenant_id=? AND company_id=?", rs -> {
      if (!rs.next()) return null;
      Map<String,Object> row = new HashMap<>(); row.put("id", rs.getLong("id")); row.put("journalDate", rs.getDate("journalDate").toLocalDate()); row.put("memo", rs.getString("memo")); row.put("status", rs.getString("status")); row.put("createdBy", rs.getLong("createdBy")); return row;
    }, id, user.tenantId(), user.companyId());
    if (journal == null) throw ApiException.bad("Posted payroll journal was not found in the active company");
    journal.put("lines", db.queryForList("SELECT account_code accountCode,account_name accountName,currency_code currencyCode,debit,credit FROM payroll_gl_line WHERE journal_id=? AND tenant_id=? AND company_id=? ORDER BY id", ((Number)journal.get("id")).longValue(), user.tenantId(), user.companyId()));
    return journal;
  }

  public record ExchangeRateRequest(String fromCurrency, String toCurrency, LocalDate effectiveOn, BigDecimal rate) {}

  @PostMapping("/finance/exchange-rates") @Transactional
  public Map<String,Object> saveExchangeRate(@AuthenticationPrincipal UserContext user, @RequestBody ExchangeRateRequest request) {
    allow(user, "ADMIN", "FINANCE");
    String from = currency(request.fromCurrency()), to = currency(request.toCurrency());
    if (from.equals(to) || request.effectiveOn() == null || request.rate() == null || request.rate().signum() <= 0) throw ApiException.bad("Choose different currencies, an effective date, and a positive conversion rate");
    long actor = actorId(user);
    db.update("INSERT INTO finance_exchange_rate(tenant_id,from_currency,to_currency,effective_on,rate,created_by) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE rate=VALUES(rate),created_by=VALUES(created_by)", user.tenantId(), from, to, request.effectiveOn(), request.rate(), actor);
    peopleAudit(user, user.companyId(), "EXCHANGE_RATE_SAVED", "finance_exchange_rate", null, from + " to " + to + " effective " + request.effectiveOn());
    return Map.of("fromCurrency", from, "toCurrency", to, "effectiveOn", request.effectiveOn(), "rate", request.rate());
  }

  @GetMapping("/reports/consolidated")
  public Map<String,Object> consolidatedReport(@AuthenticationPrincipal UserContext user,
      @RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam String currency) {
    allow(user, "ADMIN", "MANAGER", "FINANCE", "READ_ONLY_MANAGER");
    if (to.isBefore(from)) throw ApiException.bad("Report end date must be on or after its start date");
    String target = currency(currency); long actor = actorId(user);
    List<Map<String,Object>> companies = "ADMIN".equals(user.role())
      ? db.queryForList("SELECT id,code,name FROM company WHERE tenant_id=? ORDER BY name", user.tenantId())
      : db.queryForList("SELECT c.id,c.code,c.name FROM company c JOIN user_company_assignment a ON a.company_id=c.id WHERE c.tenant_id=? AND a.user_id=? ORDER BY c.name", user.tenantId(), actor);
    List<Map<String,Object>> rows = new ArrayList<>(); BigDecimal revenueTotal = ZERO, payrollTotal = ZERO; boolean complete = true;
    for (Map<String,Object> company : companies) {
      long companyId = ((Number)company.get("id")).longValue();
      db.update("INSERT IGNORE INTO company_finance_profile(tenant_id,company_id,base_currency,fiscal_year_start_month) VALUES(?,?, 'USD',1)", user.tenantId(), companyId);
      String base = (String)db.queryForObject("SELECT base_currency FROM company_finance_profile WHERE tenant_id=? AND company_id=?", String.class, user.tenantId(), companyId);
      Map<String,Object> sums = db.queryForMap("SELECT COALESCE((SELECT SUM(i.total) FROM invoice i WHERE i.tenant_id=? AND i.company_id=? AND i.invoice_date BETWEEN ? AND ? AND i.status<>'VOID'),0) revenue,COALESCE((SELECT SUM(r.gross_total) FROM payroll_run r WHERE r.tenant_id=? AND r.company_id=? AND r.period_end BETWEEN ? AND ? AND r.status='POSTED'),0) payrollExpense", user.tenantId(), companyId, from, to, user.tenantId(), companyId, from, to);
      BigDecimal revenue = (BigDecimal)sums.get("revenue"), payroll = (BigDecimal)sums.get("payrollExpense");
      BigDecimal factor = base.equals(target) ? BigDecimal.ONE : exchangeFactor(user.tenantId(), base, target, to);
      Map<String,Object> row = new HashMap<>(); row.put("companyId", companyId); row.put("code", company.get("code")); row.put("company", company.get("name")); row.put("baseCurrency", base); row.put("revenue", revenue); row.put("payrollExpense", payroll);
      if (factor == null) { row.put("convertedRevenue", null); row.put("convertedPayrollExpense", null); row.put("exchangeRateMissing", true); complete = false; }
      else { BigDecimal convertedRevenue = revenue.multiply(factor).setScale(2, RoundingMode.HALF_UP); BigDecimal convertedPayroll = payroll.multiply(factor).setScale(2, RoundingMode.HALF_UP); row.put("convertedRevenue", convertedRevenue); row.put("convertedPayrollExpense", convertedPayroll); row.put("exchangeRateMissing", false); revenueTotal = revenueTotal.add(convertedRevenue); payrollTotal = payrollTotal.add(convertedPayroll); }
      rows.add(row);
    }
    Map<String,Object> result = new HashMap<>(); result.put("from", from); result.put("to", to); result.put("reportingCurrency", target); result.put("companies", rows); result.put("complete", complete); result.put("revenueTotal", complete ? revenueTotal : null); result.put("payrollExpenseTotal", complete ? payrollTotal : null);
    return result;
  }

  private BigDecimal exchangeFactor(long tenantId, String from, String to, LocalDate onOrBefore) {
    BigDecimal direct = db.query("SELECT rate FROM finance_exchange_rate WHERE tenant_id=? AND from_currency=? AND to_currency=? AND effective_on<=? ORDER BY effective_on DESC LIMIT 1", rs -> rs.next() ? rs.getBigDecimal(1) : null, tenantId, from, to, onOrBefore);
    if (direct != null) return direct;
    BigDecimal inverse = db.query("SELECT rate FROM finance_exchange_rate WHERE tenant_id=? AND from_currency=? AND to_currency=? AND effective_on<=? ORDER BY effective_on DESC LIMIT 1", rs -> rs.next() ? rs.getBigDecimal(1) : null, tenantId, to, from, onOrBefore);
    return inverse == null || inverse.signum() == 0 ? null : BigDecimal.ONE.divide(inverse, 12, RoundingMode.HALF_UP);
  }
}
