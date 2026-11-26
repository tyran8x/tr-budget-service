package vn.tr.budget.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.tr.budget.business.BudgetReportBusiness;
import vn.tr.budget.data.criteria.BudgetReportSearchCriteria;
import vn.tr.budget.data.dto.BudgetReportData;
import vn.tr.budget.data.mapper.BudgetReportMapper;
import vn.tr.budget.excel.BudgetExcelControllerHelper;
import vn.tr.budget.excel.BudgetReportExcelService;
import vn.tr.common.core.domain.R;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.domain.data.RefData;
import vn.tr.common.log.annotation.Log;
import vn.tr.common.log.enums.BusinessType;
import vn.tr.common.ratelimiter.annotation.RateLimiter;
import vn.tr.common.ratelimiter.enums.LimitType;
import vn.tr.common.web.core.BaseController;
import vn.tr.common.web.data.dto.BulkOperationResult;
import vn.tr.common.web.data.dto.BulkSortOrderData;
import vn.tr.common.web.data.dto.DeleteData;

/**
 * Controller cho BudgetReport.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
@Tag(
    name = "Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ",
    description =
        "API quản lý Báo cáo Thực hiện Thu - Chi, Giải ngân & Tổng hợp Quyết toán định kỳ Tháng/Quý/6 tháng/Năm")
public class BudgetReportController extends BaseController {

  private final BudgetReportBusiness budgetReportBusiness;
  private final BudgetReportExcelService budgetReportExcelService;
  private final BudgetReportMapper budgetReportMapper;

  @SaCheckPermission("BudgetReportCreate")
  @PostMapping
  @Operation(summary = "Tạo mới Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  @Log(title = "[BudgetReport] Create", businessType = BusinessType.INSERT)
  public R<BudgetReportData> create(@Valid @RequestBody BudgetReportData data) {
    return success(budgetReportBusiness.create(data));
  }

  @SaCheckPermission("BudgetReportEdit")
  @PutMapping("/{id}")
  @Operation(summary = "Cập nhật Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  @Log(title = "[BudgetReport] Update", businessType = BusinessType.UPDATE)
  public R<BudgetReportData> update(
      @PathVariable Long id, @Valid @RequestBody BudgetReportData data) {
    return success(budgetReportBusiness.update(id, data));
  }

  @SaCheckPermission("BudgetReportDelete")
  @DeleteMapping("/{id}")
  @Operation(summary = "Xoá Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  @Log(title = "[BudgetReport] Delete", businessType = BusinessType.DELETE)
  public R<Void> delete(@PathVariable Long id) {
    budgetReportBusiness.delete(id);
    return success();
  }

  @SaCheckPermission("BudgetReportBatchDelete")
  @DeleteMapping
  @Operation(summary = "Xoá hàng loạt Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  @Log(title = "[BudgetReport] Bulk Delete", businessType = BusinessType.DELETE)
  public R<BulkOperationResult<Long>> bulkDelete(@Valid @RequestBody DeleteData deleteData) {
    return success(budgetReportBusiness.bulkDelete(deleteData.getIds()));
  }

  @SaCheckPermission("BudgetReportList")
  @GetMapping
  @Operation(summary = "Tìm kiếm Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ phân trang")
  public R<PagedData<BudgetReportData>> findAll(BudgetReportSearchCriteria criteria) {
    return success(budgetReportBusiness.findAll(criteria));
  }

  @SaCheckPermission(
      value = {
        "BudgetReportList",
        "BudgetReportOptions",
        "BudgetReportView",
        "BudgetReportDetail",
        "BudgetAllocationList",
        "BudgetDashboardQuery"
      },
      mode = SaMode.OR)
  @GetMapping("/options")
  @Operation(summary = "Lấy danh sách rút gọn (Lookup Options) Báo cáo Kinh phí Định kỳ")
  public R<PagedData<RefData<Long>>> getOptions(BudgetReportSearchCriteria criteria) {
    return success(budgetReportBusiness.getOptions(criteria));
  }

  @SaCheckPermission("BudgetReportDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  public R<BudgetReportData> findById(@PathVariable Long id) {
    return success(budgetReportBusiness.findById(id));
  }

  @SaCheckPermission("BudgetReportClone")
  @PostMapping("/{id}/clone")
  @Operation(summary = "Sao chép Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  @Log(title = "[BudgetReport] Clone", businessType = BusinessType.INSERT)
  public R<BudgetReportData> cloneById(@PathVariable Long id) {
    return success(budgetReportBusiness.cloneById(id));
  }

  @SaCheckPermission("BudgetReportRollback")
  @PostMapping("/{id}/rollback")
  @Operation(summary = "Khôi phục dữ liệu Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  @Log(title = "[BudgetReport] Rollback", businessType = BusinessType.UPDATE)
  public R<BudgetReportData> rollback(@PathVariable Long id, @RequestParam Long version) {
    return success(budgetReportBusiness.rollback(id, version));
  }

  @SaCheckPermission("BudgetReportSort")
  @PutMapping("/sort-order")
  @Operation(summary = "Cập nhật thứ tự hàng loạt")
  @Log(title = "[BudgetReport] Batch Update Sort Order", businessType = BusinessType.UPDATE)
  public R<Void> bulkUpdateSortOrder(@Valid @RequestBody BulkSortOrderData data) {
    budgetReportBusiness.bulkUpdateSortOrder(data);
    return success();
  }

  @RateLimiter(time = 60, count = 20, limitType = LimitType.IP)
  @SaCheckPermission("BudgetReportExport")
  @GetMapping("/export")
  @Operation(summary = "Xuất file Excel Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  @Log(
      title = "[BudgetReport] Export Excel",
      businessType = BusinessType.EXPORT,
      isSaveRequestData = false)
  public void export(BudgetReportSearchCriteria criteria, HttpServletResponse response) {
    budgetReportExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetReportImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(
      summary = "Tải file mẫu Import Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  @Log(
      title = "[BudgetReport] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate(HttpServletResponse response) {
    budgetReportExcelService.importTemplate(response);
  }

  @RateLimiter(time = 60, count = 15, limitType = LimitType.IP)
  @SaCheckPermission("BudgetReportImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    return success(
        BudgetExcelControllerHelper.validateImport(
            file, budgetReportExcelService, budgetReportMapper));
  }

  @RateLimiter(time = 60, count = 15, limitType = LimitType.IP)
  @SaCheckPermission("BudgetReportImport")
  @PostMapping("/import")
  @Operation(
      summary = "Import dữ liệu Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ từ JSON")
  @Log(title = "[BudgetReport] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(
      @Valid @RequestBody Map<String, List<BudgetReportData>> body) {
    return success(
        BudgetExcelControllerHelper.importData(
            body.get("data"),
            BudgetReportData::getId,
            id -> budgetReportBusiness.getInjectedRepo().existsById(id),
            budgetReportBusiness::create,
            budgetReportBusiness::update));
  }

  @RateLimiter(time = 60, count = 20, limitType = LimitType.IP)
  @SaCheckPermission("BudgetReportConsolidate")
  @PostMapping("/generate-consolidated")
  @Operation(
      summary =
          "Tổng hợp tự động Báo cáo Thực hiện Thu - Chi & Quyết toán định kỳ từ Sổ cái Phân bổ & Giải ngân")
  @Log(title = "[BudgetReport] Generate Consolidated", businessType = BusinessType.INSERT)
  public R<BudgetReportData> generateConsolidatedReport(
      @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetReportBusiness.generateConsolidatedReport(body));
  }

  @SaCheckPermission("BudgetReportSubmit")
  @PostMapping("/{id}/submit")
  @Operation(summary = "Trình duyệt Báo cáo Thực hiện Thu - Chi & Quyết toán định kỳ")
  @Log(title = "[BudgetReport] Submit", businessType = BusinessType.UPDATE)
  public R<BudgetReportData> submitReport(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetReportBusiness.submitReport(id, body));
  }

  @SaCheckPermission("BudgetReportReconcile")
  @PostMapping("/{id}/treasury-reconciliation")
  @Operation(summary = "Lưu kết quả xác nhận đối chiếu số dư Kho bạc Nhà nước cho báo cáo kỳ")
  @Log(title = "[BudgetReport] Confirm KBNN Reconciliation", businessType = BusinessType.UPDATE)
  public R<BudgetReportData> confirmTreasuryReconciliation(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetReportBusiness.confirmTreasuryReconciliation(id, body));
  }

  @SaCheckPermission("BudgetReportApprove")
  @PostMapping("/{id}/approve")
  @Operation(summary = "Phê duyệt Báo cáo Thực hiện Thu - Chi & Quyết toán định kỳ")
  @Log(title = "[BudgetReport] Approve", businessType = BusinessType.UPDATE)
  public R<BudgetReportData> approveReport(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetReportBusiness.approveReport(id, body));
  }

  @SaCheckPermission("BudgetReportReject")
  @PostMapping("/{id}/reject")
  @Operation(summary = "Từ chối / Trả lại Báo cáo Thực hiện Thu - Chi & Quyết toán định kỳ")
  @Log(title = "[BudgetReport] Reject", businessType = BusinessType.UPDATE)
  public R<BudgetReportData> rejectReport(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetReportBusiness.rejectReport(id, body));
  }

  @SaCheckPermission("BudgetReportList")
  @GetMapping("/kbnn-reconciliation")
  @Operation(
      summary =
          "Kết xuất Biểu mẫu Đối chiếu Dự toán & Tình hình Sử dụng Kinh phí tại Kho bạc Nhà nước (Mẫu 01a/SDKP-ĐVDT & 02/SDKP-ĐVDT)")
  public R<Map<String, Object>> getKbnnReconciliationStatement(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear,
      @RequestParam(value = "quarter", required = false) Integer quarter,
      @RequestParam(value = "nodeId", required = false) Long nodeId) {
    return success(
        budgetReportBusiness.generateKbnnReconciliationStatement(budgetYear, quarter, nodeId));
  }

  @SaCheckPermission("BudgetReportList")
  @GetMapping("/dynamic-domain-matrix")
  @Operation(
      summary =
          "Kết xuất Ma trận Động Đa chiều theo Lĩnh vực / Nhóm lĩnh vực / Loại-Khoản tính đến ngày chốt (asOfDate)")
  public R<Map<String, Object>> getDynamicDomainMatrix(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear,
      @RequestParam(value = "asOfDate", required = false) String asOfDate,
      @RequestParam(value = "groupByDimension", required = false) String groupByDimension,
      @RequestParam(value = "viewMode", required = false) String viewMode,
      @RequestParam(value = "nodeId", required = false) Long nodeId) {
    return success(
        budgetReportBusiness.generateDynamicDomainMatrix(
            budgetYear, asOfDate, groupByDimension, viewMode, nodeId));
  }
}
