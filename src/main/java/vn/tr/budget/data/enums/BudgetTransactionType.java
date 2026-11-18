package vn.tr.budget.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Loại giao dịch biến động dự toán trên Sổ cái bút toán kép.
 *
 * @author tyran8x
 */
@Getter
@RequiredArgsConstructor
public enum BudgetTransactionType {
  INITIAL_ALLOCATION("INITIAL_ALLOCATION", "Giao dự toán đầu năm"),
  SUPPLEMENT("SUPPLEMENT", "Bổ sung dự toán trong kỳ"),
  ADJUSTMENT("ADJUSTMENT", "Điều chỉnh dự toán"),
  REDUCTION("REDUCTION", "Cắt giảm / Thu hồi dự toán"),
  WITHDRAWAL("WITHDRAWAL", "Thu hồi / Hủy dự toán hết niên độ nộp trả NSNN"),
  INTERNAL_TRANSFER("INTERNAL_TRANSFER", "Điều chuyển nội bộ (Giảm đơn vị nguồn)"),
  TRANSFER_IN("TRANSFER_IN", "Nhận điều chuyển nội bộ (Tăng đơn vị đích)"),
  TRANSFER_OUT("TRANSFER_OUT", "Xuất điều chuyển nội bộ (Giảm đơn vị nguồn)"),
  CARRY_OVER_OUT("CARRY_OVER_OUT", "Chuyển nguồn sang năm sau (N -> N+1)"),
  CARRY_OVER_IN("CARRY_OVER_IN", "Nhận nguồn chuyển từ năm trước (N-1 -> N)"),
  COMMITMENT("COMMITMENT", "Cam kết chi / Tạm giữ dự toán"),
  EARMARK_HOLD("EARMARK_HOLD", "Phong tỏa / Tạm giữ chủ trương"),
  EARMARK_RELEASE("EARMARK_RELEASE", "Giải tỏa nguồn tạm giữ chủ trương"),
  TRANCHE_RELEASE("TRANCHE_RELEASE", "Mở hạn mức dự toán theo đợt"),
  BATCH_PAYROLL_INDEXATION("BATCH_PAYROLL_INDEXATION", "Bổ sung quỹ lương theo lương cơ sở"),
  AUTONOMY_FUND_DISTRIBUTION("AUTONOMY_FUND_DISTRIBUTION", "Trích lập quỹ tự chủ NĐ 60/2021");

  private final String code;
  private final String label;

  public static BudgetTransactionType fromCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    for (BudgetTransactionType value : values()) {
      if (value.code.equalsIgnoreCase(code.trim())) {
        return value;
      }
    }
    return null;
  }

  public static BudgetTransactionType fromCodeOrDefault(
      String code, BudgetTransactionType fallback) {
    BudgetTransactionType resolved = fromCode(code);
    return resolved != null ? resolved : fallback;
  }

  public static boolean isValid(String code) {
    return fromCode(code) != null;
  }
}
