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
  INTERNAL_TRANSFER("INTERNAL_TRANSFER", "Điều chuyển nội bộ (Giảm đơn vị nguồn)"),
  TRANSFER_IN("TRANSFER_IN", "Nhận điều chuyển nội bộ (Tăng đơn vị đích)"),
  CARRY_OVER_OUT("CARRY_OVER_OUT", "Chuyển nguồn sang năm sau (N -> N+1)"),
  CARRY_OVER_IN("CARRY_OVER_IN", "Nhận nguồn chuyển từ năm trước (N-1 -> N)");

  private final String code;
  private final String label;
}
