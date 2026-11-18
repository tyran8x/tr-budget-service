package vn.tr.budget.business;

import cn.hutool.core.util.StrUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetUnitConfigRepo;
import vn.tr.budget.data.dto.BudgetAllocationData;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.common.core.exception.ServiceException;

/**
 * Điều phối nhập liệu hàng loạt nguyên tử (Spreadsheet Batch Entry) và Ma trận Giao Dự toán Đầu
 * năm theo Cây Chỉ tiêu × Đơn vị Sử dụng Ngân sách.
 *
 * @author tyran8x
 */
@Service("budgetBatchEntryBusiness")
@RequiredArgsConstructor
@Transactional
public class BudgetBatchEntryBusiness {

  private static final Set<String> LOCKED_ALLOCATION_STATUSES =
      Set.of("APPROVED", "SELF_APPROVED", "FINANCE_REVIEWED");

  private final BudgetAllocationBusiness allocationBusiness;
  private final BudgetTransactionBusiness transactionBusiness;
  private final BudgetDisbursementBusiness disbursementBusiness;
  private final BudgetEstimateItemBusiness estimateItemBusiness;
  private final BudgetAllocationRepo allocationRepo;
  private final BudgetUnitConfigRepo unitConfigRepo;
  private final BudgetEstimateItemRepo estimateItemRepo;

  /** Nhập hàng loạt Phương án Phân bổ Dự toán (nguyên tử @Transactional). */
  public Map<String, Object> batchSaveAllocations(List<BudgetAllocationData> items) {
    return executeAtomicBatch(
        "Phân bổ dự toán",
        items,
        BudgetAllocationData::getCode,
        a -> a.getInitialAmount() != null ? a.getInitialAmount() : a.getProposedAmount(),
        allocationBusiness::create);
  }

  /** Nhập hàng loạt Bút toán Sổ cái Ngân sách (nguyên tử @Transactional). */
  public Map<String, Object> batchSaveTransactions(List<BudgetTransactionData> items) {
    return executeAtomicBatch(
        "Bút toán sổ cái",
        items,
        BudgetTransactionData::getCode,
        BudgetTransactionData::getAmount,
        transactionBusiness::create);
  }

  /** Nhập hàng loạt Hồ sơ Giải ngân Kho bạc (nguyên tử @Transactional). */
  public Map<String, Object> batchSaveDisbursements(List<BudgetDisbursementData> items) {
    return executeAtomicBatch(
        "Hồ sơ giải ngân",
        items,
        BudgetDisbursementData::getCode,
        BudgetDisbursementData::getAmount,
        disbursementBusiness::create);
  }

  /** Lấy Ma trận Giao Dự toán Đầu năm: Cây Chỉ tiêu × Đơn vị SDNS + các ô đã phân bổ. */
  @Transactional(readOnly = true)
  public Map<String, Object> getAllocationTreeMatrix(Integer budgetYear, String periodType) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    String normalizedPeriod = StrUtil.isNotBlank(periodType) ? periodType.trim() : "ANNUAL";

    List<Map<String, Object>> treeRows = estimateItemBusiness.getTree(year, null);
    List<BudgetUnitConfig> unitsRaw = new ArrayList<>(unitConfigRepo.findAll());
    unitsRaw.sort(
        Comparator.comparing(
                BudgetUnitConfig::getUnitLevel, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                BudgetUnitConfig::getNodeCode, Comparator.nullsLast(Comparator.naturalOrder())));

    List<Map<String, Object>> units = new ArrayList<>();
    for (BudgetUnitConfig u : unitsRaw) {
      Map<String, Object> unitMap = new LinkedHashMap<>();
      unitMap.put("id", u.getId());
      unitMap.put("nodeId", u.getNodeId());
      unitMap.put("nodeCode", u.getNodeCode());
      unitMap.put("nodeName", u.getNodeName());
      unitMap.put("unitType", u.getUnitType());
      unitMap.put("selfApproveLimitAmount", u.getSelfApproveLimitAmount());
      units.add(unitMap);
    }

    Map<String, Map<String, Object>> cellsByKey = new LinkedHashMap<>();
    for (BudgetAllocation a : allocationRepo.findByBudgetYearOrderByIdAsc(year)) {
      if (!normalizedPeriod.equalsIgnoreCase(a.getPeriodType() != null ? a.getPeriodType() : "ANNUAL")) {
        continue;
      }
      if (StrUtil.isBlank(a.getEstimateItemCode()) || StrUtil.isBlank(a.getNodeCode())) {
        continue;
      }
      String cellKey = matrixKey(a.getEstimateItemCode(), a.getNodeCode());
      Map<String, Object> cell = new LinkedHashMap<>();
      cell.put("allocationId", a.getId());
      cell.put("code", a.getCode());
      cell.put("estimateItemCode", a.getEstimateItemCode());
      cell.put("nodeCode", a.getNodeCode());
      cell.put("allocatedAmount", a.getInitialAmount() != null ? a.getInitialAmount() : BigDecimal.ZERO);
      cell.put("disbursedAmount", a.getDisbursedAmount() != null ? a.getDisbursedAmount() : BigDecimal.ZERO);
      cell.put("status", a.getWorkflowStatus());
      cellsByKey.put(cellKey, cell);
    }

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("budgetYear", year);
    res.put("periodType", normalizedPeriod);
    res.put("units", units);
    res.put("treeRows", treeRows);
    res.put("cellsByKey", cellsByKey);
    return res;
  }

  /** Đồng bộ Smart Diff-Based Upsert từ Ma trận Giao Dự toán Cây × Đơn vị. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> saveAllocationTreeMatrix(Map<String, Object> payload) {
    if (payload == null) {
      throw new ServiceException("Payload ma trận phân bổ không được để trống.");
    }
    int year =
        payload.get("budgetYear") != null
            ? Integer.parseInt(String.valueOf(payload.get("budgetYear")))
            : LocalDate.now().getYear();
    String periodType =
        payload.get("periodType") != null ? String.valueOf(payload.get("periodType")) : "ANNUAL";
    String decisionNo =
        payload.get("decisionNo") != null ? String.valueOf(payload.get("decisionNo")) : null;
    List<Map<String, Object>> cells =
        payload.get("cells") instanceof List<?> rawList
            ? (List<Map<String, Object>>) rawList
            : List.of();
    if (cells.isEmpty()) {
      throw new ServiceException("Chưa có ô dữ liệu phân bổ nào được gửi để lưu.");
    }

    Map<String, BudgetUnitConfig> unitByCode = new LinkedHashMap<>();
    for (BudgetUnitConfig u : unitConfigRepo.findAll()) {
      if (u.getNodeCode() != null) {
        unitByCode.put(u.getNodeCode().trim().toUpperCase(Locale.ROOT), u);
      }
    }
    Map<String, BudgetEstimateItem> itemByCode = new LinkedHashMap<>();
    for (BudgetEstimateItem item :
        BudgetEstimateTreeOpsBusiness.filterByYearAndType(estimateItemRepo.findAll(), year, null)) {
      if (item.getCode() != null) {
        itemByCode.put(item.getCode().trim().toUpperCase(Locale.ROOT), item);
      }
    }
    Map<String, BudgetAllocation> existingByCell = new LinkedHashMap<>();
    for (BudgetAllocation a : allocationRepo.findByBudgetYearOrderByIdAsc(year)) {
      if (Objects.equals(periodType, a.getPeriodType() != null ? a.getPeriodType() : "ANNUAL")
          && StrUtil.isNotBlank(a.getEstimateItemCode())
          && StrUtil.isNotBlank(a.getNodeCode())) {
        existingByCell.put(matrixKey(a.getEstimateItemCode(), a.getNodeCode()), a);
      }
    }

    int created = 0;
    int updated = 0;
    int skipped = 0;
    List<String> warnings = new ArrayList<>();
    BigDecimal totalAmount = BigDecimal.ZERO;

    for (Map<String, Object> cell : cells) {
      String itemCode = cell.get("estimateItemCode") != null ? String.valueOf(cell.get("estimateItemCode")).trim() : "";
      String nodeCode = cell.get("nodeCode") != null ? String.valueOf(cell.get("nodeCode")).trim() : "";
      BigDecimal amount = parseAmount(cell.get("allocatedAmount"));
      if (StrUtil.isBlank(itemCode) || StrUtil.isBlank(nodeCode) || amount.compareTo(BigDecimal.ZERO) < 0) {
        continue;
      }

      BudgetAllocation existing = existingByCell.get(matrixKey(itemCode, nodeCode));
      if (existing != null) {
        String st = existing.getWorkflowStatus() != null ? existing.getWorkflowStatus().toUpperCase(Locale.ROOT) : "DRAFT";
        if (LOCKED_ALLOCATION_STATUSES.contains(st)) {
          skipped++;
          warnings.add("Bỏ qua [" + itemCode + " × " + nodeCode + "] vì đã " + st + ".");
          totalAmount = totalAmount.add(existing.getInitialAmount() != null ? existing.getInitialAmount() : BigDecimal.ZERO);
          continue;
        }
        BudgetAllocationData dto = buildCellDto(existing, itemByCode.get(itemCode.toUpperCase(Locale.ROOT)),
            unitByCode.get(nodeCode.toUpperCase(Locale.ROOT)), itemCode, nodeCode, year, periodType, decisionNo, amount);
        allocationBusiness.update(existing.getId(), dto);
        updated++;
        totalAmount = totalAmount.add(amount);
      } else if (amount.compareTo(BigDecimal.ZERO) > 0) {
        BudgetAllocationData dto = buildCellDto(null, itemByCode.get(itemCode.toUpperCase(Locale.ROOT)),
            unitByCode.get(nodeCode.toUpperCase(Locale.ROOT)), itemCode, nodeCode, year, periodType, decisionNo, amount);
        allocationBusiness.create(dto);
        created++;
        totalAmount = totalAmount.add(amount);
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("budgetYear", year);
    result.put("periodType", periodType);
    result.put("createdCount", created);
    result.put("updatedCount", updated);
    result.put("skippedCount", skipped);
    result.put("warnings", warnings);
    result.put("totalAllocatedAmount", totalAmount);
    return result;
  }

  private <T> Map<String, Object> executeAtomicBatch(
      String entityLabel,
      List<T> items,
      Function<T, String> codeExtractor,
      Function<T, BigDecimal> amountExtractor,
      Function<T, T> creator) {
    if (items == null || items.isEmpty()) {
      throw new ServiceException("Danh sách " + entityLabel + " gửi lên đang trống.");
    }
    List<T> savedItems = new ArrayList<>();
    List<String> rowErrors = new ArrayList<>();
    BigDecimal totalAmount = BigDecimal.ZERO;

    for (int i = 0; i < items.size(); i++) {
      T item = items.get(i);
      try {
        T created = creator.apply(item);
        savedItems.add(created);
        BigDecimal amt = amountExtractor.apply(item);
        if (amt != null) {
          totalAmount = totalAmount.add(amt);
        }
      } catch (Exception ex) {
        String code = codeExtractor.apply(item);
        rowErrors.add("Dòng " + (i + 1) + (StrUtil.isNotBlank(code) ? " [" + code + "]" : "") + ": " + ex.getMessage());
      }
    }

    if (!rowErrors.isEmpty()) {
      throw new ServiceException(
          "Nhập hàng loạt " + entityLabel + " thất bại (" + rowErrors.size() + " lỗi): " + String.join("; ", rowErrors));
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("count", savedItems.size());
    response.put("totalAmount", totalAmount);
    response.put("items", savedItems);
    return response;
  }

  private static BudgetAllocationData buildCellDto(
      BudgetAllocation existing,
      BudgetEstimateItem estItem,
      BudgetUnitConfig unit,
      String itemCode,
      String nodeCode,
      int year,
      String periodType,
      String decisionNo,
      BigDecimal amount) {
    String unitName = unit != null && StrUtil.isNotBlank(unit.getNodeName()) ? unit.getNodeName() : nodeCode;
    String itemName = estItem != null && StrUtil.isNotBlank(estItem.getName()) ? estItem.getName() : itemCode;
    String generatedCode = existing != null ? existing.getCode()
        : ("PB-" + year + "-" + nodeCode + "-" + itemCode).replaceAll("[^A-Za-z0-9_-]", "_");
    return BudgetAllocationData.builder()
        .id(existing != null ? existing.getId() : null)
        .code(generatedCode)
        .name("Phân bổ " + itemName + " — " + unitName + " (" + year + ")")
        .budgetYear(year)
        .periodType(periodType)
        .nodeId(unit != null && unit.getNodeId() != null ? unit.getNodeId() : (existing != null ? existing.getNodeId() : null))
        .nodeCode(nodeCode)
        .nodeName(unitName)
        .estimateItemId(estItem != null ? estItem.getId() : null)
        .estimateItemCode(itemCode)
        .estimateItemName(itemName)
        .itemType(estItem != null && StrUtil.isNotBlank(estItem.getItemType()) ? estItem.getItemType() : "EXPENDITURE")
        .categoryId(estItem != null ? estItem.getCategoryId() : null)
        .categoryCode(estItem != null ? estItem.getCategoryCode() : null)
        .categoryName(estItem != null ? estItem.getCategoryName() : null)
        .decisionNo(StrUtil.isNotBlank(decisionNo) ? decisionNo : (existing != null ? existing.getDecisionNo() : null))
        .workflowStatus(existing != null && StrUtil.isNotBlank(existing.getWorkflowStatus()) ? existing.getWorkflowStatus() : "DRAFT")
        .proposedAmount(amount)
        .initialAmount(amount)
        .effectiveAmount(amount)
        .availableAmount(amount)
        .build();
  }

  private static String matrixKey(String itemCode, String nodeCode) {
    return itemCode.trim().toUpperCase(Locale.ROOT) + "::" + nodeCode.trim().toUpperCase(Locale.ROOT);
  }

  private static BigDecimal parseAmount(Object raw) {
    if (raw == null) return BigDecimal.ZERO;
    try {
      return new BigDecimal(String.valueOf(raw));
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }
}
