package com.ordercraft.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.sql.Statement;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class FactoryController {
  private final JdbcTemplate db;
  private final tools.jackson.databind.ObjectMapper json;
  private final ExternalAdapters.Sandbox sandbox;
  public FactoryController(JdbcTemplate db,tools.jackson.databind.ObjectMapper json,ExternalAdapters.Sandbox sandbox) {
    this.db=db;this.json=json;this.sandbox=sandbox;
  }
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
    if(rows.isEmpty())throw ApiException.missing("Record not found");return rows.getFirst();
  }
  private void planner(UserContext u) { if(!Set.of("ADMIN","MANAGER","PLANNER").contains(u.role()))throw ApiException.forbidden("Planner access required"); }
  private void integrationAdmin(UserContext u) { if(!Set.of("ADMIN","INTEGRATION_ADMIN").contains(u.role()))throw ApiException.forbidden("Integration administrator access required"); }
  private void ensureWorkCenter(UserContext u) {
    Integer count=db.queryForObject("SELECT COUNT(*) FROM work_center WHERE tenant_id=? AND company_id=?",Integer.class,u.tenantId(),u.companyId());
    if(count!=null&&count>0)return;
    long id=insert("INSERT INTO work_center(tenant_id,company_id,code,name,capacity_minutes_per_day,minutes_per_unit) VALUES(?,?,? ,?,?,?)",
      u.tenantId(),u.companyId(),"CELL-01","Assembly Cell 01",480,60);
    for(int day=1;day<=5;day++)db.update("INSERT IGNORE INTO work_shift(tenant_id,company_id,work_center_id,day_of_week,starts_at,ends_at) VALUES(?,?,?,?,?,?)",
      u.tenantId(),u.companyId(),id,day,LocalTime.of(8,0),LocalTime.of(16,0));
  }
  @GetMapping("/work-centers")
  public List<Map<String,Object>> workCenters(@AuthenticationPrincipal UserContext u) {
    ensureWorkCenter(u);
    return db.queryForList("SELECT id,code,name,capacity_minutes_per_day capacityMinutesPerDay,minutes_per_unit minutesPerUnit,active FROM work_center WHERE tenant_id=? AND company_id=? ORDER BY code",u.tenantId(),u.companyId());
  }
  public record WorkCenterRequest(@NotBlank String code,@NotBlank String name,
      @NotNull Integer capacityMinutesPerDay,@NotNull Integer minutesPerUnit) {}
  @PostMapping("/work-centers") @Transactional
  public Map<String,Object> createWorkCenter(@AuthenticationPrincipal UserContext u,@Valid @RequestBody WorkCenterRequest r) {
    planner(u);if(r.capacityMinutesPerDay()<1||r.capacityMinutesPerDay()>1440||r.minutesPerUnit()<1||r.minutesPerUnit()>1440)throw ApiException.bad("Work center capacity and operation duration must be between 1 and 1440 minutes");
    long id=insert("INSERT INTO work_center(tenant_id,company_id,code,name,capacity_minutes_per_day,minutes_per_unit) VALUES(?,?,?,?,?,?)",u.tenantId(),u.companyId(),r.code().trim(),r.name().trim(),r.capacityMinutesPerDay(),r.minutesPerUnit());
    for(int day=1;day<=5;day++)db.update("INSERT INTO work_shift(tenant_id,company_id,work_center_id,day_of_week,starts_at,ends_at) VALUES(?,?,?,?,?,?)",u.tenantId(),u.companyId(),id,day,LocalTime.of(8,0),LocalTime.of(16,0));
    audit(u,"WORK_CENTER_CREATED","work_center",id,Map.of("code",r.code(),"capacityMinutesPerDay",r.capacityMinutesPerDay()));return Map.of("id",id,"code",r.code(),"status","ACTIVE");
  }
  public record Shift(@NotNull Integer dayOfWeek,@NotNull LocalTime startsAt,@NotNull LocalTime endsAt) {}
  @PutMapping("/work-centers/{id}/shifts") @Transactional
  public Map<String,Object> setShifts(@PathVariable long id,@AuthenticationPrincipal UserContext u,@RequestBody List<@Valid Shift> shifts) {
    planner(u);scoped("work_center",id,u,true);if(shifts==null||shifts.isEmpty())throw ApiException.bad("At least one work shift is required");
    for(var s:shifts)if(s.dayOfWeek()<1||s.dayOfWeek()>7||!s.startsAt().isBefore(s.endsAt()))throw ApiException.bad("Shift day or time range is invalid");
    db.update("DELETE FROM work_shift WHERE work_center_id=? AND tenant_id=? AND company_id=?",id,u.tenantId(),u.companyId());
    for(var s:shifts)db.update("INSERT INTO work_shift(tenant_id,company_id,work_center_id,day_of_week,starts_at,ends_at) VALUES(?,?,?,?,?,?)",u.tenantId(),u.companyId(),id,s.dayOfWeek(),s.startsAt(),s.endsAt());
    audit(u,"WORK_SHIFTS_UPDATED","work_center",id,Map.of("shiftCount",shifts.size()));return Map.of("workCenterId",id,"shifts",shifts);
  }
  public record ScheduleRequest(@NotNull Long workOrderId,@NotNull Long workCenterId,
      @NotNull LocalDateTime startsAt,@NotBlank String idempotencyKey) {}
  @GetMapping("/schedule")
  public List<Map<String,Object>> schedule(@AuthenticationPrincipal UserContext u,
      @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
    LocalDate start=from==null?LocalDate.now():from,end=to==null?start.plusDays(14):to;
    if(end.isBefore(start)||end.isAfter(start.plusDays(366)))throw ApiException.bad("Schedule range must be within 366 days");
    return db.queryForList("SELECT s.id,s.work_order_id workOrderId,w.work_no workNo,w.status workOrderStatus,s.work_center_id workCenterId,c.code workCenter,c.name workCenterName,s.starts_at startsAt,s.ends_at endsAt,s.status,s.idempotency_key idempotencyKey FROM work_schedule s JOIN manufacturing_order w ON w.id=s.work_order_id JOIN work_center c ON c.id=s.work_center_id WHERE s.tenant_id=? AND s.company_id=? AND s.starts_at<? AND s.ends_at>=? ORDER BY s.starts_at,c.code",
      u.tenantId(),u.companyId(),end.plusDays(1).atStartOfDay(),start.atStartOfDay());
  }
  @PostMapping("/schedule") @Transactional
  public Map<String,Object> scheduleWork(@AuthenticationPrincipal UserContext u,@Valid @RequestBody ScheduleRequest r) {
    planner(u);if(r.idempotencyKey().length()>100)throw ApiException.bad("idempotencyKey is too long");
    var prior=db.queryForList("SELECT id,work_order_id workOrderId,work_center_id workCenterId,starts_at startsAt,ends_at endsAt,status FROM work_schedule WHERE tenant_id=? AND company_id=? AND idempotency_key=?",u.tenantId(),u.companyId(),r.idempotencyKey());
    if(!prior.isEmpty())return Map.of("idempotentReplay",true,"schedule",prior.getFirst());
    var center=scoped("work_center",r.workCenterId(),u,true);
    if(!Boolean.TRUE.equals(center.get("active")))throw ApiException.conflict("Work center is inactive");
    var work=scoped("manufacturing_order",r.workOrderId(),u,true);
    if("COMPLETED".equals(work.get("status")))throw ApiException.conflict("Completed work orders cannot be scheduled");
    var qty=(java.math.BigDecimal)work.get("quantity");var produced=(java.math.BigDecimal)work.get("produced");
    long minutes=qty.subtract(produced).multiply(java.math.BigDecimal.valueOf(((Number)center.get("minutes_per_unit")).longValue())).setScale(0,java.math.RoundingMode.CEILING).longValueExact();
    if(minutes<1||minutes>525600)throw ApiException.bad("Calculated operation duration is outside the supported range");
    LocalDateTime start=r.startsAt(),end=start.plusMinutes(minutes);
    List<Map<String,Object>> shift=db.queryForList("SELECT starts_at,ends_at FROM work_shift WHERE work_center_id=? AND tenant_id=? AND company_id=? AND day_of_week=? AND active=TRUE AND starts_at<=? AND ends_at>=?",
      r.workCenterId(),u.tenantId(),u.companyId(),start.getDayOfWeek().getValue(),start.toLocalTime(),end.toLocalTime());
    if(shift.isEmpty()||!start.toLocalDate().equals(end.toLocalDate()))throw ApiException.conflict("Operation must fit entirely within an active shift");
    Integer conflicts=db.queryForObject("SELECT COUNT(*) FROM work_schedule WHERE tenant_id=? AND company_id=? AND work_center_id=? AND status='SCHEDULED' AND starts_at<? AND ends_at>?",
      Integer.class,u.tenantId(),u.companyId(),r.workCenterId(),end,start);
    if(conflicts!=null&&conflicts>0)throw ApiException.conflict("Work center already has a scheduled operation during that time");
    Integer minutesBooked=db.queryForObject("SELECT COALESCE(SUM(TIMESTAMPDIFF(MINUTE,starts_at,ends_at)),0) FROM work_schedule WHERE tenant_id=? AND company_id=? AND work_center_id=? AND status='SCHEDULED' AND DATE(starts_at)=?",
      Integer.class,u.tenantId(),u.companyId(),r.workCenterId(),start.toLocalDate());
    if(minutesBooked!=null&&minutesBooked+minutes>((Number)center.get("capacity_minutes_per_day")).intValue())throw ApiException.conflict("Scheduling this operation would exceed the work center's daily capacity");
    long id=insert("INSERT INTO work_schedule(tenant_id,company_id,work_order_id,work_center_id,starts_at,ends_at,status,idempotency_key,created_by) VALUES(?,?,?,?,?,?,'SCHEDULED',?,?)",
      u.tenantId(),u.companyId(),r.workOrderId(),r.workCenterId(),start,end,r.idempotencyKey(),u.userId());
    audit(u,"WORK_ORDER_SCHEDULED","work_schedule",id,Map.of("workOrderId",r.workOrderId(),"workCenterId",r.workCenterId(),"startsAt",start.toString(),"endsAt",end.toString()));
    return Map.of("id",id,"workOrderId",r.workOrderId(),"workCenterId",r.workCenterId(),"startsAt",start,"endsAt",end,"status","SCHEDULED");
  }
  @PostMapping("/schedule/{id}/reschedule") @Transactional
  public Map<String,Object> reschedule(@PathVariable long id,@AuthenticationPrincipal UserContext u,@RequestBody Map<String,Object> body) {
    planner(u);var old=scoped("work_schedule",id,u,true);LocalDateTime start;
    try{start=LocalDateTime.parse(String.valueOf(body.get("startsAt")));}catch(Exception e){throw ApiException.bad("startsAt must be an ISO local date-time");}
    String key=String.valueOf(body.getOrDefault("idempotencyKey","reschedule:"+id+":"+UUID.randomUUID()));
    db.update("UPDATE work_schedule SET status='CANCELLED' WHERE id=? AND tenant_id=? AND company_id=?",id,u.tenantId(),u.companyId());
    Map<String,Object> created=scheduleWork(u,new ScheduleRequest(((Number)old.get("work_order_id")).longValue(),((Number)old.get("work_center_id")).longValue(),start,key));
    audit(u,"WORK_ORDER_RESCHEDULED","work_schedule",id,Map.of("newScheduleId",created.get("id"),"startsAt",start.toString()));
    return Map.of("previousScheduleId",id,"schedule",created);
  }
  @GetMapping("/shop-floor")
  public List<Map<String,Object>> shopFloor(@AuthenticationPrincipal UserContext u) {
    if(!Set.of("ADMIN","MANAGER","PRODUCTION","OPERATOR").contains(u.role()))throw ApiException.forbidden("Shop-floor access required");
    return db.queryForList("SELECT w.id,w.work_no workNo,w.status,w.quantity,w.produced,COALESCE(w.quantity-w.produced,0) remaining,p.sku,p.name productName,p.unit,o.order_no orderNo,(SELECT MIN(s.starts_at) FROM work_schedule s WHERE s.work_order_id=w.id AND s.tenant_id=w.tenant_id AND s.company_id=w.company_id AND s.status='SCHEDULED') nextStart,(SELECT c.name FROM work_schedule s JOIN work_center c ON c.id=s.work_center_id WHERE s.work_order_id=w.id AND s.tenant_id=w.tenant_id AND s.company_id=w.company_id AND s.status='SCHEDULED' ORDER BY s.starts_at LIMIT 1) workCenter FROM manufacturing_order w JOIN product p ON p.id=w.product_id JOIN order_line l ON l.id=w.order_line_id JOIN customer_order o ON o.id=l.order_id WHERE w.tenant_id=? AND w.company_id=? AND w.status IN ('PLANNED','MATERIAL_SHORTAGE','IN_PROGRESS') ORDER BY w.created_at,w.id",
      u.tenantId(),u.companyId());
  }
  @GetMapping("/barcodes/{entity}/{id}")
  public Map<String,Object> barcode(@PathVariable String entity,@PathVariable long id,@AuthenticationPrincipal UserContext u) {
    String table=switch(entity.toUpperCase(Locale.ROOT)){case "WORK","WORK_ORDER"->"manufacturing_order";case "ORDER"->"customer_order";case "PRODUCT"->"product";case "SHIPMENT"->"shipment";default->throw ApiException.bad("Unsupported barcode entity");};
    Map<String,Object> record=scoped(table,id,u,false);
    String kind=switch(entity.toUpperCase(Locale.ROOT)){case "WORK"->"WORK_ORDER";default->entity.toUpperCase(Locale.ROOT);};
    return Map.of("entity",kind,"recordId",id,"companyId",u.companyId(),"value","OC:"+kind+":"+u.companyId()+":"+id,"display",record.getOrDefault("work_no",record.getOrDefault("order_no",record.getOrDefault("sku",record.getOrDefault("id",id)))));
  }
  @GetMapping("/barcodes/resolve")
  public Map<String,Object> resolveBarcode(@RequestParam String value,@AuthenticationPrincipal UserContext u) {
    String[] parts=value.split(":",-1);if(parts.length!=4||!"OC".equals(parts[0]))throw ApiException.bad("Barcode is not an OrderCraft label");
    long company;long id;try{company=Long.parseLong(parts[2]);id=Long.parseLong(parts[3]);}catch(NumberFormatException e){throw ApiException.bad("Barcode value is invalid");}
    if(company!=u.companyId())throw ApiException.forbidden("Barcode is outside the active company");
    String table=switch(parts[1]){case "WORK_ORDER"->"manufacturing_order";case "ORDER"->"customer_order";case "PRODUCT"->"product";case "SHIPMENT"->"shipment";default->throw ApiException.bad("Unsupported barcode entity");};
    var record=scoped(table,id,u,false);return Map.of("entity",parts[1],"recordId",id,"record",record);
  }
  public record ScanRequest(@NotBlank String value,@NotBlank String actionType) {}
  @PostMapping("/barcodes/scan") @Transactional
  public Map<String,Object> scanBarcode(@AuthenticationPrincipal UserContext u,@Valid @RequestBody ScanRequest request) {
    String value=request.value().trim();String[] parts=value.split(":",-1);if(parts.length!=4||!"OC".equals(parts[0]))throw ApiException.bad("Barcode is not an OrderCraft label");
    long company;long id;try{company=Long.parseLong(parts[2]);id=Long.parseLong(parts[3]);}catch(NumberFormatException e){throw ApiException.bad("Barcode value is invalid");}
    if(company!=u.companyId())throw ApiException.forbidden("Barcode is outside the active company");
    String table=switch(parts[1]){case "WORK","WORK_ORDER"->"manufacturing_order";case "ORDER"->"customer_order";case "PRODUCT"->"product";case "SHIPMENT"->"shipment";case "LOCATION","WAREHOUSE"->"warehouse";default->throw ApiException.bad("Unsupported barcode entity");};
    var record=scoped(table,id,u,false);String kind=parts[1].equals("WORK")?"WORK_ORDER":parts[1];
    long scanId=insert("INSERT INTO scan_event(tenant_id,company_id,actor_id,barcode_value,entity_type,record_id,action_type,status,detail_json) VALUES(?,?,?,?,?,?,?,'RESOLVED',?)",
      u.tenantId(),u.companyId(),u.userId(),value,kind,id,request.actionType().toUpperCase(Locale.ROOT),json.writeValueAsString(Map.of("scanSource","MANUAL_OR_KEYBOARD_SCANNER")));
    audit(u,"BARCODE_SCANNED","scan_event",scanId,Map.of("entity",kind,"recordId",id,"action",request.actionType()));
    return Map.of("scanId",scanId,"entity",kind,"recordId",id,"record",record,"status","RESOLVED");
  }
  @GetMapping("/scans")
  public List<Map<String,Object>> scans(@AuthenticationPrincipal UserContext u) {
    if(!Set.of("ADMIN","MANAGER","PLANNER","PRODUCTION","OPERATOR","INVENTORY").contains(u.role()))throw ApiException.forbidden("Scan history access required");
    return db.queryForList("SELECT id,actor_id actorId,barcode_value barcodeValue,entity_type entityType,record_id recordId,action_type actionType,status,created_at createdAt FROM scan_event WHERE tenant_id=? AND company_id=? ORDER BY id DESC LIMIT 300",u.tenantId(),u.companyId());
  }
  public record DeviceRequest(@NotBlank String deviceCode,@NotBlank String name,Long workCenterId) {}
  @GetMapping("/devices")
  public List<Map<String,Object>> devices(@AuthenticationPrincipal UserContext u) {
    return db.queryForList("SELECT id,device_code deviceCode,name,work_center_id workCenterId,active FROM machine_device WHERE tenant_id=? AND company_id=? ORDER BY device_code",u.tenantId(),u.companyId());
  }
  @PostMapping("/devices") @Transactional
  public Map<String,Object> createDevice(@AuthenticationPrincipal UserContext u,@Valid @RequestBody DeviceRequest r) {
    integrationAdmin(u);if(r.workCenterId()!=null)scoped("work_center",r.workCenterId(),u,false);
    long id=insert("INSERT INTO machine_device(tenant_id,company_id,device_code,name,work_center_id) VALUES(?,?,?,?,?)",u.tenantId(),u.companyId(),r.deviceCode(),r.name(),r.workCenterId());
    audit(u,"MACHINE_DEVICE_REGISTERED","machine_device",id,Map.of("deviceCode",r.deviceCode()));return Map.of("id",id,"deviceCode",r.deviceCode(),"status","ACTIVE");
  }
  public record MachineEventRequest(@NotBlank String sourceEventId,@NotBlank String eventType,
      @NotNull LocalDateTime sourceTimestamp,@NotNull Map<String,Object> payload) {}
  @GetMapping("/machine-events")
  public List<Map<String,Object>> machineEvents(@AuthenticationPrincipal UserContext u) {
    return db.queryForList("SELECT e.id,d.device_code deviceCode,d.name deviceName,e.source_event_id sourceEventId,e.event_type eventType,e.source_timestamp sourceTimestamp,e.received_at receivedAt,e.payload_json payload,e.status,e.retry_count retryCount,e.error_text error FROM machine_event e JOIN machine_device d ON d.id=e.device_id WHERE e.tenant_id=? AND e.company_id=? ORDER BY e.id DESC LIMIT 300",u.tenantId(),u.companyId());
  }
  @PostMapping("/devices/{deviceCode}/events") @Transactional
  public Map<String,Object> machineEvent(@PathVariable String deviceCode,@AuthenticationPrincipal UserContext u,@Valid @RequestBody MachineEventRequest r) {
    integrationAdmin(u);
    var device=db.queryForList("SELECT id FROM machine_device WHERE device_code=? AND tenant_id=? AND company_id=? AND active=TRUE",deviceCode,u.tenantId(),u.companyId());
    if(device.isEmpty())throw ApiException.missing("Active device not found in the current company");
    long deviceId=((Number)device.getFirst().get("id")).longValue();
    var prior=db.queryForList("SELECT id,status FROM machine_event WHERE tenant_id=? AND company_id=? AND device_id=? AND source_event_id=?",u.tenantId(),u.companyId(),deviceId,r.sourceEventId());
    if(!prior.isEmpty())return Map.of("idempotentReplay",true,"event",prior.getFirst());
    LocalDateTime latest=db.query("SELECT MAX(source_timestamp) FROM machine_event WHERE tenant_id=? AND company_id=? AND device_id=?",rs->rs.next()?rs.getTimestamp(1)==null?null:rs.getTimestamp(1).toLocalDateTime():null,u.tenantId(),u.companyId(),deviceId);
    boolean outOfOrder=latest!=null&&r.sourceTimestamp().isBefore(latest);
    var mock=sandbox.accept(r.sourceEventId(),r.payload());String eventStatus=outOfOrder?"OUT_OF_ORDER":"RECEIVED";
    long id=insert("INSERT INTO machine_event(tenant_id,company_id,device_id,source_event_id,event_type,source_timestamp,payload_json,status) VALUES(?,?,?,?,?,?,?,?)",
      u.tenantId(),u.companyId(),deviceId,r.sourceEventId(),r.eventType().toUpperCase(Locale.ROOT),r.sourceTimestamp(),json.writeValueAsString(r.payload()),eventStatus);
    audit(u,"MACHINE_EVENT_"+eventStatus,"machine_event",id,Map.of("deviceCode",deviceCode,"sourceTimestamp",r.sourceTimestamp().toString(),"adapterStatus",mock.status()));
    return Map.of("id",id,"status",eventStatus,"adapterStatus",mock.status(),"requiresReview",outOfOrder);
  }
  @PostMapping("/machine-events/{id}/acknowledge") @Transactional
  public Map<String,Object> acknowledge(@PathVariable long id,@AuthenticationPrincipal UserContext u) {
    integrationAdmin(u);var event=scoped("machine_event",id,u,true);if(!"RECEIVED".equals(event.get("status")))throw ApiException.conflict("Only unreviewed machine events can be acknowledged");
    db.update("UPDATE machine_event SET status='ACKNOWLEDGED' WHERE id=? AND tenant_id=? AND company_id=?",id,u.tenantId(),u.companyId());
    audit(u,"MACHINE_EVENT_ACKNOWLEDGED","machine_event",id,Map.of());return Map.of("id",id,"status","ACKNOWLEDGED");
  }

  @GetMapping("/email/templates")
  public List<Map<String,Object>> emailTemplates(@AuthenticationPrincipal UserContext u) {
    return db.queryForList("SELECT id,template_code templateCode,subject_template subjectTemplate,body_template bodyTemplate,active FROM email_template WHERE tenant_id=? AND company_id=? ORDER BY template_code",u.tenantId(),u.companyId());
  }
  public record EmailTemplate(@NotBlank String templateCode,@NotBlank String subjectTemplate,@NotBlank String bodyTemplate) {}
  @PostMapping("/email/templates") @Transactional
  public Map<String,Object> saveTemplate(@AuthenticationPrincipal UserContext u,@Valid @RequestBody EmailTemplate r) {
    integrationAdmin(u);
    db.update("INSERT INTO email_template(tenant_id,company_id,template_code,subject_template,body_template) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE subject_template=VALUES(subject_template),body_template=VALUES(body_template),active=TRUE",
      u.tenantId(),u.companyId(),r.templateCode().toUpperCase(Locale.ROOT),r.subjectTemplate(),r.bodyTemplate());
    long id=db.queryForObject("SELECT id FROM email_template WHERE tenant_id=? AND company_id=? AND template_code=?",Long.class,u.tenantId(),u.companyId(),r.templateCode().toUpperCase(Locale.ROOT));
    audit(u,"EMAIL_TEMPLATE_SAVED","email_template",id,Map.of("templateCode",r.templateCode()));return Map.of("id",id,"templateCode",r.templateCode(),"status","ACTIVE");
  }
  public record EmailRequest(@NotBlank String templateCode,@NotBlank String recipient,@NotNull Long sourceId,
      @NotBlank String sourceType,@NotNull Map<String,String> values,@NotBlank String idempotencyKey,boolean simulateFailure) {}
  @GetMapping("/email/deliveries")
  public List<Map<String,Object>> emailDeliveries(@AuthenticationPrincipal UserContext u) {
    if(!Set.of("ADMIN","MANAGER","SALES","INTEGRATION_ADMIN").contains(u.role()))throw ApiException.forbidden("Email delivery access required");
    return db.queryForList("SELECT id,source_type sourceType,source_id sourceId,recipient,subject_snapshot subject,status,provider_ref providerReference,error_text error,retry_count retryCount,created_at createdAt FROM email_delivery WHERE tenant_id=? AND company_id=? ORDER BY id DESC LIMIT 200",u.tenantId(),u.companyId());
  }
  @PostMapping("/email/deliveries") @Transactional
  public Map<String,Object> sendEmail(@AuthenticationPrincipal UserContext u,@Valid @RequestBody EmailRequest r) {
    if(!Set.of("ADMIN","MANAGER","SALES","INTEGRATION_ADMIN").contains(u.role()))throw ApiException.forbidden("Email delivery access required");
    var previous=db.queryForList("SELECT id,status,provider_ref providerReference FROM email_delivery WHERE tenant_id=? AND company_id=? AND idempotency_key=?",u.tenantId(),u.companyId(),r.idempotencyKey());
    if(!previous.isEmpty())return Map.of("idempotentReplay",true,"delivery",previous.getFirst());
    var template=db.queryForList("SELECT * FROM email_template WHERE tenant_id=? AND company_id=? AND template_code=? AND active=TRUE",u.tenantId(),u.companyId(),r.templateCode().toUpperCase(Locale.ROOT));
    if(template.isEmpty())throw ApiException.missing("Active email template not found");
    var t=template.getFirst();String subject=render(String.valueOf(t.get("subject_template")),r.values()),body=render(String.valueOf(t.get("body_template")),r.values());
    var response=sandbox.deliver(r.idempotencyKey(),Map.of("recipient",r.recipient(),"subject",subject));
    boolean failed=r.simulateFailure();String status=failed?"FAILED":"DELIVERED",error=failed?"Simulated sandbox delivery failure":null,providerRef=failed?null:response.externalReference();
    long id=insert("INSERT INTO email_delivery(tenant_id,company_id,template_id,source_type,source_id,recipient,subject_snapshot,body_snapshot,status,provider_ref,error_text,idempotency_key) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
      u.tenantId(),u.companyId(),t.get("id"),r.sourceType(),r.sourceId(),r.recipient(),subject,body,status,providerRef,error,r.idempotencyKey());
    audit(u,"EMAIL_DELIVERY_"+status,"email_delivery",id,Map.of("recipient",r.recipient(),"adapterMode","MOCK"));
    Map<String,Object> out=new LinkedHashMap<>();out.put("id",id);out.put("status",status);out.put("providerReference",providerRef);if(error!=null)out.put("error",error);return out;
  }
  private String render(String source,Map<String,String> values) {
    String out=source;for(var entry:values.entrySet())out=out.replace("{{"+entry.getKey()+"}}",entry.getValue()==null?"":entry.getValue());return out;
  }
  @PostMapping("/email/deliveries/{id}/retry") @Transactional
  public Map<String,Object> retryEmail(@PathVariable long id,@AuthenticationPrincipal UserContext u) {
    integrationAdmin(u);
    var d=scoped("email_delivery",id,u,true);if(!"FAILED".equals(d.get("status")))throw ApiException.conflict("Only failed sandbox deliveries can be retried");
    var result=sandbox.deliver(String.valueOf(d.get("idempotency_key"))+":retry:"+d.get("retry_count"),Map.of("recipient",d.get("recipient")));
    db.update("UPDATE email_delivery SET status='DELIVERED',provider_ref=?,error_text=NULL,retry_count=retry_count+1 WHERE id=? AND tenant_id=? AND company_id=?",result.externalReference(),id,u.tenantId(),u.companyId());
    audit(u,"EMAIL_DELIVERY_RETRIED","email_delivery",id,Map.of("attempt",((Number)d.get("retry_count")).intValue()+1));return Map.of("id",id,"status","DELIVERED","providerReference",result.externalReference());
  }

  @PostMapping("/shipments/{id}/carrier-label") @Transactional
  public Map<String,Object> carrierLabel(@PathVariable long id,@AuthenticationPrincipal UserContext u,@RequestBody Map<String,Object> body) {
    if(!Set.of("ADMIN","MANAGER","INVENTORY").contains(u.role()))throw ApiException.forbidden("Inventory access required to create shipment labels");
    var shipment=scoped("shipment",id,u,true);String key=String.valueOf(body.getOrDefault("idempotencyKey","carrier:"+id));
    var previous=db.queryForList("SELECT id,status,external_ref externalReference,tracking_number trackingNumber,label_payload label FROM carrier_shipment WHERE tenant_id=? AND company_id=? AND shipment_id=? AND adapter_code='MOCK'",
      u.tenantId(),u.companyId(),id);if(!previous.isEmpty())return Map.of("idempotentReplay",true,"carrierShipment",previous.getFirst());
    var result=sandbox.createLabel(key,Map.of("shipmentId",id,"orderId",shipment.get("order_id")));
    String tracking="MOCK-"+result.externalReference();
    long carrierId=insert("INSERT INTO carrier_shipment(tenant_id,company_id,shipment_id,adapter_code,status,external_ref,tracking_number,label_payload,request_json,response_json,idempotency_key) VALUES(?,?,?,'MOCK','LABEL_READY',?,?,?,?,?,?)",
      u.tenantId(),u.companyId(),id,result.externalReference(),tracking,"SANDBOX LABEL — not valid for postage",json.writeValueAsString(body),json.writeValueAsString(result.details()),key);
    if(shipment.get("tracking_number")==null)db.update("UPDATE shipment SET tracking_number=? WHERE id=? AND tenant_id=? AND company_id=?",tracking,id,u.tenantId(),u.companyId());
    audit(u,"MOCK_CARRIER_LABEL_CREATED","carrier_shipment",carrierId,Map.of("adapter","MOCK","externalReference",result.externalReference()));
    return Map.of("id",carrierId,"status","LABEL_READY","trackingNumber",tracking,"label","SANDBOX LABEL — not valid for postage","mode","MOCK");
  }
  @GetMapping("/carrier-shipments")
  public List<Map<String,Object>> carrierShipments(@AuthenticationPrincipal UserContext u) {
    return db.queryForList("SELECT c.id,c.shipment_id shipmentId,s.order_id orderId,c.adapter_code adapter,c.status,c.external_ref externalReference,c.tracking_number trackingNumber,c.label_payload label,c.created_at createdAt FROM carrier_shipment c JOIN shipment s ON s.id=c.shipment_id WHERE c.tenant_id=? AND c.company_id=? ORDER BY c.id DESC",u.tenantId(),u.companyId());
  }
  public record CarrierEventRequest(@NotBlank String sourceEventId,@NotBlank String status,
      @NotNull LocalDateTime sourceTimestamp,@NotNull Map<String,Object> payload) {}
  @GetMapping("/carrier-shipments/{id}/events")
  public List<Map<String,Object>> carrierEvents(@PathVariable long id,@AuthenticationPrincipal UserContext u) {
    scoped("carrier_shipment",id,u,false);
    return db.queryForList("SELECT id,source_event_id sourceEventId,status,source_timestamp sourceTimestamp,payload_json payload,created_at createdAt FROM carrier_event WHERE carrier_shipment_id=? AND tenant_id=? AND company_id=? ORDER BY id",id,u.tenantId(),u.companyId());
  }
  @PostMapping("/carrier-shipments/{id}/events") @Transactional
  public Map<String,Object> carrierEvent(@PathVariable long id,@AuthenticationPrincipal UserContext u,@Valid @RequestBody CarrierEventRequest r) {
    if(!Set.of("ADMIN","MANAGER","INTEGRATION_ADMIN","INVENTORY").contains(u.role()))throw ApiException.forbidden("Carrier update access required");
    var carrier=scoped("carrier_shipment",id,u,true);String status=r.status().toUpperCase(Locale.ROOT);
    if(!Set.of("PICKED_UP","IN_TRANSIT","DELIVERED","EXCEPTION").contains(status))throw ApiException.bad("Unsupported sandbox carrier status");
    var prior=db.queryForList("SELECT id,status FROM carrier_event WHERE carrier_shipment_id=? AND tenant_id=? AND company_id=? AND source_event_id=?",id,u.tenantId(),u.companyId(),r.sourceEventId());
    if(!prior.isEmpty())return Map.of("idempotentReplay",true,"event",prior.getFirst());
    String current=String.valueOf(carrier.get("status"));
    if("DELIVERED".equals(current)||"EXCEPTION".equals(current))throw ApiException.conflict("Delivered or exception shipments cannot be advanced without a correction event");
    Map<String,Integer> stage=Map.of("LABEL_READY",0,"PICKED_UP",1,"IN_TRANSIT",2,"DELIVERED",3,"EXCEPTION",3);
    if(stage.containsKey(current)&&stage.containsKey(status)&&stage.get(status)<stage.get(current))throw ApiException.conflict("Carrier status cannot move backwards");
    LocalDateTime latest=db.query("SELECT MAX(source_timestamp) FROM carrier_event WHERE carrier_shipment_id=? AND tenant_id=? AND company_id=?",rs->rs.next()?rs.getTimestamp(1)==null?null:rs.getTimestamp(1).toLocalDateTime():null,id,u.tenantId(),u.companyId());
    boolean outOfOrder=latest!=null&&r.sourceTimestamp().isBefore(latest);
    var mock=sandbox.synchronize(r.sourceEventId(),r.payload());
    String processed=outOfOrder?"REVIEW":"APPLIED";
    long eventId=insert("INSERT INTO carrier_event(tenant_id,company_id,carrier_shipment_id,source_event_id,status,source_timestamp,processing_status,payload_json) VALUES(?,?,?,?,?,?,?,?)",
      u.tenantId(),u.companyId(),id,r.sourceEventId(),status,r.sourceTimestamp(),processed,json.writeValueAsString(r.payload()));
    if(!outOfOrder)db.update("UPDATE carrier_shipment SET status=? WHERE id=? AND tenant_id=? AND company_id=?",status,id,u.tenantId(),u.companyId());
    audit(u,"CARRIER_STATUS_RECEIVED","carrier_event",eventId,Map.of("carrierShipmentId",id,"status",status,"processingStatus",processed,"adapterMode","MOCK"));
    return Map.of("id",eventId,"status",status,"processingStatus",processed,"adapterStatus",mock.status());
  }
  public record SyncRequest(@NotBlank String adapterCode,@NotBlank String sourceType,@NotNull Long sourceId,
      String externalId,@NotNull Map<String,Object> payload,@NotBlank String idempotencyKey) {}
  @GetMapping("/integrations/erp-sync")
  public List<Map<String,Object>> syncJobs(@AuthenticationPrincipal UserContext u) {
    return db.queryForList("SELECT id,adapter_code adapter,source_type sourceType,source_id sourceId,external_id externalId,status,error_text error,retry_count retryCount,created_at createdAt FROM erp_sync_job WHERE tenant_id=? AND company_id=? ORDER BY id DESC LIMIT 200",u.tenantId(),u.companyId());
  }
  @PostMapping("/integrations/erp-sync") @Transactional
  public Map<String,Object> queueSync(@AuthenticationPrincipal UserContext u,@Valid @RequestBody SyncRequest r) {
    integrationAdmin(u);
    var prior=db.queryForList("SELECT id,status FROM erp_sync_job WHERE tenant_id=? AND company_id=? AND adapter_code=? AND idempotency_key=?",u.tenantId(),u.companyId(),r.adapterCode(),r.idempotencyKey());
    if(!prior.isEmpty())return Map.of("idempotentReplay",true,"job",prior.getFirst());
    long id=insert("INSERT INTO erp_sync_job(tenant_id,company_id,adapter_code,source_type,source_id,external_id,payload_json,status,idempotency_key) VALUES(?,?,?,?,?,?,?,'QUEUED',?)",
      u.tenantId(),u.companyId(),r.adapterCode().toUpperCase(Locale.ROOT),r.sourceType().toUpperCase(Locale.ROOT),r.sourceId(),r.externalId(),json.writeValueAsString(r.payload()),r.idempotencyKey());
    audit(u,"ERP_SYNC_QUEUED","erp_sync_job",id,Map.of("adapter",r.adapterCode(),"sourceType",r.sourceType()));return Map.of("id",id,"status","QUEUED");
  }
  @PostMapping("/integrations/erp-sync/{id}/run") @Transactional
  public Map<String,Object> runSync(@PathVariable long id,@AuthenticationPrincipal UserContext u) {
    integrationAdmin(u);
    var job=scoped("erp_sync_job",id,u,true);if("SUCCEEDED".equals(job.get("status")))return Map.of("id",id,"status","SUCCEEDED","externalId",job.getOrDefault("external_id",""),"idempotentReplay",true);
    Map<String,Object> payload;
    try{payload=json.readValue(String.valueOf(job.get("payload_json")),Map.class);}catch(Exception e){throw ApiException.bad("Queued sync payload is invalid");}
    var result=sandbox.synchronize(String.valueOf(job.get("idempotency_key")),payload);
    String existingId=(String)job.get("external_id");
    if(existingId!=null) {
      Integer matches=db.queryForObject("SELECT COUNT(*) FROM erp_sync_job WHERE tenant_id=? AND company_id=? AND adapter_code=? AND external_id=? AND id<>?",Integer.class,u.tenantId(),u.companyId(),job.get("adapter_code"),existingId,id);
      if(matches!=null&&matches>0) {
        db.update("UPDATE erp_sync_job SET status='CONFLICT',error_text='External identifier is already mapped to a different local record',retry_count=retry_count+1 WHERE id=? AND tenant_id=? AND company_id=?",id,u.tenantId(),u.companyId());
        audit(u,"ERP_SYNC_CONFLICT","erp_sync_job",id,Map.of("externalId",existingId));
        return Map.of("id",id,"status","CONFLICT","error","External identifier is already mapped to a different local record");
      }
    }
    if(Boolean.TRUE.equals(payload.get("simulateFailure"))) {
      db.update("UPDATE erp_sync_job SET status='FAILED',error_text='Simulated sandbox connector failure',retry_count=retry_count+1 WHERE id=? AND tenant_id=? AND company_id=?",id,u.tenantId(),u.companyId());
      audit(u,"ERP_SYNC_FAILED","erp_sync_job",id,Map.of("mode","MOCK"));
      return Map.of("id",id,"status","FAILED","error","Simulated sandbox connector failure");
    }
    db.update("UPDATE erp_sync_job SET status='SUCCEEDED',external_id=COALESCE(external_id,?),error_text=NULL,retry_count=retry_count+1 WHERE id=? AND tenant_id=? AND company_id=?",result.externalReference(),id,u.tenantId(),u.companyId());
    audit(u,"ERP_SYNC_SUCCEEDED","erp_sync_job",id,Map.of("externalReference",result.externalReference()));return Map.of("id",id,"status","SUCCEEDED","externalId",result.externalReference(),"mode","MOCK");
  }
  @GetMapping("/integrations/adapters")
  public List<Map<String,Object>> adapters() {
    return List.of(Map.of("code","PAYMENT_MOCK","category","PAYMENT","mode","SANDBOX","status","READY"),Map.of("code","TAX_EINVOICE_MOCK","category","TAX_EINVOICE","mode","SANDBOX","status","READY"),Map.of("code","BANK_IMPORT","category","BANK","mode","MANUAL_IMPORT","status","READY"),Map.of("code","CARRIER_MOCK","category","SHIPPING","mode","SANDBOX","status","READY"),Map.of("code","EMAIL_MOCK","category","EMAIL","mode","SANDBOX","status","READY"),Map.of("code","ERP_CRM_MOCK","category","ERP_CRM","mode","SANDBOX","status","READY"),Map.of("code","MACHINE_INGEST","category","IOT","mode","EVENT_INGEST","status","READY"));
  }
  @PostMapping("/integrations/invoices/{id}/{adapter}") @Transactional
  public Map<String,Object> invoiceAdapter(@PathVariable long id,@PathVariable String adapter,@AuthenticationPrincipal UserContext u,@RequestBody Map<String,Object> body) {
    if(!Set.of("ADMIN","INTEGRATION_ADMIN","FINANCE").contains(u.role()))throw ApiException.forbidden("Finance or integration administrator access required");
    Map<String,Object> invoice=scoped("invoice",id,u,true);String type=adapter.toUpperCase(Locale.ROOT);
    if(!Set.of("PAYMENT_MOCK","TAX_EINVOICE_MOCK").contains(type))throw ApiException.bad("Unsupported invoice adapter");
    String key=String.valueOf(body.getOrDefault("idempotencyKey",type.toLowerCase(Locale.ROOT)+":"+id));
    var existing=db.queryForList("SELECT id,status,external_ref externalReference,error_text error,retry_count retryCount FROM adapter_transaction WHERE tenant_id=? AND company_id=? AND adapter_type=? AND idempotency_key=?",u.tenantId(),u.companyId(),type,key);
    if(!existing.isEmpty())return Map.of("idempotentReplay",true,"transaction",existing.getFirst());
    Map<String,Object> request=new LinkedHashMap<>();request.put("invoiceId",id);request.put("invoiceNo",invoice.get("invoice_no"));request.putAll(body);
    ExternalAdapters.Result result=type.equals("PAYMENT_MOCK")?sandbox.record(key,request):sandbox.submit(key,request);
    boolean failed=Boolean.TRUE.equals(body.get("simulateFailure"));String status=failed?"FAILED":"SUCCEEDED";
    String external=failed?null:result.externalReference(),error=failed?"Simulated sandbox adapter failure":null;
    long tx=insert("INSERT INTO adapter_transaction(tenant_id,company_id,adapter_type,source_type,source_id,external_ref,status,request_json,response_json,error_text,idempotency_key) VALUES(?,?,?,'INVOICE',?,?,?,?,?,?,?)",
      u.tenantId(),u.companyId(),type,id,external,status,json.writeValueAsString(request),json.writeValueAsString(result.details()),error,key);
    audit(u,"ADAPTER_TRANSACTION_"+status,"adapter_transaction",tx,Map.of("adapter",type,"mode","MOCK"));
    Map<String,Object> response=new LinkedHashMap<>();response.put("id",tx);response.put("status",status);response.put("mode","MOCK");if(external!=null)response.put("externalReference",external);if(error!=null)response.put("error",error);return response;
  }
  @GetMapping("/integrations/transactions")
  public List<Map<String,Object>> adapterTransactions(@AuthenticationPrincipal UserContext u) {
    if(!Set.of("ADMIN","MANAGER","FINANCE","INTEGRATION_ADMIN").contains(u.role()))throw ApiException.forbidden("Integration transaction access required");
    return db.queryForList("SELECT id,adapter_type adapter,source_type sourceType,source_id sourceId,external_ref externalReference,status,error_text error,retry_count retryCount,created_at createdAt FROM adapter_transaction WHERE tenant_id=? AND company_id=? ORDER BY id DESC LIMIT 200",u.tenantId(),u.companyId());
  }
  @PostMapping("/integrations/transactions/{id}/retry") @Transactional
  public Map<String,Object> retryAdapter(@PathVariable long id,@AuthenticationPrincipal UserContext u) {
    integrationAdmin(u);var tx=scoped("adapter_transaction",id,u,true);
    if(!"FAILED".equals(tx.get("status")))throw ApiException.conflict("Only failed sandbox transactions can be retried");
    String type=String.valueOf(tx.get("adapter_type")),key=String.valueOf(tx.get("idempotency_key"));
    ExternalAdapters.Result result=switch(type){case "PAYMENT_MOCK"->sandbox.record(key+":retry:"+tx.get("retry_count"),Map.of("sourceId",tx.get("source_id")));case "TAX_EINVOICE_MOCK"->sandbox.submit(key+":retry:"+tx.get("retry_count"),Map.of("sourceId",tx.get("source_id")));default->throw ApiException.bad("This adapter transaction does not support manual retry");};
    db.update("UPDATE adapter_transaction SET status='SUCCEEDED',external_ref=?,error_text=NULL,retry_count=retry_count+1 WHERE id=? AND tenant_id=? AND company_id=?",result.externalReference(),id,u.tenantId(),u.companyId());
    audit(u,"ADAPTER_TRANSACTION_RETRIED","adapter_transaction",id,Map.of("adapter",type));
    return Map.of("id",id,"status","SUCCEEDED","externalReference",result.externalReference(),"retryCount",((Number)tx.get("retry_count")).intValue()+1);
  }
}
