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
import vn.tr.budget.business.BudgetCategoryBusiness;
import vn.tr.budget.data.criteria.BudgetCategorySearchCriteria;
import vn.tr.budget.data.dto.BudgetCategoryData;
import vn.tr.budget.data.dto.BudgetCategoryImportData;
import vn.tr.budget.data.mapper.BudgetCategoryMapper;
import vn.tr.budget.excel.BudgetCategoryExcelService;
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
 * Controller cho BudgetCategory.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
@Tag(name = "Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí", description = "API quản lý Danh mục Loại - Khoản - Mục lục Ngân sách Nhà nước và Nguồn kinh phí (kế thừa & nâng cấp từ QlkpDmLoaiKhoan)")
public class BudgetCategoryController extends BaseController {

  private final BudgetCategoryBusiness budgetCategoryBusiness;
  private final BudgetCategoryExcelService budgetCategoryExcelService;
  private final BudgetCategoryMapper budgetCategoryMapper;
  private final HttpServletResponse response;

  @SaCheckPermission("BudgetCategoryCreate")
  @PostMapping
  @Operation(summary = "Tạo mới Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  @Log(title = "[BudgetCategory] Create", businessType = BusinessType.INSERT)
  public R<BudgetCategoryData> create(@Valid @RequestBody BudgetCategoryData data) {
    return success(budgetCategoryBusiness.create(data));
  }

  @SaCheckPermission("BudgetCategoryEdit")
  @PutMapping("/{id}")
  @Operation(summary = "Cập nhật Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  @Log(title = "[BudgetCategory] Update", businessType = BusinessType.UPDATE)
  public R<BudgetCategoryData> update(@PathVariable Long id, @Valid @RequestBody BudgetCategoryData data) {
    return success(budgetCategoryBusiness.update(id, data));
  }

  @SaCheckPermission("BudgetCategoryDelete")
  @DeleteMapping("/{id}")
  @Operation(summary = "Xoá Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  @Log(title = "[BudgetCategory] Delete", businessType = BusinessType.DELETE)
  public R<Void> delete(@PathVariable Long id) {
    budgetCategoryBusiness.delete(id);
    return success();
  }

  @SaCheckPermission("BudgetCategoryBatchDelete")
  @DeleteMapping
  @Operation(summary = "Xoá hàng loạt Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  @Log(title = "[BudgetCategory] Bulk Delete", businessType = BusinessType.DELETE)
  public R<BulkOperationResult<Long>> bulkDelete(@Valid @RequestBody DeleteData deleteData) {
    return success(budgetCategoryBusiness.bulkDelete(deleteData.getIds()));
  }

  @SaCheckPermission("BudgetCategoryList")
  @GetMapping
  @Operation(summary = "Tìm kiếm Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí phân trang")
  public R<PagedData<BudgetCategoryData>> findAll(BudgetCategorySearchCriteria criteria) {
    return success(budgetCategoryBusiness.findAll(criteria));
  }

  @SaCheckPermission("BudgetCategoryDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  public R<BudgetCategoryData> findById(@PathVariable Long id) {
    return success(budgetCategoryBusiness.findById(id));
  }

  @SaCheckPermission("BudgetCategoryClone")
  @GetMapping("/{id}/clone")
  @Operation(summary = "Sao chép Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  @Log(title = "[BudgetCategory] Clone", businessType = BusinessType.INSERT)
  public R<BudgetCategoryData> cloneById(@PathVariable Long id) {
    return success(budgetCategoryBusiness.cloneById(id));
  }

  @SaCheckPermission("BudgetCategoryRollback")
  @PostMapping("/{id}/rollback")
  @Operation(summary = "Khôi phục dữ liệu Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  @Log(title = "[BudgetCategory] Rollback", businessType = BusinessType.UPDATE)
  public R<BudgetCategoryData> rollback(@PathVariable Long id, @RequestParam Long version) {
    return success(budgetCategoryBusiness.rollback(id, version));
  }

  @SaCheckPermission("BudgetCategorySort")
  @PutMapping("/sort-order")
  @Operation(summary = "Cập nhật thứ tự hàng loạt")
  @Log(title = "[BudgetCategory] Batch Update Sort Order", businessType = BusinessType.UPDATE)
  public R<Void> bulkUpdateSortOrder(@RequestBody BulkSortOrderData data) {
    budgetCategoryBusiness.bulkUpdateSortOrder(data);
    return success();
  }

  @SaCheckPermission("BudgetCategoryExport")
  @GetMapping("/export")
  @Operation(summary = "Xuất file Excel Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  @Log(
      title = "[BudgetCategory] Export Excel",
      businessType = BusinessType.EXPORT,
      isSaveRequestData = false)
  public void export(BudgetCategorySearchCriteria criteria) {
    budgetCategoryExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetCategoryImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(summary = "Tải file mẫu Import Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  @Log(
      title = "[BudgetCategory] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate() {
    budgetCategoryExcelService.importTemplate(response);
  }

  @SaCheckPermission("BudgetCategoryImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    try (InputStream is = file.getInputStream()) {
      ImportBatchResult<BudgetCategoryImportData> batchResult =
          budgetCategoryExcelService.validateExcel(is, file.getOriginalFilename(), true);

      List<BudgetCategoryData> dataList = new ArrayList<>();
      List<Map<String, Object>> errorsList = new ArrayList<>();

      for (ImportRowResult<BudgetCategoryImportData> rowResult : batchResult.getRowResults()) {
        BudgetCategoryData data = budgetCategoryMapper.toImportData(rowResult.getOriginalData());
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

  @SaCheckPermission("BudgetCategoryImport")
  @PostMapping("/import")
  @Operation(summary = "Import dữ liệu Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí từ JSON")
  @Log(title = "[BudgetCategory] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(@RequestBody Map<String, List<BudgetCategoryData>> body) {
    List<BudgetCategoryData> dataList = body.get("data");
    if (dataList == null || dataList.isEmpty()) {
      throw new BaseException("Dữ liệu import trống");
    }

    int created = 0;
    int updated = 0;
    List<Map<String, Object>> errors = new ArrayList<>();

    for (int i = 0; i < dataList.size(); i++) {
      BudgetCategoryData data = dataList.get(i);
      try {
        if (data.getId() != null
            && data.getId() > 0
            && budgetCategoryBusiness.getInjectedRepo().existsById(data.getId())) {
          budgetCategoryBusiness.update(data.getId(), data);
          updated++;
        } else {
          budgetCategoryBusiness.create(data);
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

  @SaCheckPermission("BudgetCategoryList")
  @GetMapping("/year-domain-configs")
  @Operation(
      summary =
          "Lấy cấu hình Lĩnh vực (DOMAIN) và Nhóm lĩnh vực (DOMAIN_GROUP) theo từng Năm ngân sách cụ thể")
  public R<Map<String, Object>> getYearDomainConfigs(
      @RequestParam(required = false) Integer budgetYear,
      @RequestParam(required = false) Long nodeId) {
    return success(budgetCategoryBusiness.getYearDomainConfig(budgetYear, nodeId));
  }

  @SaCheckPermission("BudgetCategoryCreate")
  @PostMapping("/year-domain-configs")
  @Operation(
      summary =
          "Tạo mới hoặc cập nhật cấu hình Lĩnh vực / Nhóm lĩnh vực cho một Năm ngân sách cụ thể")
  @Log(title = "[BudgetCategory] Save Year Domain Config", businessType = BusinessType.INSERT)
  public R<BudgetCategoryData> saveYearDomainConfig(@RequestBody BudgetCategoryData data) {
    return success(budgetCategoryBusiness.saveYearDomainConfig(data));
  }

  @SaCheckPermission("BudgetCategoryCreate")
  @PostMapping("/year-domain-configs/clone-year")
  @Operation(
      summary =
          "Sao chép cấu hình Lĩnh vực & Nhóm lĩnh vực từ năm nguồn sang năm đích (kèm ánh xạ previousYearDomainCode)")
  @Log(title = "[BudgetCategory] Clone Year Domain Config", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> cloneYearDomainConfig(@RequestBody Map<String, Object> body) {
    return success(budgetCategoryBusiness.cloneYearDomainConfig(body));
  }

}
