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
import vn.tr.budget.business.BudgetAllocationBusiness;
import vn.tr.budget.business.BudgetBatchEntryBusiness;
import vn.tr.budget.business.BudgetDisbursementBusiness;
import vn.tr.budget.data.criteria.BudgetDisbursementSearchCriteria;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.excel.BudgetDisbursementExcelService;
import vn.tr.budget.excel.BudgetExcelControllerHelper;
import vn.tr.common.core.domain.R;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.domain.data.RefData;
import vn.tr.common.log.annotation.Log;
import vn.tr.common.log.enums.BusinessType;
import vn.tr.common.web.core.BaseController;
import vn.tr.common.web.data.dto.BulkOperationResult;
import vn.tr.common.web.data.dto.BulkSortOrderData;
import vn.tr.common.web.data.dto.DeleteData;

/**
 * Controller cho BudgetDisbursement.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/disbursements")
@RequiredArgsConstructor
@Tag(
    name = "Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán",
    description =
        "API quản lý Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc Nhà nước, Hoàn ứng & Quyết toán chứng từ")
public class BudgetDisbursementController extends BaseController {

  private final BudgetDisbursementBusiness budgetDisbursementBusiness;
  private final BudgetAllocationBusiness budgetAllocationBusiness;
  private final BudgetBatchEntryBusiness budgetBatchEntryBusiness;
  private final BudgetDisbursementExcelService budgetDisbursementExcelService;
  private final BudgetDisbursementMapper budgetDisbursementMapper;

  @SaCheckPermission("BudgetDisbursementCreate")
  @PostMapping
  @Operation(summary = "Tạo mới Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán")
  @Log(title = "[BudgetDisbursement] Create", businessType = BusinessType.INSERT)
  public R<BudgetDisbursementData> create(@Valid @RequestBody BudgetDisbursementData data) {
    return success(budgetDisbursementBusiness.create(data));
  }

  @SaCheckPermission("BudgetDisbursementEdit")
  @PutMapping("/{id}")
  @Operation(summary = "Cập nhật Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán")
  @Log(title = "[BudgetDisbursement] Update", businessType = BusinessType.UPDATE)
  public R<BudgetDisbursementData> update(
      @PathVariable Long id, @Valid @RequestBody BudgetDisbursementData data) {
    return success(budgetDisbursementBusiness.update(id, data));
  }

  @SaCheckPermission("BudgetDisbursementDelete")
  @DeleteMapping("/{id}")
  @Operation(summary = "Xoá Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán")
  @Log(title = "[BudgetDisbursement] Delete", businessType = BusinessType.DELETE)
  public R<Void> delete(@PathVariable Long id) {
    budgetDisbursementBusiness.delete(id);
    return success();
  }

  @SaCheckPermission("BudgetDisbursementBatchDelete")
  @DeleteMapping
  @Operation(summary = "Xoá hàng loạt Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán")
  @Log(title = "[BudgetDisbursement] Bulk Delete", businessType = BusinessType.DELETE)
  public R<BulkOperationResult<Long>> bulkDelete(@Valid @RequestBody DeleteData deleteData) {
    return success(budgetDisbursementBusiness.bulkDelete(deleteData.getIds()));
  }

  @SaCheckPermission("BudgetDisbursementList")
  @GetMapping
  @Operation(summary = "Tìm kiếm Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán phân trang")
  public R<PagedData<BudgetDisbursementData>> findAll(BudgetDisbursementSearchCriteria criteria) {
    return success(budgetDisbursementBusiness.findAll(criteria));
  }

  @SaCheckPermission(
      value = {"BudgetDisbursementList", "BudgetReportList"},
      mode = SaMode.OR)
  @GetMapping("/options")
  @Operation(summary = "Lấy danh sách rút gọn (Lookup Options) Hồ sơ Giải ngân Kho bạc")
  public R<PagedData<RefData<Long>>> getOptions(BudgetDisbursementSearchCriteria criteria) {
    return success(budgetDisbursementBusiness.getOptions(criteria));
  }

  @SaCheckPermission("BudgetDisbursementDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán")
  public R<BudgetDisbursementData> findById(@PathVariable Long id) {
    return success(budgetDisbursementBusiness.findById(id));
  }

  @SaCheckPermission("BudgetDisbursementClone")
  @PostMapping("/{id}/clone")
  @Operation(summary = "Sao chép Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán")
  @Log(title = "[BudgetDisbursement] Clone", businessType = BusinessType.INSERT)
  public R<BudgetDisbursementData> cloneById(@PathVariable Long id) {
    return success(budgetDisbursementBusiness.cloneById(id));
  }

  @SaCheckPermission("BudgetDisbursementRollback")
  @PostMapping("/{id}/rollback")
  @Operation(summary = "Khôi phục dữ liệu Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán")
  @Log(title = "[BudgetDisbursement] Rollback", businessType = BusinessType.UPDATE)
  public R<BudgetDisbursementData> rollback(@PathVariable Long id, @RequestParam Long version) {
    return success(budgetDisbursementBusiness.rollback(id, version));
  }

  @SaCheckPermission("BudgetDisbursementSort")
  @PutMapping("/sort-order")
  @Operation(summary = "Cập nhật thứ tự hàng loạt")
  @Log(title = "[BudgetDisbursement] Batch Update Sort Order", businessType = BusinessType.UPDATE)
  public R<Void> bulkUpdateSortOrder(@RequestBody BulkSortOrderData data) {
    budgetDisbursementBusiness.bulkUpdateSortOrder(data);
    return success();
  }

  @SaCheckPermission("BudgetDisbursementExport")
  @GetMapping("/export")
  @Operation(summary = "Xuất file Excel Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán")
  @Log(
      title = "[BudgetDisbursement] Export Excel",
      businessType = BusinessType.EXPORT,
      isSaveRequestData = false)
  public void export(BudgetDisbursementSearchCriteria criteria, HttpServletResponse response) {
    budgetDisbursementExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetDisbursementImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(
      summary = "Tải file mẫu Import Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán")
  @Log(
      title = "[BudgetDisbursement] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate(HttpServletResponse response) {
    budgetDisbursementExcelService.importTemplate(response);
  }

  @SaCheckPermission("BudgetDisbursementImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    return success(
        BudgetExcelControllerHelper.validateImport(
            file, budgetDisbursementExcelService, budgetDisbursementMapper));
  }

  @SaCheckPermission("BudgetDisbursementImport")
  @PostMapping("/import")
  @Operation(
      summary = "Import dữ liệu Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán từ JSON")
  @Log(title = "[BudgetDisbursement] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(
      @RequestBody Map<String, List<BudgetDisbursementData>> body) {
    return success(
        BudgetExcelControllerHelper.importData(
            body.get("data"),
            BudgetDisbursementData::getId,
            id -> budgetDisbursementBusiness.getInjectedRepo().existsById(id),
            budgetDisbursementBusiness::create,
            budgetDisbursementBusiness::update));
  }

  @SaCheckPermission("BudgetDisbursementApprove")
  @PostMapping("/{id}/approve")
  @Operation(summary = "Phê duyệt Hồ sơ Giải ngân Kho bạc Nhà nước và tự động trừ dự toán khả dụng")
  @Log(title = "[BudgetDisbursement] Approve", businessType = BusinessType.UPDATE)
  public R<BudgetDisbursementData> approveDisbursement(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetDisbursementBusiness.approveDisbursement(id, body));
  }

  @SaCheckPermission("BudgetDisbursementSettle")
  @PostMapping("/{id}/settle")
  @Operation(summary = "Nghiệm thu Quyết toán / Hoàn ứng chứng từ Kho bạc")
  @Log(title = "[BudgetDisbursement] Settle", businessType = BusinessType.UPDATE)
  public R<BudgetDisbursementData> settleDisbursement(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetDisbursementBusiness.settleDisbursement(id, body));
  }

  @SaCheckPermission("BudgetDisbursementReject")
  @PostMapping("/{id}/reject")
  @Operation(summary = "Từ chối hồ sơ đề nghị giải ngân")
  @Log(title = "[BudgetDisbursement] Reject", businessType = BusinessType.UPDATE)
  public R<BudgetDisbursementData> rejectDisbursement(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetDisbursementBusiness.rejectDisbursement(id, body));
  }

  @SaCheckPermission("BudgetDisbursementCreate")
  @PostMapping("/reserve-commitment")
  @Operation(summary = "Khởi tạo nhanh hồ sơ Cam kết chi (COMMITMENT) liên thông")
  @Log(title = "[BudgetDisbursement] Reserve Commitment", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> reserveCommitment(@RequestBody Map<String, Object> body) {
    return success(budgetDisbursementBusiness.reserveCommitment(body));
  }

  @SaCheckPermission("BudgetDisbursementApprove")
  @PostMapping("/scan-sla-alerts")
  @Operation(summary = "Quét cảnh báo SLA hồ sơ Tạm ứng / Giải ngân sắp quá hạn quyết toán Kho bạc")
  @Log(title = "[BudgetDisbursement] Scan SLA Alerts", businessType = BusinessType.OTHER)
  public R<Map<String, Object>> scanSlaAlerts() {
    return success(budgetAllocationBusiness.scanAndDispatchSlaAlerts());
  }

  @SaCheckPermission("BudgetDisbursementCreate")
  @PostMapping("/batch")
  @Operation(summary = "Nhập hàng loạt Hồ sơ Giải ngân Kho bạc từ lưới Spreadsheet / Paste Excel")
  @Log(title = "[BudgetDisbursement] Batch Create", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> batchCreate(@RequestBody List<BudgetDisbursementData> items) {
    return success(budgetBatchEntryBusiness.batchSaveDisbursements(items));
  }

  @SaCheckPermission(
      value = {"BudgetDisbursementDetail", "BudgetDisbursementApprove"},
      mode = SaMode.OR)
  @GetMapping("/{id}/kbnn-dvc-package")
  @Operation(
      summary =
          "Kết xuất gói hồ sơ giao dịch điện tử Dịch vụ công Kho bạc Nhà nước (DVC KBNN / TABMIS) theo Mẫu C2-02a/NS")
  public R<Map<String, Object>> exportKbnnDvcPackage(@PathVariable("id") Long id) {
    return success(budgetDisbursementBusiness.exportKbnnDvcPackage(id));
  }
}
