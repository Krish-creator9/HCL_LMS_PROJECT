-- ============================================================================
-- HCL Career Shaper LMS Portal — MySQL 8.0 / 8.4 & TiDB Compatible Script
-- Direct conversion of the portal's Oracle SQL schema into production MySQL
-- ============================================================================

-- 1. Tables
CREATE TABLE IF NOT EXISTS Users (
    user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    is_admin TINYINT(1) DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS Products (
    product_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    price DECIMAL(10,2) NOT NULL,
    category VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS Orders (
    order_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    status VARCHAR(20) NOT NULL DEFAULT 'Pending',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_orders_users FOREIGN KEY (user_id) REFERENCES Users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS OrderItems (
    order_item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity DECIMAL(10,2) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_order_items_orders FOREIGN KEY (order_id) REFERENCES Orders(order_id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_products FOREIGN KEY (product_id) REFERENCES Products(product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Views
CREATE OR REPLACE VIEW UserDetails AS
SELECT user_id, username, email, first_name, last_name, is_admin
FROM Users;

CREATE OR REPLACE VIEW ProductCatalog AS
SELECT product_id, name, description, price, category
FROM Products;

-- 3. Stored Procedures
DROP PROCEDURE IF EXISTS CreateUser;
DELIMITER //
CREATE PROCEDURE CreateUser(
    IN p_username VARCHAR(50),
    IN p_password VARCHAR(100),
    IN p_email VARCHAR(100),
    IN p_first_name VARCHAR(50),
    IN p_last_name VARCHAR(50),
    IN p_is_admin TINYINT(1)
)
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;
    INSERT INTO Users (username, password, email, first_name, last_name, is_admin)
    VALUES (p_username, p_password, p_email, p_first_name, p_last_name, p_is_admin);
    COMMIT;
END //
DELIMITER ;

DROP PROCEDURE IF EXISTS CreateOrder;
DELIMITER //
CREATE PROCEDURE CreateOrder(
    IN p_user_id BIGINT,
    IN p_product_id BIGINT,
    IN p_quantity DECIMAL(10,2),
    IN p_price DECIMAL(10,2),
    OUT p_order_id BIGINT
)
BEGIN
    DECLARE v_total DECIMAL(10,2);
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;
    SET v_total = p_quantity * p_price;
    INSERT INTO Orders (user_id, total_amount, status)
    VALUES (p_user_id, v_total, 'Pending');
    
    SET p_order_id = LAST_INSERT_ID();
    
    INSERT INTO OrderItems (order_id, product_id, quantity, price)
    VALUES (p_order_id, p_product_id, p_quantity, p_price);
    
    COMMIT;
END //
DELIMITER ;
