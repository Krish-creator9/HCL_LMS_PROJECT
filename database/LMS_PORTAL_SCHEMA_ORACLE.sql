-- ============================================================================
-- HCL Career Shaper LMS Portal — Baseline Oracle Database Reference Script
-- Target RDBMS: Oracle Database 19c / 21c
-- ============================================================================

-- 1. Tables
CREATE TABLE Users (
    user_id NUMBER GENERATED ALWAYS AS IDENTITY START WITH 1 INCREMENT BY 1,
    username VARCHAR2(50) NOT NULL UNIQUE,
    password VARCHAR2(100) NOT NULL,
    email VARCHAR2(100) NOT NULL UNIQUE,
    first_name VARCHAR2(50) NOT NULL,
    last_name VARCHAR2(50) NOT NULL,
    is_admin NUMBER(1) DEFAULT 0 NOT NULL,
    created_at DATE DEFAULT SYSDATE NOT NULL,
    updated_at DATE DEFAULT SYSDATE NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (user_id)
);

CREATE TABLE Products (
    product_id NUMBER GENERATED ALWAYS AS IDENTITY START WITH 1 INCREMENT BY 1,
    name VARCHAR2(100) NOT NULL,
    description CLOB,
    price NUMBER(10,2) NOT NULL,
    category VARCHAR2(50) NOT NULL,
    created_at DATE DEFAULT SYSDATE NOT NULL,
    updated_at DATE DEFAULT SYSDATE NOT NULL,
    CONSTRAINT pk_products PRIMARY KEY (product_id)
);

CREATE TABLE Orders (
    order_id NUMBER GENERATED ALWAYS AS IDENTITY START WITH 1 INCREMENT BY 1,
    user_id NUMBER NOT NULL,
    total_amount NUMBER(10,2) NOT NULL,
    status VARCHAR2(20) NOT NULL,
    created_at DATE DEFAULT SYSDATE NOT NULL,
    updated_at DATE DEFAULT SYSDATE NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (order_id),
    CONSTRAINT fk_orders_users FOREIGN KEY (user_id) REFERENCES Users(user_id)
);

CREATE TABLE OrderItems (
    order_item_id NUMBER GENERATED ALWAYS AS IDENTITY START WITH 1 INCREMENT BY 1,
    order_id NUMBER NOT NULL,
    product_id NUMBER NOT NULL,
    quantity NUMBER NOT NULL,
    price NUMBER(10,2) NOT NULL,
    created_at DATE DEFAULT SYSDATE NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (order_item_id),
    CONSTRAINT fk_order_items_orders FOREIGN KEY (order_id) REFERENCES Orders(order_id),
    CONSTRAINT fk_order_items_products FOREIGN KEY (product_id) REFERENCES Products(product_id)
);

-- 2. Views
CREATE VIEW UserDetails AS
SELECT user_id, username, email, first_name, last_name, is_admin
FROM Users;

CREATE VIEW ProductCatalog AS
SELECT product_id, name, description, price, category
FROM Products;

-- 3. Stored Procedures
CREATE OR REPLACE PROCEDURE CreateUser(
    p_username IN VARCHAR2,
    p_password IN VARCHAR2,
    p_email IN VARCHAR2,
    p_first_name IN VARCHAR2,
    p_last_name IN VARCHAR2,
    p_is_admin IN NUMBER
)
AS
BEGIN
    INSERT INTO Users (username, password, email, first_name, last_name, is_admin)
    VALUES (p_username, p_password, p_email, p_first_name, p_last_name, p_is_admin);
    COMMIT;
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE_APPLICATION_ERROR(-20001, 'Error creating user: ' || SQLERRM);
END;
/

CREATE OR REPLACE PROCEDURE PlaceOrder(
    p_user_id IN NUMBER,
    p_order_items IN OrderItemList,
    p_order_id OUT NUMBER
)
AS
    v_total_amount NUMBER := 0;
BEGIN
    INSERT INTO Orders (user_id, total_amount, status)
    VALUES (p_user_id, v_total_amount, 'Pending')
    RETURNING order_id INTO p_order_id;
    FOR i IN 1..p_order_items.COUNT LOOP
        INSERT INTO OrderItems (order_id, product_id, quantity, price)
        VALUES (p_order_id, p_order_items(i).product_id, p_order_items(i).quantity, p_order_items(i).price);
        v_total_amount := v_total_amount + (p_order_items(i).price * p_order_items(i).quantity);
    END LOOP;
    UPDATE Orders
    SET total_amount = v_total_amount
    WHERE order_id = p_order_id;
    COMMIT;
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE_APPLICATION_ERROR(-20002, 'Error placing order: ' || SQLERRM);
END;
/
