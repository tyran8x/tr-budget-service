-- ============================================================================
-- Migration V303_004: Reconcile Schema Drift (NUMERIC 19,2 & VARCHAR lengths),
-- DB CHECK Constraints, Transactional Outbox (sys_outbox_event),
-- Idempotency Store (budget_processed_event) & Hot Partial Indexes
-- 100% Idempotent (an toàn chạy lại trên DB mới hoặc DB đã vận hành)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. ĐỒNG NHẤT KIỂU SỐ HỌC NUMERIC(19, 2) & ĐỘ DÀI VARCHAR KHỚP JPA ENTITIES
-- ----------------------------------------------------------------------------

-- 1.1 budget_estimate_item
ALTER TABLE budget_estimate_item ALTER COLUMN norm_standard_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_estimate_item ALTER COLUMN max_ceiling_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_estimate_item ALTER COLUMN parent_name TYPE VARCHAR(500);
ALTER TABLE budget_estimate_item ALTER COLUMN item_type TYPE VARCHAR(50);

-- 1.2 budget_unit_config
ALTER TABLE budget_unit_config ALTER COLUMN self_approve_limit_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_unit_config ALTER COLUMN self_transfer_limit_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_unit_config ALTER COLUMN node_name TYPE VARCHAR(500);

-- 1.3 budget_allocation
ALTER TABLE budget_allocation ALTER COLUMN proposed_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN initial_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN supplement_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN carry_over_in_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN carry_over_out_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN transfer_net_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN reduction_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN effective_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN committed_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN advanced_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN disbursed_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN settled_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN available_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN expired_cancelled_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_allocation ALTER COLUMN period_type TYPE VARCHAR(50);
ALTER TABLE budget_allocation ALTER COLUMN item_type TYPE VARCHAR(50);
ALTER TABLE budget_allocation ALTER COLUMN node_name TYPE VARCHAR(500);
ALTER TABLE budget_allocation ALTER COLUMN estimate_item_name TYPE VARCHAR(500);
ALTER TABLE budget_allocation ALTER COLUMN decision_no TYPE VARCHAR(150);
ALTER TABLE budget_allocation ALTER COLUMN linked_plan_name TYPE VARCHAR(500);
ALTER TABLE budget_allocation ALTER COLUMN approval_scope SET DEFAULT 'SUPERIOR_APPROVAL';

-- 1.4 budget_disbursement
ALTER TABLE budget_disbursement ALTER COLUMN amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_disbursement ALTER COLUMN requested_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_disbursement ALTER COLUMN approved_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_disbursement ALTER COLUMN settled_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_disbursement ALTER COLUMN tax_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_disbursement ALTER COLUMN net_payment_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_disbursement ALTER COLUMN allocation_name TYPE VARCHAR(500);
ALTER TABLE budget_disbursement ALTER COLUMN node_name TYPE VARCHAR(500);
ALTER TABLE budget_disbursement ALTER COLUMN estimate_item_name TYPE VARCHAR(500);
ALTER TABLE budget_disbursement ALTER COLUMN contract_no TYPE VARCHAR(150);
ALTER TABLE budget_disbursement ALTER COLUMN voucher_no TYPE VARCHAR(150);
ALTER TABLE budget_disbursement ALTER COLUMN treasury_ref_code TYPE VARCHAR(150);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS is_overdue_warning_sent BOOLEAN DEFAULT FALSE;
UPDATE budget_disbursement SET is_overdue_warning_sent = FALSE WHERE is_overdue_warning_sent IS NULL;

-- 1.5 budget_transaction
ALTER TABLE budget_transaction ALTER COLUMN amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_transaction ALTER COLUMN balance_before_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_transaction ALTER COLUMN balance_after_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_transaction ALTER COLUMN allocation_name TYPE VARCHAR(500);
ALTER TABLE budget_transaction ALTER COLUMN node_name TYPE VARCHAR(500);
ALTER TABLE budget_transaction ALTER COLUMN estimate_item_name TYPE VARCHAR(500);
ALTER TABLE budget_transaction ALTER COLUMN counterpart_allocation_name TYPE VARCHAR(500);
ALTER TABLE budget_transaction ALTER COLUMN counterpart_node_name TYPE VARCHAR(500);
ALTER TABLE budget_transaction ALTER COLUMN decision_no TYPE VARCHAR(150);

-- 1.6 budget_report
ALTER TABLE budget_report ALTER COLUMN total_revenue_estimate TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_revenue_actual TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_revenue_cumulative TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_expenditure_initial TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_expenditure_adjustment TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_expenditure_effective TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_expenditure_committed TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_expenditure_advanced TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_expenditure_actual TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_expenditure_settled TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_expenditure_cumulative TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_carry_over_amount TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_remaining_balance TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_expired_cancelled TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN total_surplus_or_saving TYPE NUMERIC(19, 2);
ALTER TABLE budget_report ALTER COLUMN node_name TYPE VARCHAR(500);

-- ----------------------------------------------------------------------------
-- 2. CHUẨN HÓA DỮ LIỆU ENUM LỖI THỜI (IDEMPOTENT)
-- ----------------------------------------------------------------------------
UPDATE budget_allocation SET approval_scope = 'SUPERIOR_APPROVAL' WHERE approval_scope = 'DIRECTORATE';
UPDATE budget_allocation SET approval_scope = 'UNIT_SELF_APPROVAL' WHERE approval_scope = 'UNIT_SELF';
UPDATE budget_allocation SET workflow_status = 'PENDING_APPROVAL' WHERE workflow_status = 'SUBMITTED';
UPDATE budget_transaction SET tx_type = 'EARMARK_RELEASE' WHERE tx_type = 'RELEASE_HOLD';
UPDATE budget_transaction SET approval_status = 'PENDING_APPROVAL' WHERE approval_status = 'PENDING';
UPDATE budget_category SET autonomy_type = 'AUTONOMOUS' WHERE autonomy_type = 'DEDICATED_FUND';
UPDATE budget_report SET report_period = 'HALF_YEAR' WHERE report_period = 'FIRST_HALF';

-- ----------------------------------------------------------------------------
-- 3. BỔ SUNG DB CHECK CONSTRAINTS BẢO VỆ BẤT BIẾN SỔ CÁI (IDEMPOTENT)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_budget_allocation_available_nonneg') THEN
        ALTER TABLE budget_allocation
            ADD CONSTRAINT chk_budget_allocation_available_nonneg
            CHECK (available_amount IS NULL OR available_amount >= 0) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_budget_allocation_initial_nonneg') THEN
        ALTER TABLE budget_allocation
            ADD CONSTRAINT chk_budget_allocation_initial_nonneg
            CHECK (initial_amount IS NULL OR initial_amount >= 0) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_budget_disbursement_amount_pos') THEN
        ALTER TABLE budget_disbursement
            ADD CONSTRAINT chk_budget_disbursement_amount_pos
            CHECK (amount IS NULL OR amount > 0) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_budget_unit_config_guarantee_rate') THEN
        ALTER TABLE budget_unit_config
            ADD CONSTRAINT chk_budget_unit_config_guarantee_rate
            CHECK (recurrent_self_guarantee_rate IS NULL OR (recurrent_self_guarantee_rate >= 0 AND recurrent_self_guarantee_rate <= 100)) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_budget_unit_config_self_limit_nonneg') THEN
        ALTER TABLE budget_unit_config
            ADD CONSTRAINT chk_budget_unit_config_self_limit_nonneg
            CHECK (self_approve_limit_amount IS NULL OR self_approve_limit_amount >= 0) NOT VALID;
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 4. BẢNG TRANSACTIONAL OUTBOX (sys_outbox_event) KHỚP JdbcOutboxStore
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_outbox_event (
    id              VARCHAR(64) PRIMARY KEY,
    topic           VARCHAR(150) NOT NULL,
    message_key     VARCHAR(150),
    payload         TEXT NOT NULL,
    event_type      VARCHAR(255),
    trace_id        VARCHAR(64),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count     INTEGER NOT NULL DEFAULT 0,
    locked_at       TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at         TIMESTAMP,
    error_message   TEXT
);

ALTER TABLE sys_outbox_event ADD COLUMN IF NOT EXISTS event_type VARCHAR(255);
ALTER TABLE sys_outbox_event ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
ALTER TABLE sys_outbox_event ADD COLUMN IF NOT EXISTS locked_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON sys_outbox_event (status, created_at);

-- ----------------------------------------------------------------------------
-- 5. BẢNG CHỐNG XỬ LÝ LẶP SỰ KIỆN WEBHOOK / KAFKA (budget_processed_event)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_processed_event (
    id              BIGSERIAL PRIMARY KEY,
    event_key       VARCHAR(200) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    app_code        VARCHAR(50) NOT NULL DEFAULT 'ADMIN',
    reference_id    BIGINT,
    payload_hash    VARCHAR(128),
    processed_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_budget_processed_event_key
    ON budget_processed_event (event_key);
CREATE INDEX IF NOT EXISTS idx_budget_processed_event_app_ref
    ON budget_processed_event (app_code, event_type, reference_id);

-- ----------------------------------------------------------------------------
-- 6. 4 PARTIAL INDEX NÓNG PHỤC VỤ WEBHOOK, BÚT TOÁN KÉP & TRA CỨU PHÂN BỔ
-- ----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_budget_disbursement_payment_tx_code
    ON budget_disbursement (app_code, payment_tx_code)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_budget_disbursement_pay_tx_id
    ON budget_disbursement (app_code, pay_transaction_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_budget_transaction_counterpart_tx
    ON budget_transaction (app_code, counterpart_tx_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_budget_allocation_year_node_item
    ON budget_allocation (app_code, budget_year, node_id, estimate_item_id)
    WHERE deleted_at IS NULL;
