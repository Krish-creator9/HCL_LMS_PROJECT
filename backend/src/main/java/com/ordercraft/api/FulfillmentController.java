package com.ordercraft.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class FulfillmentController {
  private final JdbcTemplate db;
  private final tools.jackson.databind.ObjectMapper json;
  private final ExternalAdapters.Sandbox sandbox;
  public FulfillmentController(JdbcTemplate db, tools.jackson.databind.ObjectMapper json,ExternalAdapters.Sandbox sandbox) { this.db=db; this.json=json;this.sandbox=sandbox; }

  private long insert(String sql,Object... args) {
    KeyHolder key=new GeneratedKeyHolder();
    db.update(c->{var p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }
  private void audit(UserContext u,String event,String type,long id,Map<String,Object> detail) {
    db.update("INSERT INTO audit_event(tenant_id,company_id,actor_id,event_type,entity_type,entity_id,detail_json) VALUES(?,?,?,?,?,?,?)",
      u.tenantId(),u.companyId(),u.userId(),event,type,id,json.writeValueAsString(detail));
  }
  private Map<String,Object> scoped(String table,long id,UserContext u,boolean lock) {
    List<Map<String,Object>> rows=db.queryForList("SELECT * FROM "+table+" WHERE id=? AND tenant_id=? AND company_id=?"+(lock?" FOR UPDATE":""),id,u.tenantId(),u.companyId());
    if(rows.isEmpty()) throw ApiException.missing("Record not found");
    return rows.getFirst();
  }
  private long account(UserContext u,String code,String name,String type) {
    db.update("INSERT IGNORE INTO gl_account(tenant_id,company_id,account_code,name,account_type,system_account) VALUES(?,?,?,?,?,TRUE)",
      u.tenantId(),u.companyId(),code,name,type);
    return db.queryForObject("SELECT id FROM gl_account WHERE tenant_id=? AND company_id=? AND account_code=?",Long.class,u.tenantId(),u.companyId(),code);
  }
  private long postingAccount(UserContext u,String rule,String defaultCode,String name,String type) {
    long fallback=account(u,defaultCode,name,type);
    db.update("INSERT IGNORE INTO posting_rule(tenant_id,company_id,rule_code,account_id) VALUES(?,?,?,?)",u.tenantId(),u.companyId(),rule,fallback);
    Long id=db.query("SELECT a.id FROM posting_rule r JOIN gl_account a ON a.id=r.account_id WHERE r.tenant_id=? AND r.company_id=? AND r.rule_code=? AND r.active=TRUE AND a.active=TRUE",rs->rs.next()?rs.getLong(1):null,u.tenantId(),u.companyId(),rule);
    if(id==null)throw ApiException.conflict("Posting rule "+rule+" has no active account");
    return id;
  }
  private Map<String,Object> financeSettings(UserContext u) {
    db.update("INSERT IGNORE INTO company_finance_profile(tenant_id,company_id,base_currency,fiscal_year_start_month) SELECT tenant_id,id,'USD',1 FROM company WHERE tenant_id=? AND id=?",u.tenantId(),u.companyId());
    String base=db.queryForObject("SELECT base_currency FROM company_finance_profile WHERE tenant_id=? AND company_id=?",String.class,u.tenantId(),u.companyId());
    db.update("INSERT IGNORE INTO finance_settings(tenant_id,company_id,currency_code) VALUES(?,?,?)",u.tenantId(),u.companyId(),base);
    return db.queryForMap("SELECT currency_code currencyCode,default_tax_rate defaultTaxRate,tax_inclusive taxInclusive,invoice_prefix invoicePrefix,payment_term_days paymentTermDays,tax_jurisdiction taxJurisdiction FROM finance_settings WHERE tenant_id=? AND company_id=?",u.tenantId(),u.companyId());
  }
  private void ensurePeriod(UserContext u,LocalDate date) {
    int year=date.getYear();
    db.update("INSERT IGNORE INTO accounting_period(tenant_id,company_id,period_name,starts_on,ends_on,status) VALUES(?,?,?,?,?,'OPEN')",
      u.tenantId(),u.companyId(),String.valueOf(year),LocalDate.of(year,1,1),LocalDate.of(year,12,31));
  }
  private long journal(UserContext u,LocalDate date,String memo,String sourceType,long sourceId,
      String key,Long reverses,List<JournalLine> lines) {
    BigDecimal debit=lines.stream().map(JournalLine::debit).reduce(BigDecimal.ZERO,BigDecimal::add);
    BigDecimal credit=lines.stream().map(JournalLine::credit).reduce(BigDecimal.ZERO,BigDecimal::add);
    if(debit.signum()<=0||debit.compareTo(credit)!=0) throw ApiException.bad("Journal debits and credits must balance and be greater than zero");
    ensurePeriod(u,date);
    Integer open=db.queryForObject("SELECT COUNT(*) FROM accounting_period WHERE tenant_id=? AND company_id=? AND starts_on<=? AND ends_on>=? AND status='OPEN'",
      Integer.class,u.tenantId(),u.companyId(),date,date);
    if(open==null||open==0) throw ApiException.conflict("The accounting period for this date is closed or unavailable");
    String no="JRN-"+date.getYear()+"-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
    long id=insert("INSERT INTO journal_entry(tenant_id,company_id,journal_no,entry_date,memo,source_type,source_id,status,reverses_journal_id,idempotency_key) VALUES(?,?,?,?,?,?,?,'POSTED',?,?)",
      u.tenantId(),u.companyId(),no,date,memo,sourceType,sourceId,reverses,key);
    for(var line:lines) db.update("INSERT INTO journal_line(journal_id,account_id,memo,debit,credit,currency_code) VALUES(?,?,?,?,?,?)",
      id,line.accountId(),line.memo(),line.debit(),line.credit(),line.currency());
    return id;
  }
  private record JournalLine(long accountId,String memo,BigDecimal debit,BigDecimal credit,String currency) {}
  private String currency(String value) {
    String code=(value==null?"USD":value.trim().toUpperCase(Locale.ROOT));
    if(!code.matches("[A-Z]{3}")) throw ApiException.bad("currencyCode must be a three-letter ISO currency code");
    return code;
  }
  private void requireShipmentWrite(UserContext u) { if(!Set.of("ADMIN","MANAGER","INVENTORY").contains(u.role()))throw ApiException.forbidden("Inventory or manager access required for shipments"); }
  private void requireFinanceRead(UserContext u) { if(!Set.of("ADMIN","MANAGER","FINANCE","READ_ONLY_MANAGER").contains(u.role()))throw ApiException.forbidden("Finance access required"); }
  private BigDecimal money(BigDecimal amount) { return amount.setScale(2,RoundingMode.HALF_UP); }

  public record ShipLine(@NotNull Long orderLineId,@NotNull @DecimalMin("0.001") BigDecimal quantity) {}
  public record ShipRequest(@NotNull Long orderId,@NotNull Long warehouseId,@NotEmpty List<@Valid ShipLine> lines,
      String trackingNumber,@NotBlank String idempotencyKey) {}

  @GetMapping("/shipments")
  public List<Map<String,Object>> shipments(@AuthenticationPrincipal UserContext u) {
    return db.queryForList("SELECT s.id,s.order_id orderId,o.order_no orderNo,s.status,s.tracking_number trackingNumber,s.shipped_at shippedAt,COUNT(l.id) AS `lines`,SUM(l.quantity) quantity FROM shipment s JOIN customer_order o ON o.id=s.order_id LEFT JOIN shipment_line l ON l.shipment_id=s.id WHERE s.tenant_id=? AND s.company_id=? GROUP BY s.id ORDER BY s.id DESC",
      u.tenantId(),u.companyId());
  }
  @GetMapping("/orders/{id}/lines")
  public List<Map<String,Object>> orderLines(@PathVariable long id,@AuthenticationPrincipal UserContext u) {
    scoped("customer_order",id,u,false);
    return db.queryForList("SELECT l.id orderLineId,l.sku_snapshot sku,l.name_snapshot name,l.quantity,l.unit_snapshot unit,l.unit_price unitPrice,l.currency_code currencyCode,l.bom_revision_snapshot bomRevision,COALESCE((SELECT SUM(sl.quantity) FROM shipment_line sl JOIN shipment sh ON sh.id=sl.shipment_id WHERE sl.order_line_id=l.id AND sh.tenant_id=? AND sh.company_id=? AND sh.status='SHIPPED'),0) shipped,COALESCE((SELECT SUM(il.quantity) FROM invoice_line il JOIN invoice i ON i.id=il.invoice_id WHERE il.shipment_line_id IN (SELECT sl2.id FROM shipment_line sl2 JOIN shipment sh2 ON sh2.id=sl2.shipment_id WHERE sl2.order_line_id=l.id AND sh2.tenant_id=? AND sh2.company_id=?)),0) invoiced FROM order_line l WHERE l.order_id=? ORDER BY l.id",
      u.tenantId(),u.companyId(),u.tenantId(),u.companyId(),id);
  }
  @PostMapping("/shipments") @Transactional
  public Map<String,Object> ship(@AuthenticationPrincipal UserContext u,@Valid @RequestBody ShipRequest r) {
    requireShipmentWrite(u);
    if(r.idempotencyKey().length()>70) throw ApiException.bad("idempotencyKey is too long");
    var existing=db.queryForList("SELECT id,status FROM shipment WHERE tenant_id=? AND company_id=? AND idempotency_key=?",u.tenantId(),u.companyId(),r.idempotencyKey());
    if(!existing.isEmpty()) return Map.of("id",existing.getFirst().get("id"),"status",existing.getFirst().get("status"),"idempotentReplay",true);
    var order=scoped("customer_order",r.orderId(),u,true);
    if(!Set.of("CONFIRMED","PARTIALLY_SHIPPED").contains(String.valueOf(order.get("status")))) throw ApiException.conflict("Only confirmed, unfulfilled orders can be shipped");
    var warehouse=db.queryForList("SELECT id FROM warehouse WHERE id=? AND tenant_id=? AND company_id=?",r.warehouseId(),u.tenantId(),u.companyId());
    if(warehouse.isEmpty()) throw ApiException.bad("Warehouse is outside the active company");
    long shipmentId=insert("INSERT INTO shipment(tenant_id,company_id,order_id,status,tracking_number,idempotency_key) VALUES(?,?,?,'SHIPPED',?,?)",
      u.tenantId(),u.companyId(),r.orderId(),r.trackingNumber(),r.idempotencyKey());
    String currencyCode=null;
    for(var requested:r.lines()) {
      List<Map<String,Object>> source=db.queryForList("SELECT l.*,p.kind,o.status order_status FROM order_line l JOIN product p ON p.id=l.product_id JOIN customer_order o ON o.id=l.order_id WHERE l.id=? AND l.order_id=? AND o.tenant_id=? AND o.company_id=? FOR UPDATE",
        requested.orderLineId(),r.orderId(),u.tenantId(),u.companyId());
      if(source.isEmpty()) throw ApiException.bad("Order line is outside the selected order");
      var line=source.getFirst();
      BigDecimal previouslyShipped=db.queryForObject("SELECT COALESCE(SUM(sl.quantity),0) FROM shipment_line sl JOIN shipment s ON s.id=sl.shipment_id WHERE sl.order_line_id=? AND s.tenant_id=? AND s.company_id=? AND s.status='SHIPPED'",
        BigDecimal.class,requested.orderLineId(),u.tenantId(),u.companyId());
      BigDecimal remaining=((BigDecimal)line.get("quantity")).subtract(previouslyShipped);
      if(requested.quantity().signum()<=0||requested.quantity().compareTo(remaining)>0) throw ApiException.conflict("Shipment quantity exceeds the unshipped order quantity");
      long product=((Number)line.get("product_id")).longValue();
      db.queryForObject("SELECT id FROM product WHERE id=? AND tenant_id=? AND company_id=? FOR UPDATE",Long.class,product,u.tenantId(),u.companyId());
      BigDecimal available=db.queryForObject("SELECT COALESCE(SUM(CASE WHEN movement_type IN ('RECEIVE','FINISH','ADJUST') THEN quantity WHEN movement_type IN ('CONSUME','SHIP') THEN -quantity ELSE 0 END),0)-COALESCE(SUM(CASE WHEN movement_type='RESERVE' THEN quantity WHEN movement_type IN ('RELEASE','CONSUME') THEN -quantity ELSE 0 END),0) FROM inventory_movement WHERE tenant_id=? AND company_id=? AND product_id=?",
        BigDecimal.class,u.tenantId(),u.companyId(),product);
      if(available.compareTo(requested.quantity())<0) throw ApiException.conflict("Insufficient available finished stock");
      String lineCurrency=currency((String)line.get("currency_code"));
      if(currencyCode!=null&&!currencyCode.equals(lineCurrency)) throw ApiException.conflict("All lines in one shipment must use the same currency");
      currencyCode=lineCurrency;
      db.update("INSERT INTO shipment_line(shipment_id,order_line_id,product_id,sku_snapshot,name_snapshot,quantity,unit_price,currency_code) VALUES(?,?,?,?,?,?,?,?)",
        shipmentId,requested.orderLineId(),product,line.get("sku_snapshot"),line.get("name_snapshot"),requested.quantity(),line.get("unit_price"),lineCurrency);
      db.update("INSERT INTO inventory_movement(tenant_id,company_id,warehouse_id,product_id,movement_type,quantity,reference_type,reference_id,idempotency_key,note) VALUES(?,?,?,?,'SHIP',?,'SHIPMENT',?,?,?)",
        u.tenantId(),u.companyId(),r.warehouseId(),product,requested.quantity(),shipmentId,"ship:"+r.idempotencyKey()+":"+requested.orderLineId(),"Customer shipment");
    }
    Integer outstanding=db.queryForObject("SELECT COUNT(*) FROM order_line l LEFT JOIN (SELECT sl.order_line_id,SUM(sl.quantity) shipped FROM shipment_line sl JOIN shipment s ON s.id=sl.shipment_id WHERE s.tenant_id=? AND s.company_id=? AND s.status='SHIPPED' GROUP BY sl.order_line_id) x ON x.order_line_id=l.id WHERE l.order_id=? AND COALESCE(x.shipped,0)<l.quantity",
      Integer.class,u.tenantId(),u.companyId(),r.orderId());
    String orderStatus=outstanding!=null&&outstanding==0?"SHIPPED":"PARTIALLY_SHIPPED";
    db.update("UPDATE customer_order SET status=? WHERE id=? AND tenant_id=? AND company_id=?",orderStatus,r.orderId(),u.tenantId(),u.companyId());
    audit(u,"SHIPMENT_CREATED","shipment",shipmentId,Map.of("orderId",r.orderId(),"lineCount",r.lines().size(),"currencyCode",currencyCode));
    return Map.of("id",shipmentId,"status","SHIPPED","orderStatus",orderStatus,"currencyCode",currencyCode);
  }

  public record InvoiceRequest(LocalDate dueDate,@DecimalMin("0") BigDecimal discountAmount,
      @DecimalMin("0") BigDecimal taxRate) {}
  @GetMapping("/invoices")
  public List<Map<String,Object>> invoices(@AuthenticationPrincipal UserContext u) {
    requireFinanceRead(u);
    return db.queryForList("SELECT i.id,i.invoice_no invoiceNo,i.shipment_id shipmentId,CASE WHEN i.status='VOID' THEN 'VOID' WHEN i.total>COALESCE(SUM(CASE WHEN p.event_type='PAYMENT' THEN p.amount ELSE -p.amount END),0) AND i.due_date<CURRENT_DATE THEN 'OVERDUE' WHEN COALESCE(SUM(CASE WHEN p.event_type='PAYMENT' THEN p.amount ELSE -p.amount END),0)>=i.total THEN 'PAID' WHEN COALESCE(SUM(CASE WHEN p.event_type='PAYMENT' THEN p.amount ELSE -p.amount END),0)>0 THEN 'PARTIALLY_PAID' ELSE i.status END status,i.invoice_date invoiceDate,i.due_date dueDate,i.currency_code currencyCode,i.subtotal,i.discount_amount discountAmount,i.tax_rate taxRate,i.tax_amount taxAmount,i.total,COALESCE(SUM(CASE WHEN p.event_type='PAYMENT' THEN p.amount ELSE -p.amount END),0) paid, i.total-COALESCE(SUM(CASE WHEN p.event_type='PAYMENT' THEN p.amount ELSE -p.amount END),0) balance,c.name customer FROM invoice i JOIN shipment s ON s.id=i.shipment_id JOIN customer_order o ON o.id=s.order_id JOIN customer c ON c.id=o.customer_id LEFT JOIN invoice_payment p ON p.invoice_id=i.id AND p.tenant_id=i.tenant_id AND p.company_id=i.company_id WHERE i.tenant_id=? AND i.company_id=? GROUP BY i.id ORDER BY i.id DESC",
      u.tenantId(),u.companyId());
  }
  @PostMapping("/invoices/from-shipment") @Transactional
  public Map<String,Object> invoice(@AuthenticationPrincipal UserContext u,@Valid @RequestBody InvoiceRequest r,
      @RequestParam long shipmentId) {
    requireFinance(u);
    var shipment=scoped("shipment",shipmentId,u,true);
    var duplicate=db.queryForList("SELECT id,invoice_no invoiceNo,status,total,currency_code currencyCode FROM invoice WHERE tenant_id=? AND company_id=? AND shipment_id=?",u.tenantId(),u.companyId(),shipmentId);
    if(!duplicate.isEmpty()) return duplicate.getFirst();
    List<Map<String,Object>> lines=db.queryForList("SELECT * FROM shipment_line WHERE shipment_id=? ORDER BY id",shipmentId);
    if(lines.isEmpty()) throw ApiException.conflict("Shipment has no invoiceable lines");
    String code=currency((String)lines.getFirst().get("currency_code"));
    BigDecimal subtotal=BigDecimal.ZERO;
    for(var line:lines) {
      if(!code.equals(currency((String)line.get("currency_code")))) throw ApiException.conflict("Invoice lines must share one currency");
      subtotal=subtotal.add(money(((BigDecimal)line.get("quantity")).multiply((BigDecimal)line.get("unit_price"))));
    }
    Map<String,Object> settings=financeSettings(u);
    BigDecimal discount=money(r.discountAmount()==null?BigDecimal.ZERO:r.discountAmount());
    BigDecimal rate=r.taxRate()==null?(BigDecimal)settings.get("defaultTaxRate"):r.taxRate();
    if(discount.compareTo(subtotal)>0) throw ApiException.bad("Discount cannot exceed the shipped subtotal");
    if(rate.compareTo(new BigDecimal("100"))>0) throw ApiException.bad("Tax rate cannot exceed 100 percent");
    BigDecimal taxable=subtotal.subtract(discount);
    boolean taxInclusive=Boolean.TRUE.equals(settings.get("taxInclusive"));
    BigDecimal tax=taxInclusive
      ? money(taxable.multiply(rate).divide(new BigDecimal("100").add(rate),8,RoundingMode.HALF_UP))
      : money(taxable.multiply(rate).divide(new BigDecimal("100"),8,RoundingMode.HALF_UP));
    BigDecimal total=taxInclusive?taxable:taxable.add(tax);
    Long customer=db.queryForObject("SELECT o.customer_id FROM shipment s JOIN customer_order o ON o.id=s.order_id WHERE s.id=? AND s.tenant_id=? AND s.company_id=?",Long.class,shipmentId,u.tenantId(),u.companyId());
    LocalDate issued=LocalDate.now();LocalDate due=r.dueDate()==null?issued.plusDays(((Number)settings.get("paymentTermDays")).longValue()):r.dueDate();
    String no=settings.get("invoicePrefix")+"-"+issued.getYear()+"-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
    long invoiceId=insert("INSERT INTO invoice(tenant_id,company_id,invoice_no,shipment_id,customer_id,status,invoice_date,due_date,currency_code,subtotal,discount_amount,tax_rate,tax_amount,total) VALUES(?,?,?,?,?,'ISSUED',?,?,?,?,?,?,?,?)",
      u.tenantId(),u.companyId(),no,shipmentId,customer,issued,due,code,subtotal,discount,rate,tax,total);
    BigDecimal allocatedDiscount=BigDecimal.ZERO,allocatedTax=BigDecimal.ZERO;
    for(int index=0;index<lines.size();index++) {
      var line=lines.get(index);
      BigDecimal lineSubtotal=money(((BigDecimal)line.get("quantity")).multiply((BigDecimal)line.get("unit_price")));
      BigDecimal lineDiscount=index==lines.size()-1?discount.subtract(allocatedDiscount):subtotal.signum()==0?BigDecimal.ZERO:money(discount.multiply(lineSubtotal).divide(subtotal,8,RoundingMode.HALF_UP));
      allocatedDiscount=allocatedDiscount.add(lineDiscount);
      BigDecimal lineTaxable=lineSubtotal.subtract(lineDiscount);
      BigDecimal lineTax=index==lines.size()-1?tax.subtract(allocatedTax):(taxInclusive
        ? money(lineTaxable.multiply(rate).divide(new BigDecimal("100").add(rate),8,RoundingMode.HALF_UP))
        : money(lineTaxable.multiply(rate).divide(new BigDecimal("100"),8,RoundingMode.HALF_UP)));
      allocatedTax=allocatedTax.add(lineTax);
      BigDecimal lineTotal=taxInclusive?lineTaxable:lineTaxable.add(lineTax);
      db.update("INSERT INTO invoice_line(invoice_id,shipment_line_id,sku_snapshot,description_snapshot,quantity,unit_price,line_subtotal,discount_amount,tax_rate,tax_amount,line_total,currency_code) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        invoiceId,line.get("id"),line.get("sku_snapshot"),line.get("name_snapshot"),line.get("quantity"),line.get("unit_price"),lineSubtotal,lineDiscount,rate,lineTax,lineTotal,code);
    }
    List<JournalLine> posting=new ArrayList<>();
    posting.add(new JournalLine(postingAccount(u,"INVOICE_RECEIVABLE","1100","Accounts receivable","ASSET"),"Invoice receivable",total,BigDecimal.ZERO,code));
    BigDecimal revenue=taxInclusive?subtotal.subtract(tax):subtotal;
    posting.add(new JournalLine(postingAccount(u,"SALES_REVENUE","4000","Sales revenue","INCOME"),"Shipped goods",BigDecimal.ZERO,revenue,code));
    if(discount.signum()>0) posting.add(new JournalLine(postingAccount(u,"SALES_DISCOUNT","4090","Sales discounts","CONTRA_INCOME"),"Invoice discount",discount,BigDecimal.ZERO,code));
    if(tax.signum()>0) posting.add(new JournalLine(postingAccount(u,"TAX_PAYABLE","2100","Tax payable","LIABILITY"),"Invoice tax",BigDecimal.ZERO,tax,code));
    journal(u,issued,"Invoice "+no,"INVOICE",invoiceId,"invoice:"+invoiceId,null,posting);
    audit(u,"INVOICE_ISSUED","invoice",invoiceId,Map.of("invoiceNo",no,"total",total,"currencyCode",code,"taxRate",rate));
    return Map.of("id",invoiceId,"invoiceNo",no,"status","ISSUED","subtotal",subtotal,"taxAmount",tax,"total",total,"currencyCode",code);
  }
  @PostMapping("/invoices/{id}/void") @Transactional
  public Map<String,Object> voidInvoice(@PathVariable long id,@AuthenticationPrincipal UserContext u,@RequestBody(required=false) Map<String,Object> body) {
    requireFinance(u);var inv=scoped("invoice",id,u,true);
    if("VOID".equals(inv.get("status")))return Map.of("id",id,"status","VOID","idempotentReplay",true);
    BigDecimal net=db.queryForObject("SELECT COALESCE(SUM(CASE WHEN event_type='PAYMENT' THEN amount ELSE -amount END),0) FROM invoice_payment WHERE invoice_id=? AND tenant_id=? AND company_id=?",BigDecimal.class,id,u.tenantId(),u.companyId());
    if(net.signum()!=0)throw ApiException.conflict("Refund or void all invoice payments before voiding the invoice");
    var original=db.queryForList("SELECT id,entry_date FROM journal_entry WHERE tenant_id=? AND company_id=? AND source_type='INVOICE' AND source_id=? AND status='POSTED'",u.tenantId(),u.companyId(),id);
    if(original.isEmpty())throw ApiException.conflict("Posted invoice journal was not found");
    long journalId=((Number)original.getFirst().get("id")).longValue();LocalDate date=LocalDate.now();
    var prior=db.queryForList("SELECT id FROM journal_entry WHERE tenant_id=? AND company_id=? AND reverses_journal_id=?",u.tenantId(),u.companyId(),journalId);
    if(prior.isEmpty()) {
      var old=db.queryForList("SELECT l.account_id,l.memo,l.debit,l.credit,l.currency_code FROM journal_line l WHERE l.journal_id=?",journalId);
      List<JournalLine> lines=old.stream().map(l->new JournalLine(((Number)l.get("account_id")).longValue(),"Void: "+l.get("memo"),(BigDecimal)l.get("credit"),(BigDecimal)l.get("debit"),String.valueOf(l.get("currency_code")))).toList();
      journal(u,date,"Void invoice "+inv.get("invoice_no"),"INVOICE_VOID",id,"invoice-void:"+id,journalId,lines);
    }
    db.update("UPDATE invoice SET status='VOID' WHERE id=? AND tenant_id=? AND company_id=?",id,u.tenantId(),u.companyId());
    audit(u,"INVOICE_VOIDED","invoice",id,Map.of("invoiceNo",inv.get("invoice_no")));return Map.of("id",id,"status","VOID");
  }
  @GetMapping("/orders/{id}/history")
  public Map<String,Object> orderHistory(@PathVariable long id,@AuthenticationPrincipal UserContext u) {
    var order=scoped("customer_order",id,u,false);
    return Map.of("order",order,
      "lines",orderLines(id,u),
      "workOrders",db.queryForList("SELECT w.id,w.work_no workNo,w.status,w.quantity,w.produced,w.created_at createdAt FROM manufacturing_order w JOIN order_line l ON l.id=w.order_line_id WHERE w.tenant_id=? AND w.company_id=? AND l.order_id=? ORDER BY w.id",u.tenantId(),u.companyId(),id),
      "shipments",db.queryForList("SELECT id,status,tracking_number trackingNumber,shipped_at shippedAt FROM shipment WHERE tenant_id=? AND company_id=? AND order_id=? ORDER BY id",u.tenantId(),u.companyId(),id),
      "invoices",db.queryForList("SELECT i.id,i.invoice_no invoiceNo,i.status,i.due_date dueDate,i.currency_code currencyCode,i.total,COALESCE(SUM(CASE WHEN p.event_type='PAYMENT' THEN p.amount ELSE -p.amount END),0) paid FROM invoice i JOIN shipment s ON s.id=i.shipment_id LEFT JOIN invoice_payment p ON p.invoice_id=i.id AND p.tenant_id=i.tenant_id AND p.company_id=i.company_id WHERE i.tenant_id=? AND i.company_id=? AND s.order_id=? GROUP BY i.id ORDER BY i.id",u.tenantId(),u.companyId(),id),
      "payments",db.queryForList("SELECT p.id,p.invoice_id invoiceId,p.event_type eventType,p.amount,p.payment_method paymentMethod,p.reference,p.recorded_at recordedAt FROM invoice_payment p JOIN invoice i ON i.id=p.invoice_id JOIN shipment s ON s.id=i.shipment_id WHERE p.tenant_id=? AND p.company_id=? AND s.order_id=? ORDER BY p.id",u.tenantId(),u.companyId(),id),
      "stockMovements",db.queryForList("SELECT m.id,m.movement_type type,m.quantity,m.reference_type referenceType,m.reference_id referenceId,m.created_at createdAt,p.sku,p.name,w.code warehouse FROM inventory_movement m JOIN product p ON p.id=m.product_id JOIN warehouse w ON w.id=m.warehouse_id WHERE m.tenant_id=? AND m.company_id=? AND ((m.reference_type='SHIPMENT' AND m.reference_id IN (SELECT id FROM shipment WHERE order_id=? AND tenant_id=? AND company_id=?)) OR (m.reference_type='WORK_ORDER' AND m.reference_id IN (SELECT w.id FROM manufacturing_order w JOIN order_line l ON l.id=w.order_line_id WHERE l.order_id=? AND w.tenant_id=? AND w.company_id=?))) ORDER BY m.id",u.tenantId(),u.companyId(),id,u.tenantId(),u.companyId(),id,u.tenantId(),u.companyId()));
  }

  public record PaymentRequest(@NotBlank String eventType,@NotBlank String paymentMethod,
      BigDecimal amount,String reference,Long paymentId,@NotBlank String idempotencyKey) {}
  @GetMapping("/invoices/{id}/payments")
  public List<Map<String,Object>> payments(@PathVariable long id,@AuthenticationPrincipal UserContext u) {
    requireFinanceRead(u);
    scoped("invoice",id,u,false);
    return db.queryForList("SELECT id,event_type eventType,amount,payment_method paymentMethod,reference,reverses_payment_id reversesPaymentId,recorded_at recordedAt FROM invoice_payment WHERE invoice_id=? AND tenant_id=? AND company_id=? ORDER BY id DESC",id,u.tenantId(),u.companyId());
  }
  @PostMapping("/invoices/{id}/payments") @Transactional
  public Map<String,Object> recordPayment(@PathVariable long id,@AuthenticationPrincipal UserContext u,@RequestBody PaymentRequest r) {
    requireFinance(u);
    var inv=scoped("invoice",id,u,true);
    var old=db.queryForList("SELECT id,event_type eventType,amount FROM invoice_payment WHERE tenant_id=? AND company_id=? AND idempotency_key=?",u.tenantId(),u.companyId(),r.idempotencyKey());
    if(!old.isEmpty()) return Map.of("idempotentReplay",true,"id",old.getFirst().get("id"));
    String type=r.eventType().toUpperCase(Locale.ROOT);
    if(!Set.of("PAYMENT","REFUND","VOID").contains(type)) throw ApiException.bad("eventType must be PAYMENT, REFUND, or VOID");
    BigDecimal total=(BigDecimal)inv.get("total");
    BigDecimal net=db.queryForObject("SELECT COALESCE(SUM(CASE WHEN event_type='PAYMENT' THEN amount ELSE -amount END),0) FROM invoice_payment WHERE invoice_id=? AND tenant_id=? AND company_id=?",BigDecimal.class,id,u.tenantId(),u.companyId());
    BigDecimal amount;
    Long reverses=null;
    if("VOID".equals(type)) {
      if(r.paymentId()==null) throw ApiException.bad("paymentId is required to void a payment");
      List<Map<String,Object>> payment=db.queryForList("SELECT id,amount FROM invoice_payment WHERE id=? AND invoice_id=? AND event_type='PAYMENT' AND tenant_id=? AND company_id=? FOR UPDATE",r.paymentId(),id,u.tenantId(),u.companyId());
      if(payment.isEmpty()) throw ApiException.bad("Only an existing payment for this invoice can be voided");
      Integer reversed=db.queryForObject("SELECT COUNT(*) FROM invoice_payment WHERE reverses_payment_id=? AND tenant_id=? AND company_id=?",Integer.class,r.paymentId(),u.tenantId(),u.companyId());
      if(reversed!=null&&reversed>0) throw ApiException.conflict("That payment has already been reversed");
      amount=(BigDecimal)payment.getFirst().get("amount");if(amount.compareTo(net)>0)throw ApiException.conflict("Payment cannot be voided after part of it has already been refunded");reverses=r.paymentId();
    } else {
      if(r.amount()==null||r.amount().signum()<=0) throw ApiException.bad("amount must be greater than zero");
      amount=money(r.amount());
      if("PAYMENT".equals(type)&&amount.compareTo(total.subtract(net))>0) throw ApiException.conflict("Payment exceeds the invoice balance");
      if("REFUND".equals(type)&&amount.compareTo(net)>0) throw ApiException.conflict("Refund exceeds the net payments recorded");
    }
    String code=currency((String)inv.get("currency_code"));
    long paymentId=insert("INSERT INTO invoice_payment(tenant_id,company_id,invoice_id,event_type,amount,payment_method,reference,reverses_payment_id,idempotency_key) VALUES(?,?,?,?,?,?,?,?,?)",
      u.tenantId(),u.companyId(),id,type,amount,r.paymentMethod().toUpperCase(Locale.ROOT),r.reference(),reverses,r.idempotencyKey());
    long cash=postingAccount(u,"CASH","1000","Cash and bank","ASSET"),ar=postingAccount(u,"INVOICE_RECEIVABLE","1100","Accounts receivable","ASSET");
    List<JournalLine> posting=new ArrayList<>();
    if("PAYMENT".equals(type)) { posting.add(new JournalLine(cash,"Payment received",amount,BigDecimal.ZERO,code));posting.add(new JournalLine(ar,"Reduce receivable",BigDecimal.ZERO,amount,code)); }
    else { posting.add(new JournalLine(ar,"Refund or void receivable",amount,BigDecimal.ZERO,code));posting.add(new JournalLine(cash,"Refund or void cash",BigDecimal.ZERO,amount,code)); }
    journal(u,LocalDate.now(),type+" for invoice "+inv.get("invoice_no"),"PAYMENT",paymentId,"payment:"+paymentId,reverses,posting);
    BigDecimal updatedNet="PAYMENT".equals(type)?net.add(amount):net.subtract(amount);
    String status=updatedNet.compareTo(total)>=0?"PAID":updatedNet.signum()>0?"PARTIALLY_PAID":"OPEN";
    db.update("UPDATE invoice SET status=? WHERE id=? AND tenant_id=? AND company_id=?",status,id,u.tenantId(),u.companyId());
    if(updatedNet.compareTo(total)>=0) {
      Long orderId=db.query("SELECT s.order_id FROM invoice i JOIN shipment s ON s.id=i.shipment_id WHERE i.id=? AND i.tenant_id=? AND i.company_id=?",rs->rs.next()?rs.getLong(1):null,id,u.tenantId(),u.companyId());
      if(orderId!=null) {
        Integer unshipped=db.queryForObject("SELECT COUNT(*) FROM order_line l LEFT JOIN (SELECT sl.order_line_id,SUM(sl.quantity) q FROM shipment_line sl JOIN shipment sh ON sh.id=sl.shipment_id WHERE sh.tenant_id=? AND sh.company_id=? AND sh.status='SHIPPED' GROUP BY sl.order_line_id) s ON s.order_line_id=l.id WHERE l.order_id=? AND COALESCE(s.q,0)<l.quantity",Integer.class,u.tenantId(),u.companyId(),orderId);
        Integer dueInvoices=db.queryForObject("SELECT COUNT(*) FROM invoice i WHERE i.tenant_id=? AND i.company_id=? AND i.shipment_id IN (SELECT id FROM shipment WHERE tenant_id=? AND company_id=? AND order_id=?) AND i.total>COALESCE((SELECT SUM(CASE WHEN p.event_type='PAYMENT' THEN p.amount ELSE -p.amount END) FROM invoice_payment p WHERE p.invoice_id=i.id AND p.tenant_id=i.tenant_id AND p.company_id=i.company_id),0)",Integer.class,u.tenantId(),u.companyId(),u.tenantId(),u.companyId(),orderId);
        if((unshipped==null||unshipped==0)&&(dueInvoices==null||dueInvoices==0))db.update("UPDATE customer_order SET status='CLOSED' WHERE id=? AND tenant_id=? AND company_id=?",orderId,u.tenantId(),u.companyId());
      }
    } else {
      Long orderId=db.query("SELECT s.order_id FROM invoice i JOIN shipment s ON s.id=i.shipment_id WHERE i.id=? AND i.tenant_id=? AND i.company_id=?",rs->rs.next()?rs.getLong(1):null,id,u.tenantId(),u.companyId());
      if(orderId!=null)db.update("UPDATE customer_order SET status='SHIPPED' WHERE id=? AND tenant_id=? AND company_id=? AND status='CLOSED'",orderId,u.tenantId(),u.companyId());
    }
    audit(u,"INVOICE_"+type,"invoice",id,Map.of("paymentId",paymentId,"amount",amount,"status",status));
    return Map.of("id",paymentId,"eventType",type,"amount",amount,"status",status,"balance",total.subtract(updatedNet));
  }

  @GetMapping("/finance/accounts")
  public List<Map<String,Object>> accounts(@AuthenticationPrincipal UserContext u) {
    requireFinanceRead(u);
    account(u,"1000","Cash and bank","ASSET");account(u,"1100","Accounts receivable","ASSET");account(u,"2100","Tax payable","LIABILITY");account(u,"4000","Sales revenue","INCOME");account(u,"4090","Sales discounts","CONTRA_INCOME");
    return db.queryForList("SELECT id,account_code accountCode,name,account_type accountType,active,system_account systemAccount FROM gl_account WHERE tenant_id=? AND company_id=? ORDER BY account_code",u.tenantId(),u.companyId());
  }
  @GetMapping("/finance/settings")
  public Map<String,Object> getFinanceSettings(@AuthenticationPrincipal UserContext u) { requireFinanceRead(u);return financeSettings(u); }
  @PutMapping("/finance/settings") @Transactional
  public Map<String,Object> updateFinanceSettings(@AuthenticationPrincipal UserContext u,@RequestBody Map<String,Object> r) {
    requireFinance(u);String code=currency(String.valueOf(r.getOrDefault("currencyCode","USD")));
    BigDecimal rate;try{rate=new BigDecimal(String.valueOf(r.getOrDefault("defaultTaxRate","0")));}catch(Exception e){throw ApiException.bad("defaultTaxRate must be numeric");}
    if(rate.signum()<0||rate.compareTo(new BigDecimal("100"))>0)throw ApiException.bad("defaultTaxRate must be between 0 and 100");
    int terms;try{terms=Integer.parseInt(String.valueOf(r.getOrDefault("paymentTermDays","30")));}catch(Exception e){throw ApiException.bad("paymentTermDays must be a whole number");}
    if(terms<0||terms>365)throw ApiException.bad("paymentTermDays must be between 0 and 365");
    String prefix=String.valueOf(r.getOrDefault("invoicePrefix","INV")).trim().toUpperCase(Locale.ROOT);
    if(!prefix.matches("[A-Z0-9]{1,12}"))throw ApiException.bad("invoicePrefix must use 1 to 12 letters or digits");
    boolean inclusive=Boolean.parseBoolean(String.valueOf(r.getOrDefault("taxInclusive",false)));
    db.update("INSERT INTO finance_settings(tenant_id,company_id,currency_code,default_tax_rate,tax_inclusive,invoice_prefix,payment_term_days,tax_jurisdiction) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE currency_code=VALUES(currency_code),default_tax_rate=VALUES(default_tax_rate),tax_inclusive=VALUES(tax_inclusive),invoice_prefix=VALUES(invoice_prefix),payment_term_days=VALUES(payment_term_days),tax_jurisdiction=VALUES(tax_jurisdiction)",
      u.tenantId(),u.companyId(),code,rate,inclusive,prefix,terms,r.get("taxJurisdiction"));
    audit(u,"FINANCE_SETTINGS_UPDATED","finance_settings",u.companyId(),Map.of("currencyCode",code,"defaultTaxRate",rate,"taxInclusive",inclusive));
    return financeSettings(u);
  }
  @GetMapping("/finance/posting-rules")
  public List<Map<String,Object>> postingRules(@AuthenticationPrincipal UserContext u) {
    requireFinanceRead(u);
    postingAccount(u,"CASH","1000","Cash and bank","ASSET");postingAccount(u,"INVOICE_RECEIVABLE","1100","Accounts receivable","ASSET");
    postingAccount(u,"TAX_PAYABLE","2100","Tax payable","LIABILITY");postingAccount(u,"SALES_REVENUE","4000","Sales revenue","INCOME");postingAccount(u,"SALES_DISCOUNT","4090","Sales discounts","CONTRA_INCOME");
    return db.queryForList("SELECT r.rule_code ruleCode,r.account_id accountId,a.account_code accountCode,a.name accountName,a.account_type accountType,r.active FROM posting_rule r JOIN gl_account a ON a.id=r.account_id WHERE r.tenant_id=? AND r.company_id=? ORDER BY r.rule_code",u.tenantId(),u.companyId());
  }
  @PutMapping("/finance/posting-rules/{code}") @Transactional
  public Map<String,Object> updatePostingRule(@PathVariable String code,@AuthenticationPrincipal UserContext u,@RequestBody Map<String,Object> body) {
    requireFinance(u);String rule=code.toUpperCase(Locale.ROOT);
    if(!Set.of("CASH","INVOICE_RECEIVABLE","TAX_PAYABLE","SALES_REVENUE","SALES_DISCOUNT").contains(rule))throw ApiException.bad("Unsupported posting rule");
    Object value=body.get("accountId");if(value==null)throw ApiException.bad("accountId is required");
    long accountId;try{accountId=Long.parseLong(String.valueOf(value));}catch(Exception e){throw ApiException.bad("accountId must be an integer");}
    var rows=db.queryForList("SELECT account_type FROM gl_account WHERE id=? AND tenant_id=? AND company_id=? AND active=TRUE",accountId,u.tenantId(),u.companyId());
    if(rows.isEmpty())throw ApiException.bad("Account is not active in the current company");
    String expected=switch(rule){case "CASH","INVOICE_RECEIVABLE"->"ASSET";case "TAX_PAYABLE"->"LIABILITY";case "SALES_REVENUE"->"INCOME";default->"CONTRA_INCOME";};
    if(!expected.equals(rows.getFirst().get("account_type")))throw ApiException.bad("Selected account has the wrong account type for this posting rule");
    db.update("INSERT INTO posting_rule(tenant_id,company_id,rule_code,account_id,active) VALUES(?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE account_id=VALUES(account_id),active=TRUE",u.tenantId(),u.companyId(),rule,accountId);
    audit(u,"POSTING_RULE_UPDATED","posting_rule",accountId,Map.of("ruleCode",rule));return Map.of("ruleCode",rule,"accountId",accountId);
  }
  public record BankLineInput(@NotBlank String externalRef,@NotNull LocalDate transactionDate,
      String description,@NotNull BigDecimal amount) {}
  public record BankImport(@NotBlank String statementRef,@NotBlank String currencyCode,
      @NotEmpty List<@Valid BankLineInput> lines) {}
  @GetMapping("/finance/bank-statements")
  public List<Map<String,Object>> bankStatements(@AuthenticationPrincipal UserContext u) {
    requireFinanceRead(u);
    return db.queryForList("SELECT s.id,s.statement_ref statementRef,s.currency_code currencyCode,s.imported_at importedAt,COUNT(l.id) lineCount,SUM(CASE WHEN l.status='MATCHED' THEN 1 ELSE 0 END) matchedCount FROM bank_statement s LEFT JOIN bank_statement_line l ON l.statement_id=s.id WHERE s.tenant_id=? AND s.company_id=? GROUP BY s.id ORDER BY s.id DESC",u.tenantId(),u.companyId());
  }
  @GetMapping("/finance/bank-statements/{id}/lines")
  public List<Map<String,Object>> bankStatementLines(@PathVariable long id,@AuthenticationPrincipal UserContext u) {
    requireFinanceRead(u);
    scoped("bank_statement",id,u,false);
    return db.queryForList("SELECT id,external_ref externalReference,transaction_date transactionDate,description,amount,status,matched_payment_id matchedPaymentId FROM bank_statement_line WHERE statement_id=? ORDER BY transaction_date,id",id);
  }
  @PostMapping("/finance/bank-statements/import") @Transactional
  public Map<String,Object> importBankStatement(@AuthenticationPrincipal UserContext u,@Valid @RequestBody BankImport r) {
    requireFinance(u);currency(r.currencyCode());
    var existing=db.queryForList("SELECT id,statement_ref statementRef FROM bank_statement WHERE tenant_id=? AND company_id=? AND statement_ref=?",u.tenantId(),u.companyId(),r.statementRef());
    if(!existing.isEmpty())return Map.of("idempotentReplay",true,"statement",existing.getFirst());
    for(var line:r.lines())if(line.amount().signum()==0)throw ApiException.bad("Bank statement line amount cannot be zero");
    long id=insert("INSERT INTO bank_statement(tenant_id,company_id,statement_ref,currency_code) VALUES(?,?,?,?)",u.tenantId(),u.companyId(),r.statementRef(),r.currencyCode().toUpperCase(Locale.ROOT));
    int index=0;for(var line:r.lines()) {
      String external=line.externalRef().trim();if(external.isBlank())external="ROW-"+(++index);
      db.update("INSERT INTO bank_statement_line(tenant_id,company_id,statement_id,external_ref,transaction_date,description,amount,status) VALUES(?,?,?,?,?,?,?,'REVIEW')",u.tenantId(),u.companyId(),id,external,line.transactionDate(),line.description(),money(line.amount()));
    }
    var result=sandbox.importStatement(r.statementRef(),Map.of("statementId",id,"lineCount",r.lines().size()));
    db.update("INSERT INTO adapter_transaction(tenant_id,company_id,adapter_type,source_type,source_id,external_ref,status,request_json,response_json,idempotency_key) VALUES(?,?,'BANK_IMPORT','BANK_STATEMENT',?,?, 'SUCCEEDED',?,?,?)",
      u.tenantId(),u.companyId(),id,result.externalReference(),json.writeValueAsString(Map.of("statementRef",r.statementRef(),"lineCount",r.lines().size())),json.writeValueAsString(result.details()),"bank:"+r.statementRef());
    audit(u,"BANK_STATEMENT_IMPORTED","bank_statement",id,Map.of("statementRef",r.statementRef(),"lineCount",r.lines().size(),"mode","MANUAL_IMPORT"));
    return Map.of("id",id,"statementRef",r.statementRef(),"lineCount",r.lines().size(),"status","IMPORTED","adapterMode","MANUAL_IMPORT");
  }
  @PostMapping("/finance/bank-lines/{id}/match") @Transactional
  public Map<String,Object> matchBankLine(@PathVariable long id,@AuthenticationPrincipal UserContext u,@RequestBody Map<String,Object> body) {
    requireFinance(u);var line=scoped("bank_statement_line",id,u,true);
    if(!"REVIEW".equals(line.get("status")))throw ApiException.conflict("Only unreconciled statement entries can be matched");
    long paymentId;try{paymentId=Long.parseLong(String.valueOf(body.get("paymentId")));}catch(Exception e){throw ApiException.bad("paymentId is required");}
    var payment=db.queryForList("SELECT id,invoice_id,amount,event_type FROM invoice_payment WHERE id=? AND tenant_id=? AND company_id=?",paymentId,u.tenantId(),u.companyId());
    if(payment.isEmpty())throw ApiException.bad("Payment is outside the active company");
    var p=payment.getFirst();
    if(!"PAYMENT".equals(p.get("event_type"))||((BigDecimal)p.get("amount")).compareTo((BigDecimal)line.get("amount"))!=0)throw ApiException.conflict("Bank entry does not exactly match an incoming payment amount");
    db.update("UPDATE bank_statement_line SET status='MATCHED',matched_payment_id=? WHERE id=? AND statement_id IN (SELECT id FROM bank_statement WHERE tenant_id=? AND company_id=?)",paymentId,id,u.tenantId(),u.companyId());
    db.update("INSERT INTO reconciliation_event(tenant_id,company_id,bank_line_id,action,payment_id,note,actor_id) VALUES(?,?,?,'MATCH',?,?,?)",u.tenantId(),u.companyId(),id,paymentId,body.getOrDefault("note","Matched to payment "+paymentId),u.userId());
    audit(u,"BANK_ENTRY_MATCHED","bank_statement_line",id,Map.of("paymentId",paymentId));return Map.of("id",id,"status","MATCHED","paymentId",paymentId);
  }
  @PostMapping("/finance/bank-lines/{id}/review") @Transactional
  public Map<String,Object> reviewBankLine(@PathVariable long id,@AuthenticationPrincipal UserContext u,@RequestBody Map<String,Object> body) {
    requireFinance(u);var line=scoped("bank_statement_line",id,u,true);
    if(!"REVIEW".equals(line.get("status")))throw ApiException.conflict("Only unreconciled statement entries can be reviewed");
    String note=String.valueOf(body.getOrDefault("note","Needs manual finance review"));
    db.update("INSERT INTO reconciliation_event(tenant_id,company_id,bank_line_id,action,note,actor_id) VALUES(?,?,?,'REVIEW',?,?)",u.tenantId(),u.companyId(),id,note,u.userId());
    audit(u,"BANK_ENTRY_LEFT_FOR_REVIEW","bank_statement_line",id,Map.of("note",note));return Map.of("id",id,"status",line.get("status"),"reviewNote",note);
  }
  @GetMapping("/finance/reconciliation-history")
  public List<Map<String,Object>> reconciliationHistory(@AuthenticationPrincipal UserContext u) {
    requireFinanceRead(u);
    return db.queryForList("SELECT e.id,e.bank_line_id bankLineId,e.action,e.payment_id paymentId,e.note,e.actor_id actorId,e.created_at createdAt,l.external_ref externalReference,s.statement_ref statementRef FROM reconciliation_event e JOIN bank_statement_line l ON l.id=e.bank_line_id JOIN bank_statement s ON s.id=l.statement_id WHERE e.tenant_id=? AND e.company_id=? ORDER BY e.id DESC LIMIT 300",u.tenantId(),u.companyId());
  }
  @PostMapping("/finance/accounts") @Transactional
  public Map<String,Object> createAccount(@AuthenticationPrincipal UserContext u,@RequestBody Map<String,Object> r) {
    requireFinance(u);
    String code=String.valueOf(r.getOrDefault("accountCode","")).trim(),name=String.valueOf(r.getOrDefault("name","")).trim(),type=String.valueOf(r.getOrDefault("accountType","")).toUpperCase(Locale.ROOT);
    if(code.isBlank()||name.isBlank()||!Set.of("ASSET","LIABILITY","EQUITY","INCOME","EXPENSE","CONTRA_INCOME").contains(type)) throw ApiException.bad("Provide a code, name, and supported accountType");
    long id=insert("INSERT INTO gl_account(tenant_id,company_id,account_code,name,account_type) VALUES(?,?,?,?,?)",u.tenantId(),u.companyId(),code,name,type);
    audit(u,"GL_ACCOUNT_CREATED","gl_account",id,Map.of("code",code,"type",type));return Map.of("id",id,"accountCode",code,"name",name,"accountType",type);
  }
  private void requireFinance(UserContext u) { if(!Set.of("ADMIN","MANAGER","FINANCE").contains(u.role())) throw ApiException.forbidden("Finance access required"); }

  @GetMapping("/finance/periods")
  public List<Map<String,Object>> periods(@AuthenticationPrincipal UserContext u) {
    requireFinanceRead(u);
    ensurePeriod(u,LocalDate.now());
    return db.queryForList("SELECT id,period_name periodName,starts_on startsOn,ends_on endsOn,status FROM accounting_period WHERE tenant_id=? AND company_id=? ORDER BY starts_on DESC",u.tenantId(),u.companyId());
  }
  @PostMapping("/finance/periods/{id}/close") @Transactional
  public Map<String,Object> closePeriod(@PathVariable long id,@AuthenticationPrincipal UserContext u) {
    requireFinance(u);var period=scoped("accounting_period",id,u,true);if(!"OPEN".equals(period.get("status")))throw ApiException.conflict("Accounting period is already closed");
    db.update("UPDATE accounting_period SET status='CLOSED' WHERE id=? AND tenant_id=? AND company_id=?",id,u.tenantId(),u.companyId());audit(u,"ACCOUNTING_PERIOD_CLOSED","accounting_period",id,Map.of());return Map.of("id",id,"status","CLOSED");
  }
  public record ManualLine(@NotNull Long accountId,@NotNull @DecimalMin("0") BigDecimal debit,
      @NotNull @DecimalMin("0") BigDecimal credit,String memo) {}
  public record ManualJournal(@NotNull LocalDate entryDate,@NotBlank String memo,@NotBlank String currencyCode,
      @NotEmpty List<@Valid ManualLine> lines,@NotBlank String idempotencyKey) {}
  @PostMapping("/finance/journals") @Transactional
  public Map<String,Object> manualJournal(@AuthenticationPrincipal UserContext u,@Valid @RequestBody ManualJournal r) {
    requireFinance(u);
    var existing=db.queryForList("SELECT id,journal_no journalNo,status FROM journal_entry WHERE tenant_id=? AND company_id=? AND idempotency_key=?",u.tenantId(),u.companyId(),"manual:"+r.idempotencyKey());
    if(!existing.isEmpty())return existing.getFirst();
    String code=currency(r.currencyCode());List<JournalLine> lines=new ArrayList<>();
    for(var l:r.lines()) {
      List<Map<String,Object>> account=db.queryForList("SELECT id FROM gl_account WHERE id=? AND tenant_id=? AND company_id=? AND active=TRUE",l.accountId(),u.tenantId(),u.companyId());
      if(account.isEmpty())throw ApiException.bad("Journal account is not active in the current company");
      if(l.debit().signum()>0&&l.credit().signum()>0)throw ApiException.bad("A journal line cannot contain both debit and credit");
      lines.add(new JournalLine(l.accountId(),l.memo(),money(l.debit()),money(l.credit()),code));
    }
    long id=journal(u,r.entryDate(),r.memo(),"MANUAL",0,"manual:"+r.idempotencyKey(),null,lines);audit(u,"JOURNAL_POSTED","journal_entry",id,Map.of("memo",r.memo()));return Map.of("id",id,"status","POSTED");
  }
  @GetMapping("/finance/journals")
  public List<Map<String,Object>> journals(@AuthenticationPrincipal UserContext u) {
    requireFinanceRead(u);
    return db.queryForList("SELECT j.id,j.journal_no journalNo,j.entry_date entryDate,j.memo,j.source_type sourceType,j.source_id sourceId,j.status,COALESCE(SUM(l.debit),0) debit,COALESCE(SUM(l.credit),0) credit FROM journal_entry j LEFT JOIN journal_line l ON l.journal_id=j.id WHERE j.tenant_id=? AND j.company_id=? GROUP BY j.id ORDER BY j.id DESC LIMIT 200",u.tenantId(),u.companyId());
  }
  @PostMapping("/finance/journals/{id}/reverse") @Transactional
  public Map<String,Object> reverseJournal(@PathVariable long id,@AuthenticationPrincipal UserContext u,@RequestBody Map<String,Object> body) {
    requireFinance(u);var original=scoped("journal_entry",id,u,true);
    if("DRAFT".equals(original.get("status")))throw ApiException.conflict("Only posted journals can be reversed");
    Integer reversed=db.queryForObject("SELECT COUNT(*) FROM journal_entry WHERE tenant_id=? AND company_id=? AND reverses_journal_id=?",Integer.class,u.tenantId(),u.companyId(),id);
    if(reversed!=null&&reversed>0)throw ApiException.conflict("Journal already has a reversal");
    LocalDate date=LocalDate.parse(String.valueOf(body.getOrDefault("entryDate",LocalDate.now())));
    String key="reverse:"+id;
    List<Map<String,Object>> old=db.queryForList("SELECT l.account_id,a.account_code,a.name,l.memo,l.debit,l.credit,l.currency_code FROM journal_line l JOIN gl_account a ON a.id=l.account_id WHERE l.journal_id=?",id);
    List<JournalLine> lines=old.stream().map(l->new JournalLine(((Number)l.get("account_id")).longValue(),"Reversal: "+l.get("memo"),(BigDecimal)l.get("credit"),(BigDecimal)l.get("debit"),String.valueOf(l.get("currency_code")))).toList();
    long reversal=journal(u,date,"Reversal of "+original.get("journal_no"),"REVERSAL",id,key,id,lines);audit(u,"JOURNAL_REVERSED","journal_entry",id,Map.of("reversalId",reversal));return Map.of("id",reversal,"status","POSTED","reversesJournalId",id);
  }
  @GetMapping("/finance/reports/trial-balance")
  public List<Map<String,Object>> trialBalance(@AuthenticationPrincipal UserContext u) {
    requireFinanceRead(u);
    return db.queryForList("SELECT a.account_code accountCode,a.name,a.account_type accountType,COALESCE(SUM(l.debit),0) debits,COALESCE(SUM(l.credit),0) credits,COALESCE(SUM(l.debit-l.credit),0) balance FROM gl_account a LEFT JOIN journal_line l ON l.account_id=a.id LEFT JOIN journal_entry j ON j.id=l.journal_id AND j.tenant_id=a.tenant_id AND j.company_id=a.company_id AND j.status='POSTED' WHERE a.tenant_id=? AND a.company_id=? GROUP BY a.id ORDER BY a.account_code",u.tenantId(),u.companyId());
  }
  @GetMapping("/finance/reports/receivables")
  public Map<String,Object> receivables(@AuthenticationPrincipal UserContext u) {
    requireFinanceRead(u);
    List<Map<String,Object>> rows=invoices(u);
    BigDecimal total=rows.stream().map(x->(BigDecimal)x.get("balance")).reduce(BigDecimal.ZERO,BigDecimal::add);
    return Map.of("totalOutstanding",total,"currencyCode",rows.isEmpty()?"USD":rows.getFirst().get("currencyCode"),"invoices",rows);
  }
}
