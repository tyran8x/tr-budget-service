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
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetUnitConfigService;
import vn.tr.budget.data.criteria.BudgetUnitConfigSearchCriteria;
import vn.tr.budget.data.dto.BudgetUnitConfigData;
import vn.tr.budget.data.mapper.BudgetUnitConfigMapper;
import vn.tr.budget.data.validator.BudgetUnitConfigValidator;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.core.exception.base.EntityNotFoundException;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.web.data.dto.BulkOperationResult;
import vn.tr.common.web.data.dto.BulkSortOrderData;
import vn.tr.common.web.utils.BusinessLogicHelper;
import vn.tr.common.web.utils.CoreUtils;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;

/**
 * Lớp điều phối nghiệp vụ cho BudgetUnitConfig.
 *
 * @author tyran8x
 */
@Service("budgetUnitConfigBusiness")
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BudgetUnitConfigBusiness {

  private final BudgetUnitConfigService budgetUnitConfigService;
  private final BudgetUnitConfigMapper budgetUnitConfigMapper;
  private final BudgetUnitConfigValidator budgetUnitConfigValidator;
  private final GenericUpsertHelper genericUpsertHelper;
  private final AuditRollbackHelper auditRollbackHelper;
  private final BusinessLogicHelper businessLogicHelper;
  private final BudgetWorkflowEngineHelper workflowEngineHelper;

  @Autowired(required = false)
  private BudgetIntegrationOrchestrator integrationOrchestrator;

  public JpaRepository<BudgetUnitConfig, Long> getInjectedRepo() {
    return budgetUnitConfigService.getRepository();
  }

  /** Tạo mới BudgetUnitConfig. */
  public BudgetUnitConfigData create(BudgetUnitConfigData data) {
    data.setId(null);
    validateData(data);
    if (data.getStatus() == null) {
      data.setStatus(LifecycleStatus.ACTIVE);
    }
    if (StrUtil.isBlank(data.getUnitType())) {
      data.setUnitType("DEPARTMENT");
    }
    if (data.getUnitLevel() == null) {
      data.setUnitLevel(3);
    }
    if (data.getRecurrentSelfGuaranteeRate() == null) {
      data.setRecurrentSelfGuaranteeRate(BigDecimal.ZERO);
    }
    if (StrUtil.isBlank(data.getDefaultChapterCode())) {
      data.setDefaultChapterCode("423");
    }
    if (data.getCanSelfAllocate() == null) {
      data.setCanSelfAllocate(Boolean.TRUE);
    }
    if (data.getCanSelfTransfer() == null) {
      data.setCanSelfTransfer(Boolean.TRUE);
    }
    if (data.getCanCarryOver() == null) {
      data.setCanCarryOver(Boolean.TRUE);
    }
    syncUnitConfigSnapshots(data);
    BudgetUnitConfig entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetUnitConfigService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> budgetUnitConfigMapper.toEntity(data),
            budgetUnitConfigMapper::updateEntityFromData,
            budgetUnitConfigService.getRepository());

    return enrichData(budgetUnitConfigMapper.toData(entity));
  }

  /** Cập nhật BudgetUnitConfig. */
  public BudgetUnitConfigData update(Long id, BudgetUnitConfigData data) {
    BudgetUnitConfig existing =
        budgetUnitConfigService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetUnitConfig.class, id));
    data.setId(id);
    validateData(data);
    syncUnitConfigSnapshots(data);
    budgetUnitConfigMapper.updateEntityFromData(data, existing);
    BudgetUnitConfig saved = budgetUnitConfigService.save(existing);
    return enrichData(budgetUnitConfigMapper.toData(saved));
  }

  private void syncUnitConfigSnapshots(BudgetUnitConfigData data) {
    if (data == null || integrationOrchestrator == null) {
      return;
    }
    if (data.getNodeId() != null) {
      data.setNodeCode(
          integrationOrchestrator.resolveNodeCodeWithFallback(
              data.getNodeId(), data.getNodeCode()));
      data.setNodeName(
          integrationOrchestrator.resolveNodeNameWithFallback(
              data.getNodeId(), data.getNodeName()));
    } else if (StrUtil.isNotBlank(data.getNodeCode())) {
      Long resolvedId = integrationOrchestrator.resolveNodeIdByCode(data.getNodeCode());
      if (resolvedId != null) {
        data.setNodeId(resolvedId);
      }
      data.setNodeName(
          integrationOrchestrator.resolveNodeNameByCodeWithFallback(
              data.getNodeCode(), data.getNodeName()));
    }
    if (data.getChiefAccountantPersonId() != null) {
      data.setChiefAccountantName(
          integrationOrchestrator.resolvePersonNameWithFallback(
              data.getChiefAccountantPersonId(), data.getChiefAccountantName()));
    }
    if (data.getApproverPersonId() != null) {
      data.setApproverPersonName(
          integrationOrchestrator.resolvePersonNameWithFallback(
              data.getApproverPersonId(), data.getApproverPersonName()));
    }
  }

  private BudgetUnitConfigData enrichData(BudgetUnitConfigData dto) {
    if (dto == null || integrationOrchestrator == null) {
      return dto;
    }
    dto.setNodeCode(
        integrationOrchestrator.resolveNodeCodeWithFallback(dto.getNodeId(), dto.getNodeCode()));
    dto.setNodeName(
        integrationOrchestrator.resolveNodeNameWithFallback(dto.getNodeId(), dto.getNodeName()));
    dto.setChiefAccountantName(
        integrationOrchestrator.resolvePersonNameWithFallback(
            dto.getChiefAccountantPersonId(), dto.getChiefAccountantName()));
    dto.setApproverPersonName(
        integrationOrchestrator.resolvePersonNameWithFallback(
            dto.getApproverPersonId(), dto.getApproverPersonName()));
    return dto;
  }

  /** Xoá mềm BudgetUnitConfig. */
  public void delete(Long id) {
    budgetUnitConfigService
        .findById(id)
        .orElseThrow(() -> new EntityNotFoundException(BudgetUnitConfig.class, id));
    budgetUnitConfigService.delete(id);
  }

  /** Xoá mềm hàng loạt BudgetUnitConfig. */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    return businessLogicHelper.executeBulkDelete(
        ids,
        budgetUnitConfigService::findAllByIds,
        BudgetUnitConfig::getId,
        null,
        null,
        budgetUnitConfigService::deleteByIds,
        "budget.budgetUnitConfig.not.found");
  }

  /** Lấy chi tiết BudgetUnitConfig theo ID. */
  @Transactional(readOnly = true)
  public BudgetUnitConfigData findById(Long id) {
    return budgetUnitConfigService
        .findById(id)
        .map(budgetUnitConfigMapper::toData)
        .map(this::enrichData)
        .orElseThrow(() -> new EntityNotFoundException(BudgetUnitConfig.class, id));
  }

  /** Tìm kiếm BudgetUnitConfig phân trang. */
  @Transactional(readOnly = true)
  public PagedData<BudgetUnitConfigData> findAll(BudgetUnitConfigSearchCriteria criteria) {
    Pageable pageable = CoreUtils.getPageRequest(criteria);
    Page<BudgetUnitConfig> page = budgetUnitConfigService.findAll(criteria, pageable);
    return PagedData.from(page, e -> enrichData(budgetUnitConfigMapper.toData(e)));
  }

  /** Sao chép BudgetUnitConfig. */
  @Transactional
  public BudgetUnitConfigData cloneById(Long id) {
    BudgetUnitConfig source =
        budgetUnitConfigService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetUnitConfig.class, id));
    BudgetUnitConfigData clonedData = budgetUnitConfigMapper.toData(source);
    clonedData.setId(null);
    clonedData.setCode(source.getCode() + "_COPY_" + System.currentTimeMillis() % 10000);
    clonedData.setName(source.getName() + " (Bản sao)");
    return create(clonedData);
  }

  /** Khôi phục phiên bản lịch sử JaVers. */
  @Transactional
  public BudgetUnitConfigData rollback(Long id, Long version) {
    return auditRollbackHelper.executeRollback(
        BudgetUnitConfig.class,
        id,
        version,
        budgetUnitConfigService::findById,
        budgetUnitConfigService::save,
        budgetUnitConfigMapper::toData,
        budgetUnitConfigMapper::updateEntityFromData,
        BudgetUnitConfigData::setId,
        this::validateData);
  }

  /** Cập nhật thứ tự sắp xếp hàng loạt. */
  @Transactional
  public void bulkUpdateSortOrder(BulkSortOrderData data) {
    if (data == null || data.getUpdates() == null || data.getUpdates().isEmpty()) {
      return;
    }
    for (BulkSortOrderData.SortOrderUpdate item : data.getUpdates()) {
      budgetUnitConfigService
          .findById(item.getId())
          .ifPresent(
              entity -> {
                entity.setSortOrder(item.getSortOrder());
                budgetUnitConfigService.save(entity);
              });
    }
  }

  private void validateData(BudgetUnitConfigData data) {
    vn.tr.common.web.core.BaseBusinessHelper.validateOrThrow(
        budgetUnitConfigValidator, data, "budgetUnitConfigData");
  }

  /** Kiểm tra quyền tự chủ tài chính của đơn vị. */
  @Transactional(readOnly = true)
  public Map<String, Object> checkAutonomy(
      Long nodeId, String categoryCode, BigDecimal proposedAmount) {
    return workflowEngineHelper.checkUnitAutonomy(nodeId, categoryCode, proposedAmount);
  }

}
