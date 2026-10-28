package vn.tr.budget.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Loại nghiệp vụ giải ngân và thanh quyết toán tại Kho bạc Nhà nước (KBNN).
 *
 * @author tyran8x
 */
@Getter
@RequiredArgsConstructor
public enum BudgetDisbursementType {
  COMMITMENT("COMMITMENT", "Tạm giữ cam kết chi (CKC)"),
  ADVANCE("ADVANCE", "Tạm ứng dự toán Kho bạc"),
  DISBURSEMENT("DISBURSEMENT", "Thực chi / Thanh toán trực tiếp"),
  SETTLEMENT("SETTLEMENT", "Nghiệm thu quyết toán / Hoàn ứng");

  private final String code;
  private final String label;
}
