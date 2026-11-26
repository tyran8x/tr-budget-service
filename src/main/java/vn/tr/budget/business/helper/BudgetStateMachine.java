package vn.tr.budget.business.helper;

import cn.hutool.core.util.StrUtil;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.tr.common.core.exception.base.BaseException;

/**
 * Máy trạng thái chặt (Strict State Machine) quản trị vòng đời chứng từ ngân sách:
 * BudgetAllocation, BudgetDisbursement, BudgetTransaction và BudgetReport.
 *
 * <p>Cưỡng chế các bất biến sổ cái: không nhảy cóc trạng thái, không chỉnh sửa / xóa / rollback
 * chứng từ đã phê duyệt hoặc đã quyết toán ({@code APPROVED}, {@code SELF_APPROVED}, {@code
 * APPROVED_DISBURSED}, {@code SETTLED}, {@code EXECUTING}, {@code LOCKED_SETTLED}).
 *
 * @author tyran8x
 */
@Component
public class BudgetStateMachine {

  private static final Set<String> LOCKED_ALLOCATION_STATUSES =
      Set.of("APPROVED", "SELF_APPROVED", "EXECUTING", "SETTLED", "LOCKED_SETTLED");

  private static final Set<String> LOCKED_DISBURSEMENT_STATUSES =
      Set.of("APPROVED", "APPROVED_DISBURSED", "SETTLED", "LOCKED_SETTLED");

  private static final Set<String> LOCKED_TRANSACTION_STATUSES =
      Set.of("APPROVED", "SETTLED", "LOCKED_SETTLED");

  private static final Set<String> LOCKED_REPORT_STATUSES =
      Set.of("APPROVED", "SETTLED", "LOCKED_SETTLED");

  private static final Map<String, Set<String>> ALLOCATION_TRANSITIONS =
      Map.ofEntries(
          Map.entry("DRAFT", Set.of("PENDING_APPROVAL", "SELF_APPROVED", "CANCELLED")),
          Map.entry("REWORK", Set.of("PENDING_APPROVAL", "SELF_APPROVED", "DRAFT", "CANCELLED")),
          Map.entry(
              "PENDING_APPROVAL",
              Set.of("APPROVED", "SELF_APPROVED", "REWORK", "REJECTED", "DRAFT")),
          Map.entry("REJECTED", Set.of("DRAFT", "PENDING_APPROVAL", "CANCELLED")),
          Map.entry("APPROVED", Set.of("EXECUTING", "SETTLED")),
          Map.entry("SELF_APPROVED", Set.of("EXECUTING", "SETTLED")),
          Map.entry("EXECUTING", Set.of("SETTLED")));

  private static final Map<String, Set<String>> DISBURSEMENT_TRANSITIONS =
      Map.ofEntries(
          Map.entry("DRAFT", Set.of("PENDING_APPROVAL", "COMMITTED", "CANCELLED")),
          Map.entry("REWORK", Set.of("PENDING_APPROVAL", "DRAFT", "CANCELLED")),
          Map.entry("COMMITTED", Set.of("PENDING_APPROVAL", "APPROVED_DISBURSED", "CANCELLED")),
          Map.entry(
              "PENDING_APPROVAL",
              Set.of("APPROVED_DISBURSED", "APPROVED", "REWORK", "REJECTED", "DRAFT")),
          Map.entry("REJECTED", Set.of("DRAFT", "PENDING_APPROVAL", "CANCELLED")),
          Map.entry("APPROVED", Set.of("APPROVED_DISBURSED", "SETTLED")),
          Map.entry("APPROVED_DISBURSED", Set.of("SETTLED")));

  private static final Map<String, Set<String>> TRANSACTION_TRANSITIONS =
      Map.ofEntries(
          Map.entry("DRAFT", Set.of("PENDING_APPROVAL", "CANCELLED")),
          Map.entry("REWORK", Set.of("PENDING_APPROVAL", "DRAFT", "CANCELLED")),
          Map.entry("PENDING_APPROVAL", Set.of("APPROVED", "REWORK", "REJECTED", "DRAFT")),
          Map.entry("REJECTED", Set.of("DRAFT", "PENDING_APPROVAL", "CANCELLED")),
          Map.entry("APPROVED", Set.of("SETTLED")));

  private static final Map<String, Set<String>> REPORT_TRANSITIONS =
      Map.ofEntries(
          Map.entry("DRAFT", Set.of("PENDING_APPROVAL", "CANCELLED")),
          Map.entry("REWORK", Set.of("PENDING_APPROVAL", "DRAFT", "CANCELLED")),
          Map.entry("PENDING_APPROVAL", Set.of("APPROVED", "REWORK", "REJECTED", "DRAFT")),
          Map.entry("REJECTED", Set.of("DRAFT", "PENDING_APPROVAL", "CANCELLED")),
          Map.entry("APPROVED", Set.of("SETTLED")));

  public String normalize(String status) {
    if (StrUtil.isBlank(status)) {
      return "DRAFT";
    }
    String upper = status.trim().toUpperCase();
    if ("SUBMITTED".equals(upper) || "PENDING".equals(upper)) {
      return "PENDING_APPROVAL";
    }
    return upper;
  }

  public boolean isLockedAllocation(String status) {
    return LOCKED_ALLOCATION_STATUSES.contains(normalize(status));
  }

  public boolean isLockedDisbursement(String status) {
    return LOCKED_DISBURSEMENT_STATUSES.contains(normalize(status));
  }

  public boolean isLockedTransaction(String status) {
    return LOCKED_TRANSACTION_STATUSES.contains(normalize(status));
  }

  public boolean isLockedReport(String status) {
    return LOCKED_REPORT_STATUSES.contains(normalize(status));
  }

  public void assertMutableAllocation(String status, String action, String code) {
    if (isLockedAllocation(status)) {
      throw new BaseException(
          "Không thể "
              + action
              + " phương án phân bổ ["
              + code
              + "] vì đang ở trạng thái đã chốt ["
              + status
              + "]. Vui lòng lập bút toán điều chỉnh sổ cái.");
    }
  }

  public void assertMutableDisbursement(String status, String action, String code) {
    if (isLockedDisbursement(status)) {
      throw new BaseException(
          "Không thể "
              + action
              + " hồ sơ giải ngân ["
              + code
              + "] vì đang ở trạng thái đã duyệt/quyết toán ["
              + status
              + "].");
    }
  }

  public void assertMutableTransaction(String status, String action, String code) {
    if (isLockedTransaction(status)) {
      throw new BaseException(
          "Không thể "
              + action
              + " bút toán sổ cái ["
              + code
              + "] vì đang ở trạng thái đã phê duyệt/quyết toán ["
              + status
              + "].");
    }
  }

  public void assertMutableReport(String status, String action, String code) {
    if (isLockedReport(status)) {
      throw new BaseException(
          "Không thể "
              + action
              + " báo cáo quyết toán ["
              + code
              + "] vì đang ở trạng thái đã phê duyệt/chốt sổ ["
              + status
              + "].");
    }
  }

  public void assertAllocationTransition(String currentStatus, String targetStatus, String code) {
    assertTransition("Phân bổ dự toán", code, currentStatus, targetStatus, ALLOCATION_TRANSITIONS);
  }

  public void assertDisbursementTransition(String currentStatus, String targetStatus, String code) {
    assertTransition("Hồ sơ giải ngân", code, currentStatus, targetStatus, DISBURSEMENT_TRANSITIONS);
  }

  public void assertTransactionTransition(String currentStatus, String targetStatus, String code) {
    assertTransition("Bút toán sổ cái", code, currentStatus, targetStatus, TRANSACTION_TRANSITIONS);
  }

  public void assertReportTransition(String currentStatus, String targetStatus, String code) {
    assertTransition("Báo cáo quyết toán", code, currentStatus, targetStatus, REPORT_TRANSITIONS);
  }

  private void assertTransition(
      String entityLabel,
      String code,
      String currentStatus,
      String targetStatus,
      Map<String, Set<String>> matrix) {
    String from = normalize(currentStatus);
    String to = normalize(targetStatus);
    Set<String> allowed = matrix.getOrDefault(from, Set.of());
    if (!allowed.contains(to)) {
      throw new BaseException(
          entityLabel
              + " ["
              + code
              + "] không hợp lệ khi chuyển trạng thái từ ["
              + from
              + "] sang ["
              + to
              + "].");
    }
  }
}
