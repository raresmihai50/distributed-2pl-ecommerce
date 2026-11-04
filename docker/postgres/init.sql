-- PostgreSQL Schema & Seed Data for Distributed 2PL E-Commerce

CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(150) NOT NULL UNIQUE,
    full_name VARCHAR(150) NOT NULL,
    balance NUMERIC(12, 2) NOT NULL DEFAULT 1000.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    price NUMERIC(12, 2) NOT NULL,
    category VARCHAR(100) NOT NULL DEFAULT 'Electronics',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS inventory (
    product_id INTEGER PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    quantity INTEGER NOT NULL CHECK (quantity >= 0),
    reserved_quantity INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS orders (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id),
    total_amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Initial Seed Data (idempotent)
INSERT INTO users (id, username, email, full_name, balance) VALUES
    (1, 'alex_popescu', 'alex.popescu@student.ro', 'Alexandru Popescu', 5000.00),
    (2, 'maria_ionescu', 'maria.ionescu@student.ro', 'Maria Ionescu', 3500.00),
    (3, 'andrei_radulescu', 'andrei.radulescu@student.ro', 'Andrei Radulescu', 2000.00)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('users', 'id'), COALESCE((SELECT MAX(id) FROM users), 1), true);

INSERT INTO products (id, name, description, price, category) VALUES
    (1, 'Laptop Pro 16"', 'High-performance developer laptop with 32GB RAM', 1200.00, 'Computers'),
    (2, 'Mechanical Keyboard RGB', 'Tactile mechanical keyboard for programming', 150.00, 'Peripherals'),
    (3, 'Wireless Noise-Canceling Headphones', 'Studio-grade wireless headphones', 250.00, 'Audio'),
    (4, 'Limited Edition GPU RTX', 'Last item in stock - ideal for concurrency race tests', 800.00, 'Hardware')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('products', 'id'), COALESCE((SELECT MAX(id) FROM products), 1), true);

INSERT INTO inventory (product_id, quantity, reserved_quantity) VALUES
    (1, 10, 0),
    (2, 25, 0),
    (3, 15, 0),
    (4, 1, 0)
ON CONFLICT (product_id) DO NOTHING;

