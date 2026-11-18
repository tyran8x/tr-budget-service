package vn.tr.budget.dao.service;

import cn.hutool.core.util.StrUtil;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import vn.tr.common.core.constant.AppCodeConstants;
import vn.tr.common.core.context.HeaderContext;

/**
 * Tiện ích chuẩn hóa truy vấn JPA an toàn cho phân hệ Ngân sách: kiểm soát whitelist sortBy chống
 * PropertyReferenceException và chuẩn hóa app_code cho native query.
 *
 * @author tyran8x
 */
public final class BudgetQueryHelper {

  public static final Set<String> COMMON_SORT_FIELDS =
      Set.of("id", "code", "name", "status", "sortOrder", "createdAt", "updatedAt");

  public static final Set<String> CATEGORY_SORT_FIELDS =
      Set.of(
          "id",
          "code",
          "name",
          "status",
          "sortOrder",
          "categoryGroup",
          "budgetYear",
          "chapterCode",
          "typeCode",
          "clauseCode",
          "levelDepth",
          "createdAt",
          "updatedAt");

  public static final Set<String> ESTIMATE_ITEM_SORT_FIELDS =
      Set.of(
          "id",
          "code",
          "name",
          "status",
          "sortOrder",
          "budgetYear",
          "itemType",
          "levelDepth",
          "normStandardAmount",
          "createdAt",
          "updatedAt");

  public static final Set<String> UNIT_CONFIG_SORT_FIELDS =
      Set.of(
          "id",
          "code",
          "name",
          "status",
          "sortOrder",
          "nodeId",
          "nodeCode",
          "unitLevel",
          "unitType",
          "selfApproveLimitAmount",
          "createdAt",
          "updatedAt");

  public static final Set<String> ALLOCATION_SORT_FIELDS =
      Set.of(
          "id",
          "code",
          "name",
          "status",
          "sortOrder",
          "budgetYear",
          "nodeId",
          "nodeCode",
          "workflowStatus",
          "initialAmount",
          "effectiveAmount",
          "disbursedAmount",
          "availableAmount",
          "disbursementRatePercent",
          "createdAt",
          "updatedAt");

  public static final Set<String> TRANSACTION_SORT_FIELDS =
      Set.of(
          "id",
          "code",
          "name",
          "status",
          "sortOrder",
          "budgetYear",
          "txType",
          "txDate",
          "amount",
          "approvalStatus",
          "createdAt",
          "updatedAt");

  public static final Set<String> DISBURSEMENT_SORT_FIELDS =
      Set.of(
          "id",
          "code",
          "name",
          "status",
          "sortOrder",
          "budgetYear",
          "disbursementType",
          "requestedAmount",
          "amount",
          "approvedAmount",
          "settledAmount",
          "slaDueDate",
          "approvalStatus",
          "createdAt",
          "updatedAt");

  public static final Set<String> REPORT_SORT_FIELDS =
      Set.of(
          "id",
          "code",
          "name",
          "status",
          "sortOrder",
          "budgetYear",
          "reportPeriod",
          "periodNumber",
          "reportDate",
          "workflowStatus",
          "disbursementRatePercent",
          "createdAt",
          "updatedAt");

  private BudgetQueryHelper() {}

  public static Sort buildSafeSort(String sortBy, String sortDir, Set<String> allowedFields) {
    if (StrUtil.isBlank(sortBy)) {
      return Sort.unsorted();
    }
    String candidate = sortBy.trim();
    Set<String> whitelist = allowedFields != null ? allowedFields : COMMON_SORT_FIELDS;
    if (!whitelist.contains(candidate)) {
      return Sort.by(Sort.Direction.DESC, "id");
    }
    Sort.Direction direction =
        "ASC".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
    return Sort.by(direction, candidate);
  }

  public static Pageable sanitizePageable(Pageable pageable, Set<String> allowedFields) {
    if (pageable == null || pageable.isUnpaged() || pageable.getSort().isUnsorted()) {
      return pageable;
    }
    Set<String> whitelist = allowedFields != null ? allowedFields : COMMON_SORT_FIELDS;
    List<Sort.Order> safeOrders =
        pageable.getSort().stream()
            .filter(order -> whitelist.contains(order.getProperty()))
            .collect(Collectors.toList());
    Sort safeSort =
        safeOrders.isEmpty() ? Sort.by(Sort.Direction.DESC, "id") : Sort.by(safeOrders);
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), safeSort);
  }

  public static String resolveCurrentAppCode() {
    String appCode = HeaderContext.getAppCode();
    return StrUtil.isNotBlank(appCode) ? appCode.trim() : AppCodeConstants.BUDGET;
  }
}
