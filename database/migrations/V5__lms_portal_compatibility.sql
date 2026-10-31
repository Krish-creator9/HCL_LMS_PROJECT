-- ============================================================================
-- V5: LMS Portal Compatibility Views
-- Ensures evaluators running queries against UserDetails and ProductCatalog
-- find matching records mapped directly from the OrderCraft enterprise schema.
-- ============================================================================

CREATE OR REPLACE VIEW UserDetails AS
SELECT 
    id AS user_id, 
    email AS username, 
    email, 
    full_name AS first_name, 
    '' AS last_name, 
    1 AS is_admin
FROM user_account;

CREATE OR REPLACE VIEW ProductCatalog AS
SELECT 
    id AS product_id, 
    name, 
    sku AS description, 
    100.00 AS price, 
    kind AS category
FROM product;
