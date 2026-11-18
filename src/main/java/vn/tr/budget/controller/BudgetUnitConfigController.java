package vn.tr.budget.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
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
import vn.tr.budget.data.mapper.BudgetUnitConfigMapper;
import vn.tr.budget.excel.BudgetExcelControllerHelper;
import vn.tr.budget.excel.BudgetUnitConfigExcelService;
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
 * Controller cho BudgetUnitConfig.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/unit-configs")
@RequiredArgsConstructor
@Tag(
    name = "Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính",
    description =
        "API quản lý Cấu hình Đơn vị Sử dụng Ngân sách và Ma trận Phân quyền Tự chủ Tài chính")
public class BudgetUnitConfigController extends BaseController {

  private final BudgetUnitConfigBusiness budgetUnitConfigBusiness;
  private final BudgetUnitConfigExcelService budgetUnitConfigExcelService;
  private final BudgetUnitConfigMapper budgetUnitConfigMapper;

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
  public R<BudgetUnitConfigData> update(
      @PathVariable Long id, @Valid @RequestBody BudgetUnitConfigData data) {
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

  @SaCheckPermission(
      value = {
        "BudgetUnitConfigList",
        "BudgetAllocationCreate",
        "BudgetAllocationEdit",
        "BudgetDisbursementCreate",
        "BudgetTransactionSubmit",
        "BudgetReportConsolidate"
      },
      mode = SaMode.OR)
  @GetMapping("/options")
  @Operation(summary = "Lấy danh sách rút gọn (Lookup Options) Đơn vị Sử dụng Ngân sách")
  public R<PagedData<RefData<Long>>> getOptions(BudgetUnitConfigSearchCriteria criteria) {
    return success(budgetUnitConfigBusiness.getOptions(criteria));
  }

  @SaCheckPermission("BudgetUnitConfigDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính")
  public R<BudgetUnitConfigData> findById(@PathVariable Long id) {
    return success(budgetUnitConfigBusiness.findById(id));
  }

  @SaCheckPermission("BudgetUnitConfigClone")
  @PostMapping("/{id}/clone")
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
  public void export(BudgetUnitConfigSearchCriteria criteria, HttpServletResponse response) {
    budgetUnitConfigExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetUnitConfigImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(summary = "Tải file mẫu Import Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính")
  @Log(
      title = "[BudgetUnitConfig] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate(HttpServletResponse response) {
    budgetUnitConfigExcelService.importTemplate(response);
  }

  @SaCheckPermission("BudgetUnitConfigImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    return success(
        BudgetExcelControllerHelper.validateImport(
            file, budgetUnitConfigExcelService, budgetUnitConfigMapper));
  }

  @SaCheckPermission("BudgetUnitConfigImport")
  @PostMapping("/import")
  @Operation(summary = "Import dữ liệu Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính từ JSON")
  @Log(title = "[BudgetUnitConfig] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(
      @RequestBody Map<String, List<BudgetUnitConfigData>> body) {
    return success(
        BudgetExcelControllerHelper.importData(
            body.get("data"),
            BudgetUnitConfigData::getId,
            id -> budgetUnitConfigBusiness.getInjectedRepo().existsById(id),
            budgetUnitConfigBusiness::create,
            budgetUnitConfigBusiness::update));
  }

  @SaCheckPermission(
      value = {
        "BudgetUnitConfigList",
        "BudgetAllocationCreate",
        "BudgetAllocationEdit",
        "BudgetAllocationSelfApprove",
        "BudgetTransactionSubmit"
      },
      mode = SaMode.OR)
  @GetMapping("/check-autonomy")
  @Operation(summary = "Kiểm tra ma trận quyền tự chủ tài chính của Đơn vị / Phòng ban")
  public R<Map<String, Object>> checkAutonomy(
      @RequestParam("nodeId") Long nodeId,
      @RequestParam(value = "categoryCode", required = false) String categoryCode,
      @RequestParam(value = "proposedAmount", required = false) BigDecimal proposedAmount) {
    return success(budgetUnitConfigBusiness.checkAutonomy(nodeId, categoryCode, proposedAmount));
  }
}
