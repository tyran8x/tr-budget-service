-- ============================================================================
-- Migration V303_003: Nâng cấp Cây Dự toán Thu - Chi hỗ trợ đa năm ngân sách
-- (cho phép sao chép / đồng bộ cấu trúc cây độc lập theo từng budget_year)
-- ============================================================================

DROP INDEX IF EXISTS uk_budget_estimate_item_app_code;

CREATE UNIQUE INDEX IF NOT EXISTS uk_budget_estimate_item_app_year_code
    ON budget_estimate_item (app_code, COALESCE(budget_year, 0), code)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_budget_estimate_item_budget_year
    ON budget_estimate_item (app_code, budget_year, sort_order)
    WHERE deleted_at IS NULL;
