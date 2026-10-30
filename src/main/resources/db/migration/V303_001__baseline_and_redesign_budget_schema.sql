-- =============================================================================
-- Flyway Migration: V303_001__baseline_and_redesign_budget_schema.sql
-- Module: tr-budget-service (Quản lý Kinh phí & Dự toán Thu - Chi NSNN)
-- Chuẩn hóa:
--   1. Multi-tenancy AppIsolated (app_code VARCHAR(50) NOT NULL DEFAULT 'ADMIN')
--   2. Bảo toàn 100% cột snapshot (*_code, *_name) kết hợp chuẩn hóa khóa ngoại *_id
--   3. Mở rộng cột JSON sang TEXT tránh tràn 2000 ký tự
--   4. Bổ sung đầy đủ các cột nghiệp vụ Kho bạc Nhà nước (TABMIS / DVC KBNN),
--      Hợp đồng / Đấu thầu, Tài sản công, Kế hoạch và Sổ cái Bút toán kép.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. BẢNG: budget_category (Danh mục Loại - Khoản - Mục lục Ngân sách & Nguồn KP)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_category (
    id                          BIGSERIAL PRIMARY KEY,
    code                        VARCHAR(100) NOT NULL,
    name                        VARCHAR(255) NOT NULL,
    description                 TEXT,
    sort_order                  INTEGER DEFAULT 0,
    status                      VARCHAR(30) DEFAULT 'ACTIVE',
    is_deleted                  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by                  VARCHAR(100),
    updated_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by                  VARCHAR(100),
    version                     BIGINT NOT NULL DEFAULT 0,
    app_code                    VARCHAR(50) NOT NULL DEFAULT 'ADMIN',

    parent_id                   BIGINT,
    parent_code                 VARCHAR(100),
    parent_name                 VARCHAR(255),
    tree_path                   VARCHAR(500),
    level_depth                 INTEGER DEFAULT 1,
    category_group              VARCHAR(50) NOT NULL,
    chapter_code                VARCHAR(50),
    type_code                   VARCHAR(50),
    clause_code                 VARCHAR(50),
    item_code                   VARCHAR(50),
    sub_item_code               VARCHAR(50),
    source_code                 VARCHAR(50),
    autonomy_type               VARCHAR(50) NOT NULL DEFAULT 'AUTONOMOUS',
    is_allow_carry_over         BOOLEAN DEFAULT TRUE,
    sla_disbursement_warning_days INTEGER DEFAULT 5
);

ALTER TABLE budget_category ADD COLUMN IF NOT EXISTS app_code VARCHAR(50) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE budget_category ADD COLUMN IF NOT EXISTS parent_id BIGINT;
ALTER TABLE budget_category ADD COLUMN IF NOT EXISTS parent_code VARCHAR(100);
ALTER TABLE budget_category ADD COLUMN IF NOT EXISTS parent_name VARCHAR(255);
ALTER TABLE budget_category ADD COLUMN IF NOT EXISTS tree_path VARCHAR(500);
ALTER TABLE budget_category ADD COLUMN IF NOT EXISTS level_depth INTEGER DEFAULT 1;
ALTER TABLE budget_category ADD COLUMN IF NOT EXISTS type_code VARCHAR(50);
ALTER TABLE budget_category ADD COLUMN IF NOT EXISTS sub_item_code VARCHAR(50);
ALTER TABLE budget_category ADD COLUMN IF NOT EXISTS source_code VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_budget_category_app_code ON budget_category (app_code, code) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_budget_category_parent_id ON budget_category (parent_id) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_budget_category_group ON budget_category (category_group, status) WHERE is_deleted = FALSE;

-- -----------------------------------------------------------------------------
-- 2. BẢNG: budget_estimate_item (Cây Danh mục & Cấu hình Dự toán Thu - Chi đa cấp)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_estimate_item (
    id                          BIGSERIAL PRIMARY KEY,
    code                        VARCHAR(100) NOT NULL,
    name                        VARCHAR(255) NOT NULL,
    description                 TEXT,
    sort_order                  INTEGER DEFAULT 0,
    status                      VARCHAR(30) DEFAULT 'ACTIVE',
    is_deleted                  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by                  VARCHAR(100),
    updated_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by                  VARCHAR(100),
    version                     BIGINT NOT NULL DEFAULT 0,
    app_code                    VARCHAR(50) NOT NULL DEFAULT 'ADMIN',

    parent_id                   BIGINT,
    parent_code                 VARCHAR(100),
    parent_name                 VARCHAR(255),
    tree_path                   VARCHAR(500),
    item_type                   VARCHAR(30) NOT NULL DEFAULT 'EXPENDITURE',
    numbering_style             VARCHAR(50),
    display_index               VARCHAR(50),
    level_depth                 INTEGER DEFAULT 1,
    is_header                   BOOLEAN DEFAULT FALSE,
    formula                     VARCHAR(255),
    category_id                 BIGINT,
    category_code               VARCHAR(100),
    category_name               VARCHAR(255),
    default_funding_source_code VARCHAR(100),
    unit_of_measure             VARCHAR(100),
    norm_standard_amount        NUMERIC(18, 2) DEFAULT 0,
    max_ceiling_amount          NUMERIC(18, 2),
    legal_basis_ref             VARCHAR(500),
    is_require_voucher          BOOLEAN DEFAULT TRUE
);

ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS app_code VARCHAR(50) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS tree_path VARCHAR(500);
ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS display_index VARCHAR(50);
ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS default_funding_source_code VARCHAR(100);
ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS max_ceiling_amount NUMERIC(18, 2);
ALTER TABLE budget_estimate_item ADD COLUMN IF NOT EXISTS legal_basis_ref VARCHAR(500);

CREATE INDEX IF NOT EXISTS idx_budget_estimate_item_app_code ON budget_estimate_item (app_code, code) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_budget_estimate_item_parent_id ON budget_estimate_item (parent_id) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_budget_estimate_item_category_id ON budget_estimate_item (category_id) WHERE is_deleted = FALSE;

-- -----------------------------------------------------------------------------
-- 3. BẢNG: budget_unit_config (Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ TC)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_unit_config (
    id                          BIGSERIAL PRIMARY KEY,
    code                        VARCHAR(100) NOT NULL,
    name                        VARCHAR(255) NOT NULL,
    description                 TEXT,
    sort_order                  INTEGER DEFAULT 0,
    status                      VARCHAR(30) DEFAULT 'ACTIVE',
    is_deleted                  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by                  VARCHAR(100),
    updated_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by                  VARCHAR(100),
    version                     BIGINT NOT NULL DEFAULT 0,
    app_code                    VARCHAR(50) NOT NULL DEFAULT 'ADMIN',

    node_id                     BIGINT NOT NULL,
    node_code                   VARCHAR(100),
    node_name                   VARCHAR(255),
    parent_node_id              BIGINT,
    unit_type                   VARCHAR(50) NOT NULL DEFAULT 'STATE_AGENCY',
    budget_relation_code        VARCHAR(20),
    chapter_code                VARCHAR(20),
    treasury_agency_code        VARCHAR(50),
    treasury_account_no         VARCHAR(100),
    treasury_deposit_account_no VARCHAR(100),
    treasury_branch_name        VARCHAR(255),
    tax_code                    VARCHAR(50),
    can_self_allocate           BOOLEAN DEFAULT TRUE,
    can_self_transfer           BOOLEAN DEFAULT TRUE,
    can_carry_over              BOOLEAN DEFAULT TRUE,
    self_approve_limit_amount   NUMERIC(18, 2) DEFAULT 0,
    self_transfer_limit_amount  NUMERIC(18, 2) DEFAULT 0,
    allowed_category_codes_json TEXT,
    unit_head_person_id         BIGINT,
    unit_head_person_name       VARCHAR(255),
    chief_accountant_person_id  BIGINT,
    chief_accountant_name       VARCHAR(255),
    approver_person_id          BIGINT,
    approver_person_name        VARCHAR(255)
);

ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS app_code VARCHAR(50) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS parent_node_id BIGINT;
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS budget_relation_code VARCHAR(20);
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS chapter_code VARCHAR(20);
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS treasury_agency_code VARCHAR(50);
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS treasury_deposit_account_no VARCHAR(100);
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS self_transfer_limit_amount NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS unit_head_person_id BIGINT;
ALTER TABLE budget_unit_config ADD COLUMN IF NOT EXISTS unit_head_person_name VARCHAR(255);
ALTER TABLE budget_unit_config ALTER COLUMN allowed_category_codes_json TYPE TEXT;
ALTER TABLE budget_unit_config ALTER COLUMN node_code DROP NOT NULL;
ALTER TABLE budget_unit_config ALTER COLUMN node_name DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_budget_unit_config_node_id ON budget_unit_config (app_code, node_id) WHERE is_deleted = FALSE;

-- -----------------------------------------------------------------------------
-- 4. BẢNG: budget_allocation (Phương án & Quyết định Phân bổ Dự toán Kinh phí)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_allocation (
    id                          BIGSERIAL PRIMARY KEY,
    code                        VARCHAR(100) NOT NULL,
    name                        VARCHAR(255) NOT NULL,
    description                 TEXT,
    sort_order                  INTEGER DEFAULT 0,
    status                      VARCHAR(30) DEFAULT 'ACTIVE',
    is_deleted                  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by                  VARCHAR(100),
    updated_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by                  VARCHAR(100),
    version                     BIGINT NOT NULL DEFAULT 0,
    app_code                    VARCHAR(50) NOT NULL DEFAULT 'ADMIN',

    parent_allocation_id        BIGINT,
    budget_year                 INTEGER NOT NULL,
    period_type                 VARCHAR(30) NOT NULL DEFAULT 'ANNUAL',
    budget_quarter              INTEGER,
    node_id                     BIGINT NOT NULL,
    node_code                   VARCHAR(100),
    node_name                   VARCHAR(255),
    estimate_item_id            BIGINT,
    estimate_item_code          VARCHAR(100),
    estimate_item_name          VARCHAR(255),
    item_type                   VARCHAR(30) DEFAULT 'EXPENDITURE',
    category_id                 BIGINT,
    category_code               VARCHAR(100),
    category_name               VARCHAR(255),
    funding_source_id           BIGINT,
    funding_source_code         VARCHAR(100),
    funding_source_name         VARCHAR(255),
    decision_no                 VARCHAR(100),
    decision_date               DATE,
    decision_issuer             VARCHAR(255),
    decision_attachment_ids     VARCHAR(1000),
    linked_plan_id              BIGINT,
    linked_plan_code            VARCHAR(100),
    linked_plan_name            VARCHAR(255),
    linked_task_id              BIGINT,
    linked_task_code            VARCHAR(100),
    linked_project_id           BIGINT,
    linked_project_code         VARCHAR(100),
    approval_scope              VARCHAR(50) DEFAULT 'DIRECTORATE',
    workflow_status             VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    wf_instance_id              BIGINT,
    wf_process_code             VARCHAR(100),
    current_version             INTEGER DEFAULT 1,
    proposed_amount             NUMERIC(18, 2) DEFAULT 0,
    initial_amount              NUMERIC(18, 2) NOT NULL DEFAULT 0,
    supplement_amount           NUMERIC(18, 2) DEFAULT 0,
    carry_over_in_amount        NUMERIC(18, 2) DEFAULT 0,
    carry_over_out_amount       NUMERIC(18, 2) DEFAULT 0,
    transfer_net_amount         NUMERIC(18, 2) DEFAULT 0,
    reduction_amount            NUMERIC(18, 2) DEFAULT 0,
    effective_amount            NUMERIC(18, 2) DEFAULT 0,
    committed_amount            NUMERIC(18, 2) DEFAULT 0,
    advanced_amount             NUMERIC(18, 2) DEFAULT 0,
    disbursed_amount            NUMERIC(18, 2) DEFAULT 0,
    settled_amount              NUMERIC(18, 2) DEFAULT 0,
    available_amount            NUMERIC(18, 2) DEFAULT 0,
    expired_cancelled_amount    NUMERIC(18, 2) DEFAULT 0,
    disbursement_rate_percent   NUMERIC(5, 2) DEFAULT 0,
    submitted_by_person_id      BIGINT,
    submitted_by_person_name    VARCHAR(255),
    submitted_date              TIMESTAMP,
    approved_by_person_id       BIGINT,
    approved_by_person_name     VARCHAR(255),
    approved_date               TIMESTAMP,
    review_comment              VARCHAR(1000)
);

ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS app_code VARCHAR(50) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS parent_allocation_id BIGINT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS funding_source_id BIGINT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS decision_attachment_ids VARCHAR(1000);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS linked_task_id BIGINT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS linked_task_code VARCHAR(100);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS linked_project_id BIGINT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS linked_project_code VARCHAR(100);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS wf_instance_id BIGINT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS wf_process_code VARCHAR(100);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS expired_cancelled_amount NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS submitted_by_person_id BIGINT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS submitted_by_person_name VARCHAR(255);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS submitted_date TIMESTAMP;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS approved_by_person_id BIGINT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS approved_by_person_name VARCHAR(255);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS approved_date TIMESTAMP;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS review_comment VARCHAR(1000);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS budget_month INTEGER;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS sub_item_code VARCHAR(50);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS domain_id BIGINT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS domain_code VARCHAR(100);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS domain_name VARCHAR(250);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS domain_group_code VARCHAR(100);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS domain_group_name VARCHAR(250);
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS dynamic_breakdown_json TEXT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS decision_document_id BIGINT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS linked_plan_item_id BIGINT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS estimate_year_plus_1 NUMERIC(19, 2) DEFAULT 0;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS estimate_year_plus_2 NUMERIC(19, 2) DEFAULT 0;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS attachment_file_ids_json TEXT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS location_id BIGINT;
ALTER TABLE budget_allocation ADD COLUMN IF NOT EXISTS currency_code VARCHAR(10) DEFAULT 'VND';
ALTER TABLE budget_allocation ALTER COLUMN node_code DROP NOT NULL;
ALTER TABLE budget_allocation ALTER COLUMN node_name DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_budget_allocation_year_node ON budget_allocation (app_code, budget_year, node_id, workflow_status) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_budget_allocation_estimate_item ON budget_allocation (estimate_item_id) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_budget_allocation_category ON budget_allocation (category_id) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_budget_allocation_linked_plan ON budget_allocation (linked_plan_code) WHERE is_deleted = FALSE;

-- -----------------------------------------------------------------------------
-- 5. BẢNG: budget_disbursement (Cam kết chi, Tạm ứng, Giải ngân KBNN & Quyết toán)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_disbursement (
    id                          BIGSERIAL PRIMARY KEY,
    code                        VARCHAR(100) NOT NULL,
    name                        VARCHAR(255) NOT NULL,
    description                 TEXT,
    sort_order                  INTEGER DEFAULT 0,
    status                      VARCHAR(30) DEFAULT 'ACTIVE',
    is_deleted                  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by                  VARCHAR(100),
    updated_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by                  VARCHAR(100),
    version                     BIGINT NOT NULL DEFAULT 0,
    app_code                    VARCHAR(50) NOT NULL DEFAULT 'ADMIN',

    allocation_id               BIGINT NOT NULL,
    allocation_code             VARCHAR(100),
    allocation_name             VARCHAR(255),
    parent_disbursement_id      BIGINT,
    commitment_disbursement_id  BIGINT,
    node_id                     BIGINT NOT NULL,
    node_code                   VARCHAR(100),
    node_name                   VARCHAR(255),
    estimate_item_id            BIGINT,
    estimate_item_code          VARCHAR(100),
    estimate_item_name          VARCHAR(255),
    sub_item_code               VARCHAR(50),
    budget_year                 INTEGER NOT NULL,
    budget_quarter              INTEGER,
    budget_month                INTEGER,
    disbursement_type           VARCHAR(50) NOT NULL,
    payment_method              VARCHAR(50) DEFAULT 'TREASURY_TRANSFER',
    amount                      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    requested_amount            NUMERIC(18, 2) DEFAULT 0,
    approved_amount             NUMERIC(18, 2) DEFAULT 0,
    settled_amount              NUMERIC(18, 2) DEFAULT 0,
    tax_amount                  NUMERIC(18, 2) DEFAULT 0,
    retention_amount            NUMERIC(18, 2) DEFAULT 0,
    advance_recovery_amount     NUMERIC(18, 2) DEFAULT 0,
    net_payment_amount          NUMERIC(18, 2) DEFAULT 0,
    beneficiary_type            VARCHAR(50) DEFAULT 'CONTRACTOR',
    beneficiary_id              BIGINT,
    beneficiary_name            VARCHAR(255),
    beneficiary_bank_account    VARCHAR(100),
    beneficiary_bank_name       VARCHAR(255),
    beneficiary_bank_code       VARCHAR(50),
    beneficiary_tax_code        VARCHAR(50),
    contract_id                 BIGINT,
    contract_no                 VARCHAR(100),
    contract_date               DATE,
    invoice_no                  VARCHAR(100),
    invoice_symbol              VARCHAR(50),
    invoice_date                DATE,
    voucher_no                  VARCHAR(100),
    voucher_date                DATE,
    treasury_ref_code           VARCHAR(100),
    treasury_Commitment_code    VARCHAR(100),
    treasury_form_code          VARCHAR(50),
    treasury_status             VARCHAR(50),
    dvc_kbnn_dossier_no         VARCHAR(100),
    attachment_ids              VARCHAR(1000),
    linked_plan_id              BIGINT,
    linked_plan_code            VARCHAR(100),
    linked_task_id              BIGINT,
    linked_task_code            VARCHAR(100),
    linked_asset_id             BIGINT,
    linked_asset_code           VARCHAR(100),
    payment_order_id            BIGINT,
    payment_tx_code             VARCHAR(100),
    sla_due_date                DATE,
    advance_settlement_due_date DATE,
    is_overdue_warning_sent     BOOLEAN DEFAULT FALSE,
    requested_by_person_id      BIGINT,
    requested_by_person_name    VARCHAR(255),
    approved_by_person_id       BIGINT,
    approved_by_person_name     VARCHAR(255),
    approved_date               TIMESTAMP,
    settled_date                TIMESTAMP,
    wf_instance_id              BIGINT,
    reject_reason               VARCHAR(1000),
    approval_status             VARCHAR(50) NOT NULL DEFAULT 'DRAFT'
);

ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS app_code VARCHAR(50) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS parent_disbursement_id BIGINT;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS commitment_disbursement_id BIGINT;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS sub_item_code VARCHAR(50);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS payment_method VARCHAR(50) DEFAULT 'TREASURY_TRANSFER';
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS requested_amount NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS approved_amount NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS settled_amount NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS retention_amount NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS advance_recovery_amount NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS beneficiary_type VARCHAR(50) DEFAULT 'CONTRACTOR';
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS beneficiary_id BIGINT;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS beneficiary_bank_code VARCHAR(50);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS contract_id BIGINT;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS invoice_no VARCHAR(100);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS invoice_symbol VARCHAR(50);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS invoice_date DATE;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS treasury_commitment_code VARCHAR(100);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS treasury_form_code VARCHAR(50);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS treasury_status VARCHAR(50);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS dvc_kbnn_dossier_no VARCHAR(100);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS attachment_ids VARCHAR(1000);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS linked_task_id BIGINT;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS linked_task_code VARCHAR(100);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS linked_asset_id BIGINT;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS linked_asset_code VARCHAR(100);
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS payment_order_id BIGINT;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS advance_settlement_due_date DATE;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS approved_date TIMESTAMP;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS settled_date TIMESTAMP;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS wf_instance_id BIGINT;
ALTER TABLE budget_disbursement ADD COLUMN IF NOT EXISTS reject_reason VARCHAR(1000);
ALTER TABLE budget_disbursement ALTER COLUMN allocation_code DROP NOT NULL;
ALTER TABLE budget_disbursement ALTER COLUMN node_name DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_budget_disbursement_alloc ON budget_disbursement (app_code, allocation_id, approval_status) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_budget_disbursement_year_node ON budget_disbursement (app_code, budget_year, node_id) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_budget_disbursement_sla ON budget_disbursement (sla_due_date, approval_status) WHERE is_deleted = FALSE;

-- -----------------------------------------------------------------------------
-- 6. BẢNG: budget_transaction (Sổ cái Biến động Nguồn Kinh phí Bút toán kép)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_transaction (
    id                          BIGSERIAL PRIMARY KEY,
    code                        VARCHAR(100) NOT NULL,
    name                        VARCHAR(255) NOT NULL,
    description                 TEXT,
    sort_order                  INTEGER DEFAULT 0,
    status                      VARCHAR(30) DEFAULT 'ACTIVE',
    is_deleted                  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by                  VARCHAR(100),
    updated_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by                  VARCHAR(100),
    version                     BIGINT NOT NULL DEFAULT 0,
    app_code                    VARCHAR(50) NOT NULL DEFAULT 'ADMIN',

    allocation_id               BIGINT NOT NULL,
    allocation_code             VARCHAR(100),
    allocation_name             VARCHAR(255),
    node_id                     BIGINT NOT NULL,
    node_code                   VARCHAR(100),
    node_name                   VARCHAR(255),
    estimate_item_id            BIGINT,
    estimate_item_code          VARCHAR(100),
    estimate_item_name          VARCHAR(255),
    category_id                 BIGINT,
    category_code               VARCHAR(100),
    funding_source_code         VARCHAR(100),
    funding_source_name         VARCHAR(255),
    tx_type                     VARCHAR(50) NOT NULL,
    entry_direction             VARCHAR(20) DEFAULT 'DEBIT',
    budget_year                 INTEGER NOT NULL,
    target_budget_year          INTEGER,
    amount                      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    balance_before_amount       NUMERIC(18, 2),
    balance_after_amount        NUMERIC(18, 2),
    counterpart_tx_id           BIGINT,
    counterpart_allocation_id   BIGINT,
    counterpart_allocation_code VARCHAR(100),
    counterpart_allocation_name VARCHAR(255),
    counterpart_node_id         BIGINT,
    counterpart_node_code       VARCHAR(100),
    counterpart_node_name       VARCHAR(255),
    decision_no                 VARCHAR(100),
    decision_date               DATE,
    tx_date                     DATE NOT NULL,
    treasury_doc_no             VARCHAR(100),
    attachment_ids              VARCHAR(1000),
    reason                      VARCHAR(1000),
    reject_reason               VARCHAR(1000),
    previous_version            INTEGER DEFAULT 1,
    new_version                 INTEGER DEFAULT 2,
    requested_by_person_id      BIGINT,
    requested_by_person_name    VARCHAR(255),
    approved_by_person_id       BIGINT,
    approved_by_person_name     VARCHAR(255),
    approved_date               TIMESTAMP,
    wf_instance_id              BIGINT,
    approval_status             VARCHAR(50) NOT NULL DEFAULT 'APPROVED'
);

ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS app_code VARCHAR(50) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS category_id BIGINT;
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS entry_direction VARCHAR(20) DEFAULT 'DEBIT';
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS balance_before_amount NUMERIC(18, 2);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS balance_after_amount NUMERIC(18, 2);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS counterpart_tx_id BIGINT;
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS counterpart_node_code VARCHAR(100);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS treasury_doc_no VARCHAR(100);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS attachment_ids VARCHAR(1000);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS reject_reason VARCHAR(1000);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS approved_date TIMESTAMP;
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS wf_instance_id BIGINT;
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS category_name VARCHAR(250);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS domain_code VARCHAR(100);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS domain_name VARCHAR(250);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS domain_group_code VARCHAR(100);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS domain_group_name VARCHAR(250);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS dynamic_breakdown_json TEXT;
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS disbursed_amount NUMERIC(19, 2) DEFAULT 0;
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS counterpart_estimate_item_id BIGINT;
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS plan_id BIGINT;
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS plan_code VARCHAR(100);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS plan_name VARCHAR(500);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS decision_document_id BIGINT;
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS wf_process_code VARCHAR(100);
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS attachment_file_ids_json TEXT;
ALTER TABLE budget_transaction ADD COLUMN IF NOT EXISTS currency_code VARCHAR(10) DEFAULT 'VND';
ALTER TABLE budget_transaction ALTER COLUMN allocation_code DROP NOT NULL;
ALTER TABLE budget_transaction ALTER COLUMN node_name DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_budget_transaction_alloc ON budget_transaction (app_code, allocation_id, approval_status) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_budget_transaction_year_type ON budget_transaction (app_code, budget_year, tx_type) WHERE is_deleted = FALSE;

-- -----------------------------------------------------------------------------
-- 7. BẢNG: budget_report (Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS budget_report (
    id                              BIGSERIAL PRIMARY KEY,
    code                            VARCHAR(100) NOT NULL,
    name                            VARCHAR(255) NOT NULL,
    description                     TEXT,
    sort_order                      INTEGER DEFAULT 0,
    status                          VARCHAR(30) DEFAULT 'ACTIVE',
    is_deleted                      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by                      VARCHAR(100),
    updated_at                      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by                      VARCHAR(100),
    version                         BIGINT NOT NULL DEFAULT 0,
    app_code                        VARCHAR(50) NOT NULL DEFAULT 'ADMIN',

    budget_year                     INTEGER NOT NULL,
    report_period                   VARCHAR(50) NOT NULL DEFAULT 'QUARTER',
    period_number                   INTEGER DEFAULT 1,
    period_label                    VARCHAR(100),
    report_scope                    VARCHAR(50) NOT NULL DEFAULT 'CONSOLIDATED_REPORT',
    form_template_code              VARCHAR(100) DEFAULT '01a/SDKP-ĐVDT',
    node_id                         BIGINT,
    node_code                       VARCHAR(100),
    node_name                       VARCHAR(255),
    report_date                     DATE NOT NULL,
    data_cutoff_date                DATE,
    total_revenue_estimate          NUMERIC(18, 2) DEFAULT 0,
    total_revenue_actual            NUMERIC(18, 2) DEFAULT 0,
    total_revenue_cumulative        NUMERIC(18, 2) DEFAULT 0,
    total_expenditure_initial       NUMERIC(18, 2) DEFAULT 0,
    total_expenditure_adjustment    NUMERIC(18, 2) DEFAULT 0,
    total_expenditure_effective     NUMERIC(18, 2) DEFAULT 0,
    total_expenditure_committed     NUMERIC(18, 2) DEFAULT 0,
    total_expenditure_advanced      NUMERIC(18, 2) DEFAULT 0,
    total_expenditure_actual        NUMERIC(18, 2) DEFAULT 0,
    total_expenditure_settled       NUMERIC(18, 2) DEFAULT 0,
    total_expenditure_cumulative    NUMERIC(18, 2) DEFAULT 0,
    total_carry_over_amount         NUMERIC(18, 2) DEFAULT 0,
    total_surplus_or_saving         NUMERIC(18, 2) DEFAULT 0,
    total_remaining_balance         NUMERIC(18, 2) DEFAULT 0,
    total_expired_cancelled         NUMERIC(18, 2) DEFAULT 0,
    disbursement_rate_percent       NUMERIC(5, 2) DEFAULT 0,
    detail_lines_json               TEXT,
    reconciliation_statement_json   TEXT,
    treasury_confirmation_code      VARCHAR(100),
    treasury_confirmed_date         DATE,
    attachment_ids                  VARCHAR(1000),
    submitted_by_person_id          BIGINT,
    submitted_by_person_name        VARCHAR(255),
    submitted_date                  TIMESTAMP,
    reviewed_by_person_id           BIGINT,
    reviewed_by_person_name         VARCHAR(255),
    approved_by_person_id           BIGINT,
    approved_by_person_name         VARCHAR(255),
    approved_date                   TIMESTAMP,
    wf_instance_id                  BIGINT,
    review_comment                  VARCHAR(1000),
    workflow_status                 VARCHAR(50) NOT NULL DEFAULT 'APPROVED'
);

ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS app_code VARCHAR(50) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS period_label VARCHAR(100);
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS form_template_code VARCHAR(100) DEFAULT '01a/SDKP-ĐVDT';
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS data_cutoff_date DATE;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS total_expenditure_adjustment NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS total_expenditure_committed NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS total_expenditure_advanced NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS total_expenditure_settled NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS total_remaining_balance NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS total_expired_cancelled NUMERIC(18, 2) DEFAULT 0;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS reconciliation_statement_json TEXT;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS treasury_confirmation_code VARCHAR(100);
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS treasury_confirmed_date DATE;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS attachment_ids VARCHAR(1000);
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS submitted_date TIMESTAMP;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS approved_by_person_id BIGINT;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS approved_by_person_name VARCHAR(255);
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS approved_date TIMESTAMP;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS wf_instance_id BIGINT;
ALTER TABLE budget_report ADD COLUMN IF NOT EXISTS review_comment VARCHAR(1000);
ALTER TABLE budget_report ALTER COLUMN detail_lines_json TYPE TEXT;

CREATE INDEX IF NOT EXISTS idx_budget_report_year_period ON budget_report (app_code, budget_year, report_period, period_number) WHERE is_deleted = FALSE;
