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
import vn.tr.budget.business.BudgetDisbursementBusiness;
import vn.tr.budget.data.criteria.BudgetDisbursementSearchCriteria;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.dto.BudgetDisbursementImportData;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.excel.BudgetDisbursementExcelService;
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
 * Controller cho BudgetDisbursement.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/disbursements")
@RequiredArgsConstructor
@Tag(name = "Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán", description = "API quản lý Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc Nhà nước, Hoàn ứng & Quyết toán chứng từ")
public class BudgetDisbursementController extends BaseController {

  private final BudgetDisbursementBusiness budgetDisbursementBusiness;
  private final BudgetDisbursementExcelService budgetDisbursementExcelService;
  private final BudgetDisbursementMapper budgetDisbursementMapper;
  private final HttpServletResponse response;

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
  public R<BudgetDisbursementData> update(@PathVariable Long id, @Valid @RequestBody BudgetDisbursementData data) {
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

  @SaCheckPermission("BudgetDisbursementDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán")
  public R<BudgetDisbursementData> findById(@PathVariable Long id) {
    return success(budgetDisbursementBusiness.findById(id));
  }

  @SaCheckPermission("BudgetDisbursementClone")
  @GetMapping("/{id}/clone")
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
  public void export(BudgetDisbursementSearchCriteria criteria) {
    budgetDisbursementExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetDisbursementImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(summary = "Tải file mẫu Import Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán")
  @Log(
      title = "[BudgetDisbursement] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate() {
    budgetDisbursementExcelService.importTemplate(response);
  }

  @SaCheckPermission("BudgetDisbursementImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    try (InputStream is = file.getInputStream()) {
      ImportBatchResult<BudgetDisbursementImportData> batchResult =
          budgetDisbursementExcelService.validateExcel(is, file.getOriginalFilename(), true);

      List<BudgetDisbursementData> dataList = new ArrayList<>();
      List<Map<String, Object>> errorsList = new ArrayList<>();

      for (ImportRowResult<BudgetDisbursementImportData> rowResult : batchResult.getRowResults()) {
        BudgetDisbursementData data = budgetDisbursementMapper.toImportData(rowResult.getOriginalData());
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

  @SaCheckPermission("BudgetDisbursementImport")
  @PostMapping("/import")
  @Operation(summary = "Import dữ liệu Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán từ JSON")
  @Log(title = "[BudgetDisbursement] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(@RequestBody Map<String, List<BudgetDisbursementData>> body) {
    List<BudgetDisbursementData> dataList = body.get("data");
    if (dataList == null || dataList.isEmpty()) {
      throw new BaseException("Dữ liệu import trống");
    }

    int created = 0;
    int updated = 0;
    List<Map<String, Object>> errors = new ArrayList<>();

    for (int i = 0; i < dataList.size(); i++) {
      BudgetDisbursementData data = dataList.get(i);
      try {
        if (data.getId() != null
            && data.getId() > 0
            && budgetDisbursementBusiness.getInjectedRepo().existsById(data.getId())) {
          budgetDisbursementBusiness.update(data.getId(), data);
          updated++;
        } else {
          budgetDisbursementBusiness.create(data);
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

  @SaCheckPermission("BudgetDisbursementEdit")
  @PostMapping("/{id}/approve")
  @Operation(summary = "Phê duyệt Hồ sơ Giải ngân Kho bạc Nhà nước và tự động trừ dự toán khả dụng")
  public R<BudgetDisbursementData> approveDisbursement(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetDisbursementBusiness.approveDisbursement(id, body));
  }

  @SaCheckPermission("BudgetDisbursementEdit")
  @PostMapping("/{id}/settle")
  @Operation(summary = "Nghiệm thu Quyết toán / Hoàn ứng chứng từ Kho bạc")
  public R<BudgetDisbursementData> settleDisbursement(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetDisbursementBusiness.settleDisbursement(id, body));
  }

  @SaCheckPermission("BudgetDisbursementEdit")
  @PostMapping("/{id}/reject")
  @Operation(summary = "Từ chối hồ sơ đề nghị giải ngân")
  public R<BudgetDisbursementData> rejectDisbursement(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetDisbursementBusiness.rejectDisbursement(id, body));
  }

  @SaCheckPermission("BudgetDisbursementCreate")
  @PostMapping("/reserve-commitment")
  @Operation(summary = "Khởi tạo nhanh hồ sơ Cam kết chi (COMMITMENT) liên thông từ Nhiệm vụ Kế hoạch / Công việc")
  public R<Map<String, Object>> reserveCommitment(@RequestBody Map<String, Object> body) {
    return success(budgetDisbursementBusiness.reserveCommitment(body));
  }

  @SaCheckPermission("BudgetDisbursementEdit")
  @PostMapping("/{id}/payment-webhook-settle")
  @Operation(
      summary =
          "Tự động đối soát và nghiệm thu quyết toán hồ sơ giải ngân từ Webhook Thanh toán (VietQR / SePay / KBNN)")
  public R<Map<String, Object>> settleFromPaymentWebhook(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetDisbursementBusiness.settleFromPaymentWebhook(id, body));
  }

}
