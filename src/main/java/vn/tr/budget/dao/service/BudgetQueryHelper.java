package vn.tr.budget.dao.service;

import cn.hutool.core.util.StrUtil;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import vn.tr.common.core.context.HeaderContext;
import vn.tr.common.core.exception.ServiceException;
import vn.tr.common.satoken.utils.LoginHelper;
import vn.tr.common.security.abac.OrgSubtreeAuthorizationHelper;

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

  /**
   * Giải mã mã ứng dụng (Tenant AppCode) hiện tại theo nguyên tắc Fail-Closed.
   *
   * <p>Ưu tiên {@link HeaderContext#getAppCode()}, dự phòng {@link LoginHelper#getAppCode()}. Tuyệt
   * đối không rơi ngầm về hằng số mặc định gây nguy cơ rò rỉ dữ liệu đa tenant.
   */
  public static String resolveCurrentAppCode() {
    String appCode = HeaderContext.getAppCode();
    if (StrUtil.isNotBlank(appCode)) {
      return appCode.trim();
    }
    try {
      String loginAppCode = LoginHelper.getAppCode();
      if (StrUtil.isNotBlank(loginAppCode)) {
        return loginAppCode.trim();
      }
    } catch (Exception ignored) {
      // Không có phiên Sa-Token hoạt động
    }
    throw new ServiceException(
        "Thiếu ngữ cảnh ứng dụng (X-App-Code) — từ chối truy cập đa tenant (fail-closed).", 400);
  }

  /**
   * Tạo điều kiện lọc ABAC cây đơn vị ({@link OrgSubtreeAuthorizationHelper.OrgSubtreeScope}) cho
   * JPA Specification.
   *
   * <p>Trả về {@code null} nếu caller không bị hạn chế (SuperAdmin / S2S). Trả về {@code
   * cb.disjunction()} (fail-closed) nếu scope bị khóa hoàn toàn (DENY_ALL).
   */
  public static <T> Predicate buildOrgSubtreePredicate(
      CriteriaBuilder cb,
      Root<T> root,
      OrgSubtreeAuthorizationHelper.OrgSubtreeScope scope,
      String nodeIdField,
      String nodeCodeField,
      boolean allowUnscopedRecords) {
    if (scope == null || !scope.restricted()) {
      return null;
    }
    Set<Long> allowedIds = scope.allowedNodeIds();
    Set<String> allowedCodes = scope.allowedNodeCodes();
    List<Predicate> branches = new ArrayList<>();

    if (allowedIds != null && !allowedIds.isEmpty() && StrUtil.isNotBlank(nodeIdField)) {
      branches.add(root.get(nodeIdField).in(allowedIds));
    }
    if (allowedCodes != null && !allowedCodes.isEmpty() && StrUtil.isNotBlank(nodeCodeField)) {
      Set<String> lowerCodes =
          allowedCodes.stream()
              .filter(StrUtil::isNotBlank)
              .map(c -> c.trim().toLowerCase(Locale.ROOT))
              .collect(Collectors.toSet());
      if (!lowerCodes.isEmpty()) {
        branches.add(cb.lower(root.get(nodeCodeField)).in(lowerCodes));
      }
    }
    if (allowUnscopedRecords && StrUtil.isNotBlank(nodeIdField)) {
      Predicate nullNodeId = cb.isNull(root.get(nodeIdField));
      if (StrUtil.isNotBlank(nodeCodeField)) {
        branches.add(
            cb.and(
                nullNodeId,
                cb.or(
                    cb.isNull(root.get(nodeCodeField)),
                    cb.equal(cb.trim(root.get(nodeCodeField)), ""))));
      } else {
        branches.add(nullNodeId);
      }
    }

    if (branches.isEmpty()) {
      return cb.disjunction();
    }
    return cb.or(branches.toArray(new Predicate[0]));
  }
}
