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
import vn.tr.budget.business.BudgetUnitConfigBusiness;
import vn.tr.budget.data.criteria.BudgetUnitConfigSearchCriteria;
import vn.tr.budget.data.dto.BudgetUnitConfigData;
import vn.tr.budget.data.dto.BudgetUnitConfigImportData;
import vn.tr.budget.data.mapper.BudgetUnitConfigMapper;
import vn.tr.budget.excel.BudgetUnitConfigExcelService;
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
 * Controller cho BudgetUnitConfig.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/unit-configs")
@RequiredArgsConstructor
@Tag(name = "Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính", description = "API quản lý Cấu hình Đơn vị Sử dụng Ngân sách và Ma trận Phân quyền Tự chủ Tài chính (kế thừa & nâng cấp từ QlkpCauHinhDonVi)")
public class BudgetUnitConfigController extends BaseController {

  private final BudgetUnitConfigBusiness budgetUnitConfigBusiness;
  private final BudgetUnitConfigExcelService budgetUnitConfigExcelService;
  private final BudgetUnitConfigMapper budgetUnitConfigMapper;
  private final HttpServletResponse response;

  @SaCheckPermission("BudgetUnitConfigCreate")
  @PostMapping
  @Operation(summary = "Tạo mới Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính")
  @Log(title = "[BudgetUnitConfig] Create", businessType = BusinessType.INSERT)
  public R<BudgetUnitConfigData> create(@Valid @RequestBody BudgetUnitConfigData data) {
    return success(budgetUnitConfigBusiness.create(data));
  }

  @SaCheckPermission("BudgetUnitConfigEdit")
  @PutMapping("/{id}")
  @Operation(summary = "Cập nhật Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính")
  @Log(title = "[BudgetUnitConfig] Update", businessType = BusinessType.UPDATE)
  public R<BudgetUnitConfigData> update(@PathVariable Long id, @Valid @RequestBody BudgetUnitConfigData data) {
    return success(budgetUnitConfigBusiness.update(id, data));
  }

  @SaCheckPermission("BudgetUnitConfigDelete")
  @DeleteMapping("/{id}")
  @Operation(summary = "Xoá Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính")
  @Log(title = "[BudgetUnitConfig] Delete", businessType = BusinessType.DELETE)
  public R<Void> delete(@PathVariable Long id) {
    budgetUnitConfigBusiness.delete(id);
    return success();
  }

  @SaCheckPermission("BudgetUnitConfigBatchDelete")
  @DeleteMapping
  @Operation(summary = "Xoá hàng loạt Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính")
  @Log(title = "[BudgetUnitConfig] Bulk Delete", businessType = BusinessType.DELETE)
  public R<BulkOperationResult<Long>> bulkDelete(@Valid @RequestBody DeleteData deleteData) {
    return success(budgetUnitConfigBusiness.bulkDelete(deleteData.getIds()));
  }

  @SaCheckPermission("BudgetUnitConfigList")
  @GetMapping
  @Operation(summary = "Tìm kiếm Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính phân trang")
  public R<PagedData<BudgetUnitConfigData>> findAll(BudgetUnitConfigSearchCriteria criteria) {
    return success(budgetUnitConfigBusiness.findAll(criteria));
  }

  @SaCheckPermission("BudgetUnitConfigDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính")
  public R<BudgetUnitConfigData> findById(@PathVariable Long id) {
    return success(budgetUnitConfigBusiness.findById(id));
  }

  @SaCheckPermission("BudgetUnitConfigClone")
  @GetMapping("/{id}/clone")
  @Operation(summary = "Sao chép Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính")
  @Log(title = "[BudgetUnitConfig] Clone", businessType = BusinessType.INSERT)
  public R<BudgetUnitConfigData> cloneById(@PathVariable Long id) {
    return success(budgetUnitConfigBusiness.cloneById(id));
  }

  @SaCheckPermission("BudgetUnitConfigRollback")
  @PostMapping("/{id}/rollback")
  @Operation(summary = "Khôi phục dữ liệu Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính")
  @Log(title = "[BudgetUnitConfig] Rollback", businessType = BusinessType.UPDATE)
  public R<BudgetUnitConfigData> rollback(@PathVariable Long id, @RequestParam Long version) {
    return success(budgetUnitConfigBusiness.rollback(id, version));
  }

  @SaCheckPermission("BudgetUnitConfigSort")
  @PutMapping("/sort-order")
  @Operation(summary = "Cập nhật thứ tự hàng loạt")
  @Log(title = "[BudgetUnitConfig] Batch Update Sort Order", businessType = BusinessType.UPDATE)
  public R<Void> bulkUpdateSortOrder(@RequestBody BulkSortOrderData data) {
    budgetUnitConfigBusiness.bulkUpdateSortOrder(data);
    return success();
  }

  @SaCheckPermission("BudgetUnitConfigExport")
  @GetMapping("/export")
  @Operation(summary = "Xuất file Excel Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính")
  @Log(
      title = "[BudgetUnitConfig] Export Excel",
      businessType = BusinessType.EXPORT,
      isSaveRequestData = false)
  public void export(BudgetUnitConfigSearchCriteria criteria) {
    budgetUnitConfigExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetUnitConfigImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(summary = "Tải file mẫu Import Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính")
  @Log(
      title = "[BudgetUnitConfig] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate() {
    budgetUnitConfigExcelService.importTemplate(response);
  }

  @SaCheckPermission("BudgetUnitConfigImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    try (InputStream is = file.getInputStream()) {
      ImportBatchResult<BudgetUnitConfigImportData> batchResult =
          budgetUnitConfigExcelService.validateExcel(is, file.getOriginalFilename(), true);

      List<BudgetUnitConfigData> dataList = new ArrayList<>();
      List<Map<String, Object>> errorsList = new ArrayList<>();

      for (ImportRowResult<BudgetUnitConfigImportData> rowResult : batchResult.getRowResults()) {
        BudgetUnitConfigData data = budgetUnitConfigMapper.toImportData(rowResult.getOriginalData());
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

  @SaCheckPermission("BudgetUnitConfigImport")
  @PostMapping("/import")
  @Operation(summary = "Import dữ liệu Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính từ JSON")
  @Log(title = "[BudgetUnitConfig] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(@RequestBody Map<String, List<BudgetUnitConfigData>> body) {
    List<BudgetUnitConfigData> dataList = body.get("data");
    if (dataList == null || dataList.isEmpty()) {
      throw new BaseException("Dữ liệu import trống");
    }

    int created = 0;
    int updated = 0;
    List<Map<String, Object>> errors = new ArrayList<>();

    for (int i = 0; i < dataList.size(); i++) {
      BudgetUnitConfigData data = dataList.get(i);
      try {
        if (data.getId() != null
            && data.getId() > 0
            && budgetUnitConfigBusiness.getInjectedRepo().existsById(data.getId())) {
          budgetUnitConfigBusiness.update(data.getId(), data);
          updated++;
        } else {
          budgetUnitConfigBusiness.create(data);
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

  @SaCheckPermission("BudgetUnitConfigList")
  @GetMapping("/check-autonomy")
  @Operation(summary = "Kiểm tra ma trận quyền tự chủ tài chính của Đơn vị / Phòng ban")
  public R<Map<String, Object>> checkAutonomy(
      @RequestParam("nodeId") Long nodeId,
      @RequestParam(value = "categoryCode", required = false) String categoryCode,
      @RequestParam(value = "proposedAmount", required = false) java.math.BigDecimal proposedAmount) {
    return success(budgetUnitConfigBusiness.checkAutonomy(nodeId, categoryCode, proposedAmount));
  }

}
