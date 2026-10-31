package com.ordercraft.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/database")
public class DatabaseController {
  private final JdbcTemplate db;

  public DatabaseController(JdbcTemplate db) {
    this.db = db;
  }

  @GetMapping("/status")
  public Map<String, Object> getStatus(@AuthenticationPrincipal UserContext u) {
    String version = db.queryForObject("SELECT VERSION()", String.class);
    Integer tableCount = db.queryForObject(
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
      Integer.class
    );
    List<Map<String, Object>> migrations = db.queryForList(
      "SELECT version, description, type, script, installed_on installedOn, success FROM flyway_schema_history ORDER BY installed_rank DESC"
    );

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("status", "ONLINE");
    res.put("rdbms", "MySQL 8.0 LTS");
    res.put("databaseVersion", version != null ? version : "8.0");
    res.put("databaseName", "ordercraft");
    res.put("connectionUrl", "jdbc:mysql://localhost:3306/ordercraft");
    res.put("host", "localhost");
    res.put("port", 3306);
    res.put("user", "ordercraft_app");
    res.put("tableCount", tableCount != null ? tableCount : 0);
    res.put("migrations", migrations);
    res.put("authenticatedUser", u.email());
    res.put("currentTenant", u.tenantId());
    res.put("currentCompany", u.companyId());
    return res;
  }

  @GetMapping("/tables")
  public List<Map<String, Object>> getTables(@AuthenticationPrincipal UserContext u) {
    return db.queryForList(
      "SELECT table_name AS tableName, table_rows AS estimatedRows, " +
      "data_length AS dataBytes, create_time AS createdAt, update_time AS updatedAt " +
      "FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name"
    );
  }

  @GetMapping("/tables/{tableName}")
  public Map<String, Object> getTableData(
    @AuthenticationPrincipal UserContext u,
    @PathVariable String tableName,
    @RequestParam(defaultValue = "50") int limit
  ) {
    if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
      throw ApiException.bad("Invalid table name identifier");
    }

    Integer exists = db.queryForObject(
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
      Integer.class, tableName
    );
    if (exists == null || exists == 0) {
      throw ApiException.missing("Table '" + tableName + "' does not exist");
    }

    List<Map<String, Object>> columns = db.queryForList(
      "SELECT column_name AS columnName, data_type AS dataType, is_nullable AS isNullable, " +
      "column_key AS columnKey, column_default AS defaultValue " +
      "FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position",
      tableName
    );

    int rowLimit = Math.min(Math.max(limit, 1), 200);
    List<Map<String, Object>> rows = db.queryForList(
      "SELECT * FROM `" + tableName + "` ORDER BY 1 DESC LIMIT " + rowLimit
    );

    Long totalRows = db.queryForObject("SELECT COUNT(*) FROM `" + tableName + "`", Long.class);

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("tableName", tableName);
    res.put("columns", columns);
    res.put("rows", rows);
    res.put("totalRows", totalRows != null ? totalRows : rows.size());
    res.put("limit", rowLimit);
    return res;
  }

  @PostMapping("/tables/{tableName}/rows")
  public Map<String, Object> insertRow(
    @AuthenticationPrincipal UserContext u,
    @PathVariable String tableName,
    @RequestBody Map<String, Object> rowData
  ) {
    if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
      throw ApiException.bad("Invalid table name identifier");
    }

    Integer exists = db.queryForObject(
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
      Integer.class, tableName
    );
    if (exists == null || exists == 0) {
      throw ApiException.missing("Table '" + tableName + "' does not exist");
    }

    List<Map<String, Object>> columns = db.queryForList(
      "SELECT column_name AS columnName, data_type AS dataType, is_nullable AS isNullable, " +
      "column_key AS columnKey, extra AS extra " +
      "FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?",
      tableName
    );

    Set<String> validColumns = new HashSet<>();
    boolean hasTenantId = false;
    boolean hasCompanyId = false;
    for (Map<String, Object> col : columns) {
      String cName = String.valueOf(col.get("columnName"));
      validColumns.add(cName);
      if ("tenant_id".equalsIgnoreCase(cName)) hasTenantId = true;
      if ("company_id".equalsIgnoreCase(cName)) hasCompanyId = true;
    }

    Map<String, Object> payload = new LinkedHashMap<>(rowData);
    if (hasTenantId && (!payload.containsKey("tenant_id") || payload.get("tenant_id") == null || String.valueOf(payload.get("tenant_id")).isBlank())) {
      payload.put("tenant_id", u.tenantId());
    }
    if (hasCompanyId && (!payload.containsKey("company_id") || payload.get("company_id") == null || String.valueOf(payload.get("company_id")).isBlank())) {
      payload.put("company_id", u.companyId());
    }

    List<String> insertCols = new ArrayList<>();
    List<Object> insertVals = new ArrayList<>();
    for (Map.Entry<String, Object> entry : payload.entrySet()) {
      String key = entry.getKey();
      Object val = entry.getValue();
      if (validColumns.contains(key)) {
        boolean isAuto = columns.stream().anyMatch(c -> key.equalsIgnoreCase(String.valueOf(c.get("columnName")))
            && String.valueOf(c.get("extra")).toLowerCase().contains("auto_increment"));
        if (isAuto && (val == null || String.valueOf(val).isBlank())) {
          continue;
        }
        insertCols.add("`" + key + "`");
        insertVals.add(val != null && !String.valueOf(val).isBlank() ? val : null);
      }
    }

    if (insertCols.isEmpty()) {
      throw ApiException.bad("No valid columns provided for table " + tableName);
    }

    String sql = "INSERT INTO `" + tableName + "` (" + String.join(", ", insertCols) + ") VALUES (" +
        String.join(", ", Collections.nCopies(insertCols.size(), "?")) + ")";

    try {
      db.update(sql, insertVals.toArray());
    } catch (Exception e) {
      throw ApiException.bad("Failed to insert record: " + e.getMessage());
    }

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("status", "SUCCESS");
    res.put("tableName", tableName);
    res.put("message", "Record successfully inserted into " + tableName);
    return res;
  }
}
