package vn.tr.budget.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.tr.budget.business.BudgetDashboardBusiness;
import vn.tr.common.core.domain.R;
import vn.tr.common.web.core.BaseController;

/**
 * Controller Thống kê KPI, Ma trận Kinh phí Đơn vị, Cây Dự toán Thu - Chi,
 * Bảng điểm Kỷ luật Giải ngân & Cố vấn AI Tài chính.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Tag(
    name = "Dashboard & Báo cáo Điều hành Kinh phí",
    description =
        "API thống kê tổng quan Dự toán Thu - Chi, Tiến độ Giải ngân, Ma trận Đơn vị, Bảng điểm Kỷ luật Giải ngân và Cố vấn AI")
public class BudgetDashboardController extends BaseController {

  private final BudgetDashboardBusiness budgetDashboardBusiness;

  @SaCheckPermission("BudgetAllocationList")
  @GetMapping({"/overview", "/summary"})
  @Operation(summary = "Lấy chỉ số KPI tổng quan Dự toán Thu - Chi, Bổ sung, Chuyển nguồn & Giải ngân")
  public R<Map<String, Object>> getOverview(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear,
      @RequestParam(value = "nodeId", required = false) Long nodeId,
      @RequestParam(value = "periodType", required = false) String periodType) {
    return success(budgetDashboardBusiness.getOverview(budgetYear, nodeId, periodType));
  }

  @SaCheckPermission("BudgetAllocationList")
  @GetMapping("/unit-matrix")
  @Operation(summary = "Lấy bảng tổng hợp phân bổ, bổ sung, chuyển nguồn & giải ngân theo từng Đơn vị / Phòng ban")
  public R<List<Map<String, Object>>> getUnitMatrix(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear) {
    return success(budgetDashboardBusiness.getUnitMatrix(budgetYear));
  }

  @SaCheckPermission("BudgetAllocationList")
  @GetMapping("/revenue-expenditure-tree")
  @Operation(summary = "Lấy bảng tổng hợp Cây Dự toán Thu - Chi đa cấp kèm số liệu phân bổ & giải ngân")
  public R<List<Map<String, Object>>> getRevenueExpenditureTree(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear) {
    return success(budgetDashboardBusiness.getRevenueExpenditureTree(budgetYear));
  }

  @SaCheckPermission("BudgetAllocationList")
  @GetMapping("/disbursement-scorecard")
  @Operation(summary = "Lấy Bảng chấm điểm Kỷ luật Giải ngân & Quyết toán theo từng Đơn vị (Thang 100 điểm)")
  public R<List<Map<String, Object>>> getDisbursementScorecard(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear) {
    return success(budgetDashboardBusiness.getDisbursementScorecard(budgetYear));
  }

  @SaCheckPermission("BudgetAllocationList")
  @PostMapping("/ai-advisor")
  @Operation(
      summary =
          "Phân tích & Đề xuất Điều hành Kinh phí thông minh bằng AI (Đẩy nhanh giải ngân, Điều chuyển nội bộ, Chuyển nguồn)")
  public R<Map<String, Object>> getAiAdvisorInsights(
      @RequestBody(required = false) Map<String, Object> body) {
    Integer budgetYear =
        body != null && body.get("budgetYear") != null
            ? Integer.valueOf(body.get("budgetYear").toString())
            : null;
    return success(budgetDashboardBusiness.generateAiAdvisorInsights(budgetYear));
  }

  @SaCheckPermission("BudgetAllocationList")
  @GetMapping("/burn-rate-forecast")
  @Operation(
      summary =
          "Dự báo tốc độ tiêu dùng kinh phí (Burn-rate Projection), cảnh báo tồn dư cuối năm & gợi ý điều chuyển nội bộ tự động")
  public R<Map<String, Object>> getBurnRateForecast(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear) {
    return success(budgetDashboardBusiness.getBurnRateForecast(budgetYear));
  }

  @SaCheckPermission("BudgetAllocationList")
  @GetMapping("/mtef-summary")
  @Operation(
      summary =
          "Tổng hợp Khung Kế hoạch Tài chính - Ngân sách Trung hạn 3 năm cuốn chiếu (3-Year Rolling MTEF)")
  public R<Map<String, Object>> getMtefSummary(
      @RequestParam(value = "baseYear", required = false) Integer baseYear) {
    return success(budgetDashboardBusiness.getMtefSummary(baseYear));
  }
}
