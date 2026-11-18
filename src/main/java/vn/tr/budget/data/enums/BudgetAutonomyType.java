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
  STATE_ADMIN("STATE_ADMIN", "Cơ quan quản lý hành chính nhà nước (Khoán chi NĐ 130)"),
  AUTONOMOUS("AUTONOMOUS", "Kinh phí giao tự chủ"),
  NON_AUTONOMOUS("NON_AUTONOMOUS", "Kinh phí không giao tự chủ"),
  PARTIALLY_AUTONOMOUS("PARTIALLY_AUTONOMOUS", "Tự chủ một phần");

  private final String code;
  private final String label;

  public static BudgetAutonomyType fromCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    for (BudgetAutonomyType value : values()) {
      if (value.code.equalsIgnoreCase(code.trim())) {
        return value;
      }
    }
    return null;
  }

  public static BudgetAutonomyType fromCodeOrDefault(String code, BudgetAutonomyType fallback) {
    BudgetAutonomyType resolved = fromCode(code);
    return resolved != null ? resolved : fallback;
  }

  public static boolean isValid(String code) {
    return fromCode(code) != null;
  }
}
