-- ============================================================================
-- HCL Career Shaper LMS Portal — Inventory & Supplier Management SQL Script
-- ============================================================================
-- Tables:
--   1. suppliers: Stores vendor details, contact info, lead time, and quality ratings.
--   2. inventory_items: Tracks current on-hand quantities against reorder thresholds.
--   3. purchase_orders: Tracks procurement replenishment orders with suppliers.
--
-- Views:
--   1. view_low_inventory_alerts: Real-time alert feed for items below threshold.
--   2. view_out_of_stock_alerts: Immediate notification feed for zero-stock items.
-- ============================================================================

-- 1. Suppliers Table
CREATE TABLE IF NOT EXISTS suppliers (
    supplier_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    supplier_name VARCHAR(160) NOT NULL,
    contact_details VARCHAR(255),
    lead_time INT NOT NULL DEFAULT 5,
    quality_rating DECIMAL(3,2) NOT NULL DEFAULT 5.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Inventory Items Table
CREATE TABLE IF NOT EXISTS inventory_items (
    item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_name VARCHAR(160) NOT NULL,
    quantity_available DECIMAL(14,3) NOT NULL DEFAULT 0,
    reorder_threshold DECIMAL(14,3) NOT NULL DEFAULT 10,
    supplier_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_inventory_items_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Purchase Orders Table
CREATE TABLE IF NOT EXISTS purchase_orders (
    po_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    quantity_ordered DECIMAL(14,3) NOT NULL,
    order_date DATE NOT NULL,
    delivery_date DATE,
    status VARCHAR(40) NOT NULL DEFAULT 'created',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_po_inventory_item FOREIGN KEY (item_id) REFERENCES inventory_items(item_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. View: Low Inventory Alerts
-- Purpose: Provides real-time alerts for inventory levels below the predefined threshold.
CREATE OR REPLACE VIEW view_low_inventory_alerts AS
SELECT item_id, item_name, quantity_available
FROM inventory_items
WHERE quantity_available < reorder_threshold;

-- 5. View: Out of Stock Alerts
-- Purpose: Provides notifications for critical items out of stock.
CREATE OR REPLACE VIEW view_out_of_stock_alerts AS
SELECT item_id, item_name
FROM inventory_items
WHERE quantity_available = 0;

-- ============================================================================
-- Verification Queries
-- ============================================================================
-- Query Low Inventory Alerts:
-- SELECT * FROM view_low_inventory_alerts;
--
-- Query Out of Stock Alerts:
-- SELECT * FROM view_out_of_stock_alerts;
