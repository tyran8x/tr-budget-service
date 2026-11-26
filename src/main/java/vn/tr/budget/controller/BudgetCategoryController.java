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
import vn.tr.budget.business.BudgetCategoryBusiness;
import vn.tr.budget.data.criteria.BudgetCategorySearchCriteria;
import vn.tr.budget.data.dto.BudgetCategoryData;
import vn.tr.budget.data.mapper.BudgetCategoryMapper;
import vn.tr.budget.excel.BudgetCategoryExcelService;
import vn.tr.budget.excel.BudgetExcelControllerHelper;
import vn.tr.common.core.domain.R;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.domain.data.RefData;
import vn.tr.common.log.annotation.Log;
import vn.tr.common.log.enums.BusinessType;
import vn.tr.common.ratelimiter.annotation.RateLimiter;
import vn.tr.common.ratelimiter.enums.LimitType;
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
@Tag(
    name = "Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí",
    description = "API quản lý Danh mục Loại - Khoản - Mục lục Ngân sách Nhà nước và Nguồn kinh phí")
public class BudgetCategoryController extends BaseController {

  private final BudgetCategoryBusiness budgetCategoryBusiness;
  private final BudgetCategoryExcelService budgetCategoryExcelService;
  private final BudgetCategoryMapper budgetCategoryMapper;

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
  public R<BudgetCategoryData> update(
      @PathVariable Long id, @Valid @RequestBody BudgetCategoryData data) {
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

  @SaCheckPermission(
      value = {
        "BudgetCategoryList",
        "BudgetCategoryOptions",
        "BudgetCategoryView",
        "BudgetCategoryDetail",
        "BudgetAllocationList",
        "BudgetAllocationCreate",
        "BudgetAllocationEdit",
        "BudgetEstimateItemList",
        "BudgetEstimateItemCreate",
        "BudgetEstimateItemEdit",
        "BudgetDisbursementList",
        "BudgetDisbursementCreate",
        "BudgetDisbursementEdit",
        "BudgetTransactionList",
        "BudgetTransactionCreate",
        "BudgetTransactionEdit",
        "BudgetTransactionSubmit",
        "BudgetReportList",
        "BudgetReportCreate",
        "BudgetReportEdit",
        "BudgetReportConsolidate",
        "BudgetUnitConfigList",
        "BudgetUnitConfigCreate",
        "BudgetUnitConfigEdit",
        "BudgetDashboardQuery"
      },
      mode = SaMode.OR)
  @GetMapping("/options")
  @Operation(summary = "Lấy danh sách rút gọn (Lookup Options) Danh mục Loại - Khoản")
  public R<PagedData<RefData<Long>>> getOptions(BudgetCategorySearchCriteria criteria) {
    return success(budgetCategoryBusiness.getOptions(criteria));
  }

  @SaCheckPermission("BudgetCategoryDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  public R<BudgetCategoryData> findById(@PathVariable Long id) {
    return success(budgetCategoryBusiness.findById(id));
  }

  @SaCheckPermission("BudgetCategoryClone")
  @PostMapping("/{id}/clone")
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
  public R<Void> bulkUpdateSortOrder(@Valid @RequestBody BulkSortOrderData data) {
    budgetCategoryBusiness.bulkUpdateSortOrder(data);
    return success();
  }

  @RateLimiter(time = 60, count = 20, limitType = LimitType.IP)
  @SaCheckPermission("BudgetCategoryExport")
  @GetMapping("/export")
  @Operation(summary = "Xuất file Excel Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  @Log(
      title = "[BudgetCategory] Export Excel",
      businessType = BusinessType.EXPORT,
      isSaveRequestData = false)
  public void export(BudgetCategorySearchCriteria criteria, HttpServletResponse response) {
    budgetCategoryExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetCategoryImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(summary = "Tải file mẫu Import Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí")
  @Log(
      title = "[BudgetCategory] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate(HttpServletResponse response) {
    budgetCategoryExcelService.importTemplate(response);
  }

  @RateLimiter(time = 60, count = 15, limitType = LimitType.IP)
  @SaCheckPermission("BudgetCategoryImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    return success(
        BudgetExcelControllerHelper.validateImport(
            file, budgetCategoryExcelService, budgetCategoryMapper));
  }

  @RateLimiter(time = 60, count = 15, limitType = LimitType.IP)
  @SaCheckPermission("BudgetCategoryImport")
  @PostMapping("/import")
  @Operation(summary = "Import dữ liệu Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí từ JSON")
  @Log(title = "[BudgetCategory] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(
      @Valid @RequestBody Map<String, List<BudgetCategoryData>> body) {
    return success(
        BudgetExcelControllerHelper.importData(
            body.get("data"),
            BudgetCategoryData::getId,
            id -> budgetCategoryBusiness.getInjectedRepo().existsById(id),
            budgetCategoryBusiness::create,
            budgetCategoryBusiness::update));
  }

  @SaCheckPermission(
      value = {
        "BudgetCategoryList",
        "BudgetAllocationList",
        "BudgetTransactionList",
        "BudgetReportList"
      },
      mode = SaMode.OR)
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
  public R<BudgetCategoryData> saveYearDomainConfig(@Valid @RequestBody BudgetCategoryData data) {
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
