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
import vn.tr.budget.business.BudgetEstimateItemBusiness;
import vn.tr.budget.business.BudgetEstimateTreeOpsBusiness;
import vn.tr.budget.business.BudgetEstimateTreeSyncBusiness;
import vn.tr.budget.data.criteria.BudgetEstimateItemSearchCriteria;
import vn.tr.budget.data.dto.BudgetEstimateItemData;
import vn.tr.budget.data.dto.BudgetEstimateTreeSaveRequest;
import vn.tr.budget.data.mapper.BudgetEstimateItemMapper;
import vn.tr.budget.excel.BudgetEstimateItemExcelService;
import vn.tr.budget.excel.BudgetExcelControllerHelper;
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
 * Controller cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
@RestController
@RequestMapping("/estimate-items")
@RequiredArgsConstructor
@Tag(
    name = "Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp",
    description = "API quản lý Cây Danh mục & Cấu hình Dự toán Thu - Chi đa cấp")
public class BudgetEstimateItemController extends BaseController {

  private final BudgetEstimateItemBusiness budgetEstimateItemBusiness;
  private final BudgetEstimateTreeSyncBusiness budgetEstimateTreeSyncBusiness;
  private final BudgetEstimateTreeOpsBusiness budgetEstimateTreeOpsBusiness;
  private final BudgetEstimateItemExcelService budgetEstimateItemExcelService;
  private final BudgetEstimateItemMapper budgetEstimateItemMapper;

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
  public R<BudgetEstimateItemData> update(
      @PathVariable Long id, @Valid @RequestBody BudgetEstimateItemData data) {
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

  @SaCheckPermission(
      value = {
        "BudgetEstimateItemList",
        "BudgetEstimateItemOptions",
        "BudgetEstimateItemView",
        "BudgetEstimateItemDetail",
        "BudgetAllocationList",
        "BudgetAllocationCreate",
        "BudgetAllocationEdit",
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
        "BudgetDashboardQuery"
      },
      mode = SaMode.OR)
  @GetMapping("/options")
  @Operation(summary = "Lấy danh sách rút gọn (Lookup Options) Mục Dự toán Thu - Chi")
  public R<PagedData<RefData<Long>>> getOptions(BudgetEstimateItemSearchCriteria criteria) {
    return success(budgetEstimateItemBusiness.getOptions(criteria));
  }

  @SaCheckPermission("BudgetEstimateItemDetail")
  @GetMapping("/{id}")
  @Operation(summary = "Lấy chi tiết Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp")
  public R<BudgetEstimateItemData> findById(@PathVariable Long id) {
    return success(budgetEstimateItemBusiness.findById(id));
  }

  @SaCheckPermission("BudgetEstimateItemClone")
  @PostMapping("/{id}/clone")
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
  public void export(BudgetEstimateItemSearchCriteria criteria, HttpServletResponse response) {
    budgetEstimateItemExcelService.export(criteria, response);
  }

  @SaCheckPermission("BudgetEstimateItemImport")
  @GetMapping({"/import-template", "/import/template"})
  @Operation(summary = "Tải file mẫu Import Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp")
  @Log(
      title = "[BudgetEstimateItem] Download Template",
      businessType = BusinessType.IMPORT,
      isSaveRequestData = false)
  public void downloadTemplate(HttpServletResponse response) {
    budgetEstimateItemExcelService.importTemplate(response);
  }

  @SaCheckPermission("BudgetEstimateItemImport")
  @PostMapping("/import/validate")
  @Operation(summary = "Validate file Excel Import")
  public R<Map<String, Object>> validateImport(@RequestParam("file") MultipartFile file) {
    return success(
        BudgetExcelControllerHelper.validateImport(
            file, budgetEstimateItemExcelService, budgetEstimateItemMapper));
  }

  @SaCheckPermission("BudgetEstimateItemImport")
  @PostMapping("/import")
  @Operation(summary = "Import dữ liệu Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp từ JSON")
  @Log(title = "[BudgetEstimateItem] Import Commit", businessType = BusinessType.IMPORT)
  public R<Map<String, Object>> importData(
      @RequestBody Map<String, List<BudgetEstimateItemData>> body) {
    return success(
        BudgetExcelControllerHelper.importData(
            body.get("data"),
            BudgetEstimateItemData::getId,
            id -> budgetEstimateItemBusiness.getInjectedRepo().existsById(id),
            budgetEstimateItemBusiness::create,
            budgetEstimateItemBusiness::update));
  }

  @SaCheckPermission(
      value = {"BudgetEstimateItemList", "BudgetAllocationList", "BudgetDashboardQuery"},
      mode = SaMode.OR)
  @GetMapping("/tree")
  @Operation(summary = "Lấy cây Danh mục Dự toán Thu - Chi đa cấp")
  public R<List<Map<String, Object>>> getEstimateTree(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear,
      @RequestParam(value = "itemType", required = false) String itemType) {
    return success(budgetEstimateItemBusiness.getTree(budgetYear, itemType));
  }

  @SaCheckPermission(
      value = {"BudgetEstimateItemCreate", "BudgetEstimateItemEdit"},
      mode = SaMode.OR)
  @PostMapping("/tree")
  @Operation(summary = "Đồng bộ toàn bộ Cây Dự toán Thu - Chi bằng Smart Diff-Based Upsert")
  @Log(title = "[BudgetEstimateItem] Save Tree", businessType = BusinessType.UPDATE)
  public R<Map<String, Object>> saveEstimateTree(@RequestBody BudgetEstimateTreeSaveRequest request) {
    return success(budgetEstimateTreeSyncBusiness.saveTree(request));
  }

  @SaCheckPermission(
      value = {"BudgetEstimateItemEdit", "BudgetEstimateItemSort"},
      mode = SaMode.OR)
  @PutMapping("/{id}/move")
  @Operation(summary = "Di chuyển node trên Cây Dự toán (kéo-thả đổi cha-con an toàn)")
  @Log(title = "[BudgetEstimateItem] Move Node", businessType = BusinessType.UPDATE)
  public R<BudgetEstimateItemData> moveEstimateNode(
      @PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
    Long newParentId =
        body != null && body.get("newParentId") != null
            ? Long.valueOf(String.valueOf(body.get("newParentId")))
            : null;
    Integer sortOrder =
        body != null && body.get("sortOrder") != null
            ? Integer.valueOf(String.valueOf(body.get("sortOrder")))
            : null;
    return success(budgetEstimateTreeSyncBusiness.moveNode(id, newParentId, sortOrder));
  }

  @SaCheckPermission("BudgetEstimateItemCreate")
  @PostMapping("/clone-year")
  @Operation(summary = "Nhân bản Cây Dự toán liên năm (N-1 -> N) kèm hệ số điều chỉnh (%)")
  @Log(title = "[BudgetEstimateItem] Clone Year Tree", businessType = BusinessType.INSERT)
  public R<Map<String, Object>> cloneYearTree(@RequestBody Map<String, Object> body) {
    return success(budgetEstimateTreeOpsBusiness.cloneYearTree(body));
  }

  @SaCheckPermission("BudgetEstimateItemList")
  @GetMapping("/health-check")
  @Operation(summary = "Kiểm tra sức khỏe Cây Dự toán (cân đối Thu - Chi, công thức, mục lục NSNN)")
  public R<Map<String, Object>> evaluateTreeHealth(
      @RequestParam(value = "budgetYear", required = false) Integer budgetYear) {
    return success(budgetEstimateTreeOpsBusiness.evaluateTreeHealth(budgetYear));
  }
}
