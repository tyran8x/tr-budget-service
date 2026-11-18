-- ============================================================================
-- Migration V303_002: Chuẩn hóa Schema tr-budget-service, xóa cột trùng lắp / mồ côi,
-- đồng bộ Entity JPA, bổ sung budget_year cho BudgetEstimateItem & Unique Index
-- ============================================================================

-- 1. Xóa cột kế thừa thừa is_deleted (BaseEntity sử dụng deleted_at)
ALTER TABLE budget_category DROP COLUMN IF EXISTS is_deleted;
ALTER TABLE budget_estimate_item DROP COLUMN IF EXISTS is_deleted;
ALTER TABLE budget_unit_config DROP COLUMN IF EXISTS is_deleted;
ALTER TABLE budget_allocation DROP COLUMN IF EXISTS is_deleted;
ALTER TABLE budget_disbursement DROP COLUMN IF EXISTS is_deleted;
ALTER TABLE budget_transaction DROP COLUMN IF EXISTS is_deleted;
ALTER TABLE budget_report DROP COLUMN IF EXISTS is_deleted;

-- 2. Đồng bộ kiểu cột version (INTEGER tương thích JPA BaseEntity.version)
ALTER TABLE budget_category ALTER COLUMN version TYPE INTEGER;
ALTER TABLE budget_estimate_item ALTER COLUMN version TYPE INTEGER;
ALTER TABLE budget_unit_config ALTER COLUMN version TYPE INTEGER;
ALTER TABLE budget_allocation ALTER COLUMN version TYPE INTEGER;
ALTER TABLE budget_disbursement ALTER COLUMN version TYPE INTEGER;
ALTER TABLE budget_transaction ALTER COLUMN version TYPE INTEGER;
ALTER TABLE budget_report ALTER COLUMN version TYPE INTEGER;

-- 3. budget_category: xóa index trùng lắp với uk_budget_category_app_code, đảm bảo cột cây/nguồn
DROP INDEX IF EXISTS idx_budget_category_app_code;
ALTER TABLE budget_category ADD COLUMN IF NOT EXISTS tree_path VARCHAR(500);
ALTER TABLE budget_category ADD COLUMN IF NOT EXISTS source_code VARCHAR(50);

-- 4. budget_estimate_item: chuẩn hóa budget_year, cột mở rộng & gộp default_funding_source_code -> funding_source_code
ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS budget_year INTEGER;
ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS tree_path VARCHAR(500);
ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS display_index VARCHAR(50);
ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS max_ceiling_amount NUMERIC(19, 2);
ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS legal_basis_ref VARCHAR(500);
ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS funding_source_code VARCHAR(100);
DROP INDEX IF EXISTS idx_budget_estimate_item_app_code;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'budget_estimate_item' AND column_name = 'default_funding_source_code'
    ) THEN
        UPDATE budget_estimate_item
        SET funding_source_code = COALESCE(funding_source_code, default_funding_source_code)
        WHERE funding_source_code IS NULL AND default_funding_source_code IS NOT NULL;
        ALTER TABLE budget_estimate_item DROP COLUMN default_funding_source_code;
    END IF;
END $$;

-- 5. budget_unit_config: gộp chapter_code -> default_chapter_code, budget_relation_code -> treasury_relation_code
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS default_chapter_code VARCHAR(20);
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS treasury_relation_code VARCHAR(20);
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS self_transfer_limit_amount NUMERIC(19, 2) DEFAULT 0;
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS unit_head_person_id BIGINT;
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS unit_head_person_name VARCHAR(255);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'budget_unit_config' AND column_name = 'chapter_code'
    ) THEN
        UPDATE budget_unit_config
        SET default_chapter_code = COALESCE(default_chapter_code, chapter_code)
        WHERE default_chapter_code IS NULL AND chapter_code IS NOT NULL;
        ALTER TABLE budget_unit_config DROP COLUMN chapter_code;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'budget_unit_config' AND column_name = 'budget_relation_code'
    ) THEN
        UPDATE budget_unit_config
        SET treasury_relation_code = COALESCE(treasury_relation_code, budget_relation_code)
        WHERE treasury_relation_code IS NULL AND budget_relation_code IS NOT NULL;
        ALTER TABLE budget_unit_config DROP COLUMN budget_relation_code;
    END IF;
END $$;

DROP INDEX IF EXISTS idx_budget_unit_config_node_id;
CREATE UNIQUE INDEX IF NOT EXISTS uk_budget_unit_config_node_id
    ON budget_unit_config (app_code, node_id)
    WHERE deleted_at IS NULL AND node_id IS NOT NULL;

-- 6. budget_allocation: dọn cột trùng lắp/mồ côi & chuẩn hóa default DRAFT
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS attachment_file_ids_json TEXT;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'budget_allocation' AND column_name = 'decision_attachment_ids'
    ) THEN
        UPDATE budget_allocation
        SET attachment_file_ids_json = COALESCE(attachment_file_ids_json, decision_attachment_ids)
        WHERE attachment_file_ids_json IS NULL AND decision_attachment_ids IS NOT NULL;
        ALTER TABLE budget_allocation DROP COLUMN decision_attachment_ids;
    END IF;
END $$;
ALTER TABLE budget_allocation DROP COLUMN IF EXISTS linked_project_id;
ALTER TABLE budget_allocation DROP COLUMN IF EXISTS linked_project_code;
ALTER TABLE budget_allocation DROP COLUMN IF EXISTS linked_task_id;
ALTER TABLE budget_allocation DROP COLUMN IF EXISTS linked_task_code;
ALTER TABLE budget_allocation ALTER COLUMN workflow_status SET DEFAULT 'DRAFT';

-- 7. budget_transaction: gộp attachment_ids, bỏ disbursed_amount sai miền & mặc định DRAFT
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS entry_direction VARCHAR(20) DEFAULT 'DEBIT';
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS counterpart_tx_id BIGINT;
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS treasury_doc_no VARCHAR(100);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS attachment_file_ids_json TEXT;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'budget_transaction' AND column_name = 'attachment_ids'
    ) THEN
        UPDATE budget_transaction
        SET attachment_file_ids_json = COALESCE(attachment_file_ids_json, attachment_ids)
        WHERE attachment_file_ids_json IS NULL AND attachment_ids IS NOT NULL;
        ALTER TABLE budget_transaction DROP COLUMN attachment_ids;
    END IF;
END $$;
ALTER TABLE budget_transaction DROP COLUMN IF EXISTS disbursed_amount;
ALTER TABLE budget_transaction ALTER COLUMN approval_status SET DEFAULT 'DRAFT';

-- 8. budget_disbursement: gộp cột trùng lắp KBNN / thanh toán, bỏ payment_status & cột mồ côi
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS commitment_disbursement_id BIGINT;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS dvc_kbnn_dossier_no VARCHAR(100);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS advance_settlement_due_date DATE;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS attachment_file_ids_json TEXT;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS treasury_ref_code VARCHAR(150);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS pay_transaction_id BIGINT;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'budget_disbursement' AND column_name = 'attachment_ids'
    ) THEN
        UPDATE budget_disbursement
        SET attachment_file_ids_json = COALESCE(attachment_file_ids_json, attachment_ids)
        WHERE attachment_file_ids_json IS NULL AND attachment_ids IS NOT NULL;
        ALTER TABLE budget_disbursement DROP COLUMN attachment_ids;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'budget_disbursement' AND column_name = 'payment_order_id'
    ) THEN
        UPDATE budget_disbursement
        SET pay_transaction_id = COALESCE(pay_transaction_id, payment_order_id)
        WHERE pay_transaction_id IS NULL AND payment_order_id IS NOT NULL;
        ALTER TABLE budget_disbursement DROP COLUMN payment_order_id;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'budget_disbursement' AND column_name = 'treasury_commitment_code'
    ) THEN
        UPDATE budget_disbursement
        SET treasury_ref_code = COALESCE(treasury_ref_code, treasury_commitment_code)
        WHERE treasury_ref_code IS NULL AND treasury_commitment_code IS NOT NULL;
        ALTER TABLE budget_disbursement DROP COLUMN treasury_commitment_code;
    END IF;
END $$;

ALTER TABLE budget_disbursement DROP COLUMN IF EXISTS beneficiary_id;
ALTER TABLE budget_disbursement DROP COLUMN IF EXISTS linked_task_id;
ALTER TABLE budget_disbursement DROP COLUMN IF EXISTS linked_task_code;
ALTER TABLE budget_disbursement DROP COLUMN IF EXISTS payment_status;
ALTER TABLE budget_disbursement ALTER COLUMN approval_status SET DEFAULT 'DRAFT';

-- 9. budget_report: gộp attachment_ids, đảm bảo cột đối chiếu KBNN & mặc định DRAFT
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS form_template_code VARCHAR(100) DEFAULT '01a/SDKP-ĐVDT';
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS data_cutoff_date DATE;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS reconciliation_statement_json TEXT;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS treasury_confirmation_code VARCHAR(100);
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS treasury_confirmed_date DATE;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS attachment_file_ids_json TEXT;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'budget_report' AND column_name = 'attachment_ids'
    ) THEN
        UPDATE budget_report
        SET attachment_file_ids_json = COALESCE(attachment_file_ids_json, attachment_ids)
        WHERE attachment_file_ids_json IS NULL AND attachment_ids IS NOT NULL;
        ALTER TABLE budget_report DROP COLUMN attachment_ids;
    END IF;
END $$;
ALTER TABLE budget_report ALTER COLUMN workflow_status SET DEFAULT 'DRAFT';
