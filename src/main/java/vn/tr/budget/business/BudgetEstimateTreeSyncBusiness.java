package vn.tr.budget.business;

import cn.hutool.core.util.StrUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetEstimateItemService;
import vn.tr.budget.data.dto.BudgetEstimateItemData;
import vn.tr.budget.data.dto.BudgetEstimateTreeSaveRequest;
import vn.tr.budget.data.dto.BudgetEstimateTreeSaveRequest.TreeNodePayload;
import vn.tr.budget.data.mapper.BudgetEstimateItemMapper;
import vn.tr.budget.data.validator.BudgetEstimateFormulaValidator;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.core.exception.ServiceException;
import vn.tr.common.core.exception.base.EntityNotFoundException;
import vn.tr.common.core.utils.ServiceUtils;

/**
 * Điều phối đồng bộ toàn bộ cây Dự toán Thu - Chi bằng Smart Diff-Based Upsert và di chuyển node
 * có kiểm tra chống vòng lặp cha - con.
 *
 * @author tyran8x
 */
@Service("budgetEstimateTreeSyncBusiness")
@RequiredArgsConstructor
@Transactional
public class BudgetEstimateTreeSyncBusiness {

  private static final String[] ROMANS = {
    "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"
  };

  private final BudgetEstimateItemService estimateItemService;
  private final BudgetEstimateItemBusiness estimateItemBusiness;
  private final BudgetEstimateTreeOpsBusiness treeOpsBusiness;
  private final BudgetEstimateItemMapper estimateItemMapper;

  /** Đồng bộ toàn bộ cây Dự toán bằng Smart Diff-Based Upsert (bảo toàn ID & lịch sử JaVers). */
  public Map<String, Object> saveTree(BudgetEstimateTreeSaveRequest request) {
    if (request == null) {
      throw new ServiceException("Dữ liệu đồng bộ cây dự toán không được để trống.");
    }
    int year = request.getBudgetYear() != null ? request.getBudgetYear() : LocalDate.now().getYear();
    String scopeItemType =
        StrUtil.isNotBlank(request.getItemType())
            ? request.getItemType().trim().toUpperCase(Locale.ROOT)
            : null;
    List<TreeNodePayload> roots = request.getNodes() != null ? request.getNodes() : List.of();

    Map<String, String> formulaByCode = new LinkedHashMap<>();
    validateTreePayloadsRecursive(roots, new HashSet<>(), new HashSet<>(), formulaByCode);
    BudgetEstimateFormulaValidator.validateFormulaGraphOrThrow(formulaByCode);

    BudgetEstimateItemRepo repo = estimateItemService.getRepo();
    List<BudgetEstimateItem> scopedExisting =
        BudgetEstimateTreeOpsBusiness.filterByYearAndType(repo.findAll(), year, scopeItemType);
    Map<Long, BudgetEstimateItem> existingById = new LinkedHashMap<>();
    Map<String, BudgetEstimateItem> existingByCode = new LinkedHashMap<>();
    for (BudgetEstimateItem item : scopedExisting) {
      if (item.getId() != null) {
        existingById.put(item.getId(), item);
      }
      if (item.getCode() != null) {
        existingByCode.put(item.getCode().toUpperCase(Locale.ROOT), item);
      }
    }

    int[] counters = new int[2]; // [0]=created, [1]=updated
    Set<Long> retainedIds = new HashSet<>();
    saveNodesRecursive(
        roots,
        null,
        null,
        null,
        "",
        "",
        1,
        year,
        scopeItemType,
        existingById,
        existingByCode,
        retainedIds,
        counters);

    int prunedCount = 0;
    if (Boolean.TRUE.equals(request.getPruneRemoved())) {
      List<Long> toDelete = new ArrayList<>();
      for (BudgetEstimateItem item : scopedExisting) {
        if (item.getId() != null
            && !retainedIds.contains(item.getId())
            && (item.getBudgetYear() == null || Objects.equals(item.getBudgetYear(), year))) {
          toDelete.add(item.getId());
        }
      }
      if (!toDelete.isEmpty()) {
        estimateItemService.deleteByIds(toDelete);
        prunedCount = toDelete.size();
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("budgetYear", year);
    result.put("createdCount", counters[0]);
    result.put("updatedCount", counters[1]);
    result.put("prunedCount", prunedCount);
    result.put("totalNodes", counters[0] + counters[1]);
    result.put("tree", estimateItemBusiness.getTree(year, scopeItemType));
    result.put("health", treeOpsBusiness.evaluateTreeHealth(year));
    return result;
  }

  /** Di chuyển một chỉ tiêu sang nút cha mới (chặn circular reference) và cập nhật cây con. */
  public BudgetEstimateItemData moveNode(Long id, Long newParentId, Integer sortOrder) {
    BudgetEstimateItem target =
        estimateItemService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetEstimateItem.class, id));
    if (newParentId != null && newParentId.equals(id)) {
      throw new ServiceException("Không thể chọn chính chỉ tiêu làm nút cha của nó.");
    }

    List<BudgetEstimateItem> all = estimateItemService.getRepo().findAll();
    if (newParentId != null && isDescendant(id, newParentId, all)) {
      throw new ServiceException(
          "Thao tác di chuyển tạo vòng lặp cha - con không hợp lệ trên Cây Dự toán.");
    }

    BudgetEstimateItem newParent =
        newParentId != null
            ? estimateItemService
                .findById(newParentId)
                .orElseThrow(
                    () -> new EntityNotFoundException(BudgetEstimateItem.class, newParentId))
            : null;
    target.setParentId(newParent != null ? newParent.getId() : null);
    target.setParentCode(newParent != null ? newParent.getCode() : null);
    target.setParentName(newParent != null ? newParent.getName() : null);
    if (sortOrder != null) {
      target.setSortOrder(sortOrder);
    }
    int baseDepth =
        newParent != null && newParent.getLevelDepth() != null ? newParent.getLevelDepth() + 1 : 1;
    String parentPath =
        newParent != null && StrUtil.isNotBlank(newParent.getTreePath())
            ? newParent.getTreePath()
            : "";
    target.setLevelDepth(baseDepth);
    target.setTreePath(parentPath + "/" + target.getCode());
    BudgetEstimateItem saved = estimateItemService.save(target);
    refreshDescendantPaths(saved, all);
    return estimateItemMapper.toData(saved);
  }

  private void saveNodesRecursive(
      List<TreeNodePayload> nodes,
      Long parentId,
      String parentCode,
      String parentName,
      String parentPrefix,
      String parentPath,
      int depth,
      int budgetYear,
      String scopeItemType,
      Map<Long, BudgetEstimateItem> existingById,
      Map<String, BudgetEstimateItem> existingByCode,
      Set<Long> retainedIds,
      int[] counters) {
    for (int i = 0; i < nodes.size(); i++) {
      TreeNodePayload node = nodes.get(i);
      String code = node.getCode().trim();
      String upperCode = code.toUpperCase(Locale.ROOT);
      BudgetEstimateItem entity = resolveUpsertTarget(node, existingById, existingByCode, upperCode, budgetYear);
      if (entity == null) {
        List<BudgetEstimateItem> softDeleted =
            estimateItemService.getRepo().findByCodeInYearSorted(code, budgetYear);
        entity =
            ServiceUtils.checkDuplicateAndReturnFirst(
                    null, "BudgetEstimateItem", code, softDeleted, BudgetEstimateItem::getId)
                .map(
                    restored -> {
                      restored.setDeletedAt(null);
                      return restored;
                    })
                .orElseGet(BudgetEstimateItem::new);
        counters[0]++;
      } else {
        counters[1]++;
      }

      String numberingStyle =
          StrUtil.isNotBlank(node.getNumberingStyle())
              ? node.getNumberingStyle().trim().toUpperCase(Locale.ROOT)
              : defaultStyleForDepth(depth);
      String displayIdx = formatPrefix(numberingStyle, i, parentPrefix);
      String path = parentPath + "/" + code;

      applyNodeFields(
          entity,
          node,
          code,
          budgetYear,
          parentId,
          parentCode,
          parentName,
          scopeItemType,
          numberingStyle,
          displayIdx,
          path,
          depth,
          i + 1);

      BudgetEstimateItem saved = estimateItemService.save(entity);
      if (saved.getId() != null) {
        retainedIds.add(saved.getId());
      }
      if (node.getChildren() != null && !node.getChildren().isEmpty()) {
        saveNodesRecursive(
            node.getChildren(),
            saved.getId(),
            saved.getCode(),
            saved.getName(),
            displayIdx,
            path,
            depth + 1,
            budgetYear,
            saved.getItemType(),
            existingById,
            existingByCode,
            retainedIds,
            counters);
      }
    }
  }

  private static BudgetEstimateItem resolveUpsertTarget(
      TreeNodePayload node,
      Map<Long, BudgetEstimateItem> existingById,
      Map<String, BudgetEstimateItem> existingByCode,
      String upperCode,
      int budgetYear) {
    if (node.getId() != null && node.getId() > 0) {
      BudgetEstimateItem byId = existingById.get(node.getId());
      if (byId != null) {
        return byId;
      }
    }
    BudgetEstimateItem byCode = existingByCode.get(upperCode);
    if (byCode != null
        && (byCode.getBudgetYear() == null || Objects.equals(byCode.getBudgetYear(), budgetYear))) {
      return byCode;
    }
    return null;
  }

  private static void applyNodeFields(
      BudgetEstimateItem entity,
      TreeNodePayload node,
      String code,
      int budgetYear,
      Long parentId,
      String parentCode,
      String parentName,
      String scopeItemType,
      String numberingStyle,
      String displayIdx,
      String path,
      int depth,
      int sortOrder) {
    entity.setCode(code);
    entity.setName(StrUtil.isNotBlank(node.getName()) ? node.getName().trim() : code);
    entity.setDescription(node.getDescription());
    entity.setBudgetYear(budgetYear);
    entity.setParentId(parentId);
    entity.setParentCode(parentCode);
    entity.setParentName(parentName);
    entity.setItemType(
        StrUtil.isNotBlank(node.getItemType())
            ? node.getItemType().trim().toUpperCase(Locale.ROOT)
            : (scopeItemType != null ? scopeItemType : "EXPENDITURE"));
    entity.setNumberingStyle(numberingStyle);
    entity.setDisplayIndex(displayIdx);
    entity.setTreePath(path);
    entity.setLevelDepth(depth);
    entity.setSortOrder(sortOrder);
    entity.setIsHeader(Boolean.TRUE.equals(node.getIsHeader()));
    entity.setFormula(node.getFormula());
    entity.setCategoryId(node.getCategoryId());
    entity.setCategoryCode(node.getCategoryCode());
    entity.setCategoryName(node.getCategoryName());
    entity.setSubItemCode(node.getSubItemCode());
    entity.setFundingSourceCode(node.getFundingSourceCode());
    entity.setAutonomyType(node.getAutonomyType());
    entity.setUnitOfMeasure(
        StrUtil.isNotBlank(node.getUnitOfMeasure()) ? node.getUnitOfMeasure() : "VNĐ/Năm");
    entity.setNormStandardAmount(
        node.getNormStandardAmount() != null ? node.getNormStandardAmount() : BigDecimal.ZERO);
    entity.setMaxCeilingAmount(node.getMaxCeilingAmount());
    entity.setLegalBasisRef(node.getLegalBasisRef());
    entity.setRequireVoucher(node.getRequireVoucher() != null ? node.getRequireVoucher() : Boolean.TRUE);
    if (entity.getStatus() == null) {
      entity.setStatus(LifecycleStatus.ACTIVE);
    }
  }

  private void validateTreePayloadsRecursive(
      List<TreeNodePayload> nodes,
      Set<String> seenCodes,
      Set<Long> seenIds,
      Map<String, String> formulaByCode) {
    for (TreeNodePayload node : nodes) {
      if (StrUtil.isBlank(node.getCode())) {
        throw new ServiceException("Mỗi chỉ tiêu trên cây bắt buộc phải có Mã chỉ tiêu.");
      }
      String key = node.getCode().trim().toUpperCase(Locale.ROOT);
      if (!seenCodes.add(key)) {
        throw new ServiceException("Mã chỉ tiêu bị trùng trên Cây Dự toán: " + node.getCode());
      }
      if (node.getId() != null && !seenIds.add(node.getId())) {
        throw new ServiceException("Phát hiện cấu trúc cây lặp ID node: " + node.getId());
      }
      BudgetEstimateFormulaValidator.validateOrThrow(node.getFormula(), node.getCode());
      formulaByCode.put(key, node.getFormula());
      if (node.getNormStandardAmount() != null
          && node.getNormStandardAmount().compareTo(BigDecimal.ZERO) < 0) {
        throw new ServiceException("Định mức của [" + node.getCode() + "] không được âm.");
      }
      if (node.getChildren() != null && !node.getChildren().isEmpty()) {
        validateTreePayloadsRecursive(node.getChildren(), seenCodes, seenIds, formulaByCode);
      }
    }
  }

  private void refreshDescendantPaths(BudgetEstimateItem parent, List<BudgetEstimateItem> all) {
    Map<Long, List<BudgetEstimateItem>> childrenByParent = new LinkedHashMap<>();
    for (BudgetEstimateItem item : all) {
      if (item.getParentId() != null && !Objects.equals(item.getId(), parent.getId())) {
        childrenByParent
            .computeIfAbsent(item.getParentId(), k -> new ArrayList<>())
            .add(item);
      }
    }
    List<BudgetEstimateItem> dirty = new ArrayList<>();
    Set<Long> visited = new HashSet<>();
    if (parent.getId() != null) {
      visited.add(parent.getId());
    }
    collectDescendantPathUpdates(parent, childrenByParent, visited, dirty);
    if (!dirty.isEmpty()) {
      estimateItemService.getRepo().saveAll(dirty);
    }
  }

  private void collectDescendantPathUpdates(
      BudgetEstimateItem parent,
      Map<Long, List<BudgetEstimateItem>> childrenByParent,
      Set<Long> visited,
      List<BudgetEstimateItem> dirty) {
    List<BudgetEstimateItem> children = childrenByParent.get(parent.getId());
    if (children == null || children.isEmpty()) {
      return;
    }
    int nextDepth = (parent.getLevelDepth() != null ? parent.getLevelDepth() : 1) + 1;
    String parentPath = parent.getTreePath() != null ? parent.getTreePath() : "";
    for (BudgetEstimateItem child : children) {
      if (child.getId() != null && !visited.add(child.getId())) {
        continue;
      }
      child.setLevelDepth(nextDepth);
      child.setParentCode(parent.getCode());
      child.setParentName(parent.getName());
      child.setTreePath(parentPath + "/" + child.getCode());
      dirty.add(child);
      collectDescendantPathUpdates(child, childrenByParent, visited, dirty);
    }
  }

  private boolean isDescendant(Long ancestorId, Long candidateId, List<BudgetEstimateItem> all) {
    Map<Long, Long> parentMap = new LinkedHashMap<>();
    for (BudgetEstimateItem item : all) {
      if (item.getId() != null) {
        parentMap.put(item.getId(), item.getParentId());
      }
    }
    Set<Long> visited = new HashSet<>();
    Long cur = candidateId;
    while (cur != null && visited.add(cur)) {
      if (Objects.equals(cur, ancestorId)) {
        return true;
      }
      cur = parentMap.get(cur);
    }
    return false;
  }

  private static String defaultStyleForDepth(int depth) {
    return switch (depth) {
      case 1 -> "ROMAN";
      case 2 -> "ARABIC";
      case 3 -> "DECIMAL";
      default -> "LETTER";
    };
  }

  private static String formatPrefix(String style, int index, String parentPrefix) {
    return switch (style) {
      case "ROMAN" -> index < ROMANS.length ? ROMANS[index] : String.valueOf(index + 1);
      case "LETTER" -> String.valueOf((char) ('a' + (index % 26)));
      case "DECIMAL" ->
          StrUtil.isNotBlank(parentPrefix)
              ? parentPrefix + "." + (index + 1)
              : String.valueOf(index + 1);
      default -> String.valueOf(index + 1);
    };
  }
}
