-- MySQL Schema & Seed Data for Distributed 2PL E-Commerce

CREATE TABLE IF NOT EXISTS payments (
    id INT AUTO_INCREMENT PRIMARY KEY,
    order_id INT NOT NULL,
    user_id INT NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    payment_method VARCHAR(50) NOT NULL DEFAULT 'CARD',
    status VARCHAR(50) NOT NULL,
    transaction_id VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS order_items (
    id INT AUTO_INCREMENT PRIMARY KEY,
    order_id INT NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    subtotal DECIMAL(12, 2) NOT NULL
);

CREATE TABLE IF NOT EXISTS transaction_logs (
    transaction_id VARCHAR(100) PRIMARY KEY,
    operation_name VARCHAR(150) NOT NULL,
    status VARCHAR(50) NOT NULL,
    phase_2pc VARCHAR(50) NOT NULL DEFAULT 'NONE',
    postgres_prepared BOOLEAN NOT NULL DEFAULT FALSE,
    mysql_prepared BOOLEAN NOT NULL DEFAULT FALSE,
    participants VARCHAR(100) NOT NULL DEFAULT 'POSTGRESQL,MYSQL',
    sql_operations TEXT,
    locks_held TEXT,
    undo_operations TEXT,
    error_message TEXT,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    duration_ms BIGINT DEFAULT 0
);

