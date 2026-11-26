package vn.tr.budget.controller;

import cn.dev33.satoken.same.SaSameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import vn.tr.budget.business.BudgetTransactionBusiness;
import vn.tr.common.core.domain.R;
import vn.tr.common.feign.budget.data.BudgetCommitmentReserveData;
import vn.tr.common.feign.budget.data.BudgetLedgerTransactionData;
import vn.tr.common.feign.budget.data.BudgetPaymentSettleData;
import vn.tr.common.feign.budget.data.BudgetPlanSyncRequestData;
import vn.tr.common.feign.budget.data.BudgetPlanSyncResultData;
import vn.tr.common.feign.dto.InternalAnalyticsMetricData;
import vn.tr.common.web.core.BaseController;

/**
 * Controller nội bộ (Service-to-Service) phục vụ giao tiếp Feign liên thông giữa các Microservices
 * ({@code tr-planning-service}, {@code tr-payment-service}, {@code tr-reporting-service}) với
 * {@code tr-budget-service}.
 *
 * <p><b>Bảo mật phòng thủ chiều sâu (TD-C01)</b>: Không gắn {@code @SaIgnore}; vừa được {@code
 * SecurityConfig} chặn tại tầng Interceptor cho đường dẫn {@code /internal/**}, vừa gọi tường minh
 * {@code SaSameUtil.checkCurrentRequestToken()} ở cả 7/7 endpoint S2S.
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
  private final BudgetTransactionBusiness budgetTransactionBusiness;
  private final BudgetDashboardBusiness budgetDashboardBusiness;
  private final ObjectMapper objectMapper;

  @PostMapping("/allocations/sync-from-plan")
  @Operation(summary = "Đồng bộ Phương án Phân bổ Kinh phí từ Kế hoạch công tác (S2S)")
  public R<BudgetPlanSyncResultData> syncAllocationFromPlan(
      @Valid @RequestBody BudgetPlanSyncRequestData request) {
    SaSameUtil.checkCurrentRequestToken();
    return success(budgetAllocationBusiness.syncFromPlan(request));
  }

  @GetMapping("/allocations/by-plan/{planCode}")
  @Operation(summary = "Tra cứu bức tranh cân đối Kinh phí theo Mã Kế hoạch công tác (S2S)")
  public R<BudgetPlanSyncResultData> getBudgetSnapshotByPlanCode(
      @PathVariable("planCode") String planCode) {
    SaSameUtil.checkCurrentRequestToken();
    return success(budgetAllocationBusiness.getBudgetSnapshotByPlanCode(planCode));
  }

  @PostMapping("/transactions/record")
  @Operation(summary = "Ghi nhận giao dịch biến động sổ cái kinh phí từ Kế hoạch công tác (S2S)")
  public R<Map<String, Object>> recordPlanLedgerTransaction(
      @Valid @RequestBody BudgetLedgerTransactionData request) {
    SaSameUtil.checkCurrentRequestToken();
    @SuppressWarnings("unchecked")
    Map<String, Object> body = objectMapper.convertValue(request, Map.class);
    return success(budgetTransactionBusiness.recordFromPlan(body));
  }

  @PostMapping("/disbursements/reserve-commitment")
  @Operation(summary = "Khởi tạo nhanh hồ sơ Cam kết chi (COMMITMENT) liên thông S2S")
  public R<Map<String, Object>> reserveCommitment(
      @Valid @RequestBody BudgetCommitmentReserveData request) {
    SaSameUtil.checkCurrentRequestToken();
    @SuppressWarnings("unchecked")
    Map<String, Object> body = objectMapper.convertValue(request, Map.class);
    return success(budgetDisbursementBusiness.reserveCommitment(body));
  }

  @PostMapping("/disbursements/{id}/payment-webhook-settle")
  @Operation(
      summary =
          "Tự động đối soát và nghiệm thu quyết toán hồ sơ giải ngân từ Webhook Thanh toán (S2S)")
  public R<Map<String, Object>> settleDisbursementFromPaymentWebhook(
      @PathVariable("id") Long id,
      @Valid @RequestBody(required = false) BudgetPaymentSettleData request) {
    SaSameUtil.checkCurrentRequestToken();
    return success(budgetDisbursementBusiness.settleFromPaymentWebhook(id, request));
  }

  @GetMapping("/dashboard/overview")
  @Operation(summary = "Truy vấn tổng quan KPI điều hành ngân sách theo năm và đơn vị (S2S)")
  public R<Map<String, Object>> getDashboardOverview(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear,
      @RequestParam(value = "nodeId", required = false) Long nodeId) {
    SaSameUtil.checkCurrentRequestToken();
    return success(budgetDashboardBusiness.getOverview(budgetYear, nodeId, null));
  }

  /**
   * Truy vấn chỉ số phân tích kinh phí & giải ngân chuẩn hóa (chỉ đọc) phục vụ Analytics Hub
   * ({@code tr-reporting-service}) qua kênh S2S Same-Token.
   */
  @GetMapping("/analytics/metrics")
  @Operation(summary = "Truy vấn chỉ số phân tích ngân sách chuẩn hóa cho Analytics Hub (S2S)")
  public R<InternalAnalyticsMetricData> getInternalAnalyticsMetrics(
      @RequestParam(value = "unitCode", required = false) String unitCode,
      @RequestParam(value = "year", required = false) Integer year,
      @RequestParam(value = "period", required = false) String period) {
    SaSameUtil.checkCurrentRequestToken();
    return success(budgetDashboardBusiness.getInternalAnalyticsMetrics(unitCode, year, period));
  }
}
