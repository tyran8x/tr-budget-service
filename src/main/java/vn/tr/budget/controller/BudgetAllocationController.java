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
import vn.tr.budget.data.criteria.BudgetAllocationSearchCriteria;
import vn.tr.budget.data.dto.BudgetAllocationData;
import vn.tr.budget.data.mapper.BudgetAllocationMapper;
import vn.tr.budget.excel.BudgetAllocationExcelService;
import vn.tr.budget.excel.BudgetExcelControllerHelper;
import vn.tr.common.core.domain.R;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.domain.data.RefData;
import vn.tr.common.feign.budget.data.BudgetPlanSyncResultData;
import vn.tr.common.log.annotation.Log;
import vn.tr.common.log.enums.BusinessType;
import vn.tr.common.web.core.BaseController;
import vn.tr.common.web.data.dto.BulkOperationResult;
import vn.tr.common.web.data.dto.BulkSortOrderData;
import vn.tr.common.web.data.dto.DeleteData;

/**
 * Controller cho BudgetAllocation.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/allocations")
@RequiredArgsConstructor
@Tag(
    name = "Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ",
    description =
        "API quản lý Lập, Thẩm định, Giao & Phân bổ Dự toán Kinh phí theo Năm/Quý/Tháng, Đơn vị và Cây Thu-Chi")
public class BudgetAllocationController extends BaseController {

  private final BudgetAllocationBusiness budgetAllocationBusiness;
  private final BudgetBatchEntryBusiness budgetBatchEntryBusiness;
  private final BudgetAllocationExcelService budgetAllocationExcelService;
  private final BudgetAllocationMapper budgetAllocationMapper;

  @SaCheckPermission("BudgetAllocationCreate")
  @PostMapping
  @Operation(summary = "Tạo mới Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ")
  @Log(title = "[BudgetAllocation] Create", businessType = BusinessType.INSERT)
  public R<BudgetAllocationData> create(@Valid @RequestBody BudgetAllocationData data) {
    return success(budgetAllocationBusiness.create(data));
  }

  @SaCheckPermission("BudgetAllocationEdit")
  @PutMapping("/{id}")
  @Operation(summary = "Cập nhật Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ")
  @Log(title = "[BudgetAllocation] Update", businessType = BusinessType.UPDATE)
  public R<BudgetAllocationData> update(
      @PathVariable Long id, @Valid @RequestBody BudgetAllocationData data) {
    return success(budgetAllocationBusiness.update(id, data));
  }

  @SaCheckPermission("BudgetAllocationDelete")
  @DeleteMapping("/{id}")
  @Operation(summary = "Xoá Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ")
  @Log(title = "[BudgetAllocation] Delete", businessType = BusinessType.DELETE)
  public R<Void> delete(@PathVariable Long id) {
    budgetAllocationBusiness.delete(id);
    return success();
  }

  @SaCheckPermission("BudgetAllocationBatchDelete")
  @DeleteMapping
  @Operation(summary = "Xoá hàng loạt Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ")
  @Log(title = "[BudgetAllocation] Bulk Delete", businessType = BusinessType.DELETE)
  public R<BulkOperationResult<Long>> bulkDelete(@Valid @RequestBody DeleteData deleteData) {
    return success(budgetAllocationBusiness.bulkDelete(deleteData.getIds()));
  }

  @SaCheckPermission("BudgetAllocationList")
  @GetMapping
  @Operation(summary = "Tìm kiếm Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ phân trang")
  public R<PagedData<BudgetAllocationData>> findAll(BudgetAllocationSearchCriteria criteria) {
    return success(budgetAllocationBusiness.findAll(criteria));
  }

  @SaCheckPermission(
      value = {
        "BudgetAllocationList",
        "BudgetAllocationOptions",
        "BudgetAllocationView",
        "BudgetAllocationDetail",
        "BudgetDisbursementList",
        "BudgetDisbursementCreate",
        "BudgetDisbursementEdit",
        "BudgetTransactionList",
        "BudgetTransactionSubmit",
        "BudgetTransactionCreate",
        "BudgetTransactionEdit",
        "BudgetReportList",
        "BudgetReportCreate",
        "BudgetReportEdit",
        "BudgetReportConsolidate",
        "BudgetDashboardQuery"
      },
      mode = SaMode.OR)
  @GetMapping("/options")
  @Operation(summary = "Lấy danh sách rút gọn (Lookup Options) Phương án Phân bổ Kinh phí")
  public R<PagedData<RefData<Long>>> getOptions(BudgetAllocationSearchCriteria criteria) {
    return success(budgetAllocationBusiness.getOptions(criteria));
  }

  @SaCheckPermission("BudgetAllocationDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ")
  public R<BudgetAllocationData> findById(@PathVariable Long id) {
    return success(budgetAllocationBusiness.findById(id));
  }

  @SaCheckPermission("BudgetAllocationClone")
  @PostMapping("/{id}/clone")
  @Operation(summary = "Sao chép Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ")
  @Log(title = "[BudgetAllocation] Clone", businessType = BusinessType.INSERT)
  public R<BudgetAllocationData> cloneById(@PathVariable Long id) {
    return success(budgetAllocationBusiness.cloneById(id));
  }

  @SaCheckPermission("BudgetAllocationRollback")
  @PostMapping("/{id}/rollback")
  @Operation(summary = "Khôi phục dữ liệu Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ")
  @Log(title = "[BudgetAllocation] Rollback", businessType = BusinessType.UPDATE)
  public R<BudgetAllocationData> rollback(@PathVariable Long id, @RequestParam Long version) {
    return success(budgetAllocationBusiness.rollback(id, version));
  }

  @SaCheckPermission("BudgetAllocationSort")
  @PutMapping("/sort-order")
  @Operation(summary = "Cập nhật thứ tự hàng loạt")
  @Log(title = "[BudgetAllocation] Batch Update Sort Order", businessType = BusinessType.UPDATE)
  public R<Void> bulkUpdateSortOrder(@RequestBody BulkSortOrderData data) {
    budgetAllocationBusiness.bulkUpdateSortOrder(data);
    return success();
  }

  @SaCheckPermission("BudgetAllocationExport")
  @GetMapping("/export")
  @Operation(summary = "Xuất file Excel Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ")
  @Log(
      title = "[BudgetAllocation] Export Excel",
      businessType = BusinessType.EXPORT,
      isSaveRequestData = false)
  public void export(BudgetAllocationSearchCriteria criteria, HttpServletResponse response) {
    budgetAllocationExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetAllocationImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(summary = "Tải file mẫu Import Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ")
  @Log(
      title = "[BudgetAllocation] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate(HttpServletResponse response) {
    budgetAllocationExcelService.importTemplate(response);
  }

  @SaCheckPermission("BudgetAllocationImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    return success(
        BudgetExcelControllerHelper.validateImport(
            file, budgetAllocationExcelService, budgetAllocationMapper));
  }

  @SaCheckPermission("BudgetAllocationImport")
  @PostMapping("/import")
  @Operation(summary = "Import dữ liệu Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ từ JSON")
  @Log(title = "[BudgetAllocation] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(
      @RequestBody Map<String, List<BudgetAllocationData>> body) {
    return success(
        BudgetExcelControllerHelper.importData(
            body.get("data"),
            BudgetAllocationData::getId,
            id -> budgetAllocationBusiness.getInjectedRepo().existsById(id),
            budgetAllocationBusiness::create,
            budgetAllocationBusiness::update));
  }

  @SaCheckPermission("BudgetAllocationSubmit")
  @PostMapping("/{id}/submit")
  @Operation(summary = "Trình thẩm định / phê duyệt Phương án Phân bổ Dự toán Kinh phí")
  @Log(title = "[BudgetAllocation] Submit", businessType = BusinessType.UPDATE)
  public R<BudgetAllocationData> submitAllocation(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetAllocationBusiness.submitAllocation(id, body));
  }

  @SaCheckPermission("BudgetAllocationSelfApprove")
  @PostMapping("/{id}/self-approve")
  @Operation(summary = "Đơn vị tự phê duyệt phân bổ chi tiết trong phạm vi tự chủ tài chính")
  @Log(title = "[BudgetAllocation] Self Approve", businessType = BusinessType.UPDATE)
  public R<BudgetAllocationData> selfApproveAllocation(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetAllocationBusiness.selfApproveAllocation(id, body));
  }

  @SaCheckPermission("BudgetAllocationApprove")
  @PostMapping("/{id}/approve")
  @Operation(summary = "Phê duyệt & Ban hành Quyết định Giao Dự toán Kinh phí")
  @Log(title = "[BudgetAllocation] Approve", businessType = BusinessType.UPDATE)
  public R<BudgetAllocationData> approveAllocation(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetAllocationBusiness.approveAllocation(id, body));
  }

  @SaCheckPermission("BudgetAllocationReject")
  @PostMapping("/{id}/reject")
  @Operation(summary = "Trả lại yêu cầu rà soát / bổ sung thuyết minh Dự toán Kinh phí")
  @Log(title = "[BudgetAllocation] Reject", businessType = BusinessType.UPDATE)
  public R<BudgetAllocationData> rejectAllocation(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetAllocationBusiness.rejectAllocation(id, body));
  }

  @SaCheckPermission("BudgetAllocationSubmit")
  @PostMapping("/{id}/withdraw")
  @Operation(summary = "Thu hồi / Rút tờ trình Phân bổ Dự toán về Dự thảo (DRAFT)")
  @Log(title = "[BudgetAllocation] Withdraw", businessType = BusinessType.UPDATE)
  public R<BudgetAllocationData> withdrawAllocation(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetAllocationBusiness.withdrawAllocation(id, body));
  }

  @SaCheckPermission("BudgetAllocationApprove")
  @PostMapping({"/{id}/recalculate", "/{id}/recalculate-balance"})
  @Operation(summary = "Tính toán lại cân đối Sổ cái Kinh phí, số dư khả dụng và tỷ lệ giải ngân")
  public R<BudgetAllocationData> recalculate(@PathVariable("id") Long id) {
    return success(budgetAllocationBusiness.recalculate(id));
  }

  @SaCheckPermission("BudgetAllocationDetail")
  @GetMapping("/{id}/workflow-timeline")
  @Operation(summary = "Lấy tiến trình các bước phê duyệt Workflow của Phương án Phân bổ Kinh phí")
  public R<Map<String, Object>> getWorkflowTimeline(@PathVariable("id") Long id) {
    return success(budgetAllocationBusiness.getWorkflowTimeline(id));
  }

  @SaCheckPermission("BudgetAllocationList")
  @GetMapping("/by-plan/{planCode}")
  @Operation(summary = "Tra cứu bức tranh cân đối Kinh phí theo Mã Kế hoạch công tác")
  public R<BudgetPlanSyncResultData> getBudgetSnapshotByPlanCode(
      @PathVariable("planCode") String planCode) {
    return success(budgetAllocationBusiness.getBudgetSnapshotByPlanCode(planCode));
  }

  @SaCheckPermission("BudgetAllocationApprove")
  @PostMapping("/{id}/sync-to-plan")
  @Operation(summary = "Đồng bộ chủ động số liệu Dự toán & Giải ngân sang Kế hoạch công tác liên kết")
  @Log(title = "[BudgetAllocation] Sync To Plan", businessType = BusinessType.UPDATE)
  public R<Map<String, Object>> syncToLinkedPlan(@PathVariable("id") Long id) {
    return success(budgetAllocationBusiness.syncAllocationToPlanById(id));
  }

  @SaCheckPermission("BudgetAllocationCreate")
  @PostMapping("/batch")
  @Operation(summary = "Nhập hàng loạt Phương án Phân bổ Dự toán từ lưới Spreadsheet / Paste Excel")
  @Log(title = "[BudgetAllocation] Batch Create", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> batchCreate(@RequestBody List<BudgetAllocationData> items) {
    return success(budgetBatchEntryBusiness.batchSaveAllocations(items));
  }

  @SaCheckPermission("BudgetAllocationList")
  @GetMapping("/tree-matrix")
  @Operation(summary = "Lấy Ma trận Giao Dự toán Đầu năm (Cây Chỉ tiêu x Đơn vị SDNS)")
  public R<Map<String, Object>> getAllocationTreeMatrix(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear,
      @RequestParam(value = "periodType", required = false) String periodType) {
    return success(budgetBatchEntryBusiness.getAllocationTreeMatrix(budgetYear, periodType));
  }

  @SaCheckPermission(
      value = {"BudgetAllocationCreate", "BudgetAllocationEdit"},
      mode = SaMode.OR)
  @PostMapping("/tree-matrix")
  @Operation(summary = "Lưu hàng loạt Ma trận Giao Dự toán (Cây Chỉ tiêu x Đơn vị SDNS)")
  @Log(title = "[BudgetAllocation] Save Tree Matrix", businessType = BusinessType.UPDATE)
  public R<Map<String, Object>> saveAllocationTreeMatrix(@RequestBody Map<String, Object> body) {
    return success(budgetBatchEntryBusiness.saveAllocationTreeMatrix(body));
  }
}
