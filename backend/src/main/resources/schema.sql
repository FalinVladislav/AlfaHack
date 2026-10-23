CREATE TABLE IF NOT EXISTS companies (id VARCHAR(32) PRIMARY KEY, name VARCHAR(160) NOT NULL, balance DECIMAL(18,2) NOT NULL);
CREATE TABLE IF NOT EXISTS demo_accounts (account_key VARCHAR(32) PRIMARY KEY, role VARCHAR(16) NOT NULL, company_id VARCHAR(32), label VARCHAR(160) NOT NULL);
CREATE TABLE IF NOT EXISTS account_credentials (account_key VARCHAR(32) PRIMARY KEY, salt VARCHAR(64) NOT NULL, password_hash VARCHAR(128) NOT NULL);
CREATE TABLE IF NOT EXISTS sessions (token VARCHAR(64) PRIMARY KEY, account_key VARCHAR(32) NOT NULL, csrf VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL);
CREATE TABLE IF NOT EXISTS drafts (id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(32) NOT NULL, version INTEGER NOT NULL, purpose VARCHAR(32), amount DECIMAL(18,2), term_months INTEGER, duration_days INTEGER, needed_by DATE, product_id VARCHAR(32), status VARCHAR(16) NOT NULL, updated_at TIMESTAMP NOT NULL);
ALTER TABLE drafts ADD COLUMN IF NOT EXISTS goal_id VARCHAR(40);
ALTER TABLE drafts ADD COLUMN IF NOT EXISTS goal_text VARCHAR(500);
CREATE TABLE IF NOT EXISTS quotes (id VARCHAR(36) PRIMARY KEY, draft_id VARCHAR(36) NOT NULL, draft_version INTEGER NOT NULL, product_id VARCHAR(32) NOT NULL, amount DECIMAL(18,2) NOT NULL, term_months INTEGER, duration_days INTEGER, annual_rate DECIMAL(8,4) NOT NULL, monthly_payment DECIMAL(18,2), total_repay DECIMAL(18,2) NOT NULL, created_at TIMESTAMP NOT NULL);
CREATE TABLE IF NOT EXISTS applications (id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(32) NOT NULL, draft_id VARCHAR(36) NOT NULL, quote_id VARCHAR(36) NOT NULL, status VARCHAR(24) NOT NULL, demo_profile VARCHAR(32), amount DECIMAL(18,2) NOT NULL, term_months INTEGER, duration_days INTEGER, product_id VARCHAR(32) NOT NULL, annual_rate DECIMAL(8,4) NOT NULL, monthly_payment DECIMAL(18,2), total_repay DECIMAL(18,2) NOT NULL, score INTEGER, reason_code VARCHAR(80), offer_version INTEGER NOT NULL, version INTEGER NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL);
CREATE TABLE IF NOT EXISTS info_requests (id VARCHAR(36) PRIMARY KEY, application_id VARCHAR(36) NOT NULL, reason_code VARCHAR(80) NOT NULL, document_type VARCHAR(32) NOT NULL, period VARCHAR(40), status VARCHAR(16) NOT NULL);
CREATE TABLE IF NOT EXISTS documents (id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(36) NOT NULL, company_id VARCHAR(32) NOT NULL, filename VARCHAR(200) NOT NULL, mime VARCHAR(80) NOT NULL, sha256 VARCHAR(64) NOT NULL, storage_name VARCHAR(36) NOT NULL, verified BOOLEAN NOT NULL, uploaded_at TIMESTAMP NOT NULL);
CREATE TABLE IF NOT EXISTS loans (id VARCHAR(36) PRIMARY KEY, application_id VARCHAR(36) NOT NULL UNIQUE, company_id VARCHAR(32) NOT NULL, amount DECIMAL(18,2) NOT NULL, balance DECIMAL(18,2) NOT NULL, next_payment_date DATE NOT NULL, next_payment_amount DECIMAL(18,2) NOT NULL, created_at TIMESTAMP NOT NULL);
CREATE TABLE IF NOT EXISTS ledger_events (id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(32) NOT NULL, event_date DATE NOT NULL, amount DECIMAL(18,2) NOT NULL, kind VARCHAR(32) NOT NULL, source VARCHAR(100) NOT NULL);
CREATE TABLE IF NOT EXISTS application_events (id VARCHAR(36) PRIMARY KEY, application_id VARCHAR(36) NOT NULL, actor VARCHAR(32) NOT NULL, old_status VARCHAR(24), new_status VARCHAR(24) NOT NULL, reason_code VARCHAR(80), created_at TIMESTAMP NOT NULL);
CREATE TABLE IF NOT EXISTS idempotency_records (account_key VARCHAR(32) NOT NULL, operation VARCHAR(40) NOT NULL, request_key VARCHAR(120) NOT NULL, body_hash VARCHAR(64) NOT NULL, result_id VARCHAR(36) NOT NULL, PRIMARY KEY(account_key,operation,request_key));
CREATE TABLE IF NOT EXISTS assistant_proposals (id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(32) NOT NULL, draft_id VARCHAR(36) NOT NULL, draft_version INTEGER NOT NULL, amount DECIMAL(18,2), term_months INTEGER, duration_days INTEGER, purpose VARCHAR(32), needed_by DATE, created_at TIMESTAMP NOT NULL);
CREATE TABLE IF NOT EXISTS scoring_reports (application_id VARCHAR(36) PRIMARY KEY, report_json TEXT NOT NULL);
-- Application workflow v2. Submitted requests remain immutable; bank terms live in offers.
ALTER TABLE applications ADD COLUMN IF NOT EXISTS assigned_to VARCHAR(32);
ALTER TABLE scoring_reports ADD COLUMN IF NOT EXISTS assessed_at TIMESTAMP;
CREATE TABLE IF NOT EXISTS scoring_runs (
    id VARCHAR(36) PRIMARY KEY, application_id VARCHAR(36) NOT NULL REFERENCES applications(id),
    report_json TEXT NOT NULL, assessed_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS scoring_runs_application ON scoring_runs(application_id,assessed_at);
INSERT INTO scoring_runs(id,application_id,report_json,assessed_at)
SELECT s.application_id,s.application_id,s.report_json,COALESCE(s.assessed_at,a.updated_at)
FROM scoring_reports s JOIN applications a ON a.id=s.application_id
WHERE NOT EXISTS (SELECT 1 FROM scoring_runs r WHERE r.application_id=s.application_id);
ALTER TABLE application_events ADD COLUMN IF NOT EXISTS event_type VARCHAR(40);
ALTER TABLE application_events ADD COLUMN IF NOT EXISTS details VARCHAR(1000);
CREATE TABLE IF NOT EXISTS application_requests (
    application_id VARCHAR(36) PRIMARY KEY REFERENCES applications(id),
    purpose VARCHAR(32), goal_text VARCHAR(500), comment VARCHAR(1000),
    product_id VARCHAR(32) NOT NULL, amount DECIMAL(18,2) NOT NULL,
    term_months INTEGER, duration_days INTEGER, needed_by DATE,
    annual_rate DECIMAL(8,4) NOT NULL, monthly_payment DECIMAL(18,2),
    total_repay DECIMAL(18,2) NOT NULL, submitted_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS company_risk_facts (
    company_id VARCHAR(32) PRIMARY KEY REFERENCES companies(id),
    current_assets DECIMAL(18,2), current_liabilities DECIMAL(18,2),
    collateral_value DECIMAL(18,2), overdue_days INTEGER,
    credit_history VARCHAR(40), source VARCHAR(80), updated_at TIMESTAMP
);
CREATE TABLE IF NOT EXISTS application_offers (
    id VARCHAR(36) PRIMARY KEY, application_id VARCHAR(36) NOT NULL REFERENCES applications(id),
    version INTEGER NOT NULL, product_id VARCHAR(32) NOT NULL,
    amount DECIMAL(18,2) NOT NULL, term_months INTEGER, duration_days INTEGER,
    annual_rate DECIMAL(8,4) NOT NULL, monthly_payment DECIMAL(18,2),
    total_repay DECIMAL(18,2) NOT NULL, expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL, UNIQUE(application_id,version)
);
CREATE TABLE IF NOT EXISTS bank_decisions (
    application_id VARCHAR(36) PRIMARY KEY REFERENCES applications(id),
    application_version INTEGER NOT NULL, staff_key VARCHAR(32) NOT NULL,
    decision_type VARCHAR(24) NOT NULL, offer_id VARCHAR(36),
    scoring_snapshot TEXT NOT NULL, internal_reason VARCHAR(2000) NOT NULL,
    client_message VARCHAR(1000) NOT NULL, divergence_reason VARCHAR(2000),
    reason_code VARCHAR(80), decided_at TIMESTAMP NOT NULL
);
ALTER TABLE bank_decisions ADD COLUMN IF NOT EXISTS reason VARCHAR(2000);
CREATE TABLE IF NOT EXISTS application_responses (
    application_id VARCHAR(36) PRIMARY KEY REFERENCES applications(id),
    offer_id VARCHAR(36) NOT NULL, offer_version INTEGER NOT NULL,
    response VARCHAR(16) NOT NULL, responded_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS application_contracts (
    application_id VARCHAR(36) PRIMARY KEY REFERENCES applications(id),
    offer_id VARCHAR(36) NOT NULL, offer_version INTEGER NOT NULL,
    number VARCHAR(48) NOT NULL UNIQUE, created_at TIMESTAMP NOT NULL,
    signed_at TIMESTAMP
);
CREATE TABLE IF NOT EXISTS application_notifications (
    id VARCHAR(36) PRIMARY KEY, application_id VARCHAR(36) NOT NULL REFERENCES applications(id),
    company_id VARCHAR(32) NOT NULL, message VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL, read_at TIMESTAMP
);
CREATE TABLE IF NOT EXISTS loan_schedule (
    loan_id VARCHAR(36) NOT NULL REFERENCES loans(id), installment INTEGER NOT NULL,
    due_date DATE NOT NULL, principal DECIMAL(18,2) NOT NULL,
    interest DECIMAL(18,2) NOT NULL, payment DECIMAL(18,2) NOT NULL,
    PRIMARY KEY(loan_id,installment)
);
CREATE INDEX IF NOT EXISTS applications_queue ON applications(status,assigned_to,created_at);
CREATE INDEX IF NOT EXISTS application_notifications_company ON application_notifications(company_id,created_at);
INSERT INTO application_requests(application_id,purpose,goal_text,product_id,amount,term_months,duration_days,needed_by,annual_rate,monthly_payment,total_repay,submitted_at)
SELECT a.id,d.purpose,d.goal_text,q.product_id,q.amount,q.term_months,q.duration_days,d.needed_by,q.annual_rate,q.monthly_payment,q.total_repay,a.created_at
FROM applications a JOIN drafts d ON d.id=a.draft_id JOIN quotes q ON q.id=a.quote_id
WHERE NOT EXISTS (SELECT 1 FROM application_requests r WHERE r.application_id=a.id);
-- Legacy scoring outcomes were not employee decisions. Reopen only undecided cases.
UPDATE applications SET status='AWAITING_DECISION',reason_code=NULL
WHERE status IN ('APPROVED','COUNTEROFFER','REJECTED')
AND NOT EXISTS (SELECT 1 FROM bank_decisions b WHERE b.application_id=applications.id);
CREATE TABLE IF NOT EXISTS insight_jobs (id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(32) NOT NULL, fingerprint VARCHAR(64) NOT NULL, context_json TEXT NOT NULL, status VARCHAR(24) NOT NULL, message VARCHAR(500) NOT NULL, lease_token VARCHAR(36), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, UNIQUE(company_id,fingerprint));
CREATE TABLE IF NOT EXISTS business_hypotheses (id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(32) NOT NULL, job_id VARCHAR(36) NOT NULL REFERENCES insight_jobs(id), source_key VARCHAR(100) NOT NULL, payload_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL, UNIQUE(job_id,source_key));
CREATE TABLE IF NOT EXISTS insight_reads (account_key VARCHAR(32) NOT NULL, hypothesis_id VARCHAR(36) NOT NULL REFERENCES business_hypotheses(id), read_at TIMESTAMP NOT NULL, PRIMARY KEY(account_key,hypothesis_id));
CREATE INDEX IF NOT EXISTS insight_jobs_queue ON insight_jobs(status,created_at);
CREATE INDEX IF NOT EXISTS business_hypotheses_company ON business_hypotheses(company_id,created_at);
