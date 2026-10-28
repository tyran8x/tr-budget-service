package vn.tr.budget.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Phân nhóm mức độ tự chủ tài chính của đơn vị sự nghiệp công lập / cơ quan nhà nước theo Nghị định
 * 60/2021/NĐ-CP và Nghị định 130/2005/NĐ-CP.
 *
 * @author tyran8x
 */
@Getter
@RequiredArgsConstructor
public enum BudgetAutonomyType {
  GROUP_1("GROUP_1", "Nhóm 1 — Tự bảo đảm chi thường xuyên và chi đầu tư"),
  GROUP_2("GROUP_2", "Nhóm 2 — Tự bảo đảm chi thường xuyên"),
  GROUP_3("GROUP_3", "Nhóm 3 — Tự bảo đảm một phần chi thường xuyên"),
  GROUP_4("GROUP_4", "Nhóm 4 — Ngân sách nhà nước bảo đảm toàn bộ"),
  STATE_ADMIN("STATE_ADMIN", "Cơ quan quản lý hành chính nhà nước (Khoán chi NĐ 130)");

  private final String code;
  private final String label;
}
