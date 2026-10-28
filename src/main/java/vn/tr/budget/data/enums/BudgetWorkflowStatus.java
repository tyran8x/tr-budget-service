package vn.tr.budget.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Trạng thái quy trình phê duyệt dự toán, giao dịch biến động và báo cáo kinh phí.
 *
 * @author tyran8x
 */
@Getter
@RequiredArgsConstructor
public enum BudgetWorkflowStatus {
  DRAFT("DRAFT", "Dự thảo"),
  PENDING_APPROVAL("PENDING_APPROVAL", "Chờ phê duyệt"),
  APPROVED("APPROVED", "Đã phê duyệt"),
  SELF_APPROVED("SELF_APPROVED", "Đơn vị tự phê duyệt (NĐ 60/2021)"),
  COMMITTED("COMMITTED", "Đã tạm giữ cam kết chi"),
  APPROVED_DISBURSED("APPROVED_DISBURSED", "Đã duyệt giải ngân KBNN"),
  SETTLED("SETTLED", "Đã nghiệm thu quyết toán"),
  REJECTED("REJECTED", "Từ chối / Trả lại"),
  CANCELLED("CANCELLED", "Đã huỷ");

  private final String code;
  private final String label;
}
