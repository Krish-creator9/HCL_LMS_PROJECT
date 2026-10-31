package com.ordercraft.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.math.BigDecimal;

@SpringBootApplication
public class OrderCraftApplication {
  public static void main(String[] args) { SpringApplication.run(OrderCraftApplication.class, args); }
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
  @Bean ApplicationRunner seed(JdbcTemplate db, PasswordEncoder encoder, org.springframework.core.env.Environment env) {
    return args -> {
      if (!Boolean.parseBoolean(env.getProperty("ordercraft.demo-seed", "true"))) return;
      if (db.queryForObject("SELECT COUNT(*) FROM user_account", Integer.class) > 0) return;
      db.update("INSERT INTO user_account(tenant_id,email,password_hash,full_name) VALUES(1,?,?,?)", "admin@ordercraft.local", encoder.encode("OrderCraft2026!"), "Jordan Lee");
      db.update("INSERT INTO user_company_assignment(user_id,company_id,role_name) VALUES(1,1,'ADMIN')");
      db.update("INSERT INTO customer(tenant_id,company_id,code,name,email) VALUES(1,1,'C-1001','Northstar Mobility','orders@northstar.example'),(1,1,'C-1002','Apex Robotics','procurement@apex.example')");
      db.update("INSERT INTO supplier(tenant_id,company_id,code,name,lead_days) VALUES(1,1,'S-2001','Alloy & Co.',7),(1,1,'S-2002','Precision Components',4)");
      db.update("INSERT INTO warehouse(tenant_id,company_id,code,name) VALUES(1,1,'MAIN','Main Warehouse'),(1,1,'FG','Finished Goods')");
      db.update("INSERT INTO product(tenant_id,company_id,sku,name,kind,unit,reorder_point) VALUES(1,1,'FG-100','Hydraulic Pump Assembly','FINISHED','ea',0),(1,1,'RM-210','6061 Aluminum Housing','MATERIAL','ea',12),(1,1,'RM-220','Seal Kit 42mm','COMPONENT','ea',20),(1,1,'RM-230','Drive Motor 1.5kW','COMPONENT','ea',8)");
      db.update("INSERT INTO bom(tenant_id,company_id,product_id,revision,status) VALUES(1,1,1,3,'ACTIVE')");
      db.update("INSERT INTO bom_line(bom_id,component_id,quantity) VALUES(1,2,1),(1,3,2),(1,4,1)");
      db.update("INSERT INTO inventory_movement(tenant_id,company_id,warehouse_id,product_id,movement_type,quantity,reference_type,idempotency_key,note) VALUES(1,1,1,2,'RECEIVE',18,'SEED','seed-rm210','Opening demo balance'),(1,1,1,3,'RECEIVE',34,'SEED','seed-rm220','Opening demo balance'),(1,1,1,4,'RECEIVE',11,'SEED','seed-rm230','Opening demo balance'),(1,1,2,1,'FINISH',24,'SEED','seed-fg100','Opening demo balance')");
      db.update("INSERT INTO customer_order(tenant_id,company_id,order_no,customer_id,status,due_date) VALUES(1,1,'ORD-2026-1042',1,'CONFIRMED','2026-10-10'),(1,1,'ORD-2026-1043',2,'DRAFT','2026-10-14')");
      db.update("INSERT INTO order_line(order_id,product_id,sku_snapshot,name_snapshot,quantity,bom_revision_snapshot,unit_snapshot) VALUES(1,1,'FG-100','Hydraulic Pump Assembly',12,3,'ea'),(2,1,'FG-100','Hydraulic Pump Assembly',8,3,'ea')");
      db.update("INSERT INTO manufacturing_order(tenant_id,company_id,work_no,order_line_id,product_id,quantity,status) VALUES(1,1,'WO-2026-0318',1,1,12,'MATERIAL_SHORTAGE')");
      db.update("INSERT INTO work_material(work_order_id,component_id,sku_snapshot,name_snapshot,unit_snapshot,quantity_per,required_quantity) VALUES(1,2,'RM-210','6061 Aluminum Housing','ea',1,12),(1,3,'RM-220','Seal Kit 42mm','ea',2,24),(1,4,'RM-230','Drive Motor 1.5kW','ea',1,12)");
      db.update("INSERT INTO audit_event(tenant_id,company_id,actor_id,event_type,entity_type,entity_id,detail_json) VALUES(1,1,1,'DEMO_SEEDED','tenant',1,'{}')");
    };
  }
}



