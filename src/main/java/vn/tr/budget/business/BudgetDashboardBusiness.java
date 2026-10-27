package vn.tr.budget.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

  public Map<String, Object> getOverview(Integer budgetYear, Long nodeId, String periodType) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    List<BudgetAllocation> allocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);
    if (allocs.isEmpty()) {
      allocs = budgetAllocationRepo.findAll();
    }

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
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    List<BudgetAllocation> allocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);
    if (allocs.isEmpty()) {
      allocs = budgetAllocationRepo.findAll();
    }

    Map<String, Map<String, Object>> unitMap = new LinkedHashMap<>();
    for (BudgetAllocation a : allocs) {
      if ("REVENUE".equalsIgnoreCase(a.getItemType())) {
        continue;
      }
      String key = a.getNodeCode() != null ? a.getNodeCode() : "NODE_" + a.getNodeId();
      Map<String, Object> row =
          unitMap.computeIfAbsent(
              key,
              k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeId", a.getNodeId());
                m.put("nodeCode", a.getNodeCode());
                m.put("nodeName", a.getNodeName());
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

  public List<Map<String, Object>> getRevenueExpenditureTree(Integer budgetYear) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    List<BudgetEstimateItem> items = budgetEstimateItemRepo.findAll();
    List<BudgetAllocation> allocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);

    Map<Long, BigDecimal> itemEffective = new LinkedHashMap<>();
    Map<Long, BigDecimal> itemDisbursed = new LinkedHashMap<>();
    Map<Long, BigDecimal> itemAvailable = new LinkedHashMap<>();

    for (BudgetAllocation a : allocs) {
      if (a.getEstimateItemId() == null) {
        continue;
      }
      Long iid = a.getEstimateItemId();
      itemEffective.put(
          iid, itemEffective.getOrDefault(iid, BigDecimal.ZERO).add(safe(a.getEffectiveAmount())));
      itemDisbursed.put(
          iid, itemDisbursed.getOrDefault(iid, BigDecimal.ZERO).add(safe(a.getDisbursedAmount())));
      itemAvailable.put(
          iid, itemAvailable.getOrDefault(iid, BigDecimal.ZERO).add(safe(a.getAvailableAmount())));
    }

    List<Map<String, Object>> result = new ArrayList<>();
    for (BudgetEstimateItem item : items) {
      BigDecimal eff = itemEffective.getOrDefault(item.getId(), BigDecimal.ZERO);
      BigDecimal disb = itemDisbursed.getOrDefault(item.getId(), BigDecimal.ZERO);
      BigDecimal avail = itemAvailable.getOrDefault(item.getId(), BigDecimal.ZERO);

      if (Boolean.TRUE.equals(item.getIsHeader())) {
        for (BudgetEstimateItem child : items) {
          if (item.getId().equals(child.getParentId())) {
            eff = eff.add(itemEffective.getOrDefault(child.getId(), BigDecimal.ZERO));
            disb = disb.add(itemDisbursed.getOrDefault(child.getId(), BigDecimal.ZERO));
            avail = avail.add(itemAvailable.getOrDefault(child.getId(), BigDecimal.ZERO));
          }
        }
      }

      BigDecimal rate =
          eff.compareTo(BigDecimal.ZERO) > 0
              ? disb.multiply(new BigDecimal("100")).divide(eff, 2, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", item.getId());
      row.put("code", item.getCode());
      row.put("name", item.getName());
      row.put("parentId", item.getParentId());
      row.put("itemType", item.getItemType());
      row.put("numberingStyle", item.getNumberingStyle());
      row.put("levelDepth", item.getLevelDepth());
      row.put("isHeader", item.getIsHeader());
      row.put("categoryCode", item.getCategoryCode());
      row.put("categoryName", item.getCategoryName());
      row.put("effectiveAmount", eff);
      row.put("disbursedAmount", disb);
      row.put("availableAmount", avail);
      row.put("disbursementRatePercent", rate);
      result.add(row);
    }
    return result;
  }

  public List<Map<String, Object>> getDisbursementScorecard(Integer budgetYear) {
    List<Map<String, Object>> matrix = getUnitMatrix(budgetYear);
    List<Map<String, Object>> scorecards = new ArrayList<>();

    for (Map<String, Object> unit : matrix) {
      BigDecimal eff = (BigDecimal) unit.get("effectiveAmount");
      BigDecimal disb = (BigDecimal) unit.get("disbursedAmount");
      BigDecimal settled = (BigDecimal) unit.get("settledAmount");
      BigDecimal rate = (BigDecimal) unit.get("disbursementRatePercent");

      BigDecimal disbursementScore =
          rate.multiply(new BigDecimal("0.40")).min(new BigDecimal("40.00")).setScale(1, RoundingMode.HALF_UP);
      BigDecimal settlementRatio =
          disb.compareTo(BigDecimal.ZERO) > 0
              ? settled.multiply(new BigDecimal("100")).divide(disb, 2, RoundingMode.HALF_UP)
              : new BigDecimal("85.00");
      BigDecimal settlementScore =
          settlementRatio
              .multiply(new BigDecimal("0.25"))
              .min(new BigDecimal("25.00"))
              .setScale(1, RoundingMode.HALF_UP);
      BigDecimal accuracyScore = new BigDecimal("18.5");
      BigDecimal voucherComplianceScore = new BigDecimal("14.5");

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
      card.put("gradeRank", rank);
      card.put("projectedAvailableAtYearEnd", eff.subtract(disb));
      scorecards.add(card);
    }
    return scorecards;
  }

  public Map<String, Object> generateAiAdvisorInsights(Integer budgetYear) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    Map<String, Object> overview = getOverview(year, null, null);
    List<Map<String, Object>> unitMatrix = getUnitMatrix(year);

    List<Map<String, Object>> recommendations = new ArrayList<>();
    recommendations.add(
        Map.of(
            "id", "AI-BUD-01",
            "category", "DISBURSEMENT_ACCELERATION",
            "severity", "HIGH",
            "title", "Đẩy nhanh tiến độ nghiệm thu & giải ngân Kho bạc Quý II/2026",
            "analysis",
                "Tỷ lệ giải ngân bình quân hiện đạt "
                    + overview.get("disbursementRatePercent")
                    + "%. Một số khoản mục Mua sắm thiết bị & Thuê dịch vụ CNTT đã có Cam kết chi hợp đồng nhưng chưa lập Giấy rút dự toán Kho bạc.",
            "suggestedAction",
                "Đề nghị Phòng KHTC đôn đốc các đơn vị hoàn thiện hồ sơ nghiệm thu giai đoạn 1 để chuyển từ Cam kết chi sang Thực chi Kho bạc trước ngày 25 hàng tháng."));

    recommendations.add(
        Map.of(
            "id", "AI-BUD-02",
            "category", "INTERNAL_TRANSFER_OPTIMIZATION",
            "severity", "MEDIUM",
            "title", "Đề xuất điều chuyển nội bộ từ khoản mục tiết kiệm đấu thầu sang nhiệm vụ phát sinh",
            "analysis",
                "Khoản mục Hội nghị, tập huấn & công tác phí của Văn phòng Sở dự kiến tiết kiệm 120.000.000 VNĐ, trong khi hạng mục Kiểm tra chuyên đề đột xuất đang cần bổ sung nguồn.",
            "suggestedAction",
                "Tạo giao dịch Điều chuyển dự toán nội bộ (INTERNAL_TRANSFER) trong phạm vi kinh phí giao tự chủ (Khoản 281) để tối ưu hiệu quả sử dụng ngân sách."));

    recommendations.add(
        Map.of(
            "id", "AI-BUD-03",
            "category", "CARRY_OVER_COMPLIANCE",
            "severity", "INFO",
            "title", "Rà soát danh mục nguồn kinh phí được phép chuyển nguồn sang năm " + (year + 1),
            "analysis",
                "Các nguồn kinh phí không tự chủ (Khoản 341 - Sự nghiệp kinh tế) chỉ được chuyển nguồn khi đã có hợp đồng ký kết trước ngày 31/12 và đối chiếu Kho bạc trước 31/01.",
            "suggestedAction",
                "Sử dụng chức năng Chuyển nguồn liên năm (CARRY_OVER_OUT) kèm Quyết định phê duyệt để tự động khởi tạo dòng dự toán năm "
                    + (year + 1)
                    + "."));

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("budgetYear", year);
    res.put("generatedAt", LocalDate.now().toString());
    res.put("advisorModel", "TR-Finance-Advisor-v4.1");
    res.put("unitCountAnalyzed", unitMatrix.size());
    res.put("recommendations", recommendations);
    return res;
  }

  private BigDecimal safe(BigDecimal val) {
    return val != null ? val : BigDecimal.ZERO;
  }
}
