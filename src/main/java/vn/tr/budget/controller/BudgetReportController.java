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
import vn.tr.budget.business.BudgetReportBusiness;
import vn.tr.budget.data.criteria.BudgetReportSearchCriteria;
import vn.tr.budget.data.dto.BudgetReportData;
import vn.tr.budget.data.dto.BudgetReportImportData;
import vn.tr.budget.data.mapper.BudgetReportMapper;
import vn.tr.budget.excel.BudgetReportExcelService;
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
 * Controller cho BudgetReport.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
@Tag(name = "Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ", description = "API quản lý Báo cáo Thực hiện Thu - Chi, Giải ngân & Tổng hợp Quyết toán định kỳ Tháng/Quý/6 tháng/Năm (kế thừa & nâng cấp từ QlkpBaoCaoThuChi & QlkpTongHopThuChi)")
public class BudgetReportController extends BaseController {

  private final BudgetReportBusiness budgetReportBusiness;
  private final BudgetReportExcelService budgetReportExcelService;
  private final BudgetReportMapper budgetReportMapper;
  private final HttpServletResponse response;

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
  public R<BudgetReportData> update(@PathVariable Long id, @Valid @RequestBody BudgetReportData data) {
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

  @SaCheckPermission("BudgetReportDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  public R<BudgetReportData> findById(@PathVariable Long id) {
    return success(budgetReportBusiness.findById(id));
  }

  @SaCheckPermission("BudgetReportClone")
  @GetMapping("/{id}/clone")
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
  public R<Void> bulkUpdateSortOrder(@RequestBody BulkSortOrderData data) {
    budgetReportBusiness.bulkUpdateSortOrder(data);
    return success();
  }

  @SaCheckPermission("BudgetReportExport")
  @GetMapping("/export")
  @Operation(summary = "Xuất file Excel Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  @Log(
      title = "[BudgetReport] Export Excel",
      businessType = BusinessType.EXPORT,
      isSaveRequestData = false)
  public void export(BudgetReportSearchCriteria criteria) {
    budgetReportExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetReportImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(summary = "Tải file mẫu Import Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ")
  @Log(
      title = "[BudgetReport] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate() {
    budgetReportExcelService.importTemplate(response);
  }

  @SaCheckPermission("BudgetReportImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    try (InputStream is = file.getInputStream()) {
      ImportBatchResult<BudgetReportImportData> batchResult =
          budgetReportExcelService.validateExcel(is, file.getOriginalFilename(), true);

      List<BudgetReportData> dataList = new ArrayList<>();
      List<Map<String, Object>> errorsList = new ArrayList<>();

      for (ImportRowResult<BudgetReportImportData> rowResult : batchResult.getRowResults()) {
        BudgetReportData data = budgetReportMapper.toImportData(rowResult.getOriginalData());
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

  @SaCheckPermission("BudgetReportImport")
  @PostMapping("/import")
  @Operation(summary = "Import dữ liệu Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ từ JSON")
  @Log(title = "[BudgetReport] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(@RequestBody Map<String, List<BudgetReportData>> body) {
    List<BudgetReportData> dataList = body.get("data");
    if (dataList == null || dataList.isEmpty()) {
      throw new BaseException("Dữ liệu import trống");
    }

    int created = 0;
    int updated = 0;
    List<Map<String, Object>> errors = new ArrayList<>();

    for (int i = 0; i < dataList.size(); i++) {
      BudgetReportData data = dataList.get(i);
      try {
        if (data.getId() != null
            && data.getId() > 0
            && budgetReportBusiness.getInjectedRepo().existsById(data.getId())) {
          budgetReportBusiness.update(data.getId(), data);
          updated++;
        } else {
          budgetReportBusiness.create(data);
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

  @SaCheckPermission("BudgetReportCreate")
  @PostMapping("/generate-consolidated")
  @Operation(
      summary =
          "Tổng hợp tự động Báo cáo Thực hiện Thu - Chi & Quyết toán định kỳ từ Sổ cái Phân bổ & Giải ngân")
  public R<BudgetReportData> generateConsolidatedReport(
      @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetReportBusiness.generateConsolidatedReport(body));
  }

  @SaCheckPermission("BudgetReportEdit")
  @PostMapping("/{id}/approve")
  @Operation(summary = "Phê duyệt Báo cáo Thực hiện Thu - Chi & Quyết toán định kỳ")
  public R<BudgetReportData> approveReport(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetReportBusiness.approveReport(id, body));
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

}
