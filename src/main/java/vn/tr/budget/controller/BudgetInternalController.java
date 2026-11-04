package vn.tr.budget.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.tr.budget.business.BudgetAllocationBusiness;
import vn.tr.budget.business.BudgetDashboardBusiness;
import vn.tr.budget.business.BudgetDisbursementBusiness;
import vn.tr.common.core.domain.R;
import vn.tr.common.feign.budget.data.BudgetPlanSyncRequestData;
import vn.tr.common.feign.budget.data.BudgetPlanSyncResultData;
import vn.tr.common.web.core.BaseController;

/**
 * Controller nội bộ (Service-to-Service) phục vụ giao tiếp Feign liên thông giữa các Microservices
 * ({@code tr-planning-service}, {@code tr-payment-service}, {@code tr-report-service}) với {@code
 * tr-budget-service}.
 *
 * <p>Bảo vệ bằng {@code SaSameUtil.checkCurrentRequestToken()} trên toàn bộ đường dẫn {@code
 * /internal/**} để cho phép gọi nội bộ không phụ thuộc phiên đăng nhập người dùng.
 *
 * @author tyran8x
 */
@Hidden
@RestController
@RequestMapping("/internal/budget")
@RequiredArgsConstructor
@Tag(
    name = "Internal Budget S2S API",
    description = "API nội bộ liên thông Kinh phí - Kế hoạch - Thanh toán (Sa-Token Same-Token)")
public class BudgetInternalController extends BaseController {

  private final BudgetAllocationBusiness budgetAllocationBusiness;
  private final BudgetDisbursementBusiness budgetDisbursementBusiness;
  private final BudgetDashboardBusiness budgetDashboardBusiness;

  @PostMapping("/allocations/sync-from-plan")
  @Operation(summary = "Đồng bộ Phương án Phân bổ Kinh phí từ Kế hoạch công tác (S2S)")
  public R<BudgetPlanSyncResultData> syncAllocationFromPlan(
      @RequestBody BudgetPlanSyncRequestData request) {
    return success(budgetAllocationBusiness.syncFromPlan(request));
  }

  @GetMapping("/allocations/by-plan/{planCode}")
  @Operation(summary = "Tra cứu bức tranh cân đối Kinh phí theo Mã Kế hoạch công tác (S2S)")
  public R<BudgetPlanSyncResultData> getBudgetSnapshotByPlanCode(
      @PathVariable("planCode") String planCode) {
    return success(budgetAllocationBusiness.getBudgetSnapshotByPlanCode(planCode));
  }

  @PostMapping("/disbursements/reserve-commitment")
  @Operation(summary = "Khởi tạo nhanh hồ sơ Cam kết chi (COMMITMENT) liên thông S2S")
  public R<Map<String, Object>> reserveCommitment(@RequestBody Map<String, Object> request) {
    return success(budgetDisbursementBusiness.reserveCommitment(request));
  }

  @PostMapping("/disbursements/{id}/payment-webhook-settle")
  @Operation(
      summary =
          "Tự động đối soát và nghiệm thu quyết toán hồ sơ giải ngân từ Webhook Thanh toán (S2S)")
  public R<Map<String, Object>> settleDisbursementFromPaymentWebhook(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> request) {
    return success(budgetDisbursementBusiness.settleFromPaymentWebhook(id, request));
  }

  @GetMapping("/dashboard/overview")
  @Operation(summary = "Truy vấn tổng quan KPI điều hành ngân sách theo năm và đơn vị (S2S)")
  public R<Map<String, Object>> getDashboardOverview(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear,
      @RequestParam(value = "nodeId", required = false) Long nodeId) {
    return success(budgetDashboardBusiness.getOverview(budgetYear, nodeId, null));
  }
}
