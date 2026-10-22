ALTER TABLE companies ADD COLUMN IF NOT EXISTS company_id VARCHAR(32);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS okved VARCHAR(16);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS business_age INTEGER;
ALTER TABLE companies ADD COLUMN IF NOT EXISTS industry VARCHAR(120);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS employees INTEGER;
ALTER TABLE companies ADD COLUMN IF NOT EXISTS annual_revenue DECIMAL(18,2);

CREATE TABLE IF NOT EXISTS transactions (
    id VARCHAR(80) PRIMARY KEY,
    company_id VARCHAR(32) NOT NULL REFERENCES companies(id),
    date DATE NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    type VARCHAR(16) NOT NULL,
    category VARCHAR(120) NOT NULL,
    counterparty_id VARCHAR(80) NOT NULL
);
CREATE TABLE IF NOT EXISTS balances (
    company_id VARCHAR(32) NOT NULL REFERENCES companies(id),
    date DATE NOT NULL,
    balance DECIMAL(18,2) NOT NULL,
    PRIMARY KEY (company_id, date)
);
CREATE TABLE IF NOT EXISTS existing_loans (
    id VARCHAR(80) PRIMARY KEY,
    company_id VARCHAR(32) NOT NULL REFERENCES companies(id),
    name VARCHAR(160) NOT NULL,
    remaining_debt DECIMAL(18,2) NOT NULL,
    monthly_payment DECIMAL(18,2) NOT NULL,
    rate DECIMAL(8,4) NOT NULL,
    next_payment_date DATE NOT NULL,
    end_date DATE NOT NULL
);
CREATE TABLE IF NOT EXISTS loan_products (
    product_id VARCHAR(40) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    type VARCHAR(40) NOT NULL,
    rate DECIMAL(8,4) NOT NULL,
    min_amount DECIMAL(18,2) NOT NULL,
    max_amount DECIMAL(18,2) NOT NULL,
    min_term INTEGER NOT NULL,
    max_term INTEGER NOT NULL,
    requirements VARCHAR(500) NOT NULL
);
CREATE TABLE IF NOT EXISTS loan_requests (
    id VARCHAR(80) PRIMARY KEY,
    company_id VARCHAR(32) NOT NULL REFERENCES companies(id),
    goal VARCHAR(300) NOT NULL,
    requested_amount DECIMAL(18,2) NOT NULL,
    expected_revenue_growth DECIMAL(8,4) NOT NULL,
    expected_expense_growth DECIMAL(8,4) NOT NULL,
    project_cost DECIMAL(18,2) NOT NULL
);
CREATE INDEX IF NOT EXISTS transactions_company_date ON transactions(company_id, date);
CREATE INDEX IF NOT EXISTS existing_loans_company ON existing_loans(company_id);
