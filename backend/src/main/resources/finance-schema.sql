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
CREATE TABLE IF NOT EXISTS loan_names (
    company_id VARCHAR(32) NOT NULL REFERENCES companies(id),
    source VARCHAR(16) NOT NULL, loan_id VARCHAR(80) NOT NULL,
    display_name VARCHAR(100) NOT NULL, updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY(company_id,source,loan_id)
);
CREATE TABLE IF NOT EXISTS loan_payments (
    id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(32) NOT NULL REFERENCES companies(id),
    account_key VARCHAR(32) NOT NULL,
    request_key VARCHAR(120) NOT NULL,
    source VARCHAR(16) NOT NULL, loan_id VARCHAR(80) NOT NULL,
    due_date DATE NOT NULL, payment DECIMAL(18,2) NOT NULL,
    principal DECIMAL(18,2) NOT NULL, interest DECIMAL(18,2) NOT NULL,
    paid_at TIMESTAMP NOT NULL,
    UNIQUE(account_key,request_key), UNIQUE(source,loan_id,due_date)
);
ALTER TABLE loan_schedule ADD COLUMN IF NOT EXISTS paid_at TIMESTAMP;
UPDATE transactions SET type='FINANCING'
WHERE type='INCOME' AND id IN (SELECT id FROM ledger_events WHERE kind='DISBURSEMENT');
INSERT INTO transactions(id,company_id,date,amount,type,category,counterparty_id)
SELECT e.id,e.company_id,e.event_date,e.amount,'FINANCING','Выдача кредита','Банк'
FROM ledger_events e WHERE e.kind='DISBURSEMENT'
AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.id=e.id);

CREATE TABLE IF NOT EXISTS business_scenario_dialogs (
    id VARCHAR(36) PRIMARY KEY,
    draft_id VARCHAR(36) NOT NULL REFERENCES drafts(id),
    account_key VARCHAR(80) NOT NULL,
    company_id VARCHAR(32) NOT NULL REFERENCES companies(id),
    goal_key VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    plan_version INTEGER NOT NULL DEFAULT 0,
    confirmed_version INTEGER NOT NULL DEFAULT 0,
    proposed_json TEXT,
    confirmed_json TEXT,
    history_json TEXT NOT NULL,
    context_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    working_quote_id VARCHAR(36),
    round_count INTEGER NOT NULL DEFAULT 0,
    lease_token VARCHAR(36),
    lease_until TIMESTAMP,
    last_error VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(draft_id, account_key, goal_key)
);
CREATE INDEX IF NOT EXISTS business_scenario_queue ON business_scenario_dialogs(status, lease_until);
CREATE TABLE IF NOT EXISTS business_scenario_runs (
    id VARCHAR(36) PRIMARY KEY,
    dialog_id VARCHAR(36) NOT NULL REFERENCES business_scenario_dialogs(id),
    quote_id VARCHAR(36) NOT NULL REFERENCES quotes(id),
    plan_version INTEGER NOT NULL,
    context_json TEXT NOT NULL,
    result_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
ALTER TABLE business_scenario_dialogs ADD COLUMN IF NOT EXISTS working_plan_json TEXT;
