package vn.tr.budget.business;

import cn.hutool.core.util.StrUtil;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.dao.service.BudgetCategoryService;
import vn.tr.budget.data.criteria.BudgetCategorySearchCriteria;
import vn.tr.budget.data.dto.BudgetCategoryData;
import vn.tr.budget.data.mapper.BudgetCategoryMapper;
import vn.tr.budget.data.validator.BudgetCategoryValidator;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.core.exception.base.EntityNotFoundException;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.web.data.dto.BulkOperationResult;
import vn.tr.common.web.data.dto.BulkSortOrderData;
import vn.tr.common.web.utils.BusinessLogicHelper;
import vn.tr.common.web.utils.CoreUtils;


/**
 * Lớp điều phối nghiệp vụ cho BudgetCategory.
 *
 * @author tyran8x
 */
@Service("budgetCategoryBusiness")
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BudgetCategoryBusiness {

  private final BudgetCategoryService budgetCategoryService;
  private final BudgetCategoryMapper budgetCategoryMapper;
  private final BudgetCategoryValidator budgetCategoryValidator;
  private final GenericUpsertHelper genericUpsertHelper;
  private final AuditRollbackHelper auditRollbackHelper;
  private final BusinessLogicHelper businessLogicHelper;


  public JpaRepository<BudgetCategory, Long> getInjectedRepo() {
    return budgetCategoryService.getRepository();
  }

  /** Tạo mới BudgetCategory. */
  public BudgetCategoryData create(BudgetCategoryData data) {
    data.setId(null);
    validateData(data);
    if (data.getStatus() == null) {
      data.setStatus(LifecycleStatus.ACTIVE);
    }
    if (StrUtil.isBlank(data.getCategoryGroup())) {
      data.setCategoryGroup("CLAUSE");
    }
    if (StrUtil.isBlank(data.getAutonomyType())) {
      data.setAutonomyType("AUTONOMOUS");
    }
    if (data.getAllowCarryOver() == null) {
      data.setAllowCarryOver(Boolean.TRUE);
    }
    if (data.getSlaDisbursementWarningDays() == null) {
      data.setSlaDisbursementWarningDays(7);
    }
    syncParentCategorySnapshot(data);
    if (data.getLevelDepth() == null) {
      data.setLevelDepth(data.getParentId() != null ? 2 : 1);
    }
    if (data.getIsLeaf() == null) {
      data.setIsLeaf(Boolean.TRUE);
    }
    BudgetCategory entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetCategoryService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> budgetCategoryMapper.toEntity(data),
            budgetCategoryMapper::updateEntityFromData,
            budgetCategoryService.getRepository());

    return enrichData(budgetCategoryMapper.toData(entity));
  }

  /** Cập nhật BudgetCategory. */
  public BudgetCategoryData update(Long id, BudgetCategoryData data) {
    BudgetCategory existing =
        budgetCategoryService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetCategory.class, id));
    data.setId(id);
    validateData(data);
    syncParentCategorySnapshot(data);
    budgetCategoryMapper.updateEntityFromData(data, existing);
    BudgetCategory saved = budgetCategoryService.save(existing);
    return enrichData(budgetCategoryMapper.toData(saved));
  }

  private void syncParentCategorySnapshot(BudgetCategoryData data) {
    if (data == null) {
      return;
    }
    if (data.getParentId() != null) {
      budgetCategoryService
          .findById(data.getParentId())
          .ifPresent(
              parent -> {
                data.setParentCode(parent.getCode());
                data.setParentName(parent.getName());
                if (data.getLevelDepth() == null) {
                  data.setLevelDepth(
                      (parent.getLevelDepth() != null ? parent.getLevelDepth() : 1) + 1);
                }
              });
    } else if (StrUtil.isNotBlank(data.getParentCode())) {
      budgetCategoryService
          .findByCodeIgnoreCaseIncludingDeleted(data.getParentCode().trim())
          .ifPresent(
              parent -> {
                data.setParentId(parent.getId());
                if (StrUtil.isBlank(data.getParentName())) {
                  data.setParentName(parent.getName());
                }
                if (data.getLevelDepth() == null) {
                  data.setLevelDepth(
                      (parent.getLevelDepth() != null ? parent.getLevelDepth() : 1) + 1);
                }
              });
    }
  }

  private BudgetCategoryData enrichData(BudgetCategoryData dto) {
    if (dto == null) {
      return null;
    }
    if (dto.getParentId() != null
        && (StrUtil.isBlank(dto.getParentCode()) || StrUtil.isBlank(dto.getParentName()))) {
      budgetCategoryService
          .findById(dto.getParentId())
          .ifPresent(
              parent -> {
                if (StrUtil.isBlank(dto.getParentCode())) {
                  dto.setParentCode(parent.getCode());
                }
                if (StrUtil.isBlank(dto.getParentName())) {
                  dto.setParentName(parent.getName());
                }
              });
    }
    return dto;
  }

  /** Xoá mềm BudgetCategory. */
  public void delete(Long id) {
    budgetCategoryService
        .findById(id)
        .orElseThrow(() -> new EntityNotFoundException(BudgetCategory.class, id));
    budgetCategoryService.delete(id);
  }

  /** Xoá mềm hàng loạt BudgetCategory. */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    return businessLogicHelper.executeBulkDelete(
        ids,
        budgetCategoryService::findAllByIds,
        BudgetCategory::getId,
        null,
        null,
        budgetCategoryService::deleteByIds,
        "budget.budgetCategory.not.found");
  }

  /** Lấy chi tiết BudgetCategory theo ID. */
  @Transactional(readOnly = true)
  public BudgetCategoryData findById(Long id) {
    return budgetCategoryService
        .findById(id)
        .map(budgetCategoryMapper::toData)
        .orElseThrow(() -> new EntityNotFoundException(BudgetCategory.class, id));
  }

  /** Tìm kiếm BudgetCategory phân trang. */
  @Transactional(readOnly = true)
  public PagedData<BudgetCategoryData> findAll(BudgetCategorySearchCriteria criteria) {
    Pageable pageable = CoreUtils.getPageRequest(criteria);
    Page<BudgetCategory> page = budgetCategoryService.findAll(criteria, pageable);
    return PagedData.from(page, budgetCategoryMapper::toData);
  }

  /** Sao chép BudgetCategory. */
  @Transactional
  public BudgetCategoryData cloneById(Long id) {
    BudgetCategory source =
        budgetCategoryService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetCategory.class, id));
    BudgetCategoryData clonedData = budgetCategoryMapper.toData(source);
    clonedData.setId(null);
    clonedData.setCode(source.getCode() + "_COPY_" + System.currentTimeMillis() % 10000);
    clonedData.setName(source.getName() + " (Bản sao)");
    return create(clonedData);
  }

  /** Khôi phục phiên bản lịch sử JaVers. */
  @Transactional
  public BudgetCategoryData rollback(Long id, Long version) {
    return auditRollbackHelper.executeRollback(
        BudgetCategory.class,
        id,
        version,
        budgetCategoryService::findById,
        budgetCategoryService::save,
        budgetCategoryMapper::toData,
        budgetCategoryMapper::updateEntityFromData,
        BudgetCategoryData::setId,
        this::validateData);
  }

  /** Cập nhật thứ tự sắp xếp hàng loạt. */
  @Transactional
  public void bulkUpdateSortOrder(BulkSortOrderData data) {
    if (data == null || data.getUpdates() == null || data.getUpdates().isEmpty()) {
      return;
    }
    for (BulkSortOrderData.SortOrderUpdate item : data.getUpdates()) {
      budgetCategoryService
          .findById(item.getId())
          .ifPresent(
              entity -> {
                entity.setSortOrder(item.getSortOrder());
                budgetCategoryService.save(entity);
              });
    }
  }

  private void validateData(BudgetCategoryData data) {
    vn.tr.common.web.core.BaseBusinessHelper.validateOrThrow(
        budgetCategoryValidator, data, "budgetCategoryData");
  }

  /**
   * Lấy cấu hình Lĩnh vực (DOMAIN) và Nhóm lĩnh vực (DOMAIN_GROUP) theo từng Năm ngân sách cụ thể.
   * Mỗi năm ngân sách hoặc mỗi đơn vị có thể có danh sách lĩnh vực / nhóm lĩnh vực khác nhau.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> getYearDomainConfig(Integer budgetYear, Long nodeId) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    List<String> groups = List.of("DOMAIN", "DOMAIN_GROUP");

    List<BudgetCategory> allYearConfigs =
        budgetCategoryService
            .getRepo()
            .findByCategoryGroupInOrderByBudgetYearAscSortOrderAscIdAsc(groups);

    Set<Integer> configuredYears = new LinkedHashSet<>();
    for (BudgetCategory c : allYearConfigs) {
      if (c.getBudgetYear() != null) {
        configuredYears.add(c.getBudgetYear());
      }
    }

    List<BudgetCategory> yearRecords =
        budgetCategoryService
            .getRepo()
            .findByBudgetYearAndCategoryGroupInOrderBySortOrderAscIdAsc(year, groups);

    List<BudgetCategoryData> domainGroups = new ArrayList<>();
    List<BudgetCategoryData> domains = new ArrayList<>();

    for (BudgetCategory item : yearRecords) {
      if (nodeId != null && item.getNodeId() != null && !nodeId.equals(item.getNodeId())) {
        continue;
      }
      BudgetCategoryData dto = enrichData(budgetCategoryMapper.toData(item));
      if (StrUtil.isBlank(dto.getDomainCode())) {
        dto.setDomainCode(extractLogicalDomainCode(dto.getCode(), year));
      }
      if ("DOMAIN_GROUP".equalsIgnoreCase(dto.getCategoryGroup())) {
        domainGroups.add(dto);
      } else {
        domains.add(dto);
      }
    }

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("budgetYear", year);
    res.put("nodeId", nodeId);
    res.put("configuredYears", new ArrayList<>(configuredYears));
    res.put("domainGroups", domainGroups);
    res.put("domains", domains);
    res.put("totalGroups", domainGroups.size());
    res.put("totalDomains", domains.size());
    return res;
  }

  /**
   * Tạo mới hoặc cập nhật (Upsert) cấu hình Lĩnh vực / Nhóm lĩnh vực cho một Năm ngân sách cụ thể.
   */
  @Transactional
  public BudgetCategoryData saveYearDomainConfig(BudgetCategoryData data) {
    int year = data.getBudgetYear() != null ? data.getBudgetYear() : LocalDate.now().getYear();
    data.setBudgetYear(year);

    String catGroup =
        "DOMAIN_GROUP".equalsIgnoreCase(data.getCategoryGroup()) ? "DOMAIN_GROUP" : "DOMAIN";
    data.setCategoryGroup(catGroup);

    String rawDomainCode =
        StrUtil.isNotBlank(data.getDomainCode())
            ? data.getDomainCode().trim()
            : extractLogicalDomainCode(data.getCode(), year);
    if (StrUtil.isBlank(rawDomainCode)) {
      rawDomainCode = "DOM_" + (System.currentTimeMillis() % 10000);
    }
    String normalizedDomainCode =
        rawDomainCode.toUpperCase().replaceAll("[^A-Z0-9_\\-]", "_");
    data.setDomainCode(normalizedDomainCode);

    String prefix = "DOMAIN_GROUP".equals(catGroup) ? "GRP-" : "DOM-";
    String nodePart = data.getNodeId() != null ? data.getNodeId() + "-" : "";
    String canonicalCode = prefix + year + "-" + nodePart + normalizedDomainCode;
    if (StrUtil.isBlank(data.getCode())
        || !data.getCode().contains(String.valueOf(year))) {
      data.setCode(canonicalCode);
    }

    if ("DOMAIN_GROUP".equals(catGroup)) {
      data.setDomainGroupCode(normalizedDomainCode);
      data.setDomainGroupName(data.getName());
      data.setGroupCode(normalizedDomainCode);
      data.setIsLeaf(Boolean.FALSE);
      data.setLevelDepth(1);
    } else {
      data.setIsLeaf(Boolean.TRUE);
      data.setLevelDepth(2);
      if (StrUtil.isNotBlank(data.getDomainGroupCode())
          && StrUtil.isBlank(data.getDomainGroupName())) {
        List<BudgetCategory> existingGroups =
            budgetCategoryService
                .getRepo()
                .findByBudgetYearAndCategoryGroupInOrderBySortOrderAscIdAsc(
                    year, List.of("DOMAIN_GROUP"));
        for (BudgetCategory g : existingGroups) {
          if (data.getDomainGroupCode().equalsIgnoreCase(g.getDomainCode())
              || data.getDomainGroupCode().equalsIgnoreCase(g.getCode())) {
            data.setDomainGroupName(g.getName());
            break;
          }
        }
      }
      if (StrUtil.isNotBlank(data.getDomainGroupCode())) {
        data.setGroupCode(data.getDomainGroupCode());
      }
    }

    if (data.getId() != null) {
      return update(data.getId(), data);
    }

    List<BudgetCategory> yearRecords =
        budgetCategoryService
            .getRepo()
            .findByBudgetYearAndCategoryGroupInOrderBySortOrderAscIdAsc(year, List.of(catGroup));
    for (BudgetCategory existing : yearRecords) {
      boolean sameNode = Objects.equals(existing.getNodeId(), data.getNodeId());
      if (sameNode
          && (normalizedDomainCode.equalsIgnoreCase(existing.getDomainCode())
              || data.getCode().equalsIgnoreCase(existing.getCode()))) {
        data.setCode(existing.getCode());
        return update(existing.getId(), data);
      }
    }

    return create(data);
  }

  /**
   * Sao chép cấu hình Lĩnh vực & Nhóm lĩnh vực từ năm nguồn (sourceYear) sang năm đích (targetYear)
   * để đơn vị tùy chỉnh cho năm ngân sách mới và tự động giữ liên kết previousYearDomainCode.
   */
  @Transactional
  public Map<String, Object> cloneYearDomainConfig(Map<String, Object> body) {
    int targetYear =
        body != null && body.get("targetYear") != null
            ? Integer.parseInt(String.valueOf(body.get("targetYear")))
            : (body != null && body.get("toYear") != null
                ? Integer.parseInt(String.valueOf(body.get("toYear")))
                : LocalDate.now().getYear() + 1);
    int sourceYear =
        body != null && body.get("sourceYear") != null
            ? Integer.parseInt(String.valueOf(body.get("sourceYear")))
            : (body != null && body.get("fromYear") != null
                ? Integer.parseInt(String.valueOf(body.get("fromYear")))
                : targetYear - 1);
    Long nodeId =
        body != null && body.get("nodeId") != null && !String.valueOf(body.get("nodeId")).isBlank()
            ? Long.parseLong(String.valueOf(body.get("nodeId")))
            : null;
    boolean overwrite =
        body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("overwrite", "false")));

    List<String> groups = List.of("DOMAIN_GROUP", "DOMAIN");
    List<BudgetCategory> sourceRecords =
        budgetCategoryService
            .getRepo()
            .findByBudgetYearAndCategoryGroupInOrderBySortOrderAscIdAsc(sourceYear, groups);

    if (overwrite) {
      List<BudgetCategory> targetExisting =
          budgetCategoryService
              .getRepo()
              .findByBudgetYearAndCategoryGroupInOrderBySortOrderAscIdAsc(targetYear, groups);
      for (BudgetCategory ex : targetExisting) {
        if (nodeId == null || Objects.equals(nodeId, ex.getNodeId())) {
          budgetCategoryService.delete(ex.getId());
        }
      }
    }

    int clonedCount = 0;
    for (BudgetCategory src : sourceRecords) {
      if (nodeId != null && src.getNodeId() != null && !nodeId.equals(src.getNodeId())) {
        continue;
      }
      BudgetCategoryData cloneDto = budgetCategoryMapper.toData(src);
      cloneDto.setId(null);
      cloneDto.setBudgetYear(targetYear);
      String logicalCode =
          StrUtil.isNotBlank(src.getDomainCode())
              ? src.getDomainCode()
              : extractLogicalDomainCode(src.getCode(), sourceYear);
      cloneDto.setDomainCode(logicalCode);
      cloneDto.setPreviousYearDomainCode(logicalCode);
      String prefix =
          "DOMAIN_GROUP".equalsIgnoreCase(src.getCategoryGroup()) ? "GRP-" : "DOM-";
      String nodePart = src.getNodeId() != null ? src.getNodeId() + "-" : "";
      cloneDto.setCode(prefix + targetYear + "-" + nodePart + logicalCode);
      saveYearDomainConfig(cloneDto);
      clonedCount++;
    }

    Map<String, Object> result = new LinkedHashMap<>(getYearDomainConfig(targetYear, nodeId));
    result.put("sourceYear", sourceYear);
    result.put("targetYear", targetYear);
    result.put("clonedCount", clonedCount);
    return result;
  }

  private String extractLogicalDomainCode(String rawCode, int year) {
    if (StrUtil.isBlank(rawCode)) {
      return "";
    }
    String cleaned = rawCode.trim();
    cleaned = cleaned.replaceFirst("(?i)^(DOM|GRP)-" + year + "-(\\d+-)?", "");
    cleaned = cleaned.replaceFirst("(?i)^(DOM|GRP)_" + year + "_(\\d+_)?", "");
    return cleaned;
  }

}
