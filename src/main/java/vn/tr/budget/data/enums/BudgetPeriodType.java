package vn.tr.budget.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Chu kỳ phân bổ và báo cáo ngân sách.
 *
 * @author tyran8x
 */
@Getter
@RequiredArgsConstructor
public enum BudgetPeriodType {
  MONTH("MONTH", "Theo tháng"),
  QUARTER("QUARTER", "Theo quý"),
  HALF_YEAR("HALF_YEAR", "6 tháng (Bán niên)"),
  YEAR("YEAR", "Cả năm ngân sách"),
  MULTI_YEAR("MULTI_YEAR", "Kế hoạch trung hạn 3 năm (MTEF)");

  private final String code;
  private final String label;
}
