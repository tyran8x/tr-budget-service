package vn.tr.budget.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Phạm vi thẩm quyền phê duyệt dự toán kinh phí.
 *
 * @author tyran8x
 */
@Getter
@RequiredArgsConstructor
public enum BudgetApprovalScope {
  UNIT_SELF_APPROVAL("UNIT_SELF_APPROVAL", "Thủ trưởng đơn vị tự phê duyệt trong hạn mức tự chủ"),
  SUPERIOR_APPROVAL("SUPERIOR_APPROVAL", "Trình cơ quan chủ quản cấp trên phê duyệt"),
  TREASURY_RECONCILED("TREASURY_RECONCILED", "Đã đối chiếu Kho bạc Nhà nước (KBNN)");

  private final String code;
  private final String label;

  public static BudgetApprovalScope fromCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    for (BudgetApprovalScope value : values()) {
      if (value.code.equalsIgnoreCase(code.trim())) {
        return value;
      }
    }
    return null;
  }
}
