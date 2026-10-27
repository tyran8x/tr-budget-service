package vn.tr.budget.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
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
import vn.tr.budget.data.criteria.BudgetAllocationSearchCriteria;
import vn.tr.budget.data.dto.BudgetAllocationData;
import vn.tr.budget.data.dto.BudgetAllocationImportData;
import vn.tr.budget.data.mapper.BudgetAllocationMapper;
import vn.tr.budget.excel.BudgetAllocationExcelService;
import vn.tr.common.core.domain.R;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.exception.base.BaseException;
import vn.tr.common.core.utils.MessageUtils;
import vn.tr.common.excel.core.ImportBatchResult;
import vn.tr.common.excel.core.ImportRowResult;
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
@Tag(name = "Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ", description = "API quản lý Lập, Thẩm định, Giao & Phân bổ Dự toán Kinh phí theo Năm/Quý/Tháng, Đơn vị và Cây Thu-Chi (kế thừa & nâng cấp từ QlkpPhanBoKinhPhi)")
public class BudgetAllocationController extends BaseController {

  private final BudgetAllocationBusiness budgetAllocationBusiness;
  private final BudgetAllocationExcelService budgetAllocationExcelService;
  private final BudgetAllocationMapper budgetAllocationMapper;
  private final HttpServletResponse response;

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
  public R<BudgetAllocationData> update(@PathVariable Long id, @Valid @RequestBody BudgetAllocationData data) {
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

  @SaCheckPermission("BudgetAllocationDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ")
  public R<BudgetAllocationData> findById(@PathVariable Long id) {
    return success(budgetAllocationBusiness.findById(id));
  }

  @SaCheckPermission("BudgetAllocationClone")
  @GetMapping("/{id}/clone")
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
  public void export(BudgetAllocationSearchCriteria criteria) {
    budgetAllocationExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetAllocationImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(summary = "Tải file mẫu Import Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ")
  @Log(
      title = "[BudgetAllocation] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate() {
    budgetAllocationExcelService.importTemplate(response);
  }

  @SaCheckPermission("BudgetAllocationImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    try (InputStream is = file.getInputStream()) {
      ImportBatchResult<BudgetAllocationImportData> batchResult =
          budgetAllocationExcelService.validateExcel(is, file.getOriginalFilename(), true);

      List<BudgetAllocationData> dataList = new ArrayList<>();
      List<Map<String, Object>> errorsList = new ArrayList<>();

      for (ImportRowResult<BudgetAllocationImportData> rowResult : batchResult.getRowResults()) {
        BudgetAllocationData data = budgetAllocationMapper.toImportData(rowResult.getOriginalData());
        dataList.add(data);

        if (rowResult.hasErrors()) {
          for (String err : rowResult.getValidationErrors()) {
            Map<String, Object> errMap = new HashMap<>();
            errMap.put("row", rowResult.getRowNumber());
            errMap.put("field", "");
            errMap.put("message", err);
            errorsList.add(errMap);
          }
        }
      }

      Map<String, Object> summary = new HashMap<>();
      summary.put("total", batchResult.getTotalRows());
      summary.put("valid", batchResult.getTotalRows() - batchResult.getErrorCount());
      summary.put("invalid", batchResult.getErrorCount());

      Map<String, Object> result = new HashMap<>();
      result.put("data", dataList);
      result.put("errors", errorsList);
      result.put("summary", summary);
      result.put("valid", batchResult.getErrorCount() == 0);

      return success(result);
    } catch (IOException e) {
      throw new BaseException(MessageUtils.message("error.excel.read", e.getMessage()));
    }
  }

  @SaCheckPermission("BudgetAllocationImport")
  @PostMapping("/import")
  @Operation(summary = "Import dữ liệu Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ từ JSON")
  @Log(title = "[BudgetAllocation] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(@RequestBody Map<String, List<BudgetAllocationData>> body) {
    List<BudgetAllocationData> dataList = body.get("data");
    if (dataList == null || dataList.isEmpty()) {
      throw new BaseException("Dữ liệu import trống");
    }

    int created = 0;
    int updated = 0;
    List<Map<String, Object>> errors = new ArrayList<>();

    for (int i = 0; i < dataList.size(); i++) {
      BudgetAllocationData data = dataList.get(i);
      try {
        if (data.getId() != null
            && data.getId() > 0
            && budgetAllocationBusiness.getInjectedRepo().existsById(data.getId())) {
          budgetAllocationBusiness.update(data.getId(), data);
          updated++;
        } else {
          budgetAllocationBusiness.create(data);
          created++;
        }
      } catch (Exception e) {
        Map<String, Object> errMap = new HashMap<>();
        errMap.put("row", i + 1);
        errMap.put("message", e.getMessage());
        errors.add(errMap);
      }
    }

    Map<String, Object> result = new HashMap<>();
    result.put("created", created);
    result.put("updated", updated);
    result.put("errors", errors);
    return success(result);
  }

  @SaCheckPermission("BudgetAllocationEdit")
  @PostMapping("/{id}/submit")
  @Operation(summary = "Trình thẩm định / phê duyệt Phương án Phân bổ Dự toán Kinh phí")
  public R<BudgetAllocationData> submitAllocation(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetAllocationBusiness.submitAllocation(id, body));
  }

  @SaCheckPermission("BudgetAllocationEdit")
  @PostMapping("/{id}/self-approve")
  @Operation(summary = "Đơn vị tự phê duyệt phân bổ chi tiết trong phạm vi tự chủ tài chính")
  public R<BudgetAllocationData> selfApproveAllocation(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetAllocationBusiness.selfApproveAllocation(id, body));
  }

  @SaCheckPermission("BudgetAllocationEdit")
  @PostMapping("/{id}/approve")
  @Operation(summary = "Phê duyệt & Ban hành Quyết định Giao Dự toán Kinh phí")
  public R<BudgetAllocationData> approveAllocation(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetAllocationBusiness.approveAllocation(id, body));
  }

  @SaCheckPermission("BudgetAllocationEdit")
  @PostMapping("/{id}/reject")
  @Operation(summary = "Trả lại yêu cầu rà soát / bổ sung thuyết minh Dự toán Kinh phí")
  public R<BudgetAllocationData> rejectAllocation(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetAllocationBusiness.rejectAllocation(id, body));
  }

  @SaCheckPermission("BudgetAllocationEdit")
  @PostMapping("/{id}/withdraw")
  @Operation(summary = "Thu hồi / Rút tờ trình Phân bổ Dự toán về Dự thảo (DRAFT)")
  public R<BudgetAllocationData> withdrawAllocation(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetAllocationBusiness.withdrawAllocation(id, body));
  }

  @SaCheckPermission("BudgetAllocationEdit")
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
  @PostMapping("/scan-sla-alerts")
  @Operation(summary = "Quét cảnh báo SLA hồ sơ Tạm ứng / Giải ngân sắp quá hạn quyết toán Kho bạc")
  public R<Map<String, Object>> scanSlaAlerts() {
    return success(budgetAllocationBusiness.scanAndDispatchSlaAlerts());
  }

}
