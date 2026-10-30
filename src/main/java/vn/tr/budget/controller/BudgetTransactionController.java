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
import vn.tr.budget.business.BudgetTransactionBusiness;
import vn.tr.budget.data.criteria.BudgetTransactionSearchCriteria;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.budget.data.dto.BudgetTransactionImportData;
import vn.tr.budget.data.mapper.BudgetTransactionMapper;
import vn.tr.budget.excel.BudgetTransactionExcelService;
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
 * Controller cho BudgetTransaction.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
@Tag(name = "Sổ cái Biến động Nguồn Kinh phí Bút toán kép", description = "API quản lý Sổ cái Biến động Nguồn Kinh phí: Giao đầu năm, Bổ sung nguồn, Chuyển nguồn liên năm (N -> N+1), Điều chuyển nội bộ và Thu hồi dự toán")
public class BudgetTransactionController extends BaseController {

  private final BudgetTransactionBusiness budgetTransactionBusiness;
  private final BudgetTransactionExcelService budgetTransactionExcelService;
  private final BudgetTransactionMapper budgetTransactionMapper;
  private final HttpServletResponse response;

  @SaCheckPermission("BudgetTransactionCreate")
  @PostMapping
  @Operation(summary = "Tạo mới Sổ cái Biến động Nguồn Kinh phí Bút toán kép")
  @Log(title = "[BudgetTransaction] Create", businessType = BusinessType.INSERT)
  public R<BudgetTransactionData> create(@Valid @RequestBody BudgetTransactionData data) {
    return success(budgetTransactionBusiness.create(data));
  }

  @SaCheckPermission("BudgetTransactionEdit")
  @PutMapping("/{id}")
  @Operation(summary = "Cập nhật Sổ cái Biến động Nguồn Kinh phí Bút toán kép")
  @Log(title = "[BudgetTransaction] Update", businessType = BusinessType.UPDATE)
  public R<BudgetTransactionData> update(@PathVariable Long id, @Valid @RequestBody BudgetTransactionData data) {
    return success(budgetTransactionBusiness.update(id, data));
  }

  @SaCheckPermission("BudgetTransactionDelete")
  @DeleteMapping("/{id}")
  @Operation(summary = "Xoá Sổ cái Biến động Nguồn Kinh phí Bút toán kép")
  @Log(title = "[BudgetTransaction] Delete", businessType = BusinessType.DELETE)
  public R<Void> delete(@PathVariable Long id) {
    budgetTransactionBusiness.delete(id);
    return success();
  }

  @SaCheckPermission("BudgetTransactionBatchDelete")
  @DeleteMapping
  @Operation(summary = "Xoá hàng loạt Sổ cái Biến động Nguồn Kinh phí Bút toán kép")
  @Log(title = "[BudgetTransaction] Bulk Delete", businessType = BusinessType.DELETE)
  public R<BulkOperationResult<Long>> bulkDelete(@Valid @RequestBody DeleteData deleteData) {
    return success(budgetTransactionBusiness.bulkDelete(deleteData.getIds()));
  }

  @SaCheckPermission("BudgetTransactionList")
  @GetMapping
  @Operation(summary = "Tìm kiếm Sổ cái Biến động Nguồn Kinh phí Bút toán kép phân trang")
  public R<PagedData<BudgetTransactionData>> findAll(BudgetTransactionSearchCriteria criteria) {
    return success(budgetTransactionBusiness.findAll(criteria));
  }

  @SaCheckPermission("BudgetTransactionDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Sổ cái Biến động Nguồn Kinh phí Bút toán kép")
  public R<BudgetTransactionData> findById(@PathVariable Long id) {
    return success(budgetTransactionBusiness.findById(id));
  }

  @SaCheckPermission("BudgetTransactionClone")
  @GetMapping("/{id}/clone")
  @Operation(summary = "Sao chép Sổ cái Biến động Nguồn Kinh phí Bút toán kép")
  @Log(title = "[BudgetTransaction] Clone", businessType = BusinessType.INSERT)
  public R<BudgetTransactionData> cloneById(@PathVariable Long id) {
    return success(budgetTransactionBusiness.cloneById(id));
  }

  @SaCheckPermission("BudgetTransactionRollback")
  @PostMapping("/{id}/rollback")
  @Operation(summary = "Khôi phục dữ liệu Sổ cái Biến động Nguồn Kinh phí Bút toán kép")
  @Log(title = "[BudgetTransaction] Rollback", businessType = BusinessType.UPDATE)
  public R<BudgetTransactionData> rollback(@PathVariable Long id, @RequestParam Long version) {
    return success(budgetTransactionBusiness.rollback(id, version));
  }

  @SaCheckPermission("BudgetTransactionSort")
  @PutMapping("/sort-order")
  @Operation(summary = "Cập nhật thứ tự hàng loạt")
  @Log(title = "[BudgetTransaction] Batch Update Sort Order", businessType = BusinessType.UPDATE)
  public R<Void> bulkUpdateSortOrder(@RequestBody BulkSortOrderData data) {
    budgetTransactionBusiness.bulkUpdateSortOrder(data);
    return success();
  }

  @SaCheckPermission("BudgetTransactionExport")
  @GetMapping("/export")
  @Operation(summary = "Xuất file Excel Sổ cái Biến động Nguồn Kinh phí Bút toán kép")
  @Log(
      title = "[BudgetTransaction] Export Excel",
      businessType = BusinessType.EXPORT,
      isSaveRequestData = false)
  public void export(BudgetTransactionSearchCriteria criteria) {
    budgetTransactionExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetTransactionImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(summary = "Tải file mẫu Import Sổ cái Biến động Nguồn Kinh phí Bút toán kép")
  @Log(
      title = "[BudgetTransaction] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate() {
    budgetTransactionExcelService.importTemplate(response);
  }

  @SaCheckPermission("BudgetTransactionImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    try (InputStream is = file.getInputStream()) {
      ImportBatchResult<BudgetTransactionImportData> batchResult =
          budgetTransactionExcelService.validateExcel(is, file.getOriginalFilename(), true);

      List<BudgetTransactionData> dataList = new ArrayList<>();
      List<Map<String, Object>> errorsList = new ArrayList<>();

      for (ImportRowResult<BudgetTransactionImportData> rowResult : batchResult.getRowResults()) {
        BudgetTransactionData data = budgetTransactionMapper.toImportData(rowResult.getOriginalData());
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

  @SaCheckPermission("BudgetTransactionImport")
  @PostMapping("/import")
  @Operation(summary = "Import dữ liệu Sổ cái Biến động Nguồn Kinh phí Bút toán kép từ JSON")
  @Log(title = "[BudgetTransaction] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(@RequestBody Map<String, List<BudgetTransactionData>> body) {
    List<BudgetTransactionData> dataList = body.get("data");
    if (dataList == null || dataList.isEmpty()) {
      throw new BaseException("Dữ liệu import trống");
    }

    int created = 0;
    int updated = 0;
    List<Map<String, Object>> errors = new ArrayList<>();

    for (int i = 0; i < dataList.size(); i++) {
      BudgetTransactionData data = dataList.get(i);
      try {
        if (data.getId() != null
            && data.getId() > 0
            && budgetTransactionBusiness.getInjectedRepo().existsById(data.getId())) {
          budgetTransactionBusiness.update(data.getId(), data);
          updated++;
        } else {
          budgetTransactionBusiness.create(data);
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

  @SaCheckPermission("BudgetTransactionEdit")
  @PostMapping("/{id}/approve")
  @Operation(
      summary =
          "Phê duyệt bút toán Bổ sung nguồn / Chuyển nguồn liên năm (N -> N+1) / Điều chuyển nội bộ và cập nhật Sổ cái Bút toán kép")
  public R<BudgetTransactionData> approveTransaction(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetTransactionBusiness.approveTransaction(id, body));
  }

  @SaCheckPermission("BudgetTransactionEdit")
  @PostMapping("/{id}/reject")
  @Operation(summary = "Từ chối giao dịch biến động nguồn kinh phí")
  public R<BudgetTransactionData> rejectTransaction(
      @PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
    return success(budgetTransactionBusiness.rejectTransaction(id, body));
  }

  @SaCheckPermission("BudgetTransactionCreate")
  @PostMapping("/execute-reallocation")
  @Operation(
      summary =
          "Thực thi Điều chuyển Dự toán Thông minh 1-Click (Smart Reallocation) từ đề xuất tối ưu Burn-rate / AI")
  @Log(title = "[BudgetTransaction] Execute Smart Reallocation", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> executeSmartReallocation(
      @RequestBody Map<String, Object> body) {
    return success(budgetTransactionBusiness.executeSmartReallocation(body));
  }

  @SaCheckPermission("BudgetTransactionCreate")
  @PostMapping("/supplement-content")
  @Operation(
      summary =
          "Nghiệp vụ phụ NV-01: Bổ sung nội dung nhiệm vụ chi hoặc bổ sung dự toán đa lĩnh vực trong năm")
  @Log(title = "[BudgetTransaction] Supplement Content", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> supplementBudgetContent(
      @RequestBody Map<String, Object> body) {
    return success(budgetTransactionBusiness.supplementBudgetContent(body));
  }

  @SaCheckPermission("BudgetTransactionCreate")
  @PostMapping("/carry-over-year")
  @Operation(
      summary =
          "Nghiệp vụ phụ NV-02: Chuyển nguồn kinh phí liên năm (N-1 -> N) kèm ánh xạ tự động Lĩnh vực năm cũ sang năm mới")
  @Log(title = "[BudgetTransaction] Carry Over Year", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> executeYearCarryOver(
      @RequestBody Map<String, Object> body) {
    return success(budgetTransactionBusiness.executeYearCarryOver(body));
  }

  @SaCheckPermission("BudgetTransactionCreate")
  @PostMapping("/cross-domain-transfer")
  @Operation(
      summary =
          "Nghiệp vụ phụ NV-03: Điều chuyển nội bộ chéo giữa các Lĩnh vực trong cùng đơn vị (Tổng biến động đơn vị = 0)")
  @Log(title = "[BudgetTransaction] Cross Domain Transfer", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> executeCrossDomainTransfer(
      @RequestBody Map<String, Object> body) {
    return success(budgetTransactionBusiness.executeCrossDomainTransfer(body));
  }

  @SaCheckPermission("BudgetTransactionCreate")
  @PostMapping("/saving-reduction")
  @Operation(
      summary =
          "Nghiệp vụ phụ NV-04: Tiết kiệm chi thường xuyên (NQ 135), Điều chỉnh giảm theo Phương án tự chủ hoặc Giảm do thừa")
  @Log(title = "[BudgetTransaction] Saving & Reduction", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> executeSavingAndReduction(
      @RequestBody Map<String, Object> body) {
    return success(budgetTransactionBusiness.executeSavingAndReduction(body));
  }

  @SaCheckPermission("BudgetTransactionCreate")
  @PostMapping("/cancel-expired")
  @Operation(
      summary =
          "Nghiệp vụ phụ NV-05: Thu hồi / Hủy dự toán hết niên độ nộp trả NSNN đối với kinh phí không chuyển nguồn")
  @Log(title = "[BudgetTransaction] Cancel Expired Allocation", businessType = BusinessType.UPDATE)
  public R<Map<String, Object>> cancelExpiredAllocation(
      @RequestBody Map<String, Object> body) {
    return success(budgetTransactionBusiness.cancelExpiredAllocation(body));
  }

  @SaCheckPermission("BudgetTransactionView")
  @GetMapping("/operation-role-matrix")
  @Operation(
      summary =
          "Lấy Ma trận Phân tách Vai trò & Thẩm quyền Thực hiện các Nghiệp vụ Phụ Ngân sách (NV-01..NV-05 & DX-01..DX-04)")
  public R<Map<String, Object>> getOperationRoleMatrix() {
    return success(budgetTransactionBusiness.getOperationRoleMatrix());
  }

  @SaCheckPermission("BudgetTransactionCreate")
  @PostMapping("/earmark-hold")
  @Operation(
      summary =
          "Nghiệp vụ phụ nâng cao DX-01: Tạm giữ / Phong tỏa hoặc Giải tỏa dự toán chờ duyệt chủ trương")
  @Log(title = "[BudgetTransaction] Earmark Hold / Release", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> executeEarmarkHold(
      @RequestBody Map<String, Object> body) {
    return success(budgetTransactionBusiness.executeEarmarkHold(body));
  }

  @SaCheckPermission("BudgetTransactionCreate")
  @PostMapping("/tranche-release")
  @Operation(
      summary =
          "Nghiệp vụ phụ nâng cao DX-02: Giải tỏa / Cấp phát dự toán đầu năm theo đợt hoặc theo tiến độ thu")
  @Log(title = "[BudgetTransaction] Tranche Release", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> executeTrancheRelease(
      @RequestBody Map<String, Object> body) {
    return success(budgetTransactionBusiness.executeTrancheRelease(body));
  }

  @SaCheckPermission("BudgetTransactionCreate")
  @PostMapping("/batch-payroll-indexation")
  @Operation(
      summary =
          "Nghiệp vụ phụ nâng cao DX-03: Điều chỉnh dự toán hàng loạt theo biến động lương cơ sở & biên chế")
  @Log(title = "[BudgetTransaction] Batch Payroll Indexation", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> executeBatchPayrollIndexation(
      @RequestBody Map<String, Object> body) {
    return success(budgetTransactionBusiness.executeBatchPayrollIndexation(body));
  }

  @SaCheckPermission("BudgetTransactionCreate")
  @PostMapping("/autonomy-fund-distribution")
  @Operation(
      summary =
          "Nghiệp vụ phụ nâng cao DX-04: Trích lập 4 Quỹ tự chủ tài chính cuối năm theo Nghị định 60/2021/NĐ-CP")
  @Log(title = "[BudgetTransaction] Autonomy Fund Distribution", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> executeAutonomyFundDistribution(
      @RequestBody Map<String, Object> body) {
    return success(budgetTransactionBusiness.executeAutonomyFundDistribution(body));
  }

}
