package vn.tr.budget.business;

import cn.hutool.core.util.StrUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.service.BudgetEstimateItemService;
import vn.tr.budget.data.validator.BudgetEstimateFormulaValidator;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.core.exception.ServiceException;

/**
 * Nghiệp vụ nhân bản Cây Dự toán liên năm (N-1 → N) kèm hệ số điều chỉnh (%) và kiểm tra sức khỏe
 * cân đối Thu - Chi / công thức / mục lục NSNN.
 *
 * @author tyran8x
 */
@Service("budgetEstimateTreeOpsBusiness")
@RequiredArgsConstructor
@Transactional
public class BudgetEstimateTreeOpsBusiness {

  private final BudgetEstimateItemService estimateItemService;
  private final BudgetEstimateItemBusiness estimateItemBusiness;

  /** Lọc danh sách chỉ tiêu theo năm và loại (giữ bản ghi dùng chung budgetYear=null nếu chưa ghi đè). */
  public static List<BudgetEstimateItem> filterByYearAndType(
      List<BudgetEstimateItem> all, Integer year, String itemType) {
    Map<String, BudgetEstimateItem> resolvedByCode = new LinkedHashMap<>();
    for (BudgetEstimateItem item : all) {
      if (itemType != null && !itemType.equalsIgnoreCase(item.getItemType())) {
        continue;
      }
      if (year != null && item.getBudgetYear() != null && !year.equals(item.getBudgetYear())) {
        continue;
      }
      String key =
          item.getCode() != null ? item.getCode().toUpperCase(Locale.ROOT) : "#" + item.getId();
      BudgetEstimateItem prev = resolvedByCode.get(key);
      if (prev == null || (prev.getBudgetYear() == null && item.getBudgetYear() != null)) {
        resolvedByCode.put(key, item);
      }
    }
    return new ArrayList<>(resolvedByCode.values());
  }

  /** Nhân bản cây Dự toán từ năm N-1 sang năm N kèm hệ số điều chỉnh định mức (%). */
  public Map<String, Object> cloneYearTree(Map<String, Object> body) {
    int currentYear = LocalDate.now().getYear();
    int sourceYear = toInt(body != null ? body.get("sourceYear") : null, currentYear - 1);
    int targetYear = toInt(body != null ? body.get("targetYear") : null, currentYear);
    if (sourceYear == targetYear) {
      throw new ServiceException("Năm nguồn và năm đích phải khác nhau khi sao chép cây.");
    }
    BigDecimal ratePercent = toBigDecimal(body != null ? body.get("adjustmentRatePercent") : null);
    BigDecimal multiplier =
        BigDecimal.ONE.add(ratePercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
    String filterType = normalizeType(body != null ? body.get("applyToItemType") : null);
    boolean overwrite = body != null && Boolean.TRUE.equals(body.get("overwrite"));

    List<BudgetEstimateItem> all = estimateItemService.getRepo().findAll();
    List<BudgetEstimateItem> sourceItems = filterByYearAndType(all, sourceYear, filterType);
    sourceItems.sort(
        Comparator.comparing(
                BudgetEstimateItem::getLevelDepth, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                BudgetEstimateItem::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                BudgetEstimateItem::getId, Comparator.nullsLast(Comparator.naturalOrder())));

    Map<String, BudgetEstimateItem> existingTargetByCode = new LinkedHashMap<>();
    for (BudgetEstimateItem e : all) {
      if (Objects.equals(e.getBudgetYear(), targetYear) && e.getCode() != null) {
        existingTargetByCode.put(e.getCode().toUpperCase(Locale.ROOT), e);
      }
    }

    Map<Long, BudgetEstimateItem> clonedByOldId = new LinkedHashMap<>();
    int clonedCount = 0;
    int skippedCount = 0;
    for (BudgetEstimateItem src : sourceItems) {
      String codeKey = src.getCode() != null ? src.getCode().toUpperCase(Locale.ROOT) : "";
      BudgetEstimateItem existingTarget = existingTargetByCode.get(codeKey);
      if (existingTarget != null && !overwrite) {
        clonedByOldId.put(src.getId(), existingTarget);
        skippedCount++;
        continue;
      }
      BudgetEstimateItem target = copyItemForYear(src, existingTarget, targetYear, multiplier);
      BudgetEstimateItem mappedParent =
          src.getParentId() != null ? clonedByOldId.get(src.getParentId()) : null;
      if (mappedParent != null) {
        target.setParentId(mappedParent.getId());
        target.setParentCode(mappedParent.getCode());
        target.setParentName(mappedParent.getName());
      } else {
        target.setParentId(null);
        target.setParentCode(src.getParentCode());
        target.setParentName(src.getParentName());
      }
      BudgetEstimateItem saved = estimateItemService.save(target);
      clonedByOldId.put(src.getId(), saved);
      clonedCount++;
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sourceYear", sourceYear);
    result.put("targetYear", targetYear);
    result.put("adjustmentRatePercent", ratePercent);
    result.put("clonedCount", clonedCount);
    result.put("skippedCount", skippedCount);
    result.put("tree", estimateItemBusiness.getTree(targetYear, filterType));
    return result;
  }

  /** Đánh giá sức khỏe cây Dự toán (cân đối Thu - Chi, công thức, mục lục NSNN, vượt trần). */
  @Transactional(readOnly = true)
  public Map<String, Object> evaluateTreeHealth(Integer budgetYear) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    List<BudgetEstimateItem> scoped =
        filterByYearAndType(estimateItemService.getRepo().findAll(), year, null);
    Map<Long, List<BudgetEstimateItem>> childrenByParent = new LinkedHashMap<>();
    Set<String> allCodes = new HashSet<>();
    Map<String, String> formulaByCode = new LinkedHashMap<>();
    for (BudgetEstimateItem item : scoped) {
      if (item.getParentId() != null) {
        childrenByParent.computeIfAbsent(item.getParentId(), k -> new ArrayList<>()).add(item);
      }
      if (StrUtil.isNotBlank(item.getCode())) {
        String upper = item.getCode().trim().toUpperCase(Locale.ROOT);
        allCodes.add(upper);
        formulaByCode.put(upper, item.getFormula());
      }
    }

    List<Map<String, Object>> issues = new ArrayList<>();
    BigDecimal totalRevenue = BigDecimal.ZERO;
    BigDecimal totalExpenditure = BigDecimal.ZERO;
    int headerCount = 0;
    int leafCount = 0;

    for (BudgetEstimateItem item : scoped) {
      List<BudgetEstimateItem> children = childrenByParent.getOrDefault(item.getId(), List.of());
      boolean isHeader = Boolean.TRUE.equals(item.getIsHeader()) || !children.isEmpty();
      if (isHeader) {
        headerCount++;
      } else {
        leafCount++;
        BigDecimal amt =
            item.getNormStandardAmount() != null ? item.getNormStandardAmount() : BigDecimal.ZERO;
        if ("REVENUE".equalsIgnoreCase(item.getItemType())) {
          totalRevenue = totalRevenue.add(amt);
        } else {
          totalExpenditure = totalExpenditure.add(amt);
        }
        if (StrUtil.isBlank(item.getCategoryCode())) {
          issues.add(
              issue(
                  "WARNING",
                  "MISSING_CATEGORY",
                  item.getCode(),
                  "Chỉ tiêu lá [" + item.getCode() + "] chưa liên kết Khoản mục Mục lục NSNN."));
        }
      }
      for (String formulaErr :
          BudgetEstimateFormulaValidator.inspectFormula(
              item.getFormula(), item.getCode(), allCodes)) {
        issues.add(issue("ERROR", "INVALID_FORMULA", item.getCode(), formulaErr));
      }
      if (item.getMaxCeilingAmount() != null) {
        BigDecimal effectiveNorm = computeRolledNorm(item, childrenByParent, new HashSet<>());
        if (effectiveNorm.compareTo(item.getMaxCeilingAmount()) > 0) {
          issues.add(
              issue(
                  "ERROR",
                  "EXCEED_CEILING",
                  item.getCode(),
                  "Chỉ tiêu ["
                      + item.getCode()
                      + "] vượt trần tối đa "
                      + item.getMaxCeilingAmount()
                      + "."));
        }
      }
    }

    for (String cycleErr : BudgetEstimateFormulaValidator.validateFormulaGraph(formulaByCode)) {
      issues.add(issue("ERROR", "FORMULA_CYCLE", null, cycleErr));
    }

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("budgetYear", year);
    summary.put("totalNodes", scoped.size());
    summary.put("headerCount", headerCount);
    summary.put("leafCount", leafCount);
    summary.put("totalRevenueNorm", totalRevenue);
    summary.put("totalExpenditureNorm", totalExpenditure);
    summary.put("balanceGap", totalRevenue.subtract(totalExpenditure));
    summary.put("issueCount", issues.size());
    summary.put("issues", issues);
    return summary;
  }

  private static BudgetEstimateItem copyItemForYear(
      BudgetEstimateItem src,
      BudgetEstimateItem existingTarget,
      int targetYear,
      BigDecimal multiplier) {
    BudgetEstimateItem target =
        existingTarget != null
            ? existingTarget
            : BudgetEstimateItem.builder().code(src.getCode()).build();
    target.setBudgetYear(targetYear);
    target.setName(src.getName());
    target.setDescription(src.getDescription());
    target.setStatus(src.getStatus() != null ? src.getStatus() : LifecycleStatus.ACTIVE);
    target.setSortOrder(src.getSortOrder());
    target.setItemType(src.getItemType());
    target.setNumberingStyle(src.getNumberingStyle());
    target.setLevelDepth(src.getLevelDepth());
    target.setIsHeader(src.getIsHeader());
    target.setFormula(src.getFormula());
    target.setCategoryId(src.getCategoryId());
    target.setCategoryCode(src.getCategoryCode());
    target.setCategoryName(src.getCategoryName());
    target.setSubItemCode(src.getSubItemCode());
    target.setFundingSourceCode(src.getFundingSourceCode());
    target.setAutonomyType(src.getAutonomyType());
    target.setUnitOfMeasure(src.getUnitOfMeasure());
    target.setRequireVoucher(src.getRequireVoucher());
    target.setDisplayIndex(src.getDisplayIndex());
    target.setTreePath(src.getTreePath());
    target.setLegalBasisRef(src.getLegalBasisRef());
    target.setNormStandardAmount(scaleAmount(src.getNormStandardAmount(), multiplier));
    target.setMaxCeilingAmount(scaleAmount(src.getMaxCeilingAmount(), multiplier));
    return target;
  }

  private BigDecimal computeRolledNorm(
      BudgetEstimateItem node, Map<Long, List<BudgetEstimateItem>> childrenByParent, Set<Long> seen) {
    List<BudgetEstimateItem> children = childrenByParent.getOrDefault(node.getId(), List.of());
    if (children.isEmpty() || !seen.add(node.getId())) {
      return node.getNormStandardAmount() != null ? node.getNormStandardAmount() : BigDecimal.ZERO;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (BudgetEstimateItem child : children) {
      sum = sum.add(computeRolledNorm(child, childrenByParent, seen));
    }
    return sum;
  }

  private static String normalizeType(Object raw) {
    if (raw == null) return null;
    String s = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
    return ("ALL".equals(s) || s.isEmpty()) ? null : s;
  }

  private static BigDecimal scaleAmount(BigDecimal value, BigDecimal multiplier) {
    return value == null ? BigDecimal.ZERO : value.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal toBigDecimal(Object val) {
    if (val == null) return BigDecimal.ZERO;
    try {
      return new BigDecimal(String.valueOf(val));
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  private static int toInt(Object val, int fallback) {
    if (val == null) return fallback;
    try {
      return Integer.parseInt(String.valueOf(val));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static Map<String, Object> issue(String severity, String type, String code, String message) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("severity", severity);
    m.put("issueType", type);
    m.put("code", code);
    m.put("message", message);
    return m;
  }
}
