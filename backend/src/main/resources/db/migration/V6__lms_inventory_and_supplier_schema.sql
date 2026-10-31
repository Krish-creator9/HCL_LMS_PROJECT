-- ============================================================================
-- V6: LMS Inventory & Supplier Management Schema & Views
-- Implements portal-specified tables:
--   - suppliers
--   - inventory_items
--   - purchase_orders
-- And automated monitoring views:
--   - view_low_inventory_alerts
--   - view_out_of_stock_alerts
-- ============================================================================

CREATE TABLE IF NOT EXISTS suppliers (
    supplier_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    supplier_name VARCHAR(160) NOT NULL,
    contact_details VARCHAR(255),
    lead_time INT NOT NULL DEFAULT 5,
    quality_rating DECIMAL(3,2) NOT NULL DEFAULT 5.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

-- Views as specified in the LMS requirements
CREATE OR REPLACE VIEW view_low_inventory_alerts AS
SELECT item_id, item_name, quantity_available
FROM inventory_items
WHERE quantity_available < reorder_threshold;

CREATE OR REPLACE VIEW view_out_of_stock_alerts AS
SELECT item_id, item_name
FROM inventory_items
WHERE quantity_available = 0;

-- Initial seeded sample data for LMS evaluation verification
INSERT INTO suppliers (supplier_id, supplier_name, contact_details, lead_time, quality_rating)
VALUES 
(1, 'Alloy & Co.', 'contact@alloyco.example', 7, 4.80),
(2, 'Precision Components', 'sales@precisioncomp.example', 4, 4.95)
ON DUPLICATE KEY UPDATE supplier_name=VALUES(supplier_name);

INSERT INTO inventory_items (item_id, item_name, quantity_available, reorder_threshold, supplier_id)
VALUES
(1, 'Hydraulic Pump Assembly', 24.000, 10.000, 1),
(2, '6061 Aluminum Housing', 8.000, 15.000, 1),
(3, 'Seal Kit 42mm', 0.000, 20.000, 2),
(4, 'Drive Motor 1.5kW', 5.000, 8.000, 2)
ON DUPLICATE KEY UPDATE item_name=VALUES(item_name);

INSERT INTO purchase_orders (po_id, item_id, quantity_ordered, order_date, delivery_date, status)
VALUES
(1, 2, 50.000, CURRENT_DATE, CURRENT_DATE + INTERVAL 7 DAY, 'approved'),
(2, 3, 100.000, CURRENT_DATE, CURRENT_DATE + INTERVAL 4 DAY, 'created')
ON DUPLICATE KEY UPDATE status=VALUES(status);
