package vn.tr.budget.business;

import cn.hutool.core.util.StrUtil;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

}
