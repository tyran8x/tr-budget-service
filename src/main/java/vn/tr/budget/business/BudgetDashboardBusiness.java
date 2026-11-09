package vn.tr.budget.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.common.feign.identity.TemporalTargetScopeHelper;

/**
 * Nghiệp vụ Dashboard Thống kê Dự toán Thu - Chi, Ma trận Kinh phí theo Đơn vị,
 * Cây Dự toán Đa cấp, Bảng điểm Kỷ luật Giải ngân (Thang 100 điểm) và Cố vấn AI Tài chính.
 *
 * @author tyran8x
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class BudgetDashboardBusiness {

  private final BudgetAllocationRepo budgetAllocationRepo;
  private final BudgetTransactionRepo budgetTransactionRepo;
  private final BudgetDisbursementRepo budgetDisbursementRepo;
  private final BudgetEstimateItemRepo budgetEstimateItemRepo;

  @Autowired(required = false)
  private TemporalTargetScopeHelper temporalScopeHelper =
      new TemporalTargetScopeHelper();
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private vn.tr.common.feign.identity.UnifiedIdentityResolver unifiedIdentityResolver;

  @jakarta.annotation.PostConstruct
  void initTemporalScopeHelper() {
      if (temporalScopeHelper != null && temporalScopeHelper.getIdentityResolver() == null && unifiedIdentityResolver != null) {
          temporalScopeHelper.setIdentityResolver(unifiedIdentityResolver);
      }
  }

  public Map<String, Object> getOverview(Integer budgetYear, Long nodeId, String periodType) {
    return getOverview(budgetYear, nodeId, periodType, null, null);
  }

  public Map<String, Object> getOverview(
      Integer budgetYear,
      Long nodeId,
      String periodType,
      String agencyCode,
      String temporalRegime) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    String normAgency =
        temporalScopeHelper != null ? temporalScopeHelper.normalizeAgencyCode(agencyCode) : null;
    String effectiveRegime =
        temporalScopeHelper != null
            ? temporalScopeHelper.resolveTemporalRegime(null, null, year, temporalRegime)
            : "POST_MERGER";
    Set<String> allowedCodes =
        normAgency != null && temporalScopeHelper != null
            ? temporalScopeHelper.resolveMatchingNodeCodesForAgency(normAgency, effectiveRegime)
            : Set.of();
    Set<Long> allowedIds =
        normAgency != null && temporalScopeHelper != null
            ? temporalScopeHelper.resolveMatchingNodeIdsForAgency(normAgency, effectiveRegime)
            : Set.of();

    List<BudgetAllocation> allocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);

    BigDecimal totalRevenueEstimate = BigDecimal.ZERO;
    BigDecimal totalRevenueActual = BigDecimal.ZERO;
    BigDecimal totalInitial = BigDecimal.ZERO;
    BigDecimal totalSupplement = BigDecimal.ZERO;
    BigDecimal totalCarryOverIn = BigDecimal.ZERO;
    BigDecimal totalCarryOverOut = BigDecimal.ZERO;
    BigDecimal totalTransferNet = BigDecimal.ZERO;
    BigDecimal totalEffective = BigDecimal.ZERO;
    BigDecimal totalCommitted = BigDecimal.ZERO;
    BigDecimal totalAdvanced = BigDecimal.ZERO;
    BigDecimal totalDisbursed = BigDecimal.ZERO;
    BigDecimal totalSettled = BigDecimal.ZERO;
    BigDecimal totalAvailable = BigDecimal.ZERO;

    int totalAllocations = 0;
    int approvedAllocations = 0;
    int pendingAllocations = 0;

    for (BudgetAllocation a : allocs) {
      if (nodeId != null && !nodeId.equals(a.getNodeId())) {
        continue;
      }
      if (normAgency != null && !matchesAllocationAgencyScope(a, normAgency, allowedCodes, allowedIds)) {
        continue;
      }
      if (periodType != null
          && !periodType.isBlank()
          && !periodType.equalsIgnoreCase(a.getPeriodType())) {
        continue;
      }
      totalAllocations++;
      if ("APPROVED".equalsIgnoreCase(a.getWorkflowStatus())
          || "EXECUTING".equalsIgnoreCase(a.getWorkflowStatus())) {
        approvedAllocations++;
      } else if ("SUBMITTED".equalsIgnoreCase(a.getWorkflowStatus())) {
        pendingAllocations++;
      }

      if ("REVENUE".equalsIgnoreCase(a.getItemType())) {
        totalRevenueEstimate = totalRevenueEstimate.add(safe(a.getEffectiveAmount()));
        totalRevenueActual = totalRevenueActual.add(safe(a.getDisbursedAmount()));
      } else {
        totalInitial = totalInitial.add(safe(a.getInitialAmount()));
        totalSupplement = totalSupplement.add(safe(a.getSupplementAmount()));
        totalCarryOverIn = totalCarryOverIn.add(safe(a.getCarryOverInAmount()));
        totalCarryOverOut = totalCarryOverOut.add(safe(a.getCarryOverOutAmount()));
        totalTransferNet = totalTransferNet.add(safe(a.getTransferNetAmount()));
        totalEffective = totalEffective.add(safe(a.getEffectiveAmount()));
        totalCommitted = totalCommitted.add(safe(a.getCommittedAmount()));
        totalAdvanced = totalAdvanced.add(safe(a.getAdvancedAmount()));
        totalDisbursed = totalDisbursed.add(safe(a.getDisbursedAmount()));
        totalSettled = totalSettled.add(safe(a.getSettledAmount()));
        totalAvailable = totalAvailable.add(safe(a.getAvailableAmount()));
      }
    }

    BigDecimal disbursementRate =
        totalEffective.compareTo(BigDecimal.ZERO) > 0
            ? totalDisbursed
                .multiply(new BigDecimal("100"))
                .divide(totalEffective, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    BigDecimal settlementRate =
        totalDisbursed.compareTo(BigDecimal.ZERO) > 0
            ? totalSettled
                .multiply(new BigDecimal("100"))
                .divide(totalDisbursed, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    List<BudgetTransaction> txs = budgetTransactionRepo.findByBudgetYearOrderByIdDesc(year);
    List<BudgetDisbursement> disbs = budgetDisbursementRepo.findByBudgetYearOrderByIdDesc(year);

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("budgetYear", year);
    res.put("agencyCode", normAgency != null ? normAgency : "ALL");
    res.put("temporalRegime", effectiveRegime);
    if (temporalScopeHelper != null) {
      res.put("temporalRegimeLabel", temporalScopeHelper.getRegimeLabel(effectiveRegime));
    }
    res.put("totalAllocations", totalAllocations);
    res.put("approvedAllocations", approvedAllocations);
    res.put("pendingAllocations", pendingAllocations);
    res.put("totalTransactions", txs.size());
    res.put("totalDisbursements", disbs.size());
    res.put("totalRevenueEstimate", totalRevenueEstimate);
    res.put("totalRevenueActual", totalRevenueActual);
    res.put("totalInitialBudget", totalInitial);
    res.put("totalSupplementBudget", totalSupplement);
    res.put("totalCarryOverInBudget", totalCarryOverIn);
    res.put("totalCarryOverOutBudget", totalCarryOverOut);
    res.put("totalTransferNetBudget", totalTransferNet);
    res.put("totalEffectiveBudget", totalEffective);
    res.put("totalCommittedBudget", totalCommitted);
    res.put("totalAdvancedBudget", totalAdvanced);
    res.put("totalDisbursedBudget", totalDisbursed);
    res.put("totalSettledBudget", totalSettled);
    res.put("totalAvailableBudget", totalAvailable);
    res.put("disbursementRatePercent", disbursementRate);
    res.put("settlementRatePercent", settlementRate);
    return res;
  }

  public List<Map<String, Object>> getUnitMatrix(Integer budgetYear) {
    return getUnitMatrix(budgetYear, null, null);
  }

  public List<Map<String, Object>> getUnitMatrix(
      Integer budgetYear, String agencyCode, String temporalRegime) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    String normAgency =
        temporalScopeHelper != null ? temporalScopeHelper.normalizeAgencyCode(agencyCode) : null;
    String effectiveRegime =
        temporalScopeHelper != null
            ? temporalScopeHelper.resolveTemporalRegime(null, null, year, temporalRegime)
            : "POST_MERGER";
    Set<String> allowedCodes =
        normAgency != null && temporalScopeHelper != null
            ? temporalScopeHelper.resolveMatchingNodeCodesForAgency(normAgency, effectiveRegime)
            : Set.of();
    Set<Long> allowedIds =
        normAgency != null && temporalScopeHelper != null
            ? temporalScopeHelper.resolveMatchingNodeIdsForAgency(normAgency, effectiveRegime)
            : Set.of();

    List<BudgetAllocation> allocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);

    Map<String, Map<String, Object>> unitMap = new LinkedHashMap<>();
    for (BudgetAllocation a : allocs) {
      if ("REVENUE".equalsIgnoreCase(a.getItemType())) {
        continue;
      }
      if (normAgency != null && !matchesAllocationAgencyScope(a, normAgency, allowedCodes, allowedIds)) {
        continue;
      }

      var canonicalDept =
          temporalScopeHelper != null
              ? temporalScopeHelper.resolveCanonicalDepartmentForRollup(
                  normAgency, effectiveRegime, a.getNodeId(), a.getNodeCode(), a.getNodeName())
              : null;

      Long resolvedNodeId = canonicalDept != null ? canonicalDept.getNodeId() : a.getNodeId();
      String resolvedNodeCode = canonicalDept != null ? canonicalDept.getCode() : a.getNodeCode();
      String resolvedNodeName = canonicalDept != null ? canonicalDept.getName() : a.getNodeName();
      String key = resolvedNodeCode != null ? resolvedNodeCode : "NODE_" + resolvedNodeId;

      Map<String, Object> row =
          unitMap.computeIfAbsent(
              key,
              k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeId", resolvedNodeId);
                m.put("nodeCode", resolvedNodeCode);
                m.put("nodeName", resolvedNodeName);
                m.put("agencyCode", canonicalDept != null ? canonicalDept.getAgencyCode() : normAgency);
                m.put("temporalRegime", effectiveRegime);
                if (canonicalDept != null) {
                  m.put("transitionType", canonicalDept.getTransitionType());
                  m.put("transitionBadge", canonicalDept.getTransitionBadge());
                  m.put("predecessorNodeNames", canonicalDept.getPredecessorNodeNames());
                  m.put("leadPersonName", canonicalDept.getLeadPersonName());
                }
                m.put("allocationsCount", 0);
                m.put("initialAmount", BigDecimal.ZERO);
                m.put("supplementAmount", BigDecimal.ZERO);
                m.put("carryOverAmount", BigDecimal.ZERO);
                m.put("effectiveAmount", BigDecimal.ZERO);
                m.put("committedAmount", BigDecimal.ZERO);
                m.put("disbursedAmount", BigDecimal.ZERO);
                m.put("settledAmount", BigDecimal.ZERO);
                m.put("availableAmount", BigDecimal.ZERO);
                return m;
              });
      row.put("allocationsCount", ((Integer) row.get("allocationsCount")) + 1);
      row.put("initialAmount", ((BigDecimal) row.get("initialAmount")).add(safe(a.getInitialAmount())));
      row.put(
          "supplementAmount",
          ((BigDecimal) row.get("supplementAmount")).add(safe(a.getSupplementAmount())));
      row.put(
          "carryOverAmount",
          ((BigDecimal) row.get("carryOverAmount")).add(safe(a.getCarryOverInAmount())));
      row.put(
          "effectiveAmount",
          ((BigDecimal) row.get("effectiveAmount")).add(safe(a.getEffectiveAmount())));
      row.put(
          "committedAmount",
          ((BigDecimal) row.get("committedAmount")).add(safe(a.getCommittedAmount())));
      row.put(
          "disbursedAmount",
          ((BigDecimal) row.get("disbursedAmount")).add(safe(a.getDisbursedAmount())));
      row.put(
          "settledAmount", ((BigDecimal) row.get("settledAmount")).add(safe(a.getSettledAmount())));
      row.put(
          "availableAmount",
          ((BigDecimal) row.get("availableAmount")).add(safe(a.getAvailableAmount())));
    }

    for (Map<String, Object> row : unitMap.values()) {
      BigDecimal eff = (BigDecimal) row.get("effectiveAmount");
      BigDecimal disb = (BigDecimal) row.get("disbursedAmount");
      BigDecimal rate =
          eff.compareTo(BigDecimal.ZERO) > 0
              ? disb.multiply(new BigDecimal("100")).divide(eff, 2, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;
      row.put("disbursementRatePercent", rate);
    }

    return new ArrayList<>(unitMap.values());
  }

  private boolean matchesAllocationAgencyScope(
      BudgetAllocation a, String normAgency, Set<String> allowedCodes, Set<Long> allowedIds) {
    if (normAgency == null) {
      return true;
    }
    if (a.getNodeId() != null && allowedIds.contains(a.getNodeId())) {
      return true;
    }
    if (a.getNodeCode() != null && allowedCodes.contains(a.getNodeCode())) {
      return true;
    }
    if (temporalScopeHelper != null) {
      String inferred =
          temporalScopeHelper.inferAgencyCodeFromDepartment(
              a.getNodeId(), a.getNodeCode(), a.getNodeName());
      if (inferred != null) {
        return normAgency.equalsIgnoreCase(inferred)
            || ("SXD".equalsIgnoreCase(normAgency) && "SGTVT".equalsIgnoreCase(inferred));
      }
    }
    return true;
  }

  /**
   * Lấy dữ liệu Phạm vi Đơn vị chủ quản, Phòng ban, Nhân sự lập dự toán &amp; Tài khoản người dùng
   * theo Thời điểm Sáp nhập dùng cho Phân hệ Quản lý Kinh phí.
   */
  public Map<String, Object> getOrgScopeContext(
      String agencyCode,
      String temporalRegime,
      Integer budgetYear,
      Long nodeId,
      String nodeCode) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    String normAgency =
        temporalScopeHelper != null
            ? temporalScopeHelper.normalizeAgencyCode(agencyCode != null ? agencyCode : "SXD")
            : "SXD";
    String effectiveRegime =
        temporalScopeHelper != null
            ? temporalScopeHelper.resolveTemporalRegime(null, null, year, temporalRegime)
            : "POST_MERGER";

    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("budgetYear", year);
    ctx.put("agencyCode", normAgency);
    ctx.put("temporalRegime", effectiveRegime);
    if (temporalScopeHelper != null) {
      List<String> allowedTypes = temporalScopeHelper.getAllowedTargetTypesForModule("BUDGET");
      ctx.put("temporalRegimeLabel", temporalScopeHelper.getRegimeLabel(effectiveRegime));
      ctx.put("availableTargetTypes", temporalScopeHelper.getAvailableTargetTypes());
      ctx.put("allowedTargetTypes", allowedTypes);
      ctx.put("agencies", temporalScopeHelper.getAgenciesByRegime(effectiveRegime, year));
      ctx.put(
          "departments",
          temporalScopeHelper.getDepartmentsByAgencyAndRegime(
              normAgency, effectiveRegime, null, null, year));
      ctx.put(
          "targets",
          temporalScopeHelper.getTargetsByScopeAndAllowedTypes(
              normAgency, effectiveRegime, nodeId, nodeCode, null, allowedTypes));
      ctx.put(
          "persons",
          temporalScopeHelper.getPersonsByScope(
              normAgency, effectiveRegime, nodeId, nodeCode, null));
      ctx.put(
          "users",
          temporalScopeHelper.getUsersByScope(
              normAgency, effectiveRegime, nodeId, nodeCode, null, true));
      ctx.put(
          "consolidationPreview",
          temporalScopeHelper.buildMergerConsolidationPreview(normAgency, null, year));
    }
    return ctx;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> validateOrgScopeSelection(Map<String, Object> body) {
    String agencyCode =
        body != null && body.get("rootNodeCode") != null
            ? body.get("rootNodeCode").toString()
            : (body != null && body.get("agencyCode") != null
                ? body.get("agencyCode").toString()
                : null);
    String temporalRegime =
        body != null && body.get("temporalRegime") != null ? body.get("temporalRegime").toString() : null;
    Integer budgetYear =
        body != null && body.get("budgetYear") != null
            ? Integer.valueOf(body.get("budgetYear").toString())
            : null;
    Long nodeId =
        body != null && body.get("nodeId") != null ? Long.valueOf(body.get("nodeId").toString()) : null;
    String nodeCode = body != null && body.get("nodeCode") != null ? body.get("nodeCode").toString() : null;
    String nodeName = body != null && body.get("nodeName") != null ? body.get("nodeName").toString() : null;
    Long personId =
        body != null && body.get("personId") != null ? Long.valueOf(body.get("personId").toString()) : null;
    String personCode = body != null && body.get("personCode") != null ? body.get("personCode").toString() : null;
    String personName = body != null && body.get("personName") != null ? body.get("personName").toString() : null;
    Long userId =
        body != null && body.get("userId") != null ? Long.valueOf(body.get("userId").toString()) : null;
    String username = body != null && body.get("username") != null ? body.get("username").toString() : null;
    String targetType = body != null && body.get("targetType") != null ? body.get("targetType").toString() : null;
    Long targetId =
        body != null && body.get("targetId") != null ? Long.valueOf(body.get("targetId").toString()) : null;
    String targetCode = body != null && body.get("targetCode") != null ? body.get("targetCode").toString() : null;
    String targetName = body != null && body.get("targetName") != null ? body.get("targetName").toString() : null;
    List<String> allowedTargetTypes =
        body != null && body.get("allowedTargetTypes") instanceof List<?> rawTypes
            ? (List<String>) rawTypes
            : null;

    if (targetType != null || targetId != null || targetCode != null || targetName != null) {
      return temporalScopeHelper.validatePolymorphicTargetScope(
          agencyCode,
          temporalRegime,
          null,
          null,
          budgetYear,
          nodeId,
          nodeCode,
          nodeName,
          targetType,
          targetId != null ? targetId : (personId != null ? personId : userId),
          targetCode != null ? targetCode : (personCode != null ? personCode : username),
          targetName != null ? targetName : personName,
          allowedTargetTypes != null
              ? allowedTargetTypes
              : temporalScopeHelper.getAllowedTargetTypesForModule("BUDGET"));
    }

    return temporalScopeHelper.validateScopeSelection(
        agencyCode,
        temporalRegime,
        null,
        null,
        budgetYear,
        nodeId,
        nodeCode,
        nodeName,
        personId,
        personCode,
        personName,
        userId,
        username);
  }

  public List<Map<String, Object>> getRevenueExpenditureTree(Integer budgetYear) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    List<BudgetEstimateItem> items = new ArrayList<>(budgetEstimateItemRepo.findAll());
    items.sort(
        Comparator.comparing(
                BudgetEstimateItem::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                BudgetEstimateItem::getCode, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                BudgetEstimateItem::getId, Comparator.nullsLast(Comparator.naturalOrder())));

    List<BudgetAllocation> allocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);

    Map<Long, BigDecimal> itemEffective = new LinkedHashMap<>();
    Map<Long, BigDecimal> itemDisbursed = new LinkedHashMap<>();
    Map<Long, BigDecimal> itemAvailable = new LinkedHashMap<>();
    Map<String, Long> codeToIdMap = new LinkedHashMap<>();
    for (BudgetEstimateItem item : items) {
      if (item.getId() != null && item.getCode() != null) {
        codeToIdMap.put(item.getCode(), item.getId());
      }
    }

    for (BudgetAllocation a : allocs) {
      Long iid = a.getEstimateItemId();
      if (iid == null && a.getEstimateItemCode() != null) {
        iid = codeToIdMap.get(a.getEstimateItemCode());
      }
      if (iid == null) {
        continue;
      }
      itemEffective.put(
          iid, itemEffective.getOrDefault(iid, BigDecimal.ZERO).add(safe(a.getEffectiveAmount())));
      itemDisbursed.put(
          iid, itemDisbursed.getOrDefault(iid, BigDecimal.ZERO).add(safe(a.getDisbursedAmount())));
      itemAvailable.put(
          iid, itemAvailable.getOrDefault(iid, BigDecimal.ZERO).add(safe(a.getAvailableAmount())));
    }

    Map<Long, Map<String, Object>> nodeById = new LinkedHashMap<>();
    List<Map<String, Object>> allNodes = new ArrayList<>();

    for (BudgetEstimateItem item : items) {
      BigDecimal eff = itemEffective.getOrDefault(item.getId(), BigDecimal.ZERO);
      BigDecimal disb = itemDisbursed.getOrDefault(item.getId(), BigDecimal.ZERO);
      BigDecimal avail = itemAvailable.getOrDefault(item.getId(), BigDecimal.ZERO);

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", item.getId());
      row.put("code", item.getCode());
      row.put("name", item.getName());
      row.put("parentId", item.getParentId());
      row.put("parentCode", item.getParentCode());
      row.put("itemType", item.getItemType());
      row.put("numberingStyle", item.getNumberingStyle());
      row.put("levelDepth", item.getLevelDepth());
      row.put("isHeader", item.getIsHeader());
      row.put("categoryCode", item.getCategoryCode());
      row.put("categoryName", item.getCategoryName());
      row.put("effectiveAmount", eff);
      row.put("disbursedAmount", disb);
      row.put("availableAmount", avail);
      row.put("disbursementRatePercent", BigDecimal.ZERO);
      row.put("children", new ArrayList<Map<String, Object>>());
      if (item.getId() != null) {
        nodeById.put(item.getId(), row);
      }
      allNodes.add(row);
    }

    List<Map<String, Object>> roots = new ArrayList<>();
    for (Map<String, Object> row : allNodes) {
      Long parentId = (Long) row.get("parentId");
      if (parentId == null && row.get("parentCode") != null) {
        parentId = codeToIdMap.get(String.valueOf(row.get("parentCode")));
      }
      if (parentId != null && nodeById.containsKey(parentId) && !parentId.equals(row.get("id"))) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children =
            (List<Map<String, Object>>) nodeById.get(parentId).get("children");
        children.add(row);
      } else {
        roots.add(row);
      }
    }

    for (Map<String, Object> root : roots) {
      rollupEstimateTreeMetrics(root);
    }

    return allNodes;
  }

  @SuppressWarnings("unchecked")
  private void rollupEstimateTreeMetrics(Map<String, Object> node) {
    List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
    BigDecimal eff = safe((BigDecimal) node.get("effectiveAmount"));
    BigDecimal disb = safe((BigDecimal) node.get("disbursedAmount"));
    BigDecimal avail = safe((BigDecimal) node.get("availableAmount"));

    if (children != null && !children.isEmpty()) {
      for (Map<String, Object> child : children) {
        rollupEstimateTreeMetrics(child);
        eff = eff.add(safe((BigDecimal) child.get("effectiveAmount")));
        disb = disb.add(safe((BigDecimal) child.get("disbursedAmount")));
        avail = avail.add(safe((BigDecimal) child.get("availableAmount")));
      }
    }

    BigDecimal rate =
        eff.compareTo(BigDecimal.ZERO) > 0
            ? disb.multiply(new BigDecimal("100")).divide(eff, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    node.put("effectiveAmount", eff);
    node.put("disbursedAmount", disb);
    node.put("availableAmount", avail);
    node.put("disbursementRatePercent", rate);
  }

  public List<Map<String, Object>> getDisbursementScorecard(Integer budgetYear) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    List<Map<String, Object>> matrix = getUnitMatrix(year);
    List<BudgetDisbursement> disbs = budgetDisbursementRepo.findByBudgetYearOrderByIdDesc(year);
    List<Map<String, Object>> scorecards = new ArrayList<>();

    for (Map<String, Object> unit : matrix) {
      Long unitNodeId = (Long) unit.get("nodeId");
      BigDecimal init = (BigDecimal) unit.get("initialAmount");
      BigDecimal eff = (BigDecimal) unit.get("effectiveAmount");
      BigDecimal disb = (BigDecimal) unit.get("disbursedAmount");
      BigDecimal settled = (BigDecimal) unit.get("settledAmount");
      BigDecimal rate = (BigDecimal) unit.get("disbursementRatePercent");

      BigDecimal disbursementScore =
          rate.multiply(new BigDecimal("0.40"))
              .min(new BigDecimal("40.00"))
              .setScale(1, RoundingMode.HALF_UP);
      BigDecimal settlementRatio =
          disb.compareTo(BigDecimal.ZERO) > 0
              ? settled.multiply(new BigDecimal("100")).divide(disb, 2, RoundingMode.HALF_UP)
              : new BigDecimal("85.00");
      BigDecimal settlementScore =
          settlementRatio
              .multiply(new BigDecimal("0.25"))
              .min(new BigDecimal("25.00"))
              .setScale(1, RoundingMode.HALF_UP);

      // Tính động điểm sát thực tế lập dự toán (Thang 20 điểm)
      BigDecimal varianceRatio =
          init != null && init.compareTo(BigDecimal.ZERO) > 0
              ? eff.subtract(init).abs().divide(init, 4, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;
      BigDecimal accuracyScore =
          new BigDecimal("20.0")
              .subtract(varianceRatio.multiply(new BigDecimal("20.0")))
              .max(new BigDecimal("12.0"))
              .min(new BigDecimal("20.0"))
              .setScale(1, RoundingMode.HALF_UP);

      // Tính động điểm tuân thủ chứng từ Kho bạc (Thang 15 điểm)
      int unitDisbCount = 0;
      int compliantVoucherCount = 0;
      for (BudgetDisbursement d : disbs) {
        if (unitNodeId != null && unitNodeId.equals(d.getNodeId())) {
          unitDisbCount++;
          if ((d.getVoucherNo() != null && !d.getVoucherNo().isBlank())
              || (d.getTreasuryRefCode() != null && !d.getTreasuryRefCode().isBlank())) {
            compliantVoucherCount++;
          }
        }
      }
      BigDecimal voucherComplianceScore =
          unitDisbCount > 0
              ? BigDecimal.valueOf(compliantVoucherCount)
                  .multiply(new BigDecimal("15.0"))
                  .divide(BigDecimal.valueOf(unitDisbCount), 1, RoundingMode.HALF_UP)
              : new BigDecimal("14.5");

      BigDecimal totalScore =
          disbursementScore.add(settlementScore).add(accuracyScore).add(voucherComplianceScore);
      String rank =
          totalScore.compareTo(new BigDecimal("90")) >= 0
              ? "XUAT_SAC"
              : (totalScore.compareTo(new BigDecimal("80")) >= 0
                  ? "TOT"
                  : (totalScore.compareTo(new BigDecimal("65")) >= 0 ? "KHA" : "CAN_CAI_THIEN"));

      Map<String, Object> card = new LinkedHashMap<>(unit);
      card.put("disbursementProgressScore", disbursementScore);
      card.put("settlementTimelinessScore", settlementScore);
      card.put("estimateAccuracyScore", accuracyScore);
      card.put("voucherComplianceScore", voucherComplianceScore);
      card.put("totalDisciplineScore", totalScore);
      card.put("disciplineScore", totalScore);
      card.put("gradeRank", rank);
      card.put("rankGrade", rank);
      card.put("projectedAvailableAtYearEnd", eff.subtract(disb));
      scorecards.add(card);
    }
    return scorecards;
  }

  public Map<String, Object> generateAiAdvisorInsights(Integer budgetYear) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    Map<String, Object> overview = getOverview(year, null, null);
    List<Map<String, Object>> unitMatrix = getUnitMatrix(year);
    Map<String, Object> burnForecast = getBurnRateForecast(year);

    String slowestUnitName = "Các đơn vị trực thuộc";
    BigDecimal slowestRate = new BigDecimal("100");
    String highestSurplusUnitName = "Văn phòng Sở";
    BigDecimal highestSurplusAmount = BigDecimal.ZERO;

    for (Map<String, Object> u : unitMatrix) {
      BigDecimal r = (BigDecimal) u.get("disbursementRatePercent");
      BigDecimal avail = (BigDecimal) u.get("availableAmount");
      if (r != null && r.compareTo(slowestRate) < 0) {
        slowestRate = r;
        slowestUnitName = String.valueOf(u.get("nodeName"));
      }
      if (avail != null && avail.compareTo(highestSurplusAmount) > 0) {
        highestSurplusAmount = avail;
        highestSurplusUnitName = String.valueOf(u.get("nodeName"));
      }
    }

    List<Map<String, Object>> recommendations = new ArrayList<>();
    String action1 =
        "Đề nghị Phòng KHTC đôn đốc "
            + slowestUnitName
            + " hoàn thiện hồ sơ nghiệm thu giai đoạn 1 để chuyển từ Cam kết chi sang Thực chi Kho bạc trước ngày 25 hàng tháng.";
    recommendations.add(
        Map.of(
            "id", "AI-BUD-01",
            "category", "DISBURSEMENT_ACCELERATION",
            "severity", "HIGH",
            "title",
                "Đẩy nhanh tiến độ nghiệm thu & giải ngân Kho bạc tại " + slowestUnitName,
            "analysis",
                "Tỷ lệ giải ngân bình quân toàn ngành đạt "
                    + overview.get("disbursementRatePercent")
                    + "%, trong đó "
                    + slowestUnitName
                    + " đang ở mức "
                    + slowestRate
                    + "%. Một số khoản mục đã có Cam kết chi hợp đồng nhưng chưa lập Giấy rút dự toán Kho bạc (C2-02a/NS).",
            "suggestedAction", action1,
            "suggestion", action1));

    String action2 =
        "Tạo giao dịch Điều chuyển dự toán nội bộ (INTERNAL_TRANSFER) trong phạm vi kinh phí giao tự chủ (Khoản 281) từ đơn vị thừa vốn sang nhiệm vụ phát sinh cấp bách.";
    recommendations.add(
        Map.of(
            "id", "AI-BUD-02",
            "category", "INTERNAL_TRANSFER_OPTIMIZATION",
            "severity", "MEDIUM",
            "title",
                "Đề xuất điều chuyển nội bộ từ nguồn dự kiến kết dư của " + highestSurplusUnitName,
            "analysis",
                highestSurplusUnitName
                    + " hiện còn khả dụng "
                    + highestSurplusAmount.toPlainString()
                    + " VNĐ. Dự báo cuối năm toàn ngành có thể dư "
                    + burnForecast.get("totalProjectedUnspentAtYearEnd")
                    + " VNĐ nếu giữ tốc độ tiêu dùng hiện tại.",
            "suggestedAction", action2,
            "suggestion", action2));

    String action3 =
        "Sử dụng chức năng Chuyển nguồn liên năm (CARRY_OVER_OUT) kèm Quyết định phê duyệt để tự động khởi tạo dòng dự toán năm "
            + (year + 1)
            + ".";
    recommendations.add(
        Map.of(
            "id", "AI-BUD-03",
            "category", "CARRY_OVER_COMPLIANCE",
            "severity", "INFO",
            "title", "Rà soát danh mục nguồn kinh phí được phép chuyển nguồn sang năm " + (year + 1),
            "analysis",
                "Các nguồn kinh phí không tự chủ (Khoản 341 - Sự nghiệp kinh tế) chỉ được chuyển nguồn khi đã có hợp đồng ký kết trước ngày 31/12 và đối chiếu Kho bạc trước 31/01.",
            "suggestedAction", action3,
            "suggestion", action3));

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("budgetYear", year);
    res.put("generatedAt", LocalDate.now().toString());
    res.put("advisorModel", "TR-Finance-Advisor-v4.2");
    res.put("unitCountAnalyzed", unitMatrix.size());
    res.put("burnRateSummary", burnForecast);
    res.put("recommendations", recommendations);
    return res;
  }

  /**
   * Dự báo tốc độ tiêu dùng kinh phí (Burn-rate Projection), cảnh báo nguy cơ huỷ dự toán cuối năm
   * và tự động đề xuất phương án điều chuyển nội bộ (UPG-02).
   */
  public Map<String, Object> getBurnRateForecast(Integer budgetYear) {
    int currentYear = LocalDate.now().getYear();
    int year = budgetYear != null ? budgetYear : currentYear;
    int elapsedMonths =
        year < currentYear
            ? 12
            : (year > currentYear ? 1 : Math.max(LocalDate.now().getMonthValue(), 1));
    int remainingMonths = Math.max(12 - elapsedMonths, 0);

    List<Map<String, Object>> unitMatrix = getUnitMatrix(year);
    List<Map<String, Object>> unitForecasts = new ArrayList<>();
    List<Map<String, Object>> reallocationSuggestions = new ArrayList<>();

    BigDecimal totalProjectedYearEndDisbursed = BigDecimal.ZERO;
    BigDecimal totalProjectedUnspent = BigDecimal.ZERO;

    Map<String, Object> topSurplusUnit = null;
    BigDecimal maxSurplus = BigDecimal.ZERO;
    Map<String, Object> topDeficitUnit = null;
    BigDecimal maxBurnRate = BigDecimal.ZERO;

    for (Map<String, Object> unit : unitMatrix) {
      BigDecimal eff = safe((BigDecimal) unit.get("effectiveAmount"));
      BigDecimal disb = safe((BigDecimal) unit.get("disbursedAmount"));
      BigDecimal comm = safe((BigDecimal) unit.get("committedAmount"));

      BigDecimal monthlyBurnRate =
          disb.divide(BigDecimal.valueOf(elapsedMonths), 0, RoundingMode.HALF_UP);
      BigDecimal projectedAdditional =
          monthlyBurnRate
              .multiply(BigDecimal.valueOf(remainingMonths))
              .add(comm.multiply(new BigDecimal("0.85")).setScale(0, RoundingMode.HALF_UP));
      BigDecimal projectedYearEndDisbursed = disb.add(projectedAdditional).min(eff);
      BigDecimal projectedUnspent = eff.subtract(projectedYearEndDisbursed).max(BigDecimal.ZERO);
      BigDecimal projectedRate =
          eff.compareTo(BigDecimal.ZERO) > 0
              ? projectedYearEndDisbursed
                  .multiply(new BigDecimal("100"))
                  .divide(eff, 2, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;

      String riskLevel =
          projectedRate.compareTo(new BigDecimal("75")) < 0
              ? "HIGH_SURPLUS_RISK"
              : (projectedRate.compareTo(new BigDecimal("92")) < 0 ? "MODERATE" : "OPTIMAL_HIGH_BURN");
      String riskStatus =
          "HIGH_SURPLUS_RISK".equals(riskLevel)
              ? "SURPLUS_RISK"
              : ("MODERATE".equals(riskLevel) ? "MODERATE" : "ON_TRACK");

      totalProjectedYearEndDisbursed = totalProjectedYearEndDisbursed.add(projectedYearEndDisbursed);
      totalProjectedUnspent = totalProjectedUnspent.add(projectedUnspent);

      Map<String, Object> f = new LinkedHashMap<>();
      f.put("nodeId", unit.get("nodeId"));
      f.put("nodeCode", unit.get("nodeCode"));
      f.put("nodeName", unit.get("nodeName"));
      f.put("effectiveAmount", eff);
      f.put("currentDisbursedAmount", disb);
      f.put("disbursedAmount", disb);
      f.put("monthlyBurnRate", monthlyBurnRate);
      f.put("projectedYearEndDisbursed", projectedYearEndDisbursed);
      f.put("projectedYearEndUnspent", projectedUnspent);
      f.put("projectedYearEndRatePercent", projectedRate);
      f.put("projectedYearEndRate", projectedRate);
      f.put("riskLevel", riskLevel);
      f.put("riskStatus", riskStatus);
      unitForecasts.add(f);

      if (projectedUnspent.compareTo(maxSurplus) > 0) {
        maxSurplus = projectedUnspent;
        topSurplusUnit = f;
      }
      if (projectedRate.compareTo(maxBurnRate) > 0) {
        maxBurnRate = projectedRate;
        topDeficitUnit = f;
      }
    }

    if (topSurplusUnit != null
        && topDeficitUnit != null
        && !String.valueOf(topSurplusUnit.get("nodeCode"))
            .equals(String.valueOf(topDeficitUnit.get("nodeCode")))
        && maxSurplus.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal suggestedTransfer =
          maxSurplus.multiply(new BigDecimal("0.40")).setScale(0, RoundingMode.HALF_UP);
      Map<String, Object> suggestion = new LinkedHashMap<>();
      suggestion.put("txType", "INTERNAL_TRANSFER");
      suggestion.put("sourceUnitCode", topSurplusUnit.get("nodeCode"));
      suggestion.put("sourceUnitName", topSurplusUnit.get("nodeName"));
      suggestion.put("fromUnitCode", topSurplusUnit.get("nodeCode"));
      suggestion.put("fromUnitName", topSurplusUnit.get("nodeName"));
      suggestion.put("targetUnitCode", topDeficitUnit.get("nodeCode"));
      suggestion.put("targetUnitName", topDeficitUnit.get("nodeName"));
      suggestion.put("toUnitCode", topDeficitUnit.get("nodeCode"));
      suggestion.put("toUnitName", topDeficitUnit.get("nodeName"));
      suggestion.put("suggestedTransferAmount", suggestedTransfer);
      suggestion.put(
          "legalBasis",
          "Điều 53 Luật NSNN 2015 & Nghị định 60/2021/NĐ-CP (Tự chủ Khoản 281)");
      suggestion.put(
          "reason",
          "Điều chuyển tối ưu nguồn dự kiến dư cuối năm từ "
              + topSurplusUnit.get("nodeName")
              + " sang bổ sung cho "
              + topDeficitUnit.get("nodeName"));
      reallocationSuggestions.add(suggestion);
    }

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("budgetYear", year);
    res.put("elapsedMonths", elapsedMonths);
    res.put("remainingMonths", remainingMonths);
    res.put("totalProjectedYearEndDisbursed", totalProjectedYearEndDisbursed);
    res.put("totalProjectedUnspentAtYearEnd", totalProjectedUnspent);
    res.put("unitForecasts", unitForecasts);
    res.put("reallocationSuggestions", reallocationSuggestions);
    res.put("reallocationProposals", reallocationSuggestions);
    return res;
  }

  /**
   * Tổng hợp Khung Kế hoạch Tài chính - Ngân sách Trung hạn 3 năm cuốn chiếu (3-Year Rolling MTEF)
   * theo Luật Ngân sách Nhà nước 2015 (UPG-04).
   */
  public Map<String, Object> getMtefSummary(Integer baseYear) {
    int yearN = baseYear != null ? baseYear : LocalDate.now().getYear();
    Map<String, Object> currentOverview = getOverview(yearN, null, null);
    BigDecimal baseEffective = safe((BigDecimal) currentOverview.get("totalEffectiveBudget"));
    BigDecimal baseRevenue = safe((BigDecimal) currentOverview.get("totalRevenueEstimate"));

    List<BudgetAllocation> yearAllocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(yearN);
    BigDecimal sumEstimatePlus1 = BigDecimal.ZERO;
    BigDecimal sumEstimatePlus2 = BigDecimal.ZERO;
    for (BudgetAllocation alloc : yearAllocs) {
      if (!"REVENUE".equalsIgnoreCase(alloc.getItemType())) {
        sumEstimatePlus1 = sumEstimatePlus1.add(safe(alloc.getEstimateYearPlus1()));
        sumEstimatePlus2 = sumEstimatePlus2.add(safe(alloc.getEstimateYearPlus2()));
      }
    }

    List<Map<String, Object>> rollingYears = new ArrayList<>();
    BigDecimal[] growthMultipliers = {
      BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("1.16")
    };
    String[] stageLabels = {
      "Năm hiện hành (N) — Dự toán đang chấp hành",
      "Năm kế hoạch (N+1) — Dự kiến trần phân bổ",
      "Năm định hướng (N+2) — Khung MTEF trung hạn"
    };

    for (int i = 0; i < 3; i++) {
      int y = yearN + i;
      BigDecimal mult = growthMultipliers[i];
      BigDecimal expCeiling;
      if (i == 0) {
        expCeiling = baseEffective.setScale(0, RoundingMode.HALF_UP);
      } else if (i == 1 && sumEstimatePlus1.compareTo(BigDecimal.ZERO) > 0) {
        expCeiling = sumEstimatePlus1.setScale(0, RoundingMode.HALF_UP);
      } else if (i == 2 && sumEstimatePlus2.compareTo(BigDecimal.ZERO) > 0) {
        expCeiling = sumEstimatePlus2.setScale(0, RoundingMode.HALF_UP);
      } else {
        expCeiling = baseEffective.multiply(mult).setScale(0, RoundingMode.HALF_UP);
      }
      BigDecimal revProjection = baseRevenue.multiply(mult).setScale(0, RoundingMode.HALF_UP);
      BigDecimal baselineRecurrent =
          expCeiling.multiply(new BigDecimal("0.72")).setScale(0, RoundingMode.HALF_UP);
      BigDecimal devInvestment = expCeiling.subtract(baselineRecurrent);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("fiscalYear", y);
      row.put("stageLabel", stageLabels[i]);
      row.put("projectedRevenue", revProjection);
      row.put("expenditureCeiling", expCeiling);
      row.put("baselineRecurrentCeiling", baselineRecurrent);
      row.put("developmentInvestmentCeiling", devInvestment);
      row.put("totalMtefCeiling", expCeiling);
      row.put(
          "autonomySharePercent",
          i == 0 ? new BigDecimal("68.5") : (i == 1 ? new BigDecimal("72.0") : new BigDecimal("75.0")));
      rollingYears.add(row);
    }

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("baseYear", yearN);
    res.put("periodRange", yearN + "-" + (yearN + 2));
    res.put("frameworkPeriod", yearN + " - " + (yearN + 2));
    res.put("legalBasis", "Luật Ngân sách Nhà nước 2015 & Nghị định 45/2017/NĐ-CP");
    res.put("rollingYears", rollingYears);
    res.put("yearlyProjections", rollingYears);
    return res;
  }

  /**
   * Mô phỏng kịch bản ngân sách What-If (Tiết kiệm chi thường xuyên, biến động thu & tái phân bổ ưu tiên - UPG-08).
   */
  public Map<String, Object> simulateWhatIfScenario(Map<String, Object> body) {
    int year =
        body != null && body.get("budgetYear") != null
            ? Integer.parseInt(String.valueOf(body.get("budgetYear")))
            : LocalDate.now().getYear();
    BigDecimal savingRatePercent =
        body != null && body.get("recurrentSavingRatePercent") != null
            ? new BigDecimal(String.valueOf(body.get("recurrentSavingRatePercent")))
            : new BigDecimal("10.0");
    BigDecimal revenueChangePercent =
        body != null && body.get("revenueChangeRatePercent") != null
            ? new BigDecimal(String.valueOf(body.get("revenueChangeRatePercent")))
            : BigDecimal.ZERO;
    BigDecimal reallocateToPriorityPercent =
        body != null && body.get("reallocateSavedToDevelopmentPercent") != null
            ? new BigDecimal(String.valueOf(body.get("reallocateSavedToDevelopmentPercent")))
            : new BigDecimal("70.0");

    Map<String, Object> overview = getOverview(year, null, null);
    List<Map<String, Object>> unitMatrix = getUnitMatrix(year);

    BigDecimal baseEffective = safe((BigDecimal) overview.get("totalEffectiveBudget"));
    BigDecimal baseRevenue = safe((BigDecimal) overview.get("totalRevenueEstimate"));
    BigDecimal baseDisbursed = safe((BigDecimal) overview.get("totalDisbursedBudget"));

    BigDecimal savedRecurrentAmount =
        baseEffective
            .multiply(savingRatePercent)
            .divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
    BigDecimal reallocatedToPriorityAmount =
        savedRecurrentAmount
            .multiply(reallocateToPriorityPercent)
            .divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
    BigDecimal salaryReformReserveAmount = savedRecurrentAmount.subtract(reallocatedToPriorityAmount);

    BigDecimal simulatedRevenue =
        baseRevenue
            .multiply(new BigDecimal("100").add(revenueChangePercent))
            .divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
    BigDecimal simulatedEffectiveBudget =
        baseEffective.subtract(savedRecurrentAmount).add(reallocatedToPriorityAmount);
    BigDecimal simulatedDisbursementRate =
        simulatedEffectiveBudget.compareTo(BigDecimal.ZERO) > 0
            ? baseDisbursed
                .add(reallocatedToPriorityAmount.multiply(new BigDecimal("0.85")))
                .multiply(new BigDecimal("100"))
                .divide(simulatedEffectiveBudget, 2, RoundingMode.HALF_UP)
                .min(new BigDecimal("100.00"))
            : BigDecimal.ZERO;

    List<Map<String, Object>> unitImpacts = new ArrayList<>();
    int unitCount = Math.max(unitMatrix.size(), 1);
    BigDecimal priorityBonusPerUnit =
        reallocatedToPriorityAmount.divide(BigDecimal.valueOf(unitCount), 0, RoundingMode.HALF_UP);

    for (Map<String, Object> unit : unitMatrix) {
      BigDecimal eff = safe((BigDecimal) unit.get("effectiveAmount"));
      BigDecimal mandatorySaving =
          eff.multiply(savingRatePercent).divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
      BigDecimal netSimulated = eff.subtract(mandatorySaving).add(priorityBonusPerUnit);
      Map<String, Object> impact = new LinkedHashMap<>();
      impact.put("nodeId", unit.get("nodeId"));
      impact.put("nodeCode", unit.get("nodeCode"));
      impact.put("nodeName", unit.get("nodeName"));
      impact.put("currentEffectiveAmount", eff);
      impact.put("mandatorySavingAmount", mandatorySaving);
      impact.put("priorityBonusAmount", priorityBonusPerUnit);
      impact.put("netSimulatedBudget", netSimulated);
      unitImpacts.add(impact);
    }

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("budgetYear", year);
    res.put("recurrentSavingRatePercent", savingRatePercent);
    res.put("revenueChangeRatePercent", revenueChangePercent);
    res.put("reallocateSavedToDevelopmentPercent", reallocateToPriorityPercent);
    res.put("baselineEffectiveBudget", baseEffective);
    res.put("baselineRevenueEstimate", baseRevenue);
    res.put("baselineDisbursedBudget", baseDisbursed);
    res.put("baseDisbursed", baseDisbursed);
    res.put("savedRecurrentAmount", savedRecurrentAmount);
    res.put("reallocatedToPriorityAmount", reallocatedToPriorityAmount);
    res.put("salaryReformReserveAmount", salaryReformReserveAmount);
    res.put("simulatedRevenueEstimate", simulatedRevenue);
    res.put("simulatedEffectiveBudget", simulatedEffectiveBudget);
    res.put("simulatedYearEndDisbursementRate", simulatedDisbursementRate);
    res.put("unitImpacts", unitImpacts);
    res.put(
        "policyRecommendation",
        "Khi cắt giảm "
            + savingRatePercent
            + "% chi thường xuyên, toàn ngành tiết kiệm được "
            + savedRecurrentAmount.toPlainString()
            + " VNĐ, trong đó tái phân bổ "
            + reallocatedToPriorityAmount.toPlainString()
            + " VNĐ cho nhiệm vụ trọng điểm / Chuyển đổi số và trích lập "
            + salaryReformReserveAmount.toPlainString()
            + " VNĐ vào Quỹ cải cách tiền lương & dự phòng ngân sách.");
    return res;
  }

  private BigDecimal safe(BigDecimal val) {
    return val != null ? val : BigDecimal.ZERO;
  }
}
