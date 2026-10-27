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
import vn.tr.budget.business.BudgetEstimateItemBusiness;
import vn.tr.budget.data.criteria.BudgetEstimateItemSearchCriteria;
import vn.tr.budget.data.dto.BudgetEstimateItemData;
import vn.tr.budget.data.dto.BudgetEstimateItemImportData;
import vn.tr.budget.data.mapper.BudgetEstimateItemMapper;
import vn.tr.budget.excel.BudgetEstimateItemExcelService;
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
 * Controller cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/estimate-items")
@RequiredArgsConstructor
@Tag(name = "Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp", description = "API quản lý Cây Danh mục & Cấu hình Dự toán Thu - Chi đa cấp (kế thừa & nâng cấp từ QlkpDmDuToanThuChi & QlkpCauHinhDuToan)")
public class BudgetEstimateItemController extends BaseController {

  private final BudgetEstimateItemBusiness budgetEstimateItemBusiness;
  private final BudgetEstimateItemExcelService budgetEstimateItemExcelService;
  private final BudgetEstimateItemMapper budgetEstimateItemMapper;
  private final HttpServletResponse response;

  @SaCheckPermission("BudgetEstimateItemCreate")
  @PostMapping
  @Operation(summary = "Tạo mới Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp")
  @Log(title = "[BudgetEstimateItem] Create", businessType = BusinessType.INSERT)
  public R<BudgetEstimateItemData> create(@Valid @RequestBody BudgetEstimateItemData data) {
    return success(budgetEstimateItemBusiness.create(data));
  }

  @SaCheckPermission("BudgetEstimateItemEdit")
  @PutMapping("/{id}")
  @Operation(summary = "Cập nhật Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp")
  @Log(title = "[BudgetEstimateItem] Update", businessType = BusinessType.UPDATE)
  public R<BudgetEstimateItemData> update(@PathVariable Long id, @Valid @RequestBody BudgetEstimateItemData data) {
    return success(budgetEstimateItemBusiness.update(id, data));
  }

  @SaCheckPermission("BudgetEstimateItemDelete")
  @DeleteMapping("/{id}")
  @Operation(summary = "Xoá Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp")
  @Log(title = "[BudgetEstimateItem] Delete", businessType = BusinessType.DELETE)
  public R<Void> delete(@PathVariable Long id) {
    budgetEstimateItemBusiness.delete(id);
    return success();
  }

  @SaCheckPermission("BudgetEstimateItemBatchDelete")
  @DeleteMapping
  @Operation(summary = "Xoá hàng loạt Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp")
  @Log(title = "[BudgetEstimateItem] Bulk Delete", businessType = BusinessType.DELETE)
  public R<BulkOperationResult<Long>> bulkDelete(@Valid @RequestBody DeleteData deleteData) {
    return success(budgetEstimateItemBusiness.bulkDelete(deleteData.getIds()));
  }

  @SaCheckPermission("BudgetEstimateItemList")
  @GetMapping
  @Operation(summary = "Tìm kiếm Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp phân trang")
  public R<PagedData<BudgetEstimateItemData>> findAll(BudgetEstimateItemSearchCriteria criteria) {
    return success(budgetEstimateItemBusiness.findAll(criteria));
  }

  @SaCheckPermission("BudgetEstimateItemDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp")
  public R<BudgetEstimateItemData> findById(@PathVariable Long id) {
    return success(budgetEstimateItemBusiness.findById(id));
  }

  @SaCheckPermission("BudgetEstimateItemClone")
  @GetMapping("/{id}/clone")
  @Operation(summary = "Sao chép Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp")
  @Log(title = "[BudgetEstimateItem] Clone", businessType = BusinessType.INSERT)
  public R<BudgetEstimateItemData> cloneById(@PathVariable Long id) {
    return success(budgetEstimateItemBusiness.cloneById(id));
  }

  @SaCheckPermission("BudgetEstimateItemRollback")
  @PostMapping("/{id}/rollback")
  @Operation(summary = "Khôi phục dữ liệu Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp")
  @Log(title = "[BudgetEstimateItem] Rollback", businessType = BusinessType.UPDATE)
  public R<BudgetEstimateItemData> rollback(@PathVariable Long id, @RequestParam Long version) {
    return success(budgetEstimateItemBusiness.rollback(id, version));
  }

  @SaCheckPermission("BudgetEstimateItemSort")
  @PutMapping("/sort-order")
  @Operation(summary = "Cập nhật thứ tự hàng loạt")
  @Log(title = "[BudgetEstimateItem] Batch Update Sort Order", businessType = BusinessType.UPDATE)
  public R<Void> bulkUpdateSortOrder(@RequestBody BulkSortOrderData data) {
    budgetEstimateItemBusiness.bulkUpdateSortOrder(data);
    return success();
  }

  @SaCheckPermission("BudgetEstimateItemExport")
  @GetMapping("/export")
  @Operation(summary = "Xuất file Excel Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp")
  @Log(
      title = "[BudgetEstimateItem] Export Excel",
      businessType = BusinessType.EXPORT,
      isSaveRequestData = false)
  public void export(BudgetEstimateItemSearchCriteria criteria) {
    budgetEstimateItemExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetEstimateItemImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(summary = "Tải file mẫu Import Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp")
  @Log(
      title = "[BudgetEstimateItem] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate() {
    budgetEstimateItemExcelService.importTemplate(response);
  }

  @SaCheckPermission("BudgetEstimateItemImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    try (InputStream is = file.getInputStream()) {
      ImportBatchResult<BudgetEstimateItemImportData> batchResult =
          budgetEstimateItemExcelService.validateExcel(is, file.getOriginalFilename(), true);

      List<BudgetEstimateItemData> dataList = new ArrayList<>();
      List<Map<String, Object>> errorsList = new ArrayList<>();

      for (ImportRowResult<BudgetEstimateItemImportData> rowResult : batchResult.getRowResults()) {
        BudgetEstimateItemData data = budgetEstimateItemMapper.toImportData(rowResult.getOriginalData());
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

  @SaCheckPermission("BudgetEstimateItemImport")
  @PostMapping("/import")
  @Operation(summary = "Import dữ liệu Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp từ JSON")
  @Log(title = "[BudgetEstimateItem] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(@RequestBody Map<String, List<BudgetEstimateItemData>> body) {
    List<BudgetEstimateItemData> dataList = body.get("data");
    if (dataList == null || dataList.isEmpty()) {
      throw new BaseException("Dữ liệu import trống");
    }

    int created = 0;
    int updated = 0;
    List<Map<String, Object>> errors = new ArrayList<>();

    for (int i = 0; i < dataList.size(); i++) {
      BudgetEstimateItemData data = dataList.get(i);
      try {
        if (data.getId() != null
            && data.getId() > 0
            && budgetEstimateItemBusiness.getInjectedRepo().existsById(data.getId())) {
          budgetEstimateItemBusiness.update(data.getId(), data);
          updated++;
        } else {
          budgetEstimateItemBusiness.create(data);
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

  @SaCheckPermission("BudgetEstimateItemList")
  @GetMapping("/tree")
  @Operation(summary = "Lấy cây Danh mục Dự toán Thu - Chi đa cấp")
  public R<List<Map<String, Object>>> getEstimateTree(
      @RequestParam(value = "itemType", required = false) String itemType) {
    return success(budgetEstimateItemBusiness.getEstimateTree(itemType));
  }

}
