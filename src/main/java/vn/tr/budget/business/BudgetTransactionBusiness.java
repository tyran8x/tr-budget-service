package vn.tr.budget.business;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.budget.business.helper.BudgetSecurityGuardHelper;
import vn.tr.budget.business.helper.BudgetStateMachine;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetCategoryRepo;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.dao.service.BudgetTransactionService;
import vn.tr.budget.data.criteria.BudgetTransactionSearchCriteria;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.budget.data.mapper.BudgetTransactionMapper;
import vn.tr.budget.data.validator.BudgetTransactionValidator;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.domain.data.RefData;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.core.exception.base.BaseException;
import vn.tr.common.core.exception.base.EntityNotFoundException;
import vn.tr.common.core.exception.base.InvalidDataException;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.satoken.utils.LoginHelper;
import vn.tr.common.security.abac.OrgSubtreeAuthorizationHelper;
import vn.tr.common.web.core.BaseBusinessHelper;
import vn.tr.common.web.data.dto.BulkOperationResult;
import vn.tr.common.web.data.dto.BulkSortOrderData;
import vn.tr.common.web.utils.BusinessLogicHelper;
import vn.tr.common.web.utils.CoreUtils;
import vn.tr.common.web.utils.LookupOptionsHelper;
import vn.tr.common.web.workflow.WorkflowBusinessSupport;
import vn.tr.common.web.workflow.WorkflowCallbackPayload;

/**
 * Lớp điều phối nghiệp vụ cho BudgetTransaction.
 *
 * @author tyran8x
 */
@Service("budgetTransactionBusiness")
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BudgetTransactionBusiness implements WorkflowBusinessSupport<BudgetTransaction> {

  private final BudgetTransactionService budgetTransactionService;
  private final BudgetTransactionMapper budgetTransactionMapper;
  private final BudgetTransactionValidator budgetTransactionValidator;
  private final GenericUpsertHelper genericUpsertHelper;
  private final AuditRollbackHelper auditRollbackHelper;
  private final BusinessLogicHelper businessLogicHelper;
  private final BudgetWorkflowEngineHelper workflowEngineHelper;
  private final BudgetAllocationRepo budgetAllocationRepo;

  @Autowired(required = false)
  private BudgetEstimateItemRepo budgetEstimateItemRepo;

  @Autowired(required = false)
  private BudgetCategoryRepo budgetCategoryRepo;

  @Autowired(required = false)
  private BudgetIntegrationOrchestrator integrationOrchestrator;

  @Autowired(required = false)
  private BudgetTransactionRepo budgetTransactionRepo;

  @Autowired(required = false)
  private BudgetStateMachine stateMachine;

  @Autowired(required = false)
  private BudgetSecurityGuardHelper securityGuard;

  public void setBudgetTransactionRepo(BudgetTransactionRepo budgetTransactionRepo) {
    this.budgetTransactionRepo = budgetTransactionRepo;
  }

  public void setIntegrationOrchestrator(BudgetIntegrationOrchestrator integrationOrchestrator) {
    this.integrationOrchestrator = integrationOrchestrator;
  }

  public void setStateMachine(BudgetStateMachine stateMachine) {
    this.stateMachine = stateMachine;
  }

  public void setSecurityGuard(BudgetSecurityGuardHelper securityGuard) {
    this.securityGuard = securityGuard;
  }

  private BudgetStateMachine sm() {
    if (stateMachine == null) {
      stateMachine = new BudgetStateMachine();
    }
    return stateMachine;
  }

  private BudgetSecurityGuardHelper guard() {
    if (securityGuard == null) {
      securityGuard = new BudgetSecurityGuardHelper();
    }
    return securityGuard;
  }

  public JpaRepository<BudgetTransaction, Long> getInjectedRepo() {
    return budgetTransactionService.getRepository();
  }

  /** Tạo mới BudgetTransaction (ép cứng trạng thái DRAFT và khử Mass Assignment). */
  public BudgetTransactionData create(BudgetTransactionData data) {
    return createInternal(data, "DRAFT", false);
  }

  private BudgetTransactionData createInternal(
      BudgetTransactionData data, String forcedStatus, boolean trustedS2s) {
    data.setId(null);
    validateData(data);
    if (data.getStatus() == null) {
      data.setStatus(LifecycleStatus.ACTIVE);
    }
    if (data.getTxDate() == null) {
      data.setTxDate(LocalDate.now());
    }
    if (data.getBudgetYear() == null) {
      data.setBudgetYear(LocalDate.now().getYear());
    }
    if (StrUtil.isBlank(data.getTxType())) {
      data.setTxType("SUPPLEMENT");
    }
    data.setApprovalStatus(forcedStatus);
    data.setApprovedByPersonId(null);
    data.setApprovedByPersonName(null);
    data.setApprovedDate(null);
    data.setWfInstanceId(null);

    Long authPersonId = resolveAuthenticatedPersonId();
    if (authPersonId != null) {
      data.setRequestedByPersonId(authPersonId);
      data.setRequestedByPersonName(resolveAuthenticatedActorName());
    }

    syncTransactionSnapshots(data);
    if (!trustedS2s) {
      guard().assertNodeAccess(data.getNodeId(), data.getNodeCode());
      if (data.getCounterpartNodeId() != null || StrUtil.isNotBlank(data.getCounterpartNodeCode())) {
        guard().assertNodeAccess(data.getCounterpartNodeId(), data.getCounterpartNodeCode());
      }
    }
    validateLedgerBalanceBeforeTransferLocked(data);

    BudgetTransaction entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetTransactionService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> {
              BudgetTransaction created = budgetTransactionMapper.toEntity(data);
              guard().sanitizeTransactionForCreate(created);
              created.setApprovalStatus(forcedStatus);
              return created;
            },
            (src, target) -> {
              sm().assertMutableTransaction(target.getApprovalStatus(), "ghi đè", target.getCode());
              budgetTransactionMapper.updateEntityFromData(src, target);
              guard().sanitizeTransactionForCreate(target);
              target.setApprovalStatus(forcedStatus);
            },
            budgetTransactionService.getRepository());
    if ("APPROVED".equalsIgnoreCase(entity.getApprovalStatus())) {
      if (entity.getApprovedDate() == null) {
        entity.setApprovedDate(LocalDateTime.now());
      }
      workflowEngineHelper.executeDoubleEntryLedger(entity);
      syncAffectedAllocationsToPlan(entity);
    } else if ("PENDING_APPROVAL".equalsIgnoreCase(entity.getApprovalStatus())
        && integrationOrchestrator != null) {
      Long wfId = integrationOrchestrator.startTransactionWorkflow(entity);
      if (wfId != null && wfId > 0L) {
        entity.setWfInstanceId(wfId);
        if (StrUtil.isBlank(entity.getWfProcessCode())) {
          entity.setWfProcessCode(BudgetIntegrationOrchestrator.WF_PROCESS_TRANSACTION);
        }
        BudgetTransaction persisted = budgetTransactionService.save(entity);
        if (persisted != null) {
          entity = persisted;
        }
      }
    }
    return enrichData(budgetTransactionMapper.toData(entity));
  }

  /** Cập nhật BudgetTransaction (chặn sửa bút toán đã phê duyệt/quyết toán & khử Mass Assignment). */
  public BudgetTransactionData update(Long id, BudgetTransactionData data) {
    BudgetTransaction existing =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    guard().assertNodeAccess(existing.getNodeId(), existing.getNodeCode());
    sm().assertMutableTransaction(existing.getApprovalStatus(), "cập nhật", existing.getCode());

    Long oldAllocId = existing.getAllocationId();
    Long oldCounterpartId = existing.getCounterpartAllocationId();
    data.setId(id);
    validateData(data);
    syncTransactionSnapshots(data);
    guard().assertNodeAccess(data.getNodeId(), data.getNodeCode());
    validateLedgerBalanceBeforeTransferLocked(data);

    BudgetTransaction snapshot = existing.toBuilder().build();
    budgetTransactionMapper.updateEntityFromData(data, existing);
    guard().sanitizeTransactionForUpdate(existing, snapshot);

    BudgetTransaction saved = budgetTransactionService.save(existing);
    Set<Long> affectedIds = new LinkedHashSet<>();
    if (oldAllocId != null) affectedIds.add(oldAllocId);
    if (oldCounterpartId != null) affectedIds.add(oldCounterpartId);
    if (saved.getAllocationId() != null) affectedIds.add(saved.getAllocationId());
    if (saved.getCounterpartAllocationId() != null) affectedIds.add(saved.getCounterpartAllocationId());
    affectedIds.addAll(syncCounterpartTransactions(saved, false, false, null));
    recalculateAllocationIds(affectedIds);
    return enrichData(budgetTransactionMapper.toData(saved));
  }

  /** Xoá mềm BudgetTransaction (chặn xóa bút toán đã phê duyệt/quyết toán). */
  public void delete(Long id) {
    BudgetTransaction existing =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    guard().assertNodeAccess(existing.getNodeId(), existing.getNodeCode());
    sm().assertMutableTransaction(existing.getApprovalStatus(), "xóa", existing.getCode());

    Set<Long> affectedIds = new LinkedHashSet<>();
    if (existing.getAllocationId() != null) affectedIds.add(existing.getAllocationId());
    if (existing.getCounterpartAllocationId() != null) {
      affectedIds.add(existing.getCounterpartAllocationId());
    }
    budgetTransactionService.delete(id);
    affectedIds.addAll(syncCounterpartTransactions(existing, true, false, null));
    recalculateAllocationIds(affectedIds);
  }

  /** Xoá mềm hàng loạt BudgetTransaction (kiểm tra ABAC & khóa bút toán đã duyệt). */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    List<BudgetTransaction> toDelete =
        ids != null && !ids.isEmpty() ? budgetTransactionService.findAllByIds(ids) : List.of();
    for (BudgetTransaction tx : toDelete) {
      guard().assertNodeAccess(tx.getNodeId(), tx.getNodeCode());
    }
    BulkOperationResult<Long> result =
        businessLogicHelper.executeBulkDelete(
            ids,
            budgetTransactionService::findAllByIds,
            BudgetTransaction::getId,
            tx -> guard().assertNodeAccess(tx.getNodeId(), tx.getNodeCode()),
            tx -> sm().assertMutableTransaction(tx.getApprovalStatus(), "xóa", tx.getCode()),
            budgetTransactionService::deleteByIds,
            "budget.budgetTransaction.not.found");
    Set<Long> affectedIds = new LinkedHashSet<>();
    for (BudgetTransaction tx : toDelete) {
      if (!sm().isLockedTransaction(tx.getApprovalStatus())) {
        if (tx.getAllocationId() != null) affectedIds.add(tx.getAllocationId());
        if (tx.getCounterpartAllocationId() != null) affectedIds.add(tx.getCounterpartAllocationId());
        affectedIds.addAll(syncCounterpartTransactions(tx, true, false, null));
      }
    }
    recalculateAllocationIds(affectedIds);
    return result;
  }

  private void recalculateAllocationIds(Set<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    for (Long id : ids) {
      if (id != null) {
        workflowEngineHelper.recalculateAllocationMetrics(id);
      }
    }
  }

  private Set<Long> syncCounterpartTransactions(
      BudgetTransaction source, boolean deleteMode, boolean rejectMode, String rejectReason) {
    Set<Long> affectedIds = new LinkedHashSet<>();
    if (source == null || source.getId() == null) {
      return affectedIds;
    }
    Set<BudgetTransaction> counterparts = new LinkedHashSet<>();
    if (source.getCounterpartTxId() != null && !source.getCounterpartTxId().equals(source.getId())) {
      budgetTransactionService.findById(source.getCounterpartTxId()).ifPresent(counterparts::add);
    }
    BudgetTransactionRepo repo = resolveTransactionRepo();
    if (repo != null) {
      for (BudgetTransaction found : repo.findByCounterpartTxId(source.getId())) {
        if (found != null && found.getId() != null && !found.getId().equals(source.getId())) {
          counterparts.add(found);
        }
      }
    }
    for (BudgetTransaction cp : counterparts) {
      if (cp.getAllocationId() != null) affectedIds.add(cp.getAllocationId());
      if (cp.getCounterpartAllocationId() != null) affectedIds.add(cp.getCounterpartAllocationId());
      if (deleteMode) {
        budgetTransactionService.delete(cp.getId());
      } else if (rejectMode) {
        cp.setApprovalStatus("REJECTED");
        if (StrUtil.isNotBlank(rejectReason)) {
          cp.setRejectReason(rejectReason);
        }
        budgetTransactionService.save(cp);
      } else {
        if (source.getAmount() != null) {
          cp.setAmount(source.getAmount().abs());
        }
        cp.setDecisionNo(source.getDecisionNo());
        cp.setDecisionDate(source.getDecisionDate());
        cp.setTxDate(source.getTxDate());
        cp.setReason(source.getReason());
        budgetTransactionService.save(cp);
      }
    }
    return affectedIds;
  }

  private BudgetTransactionRepo resolveTransactionRepo() {
    if (budgetTransactionRepo != null) {
      return budgetTransactionRepo;
    }
    if (budgetTransactionService.getRepository() instanceof BudgetTransactionRepo repo) {
      return repo;
    }
    return null;
  }

  private void recalculateAffectedAllocations(Long allocId, Long counterpartId) {
    if (allocId != null) {
      workflowEngineHelper.recalculateAllocationMetrics(allocId);
    }
    if (counterpartId != null && !counterpartId.equals(allocId)) {
      workflowEngineHelper.recalculateAllocationMetrics(counterpartId);
    }
  }

  /** Lấy chi tiết BudgetTransaction theo ID (kiểm tra ABAC). */
  @Transactional(readOnly = true)
  public BudgetTransactionData findById(Long id) {
    BudgetTransaction entity =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    guard().assertNodeAccess(entity.getNodeId(), entity.getNodeCode());
    return enrichData(budgetTransactionMapper.toData(entity));
  }

  /** Tìm kiếm BudgetTransaction phân trang. */
  @Transactional(readOnly = true)
  public PagedData<BudgetTransactionData> findAll(BudgetTransactionSearchCriteria criteria) {
    Pageable pageable = CoreUtils.getPageRequest(criteria);
    Page<BudgetTransaction> page = budgetTransactionService.findAll(criteria, pageable);
    return PagedData.from(
        page, entity -> enrichData(budgetTransactionMapper.toData(entity)));
  }

  /**
   * Đồng bộ hai chiều (Hybrid Live-Resolve + Snapshot Fallback) cho Allocation, Counterpart, Node,
   * EstimateItem và Person.
   */
  private void syncTransactionSnapshots(BudgetTransactionData data) {
    if (data == null) {
      return;
    }
    if (budgetAllocationRepo != null) {
      if (data.getAllocationId() != null) {
        BudgetAllocation alloc =
            budgetAllocationRepo.findById(data.getAllocationId()).orElse(null);
        if (alloc != null) {
          data.setAllocationCode(alloc.getCode());
          data.setAllocationName(alloc.getName());
          if (data.getNodeId() == null && alloc.getNodeId() != null) {
            data.setNodeId(alloc.getNodeId());
            data.setNodeCode(alloc.getNodeCode());
            data.setNodeName(alloc.getNodeName());
          }
          if (data.getEstimateItemId() == null && alloc.getEstimateItemId() != null) {
            data.setEstimateItemId(alloc.getEstimateItemId());
            data.setEstimateItemCode(alloc.getEstimateItemCode());
            data.setEstimateItemName(alloc.getEstimateItemName());
          }
          if (data.getCategoryId() == null && alloc.getCategoryId() != null) {
            data.setCategoryId(alloc.getCategoryId());
          }
          if (StrUtil.isBlank(data.getCategoryCode())
              && StrUtil.isNotBlank(alloc.getCategoryCode())) {
            data.setCategoryCode(alloc.getCategoryCode());
          }
        }
      } else if (StrUtil.isNotBlank(data.getAllocationCode())) {
        List<BudgetAllocation> allocs =
            budgetAllocationRepo.findAllByCodeIgnoreCaseIncludingDeletedSorted(
                data.getAllocationCode().trim());
        if (!allocs.isEmpty()) {
          BudgetAllocation alloc = allocs.get(0);
          data.setAllocationId(alloc.getId());
          data.setAllocationCode(alloc.getCode());
          if (StrUtil.isBlank(data.getAllocationName())) {
            data.setAllocationName(alloc.getName());
          }
        }
      }

      if (data.getCounterpartAllocationId() != null) {
        BudgetAllocation cp =
            budgetAllocationRepo.findById(data.getCounterpartAllocationId()).orElse(null);
        if (cp != null) {
          data.setCounterpartAllocationCode(cp.getCode());
          data.setCounterpartAllocationName(cp.getName());
          if (data.getCounterpartNodeId() == null && cp.getNodeId() != null) {
            data.setCounterpartNodeId(cp.getNodeId());
            data.setCounterpartNodeCode(cp.getNodeCode());
            data.setCounterpartNodeName(cp.getNodeName());
          }
        }
      } else if (StrUtil.isNotBlank(data.getCounterpartAllocationCode())) {
        List<BudgetAllocation> cps =
            budgetAllocationRepo.findAllByCodeIgnoreCaseIncludingDeletedSorted(
                data.getCounterpartAllocationCode().trim());
        if (!cps.isEmpty()) {
          BudgetAllocation cp = cps.get(0);
          data.setCounterpartAllocationId(cp.getId());
          data.setCounterpartAllocationCode(cp.getCode());
          if (StrUtil.isBlank(data.getCounterpartAllocationName())) {
            data.setCounterpartAllocationName(cp.getName());
          }
        }
      }
    }
    if (budgetEstimateItemRepo != null && data.getEstimateItemId() != null) {
      BudgetEstimateItem item =
          budgetEstimateItemRepo.findById(data.getEstimateItemId()).orElse(null);
      if (item != null) {
        data.setEstimateItemCode(item.getCode());
        data.setEstimateItemName(item.getName());
      }
    }
    if (integrationOrchestrator != null) {
      if (data.getNodeId() != null) {
        data.setNodeCode(
            integrationOrchestrator.resolveNodeCodeWithFallback(
                data.getNodeId(), data.getNodeCode()));
        data.setNodeName(
            integrationOrchestrator.resolveNodeNameWithFallback(
                data.getNodeId(), data.getNodeName()));
      } else if (StrUtil.isNotBlank(data.getNodeCode())) {
        Long resolvedNodeId = integrationOrchestrator.resolveNodeIdByCode(data.getNodeCode());
        if (resolvedNodeId != null) {
          data.setNodeId(resolvedNodeId);
        }
        data.setNodeName(
            integrationOrchestrator.resolveNodeNameByCodeWithFallback(
                data.getNodeCode(), data.getNodeName()));
      }
      if (data.getCounterpartNodeId() != null) {
        data.setCounterpartNodeCode(
            integrationOrchestrator.resolveNodeCodeWithFallback(
                data.getCounterpartNodeId(), data.getCounterpartNodeCode()));
        data.setCounterpartNodeName(
            integrationOrchestrator.resolveNodeNameWithFallback(
                data.getCounterpartNodeId(), data.getCounterpartNodeName()));
      }
      if (data.getRequestedByPersonId() != null) {
        data.setRequestedByPersonName(
            integrationOrchestrator.resolvePersonNameWithFallback(
                data.getRequestedByPersonId(), data.getRequestedByPersonName()));
      }
      if (data.getApprovedByPersonId() != null) {
        data.setApprovedByPersonName(
            integrationOrchestrator.resolvePersonNameWithFallback(
                data.getApprovedByPersonId(), data.getApprovedByPersonName()));
      }
    }
  }

  private BudgetTransactionData enrichData(BudgetTransactionData dto) {
    if (dto == null || integrationOrchestrator == null) {
      return dto;
    }
    if (dto.getNodeId() != null) {
      dto.setNodeCode(
          integrationOrchestrator.resolveNodeCodeWithFallback(dto.getNodeId(), dto.getNodeCode()));
      dto.setNodeName(
          integrationOrchestrator.resolveNodeNameWithFallback(dto.getNodeId(), dto.getNodeName()));
    }
    if (dto.getCounterpartNodeId() != null) {
      dto.setCounterpartNodeCode(
          integrationOrchestrator.resolveNodeCodeWithFallback(
              dto.getCounterpartNodeId(), dto.getCounterpartNodeCode()));
      dto.setCounterpartNodeName(
          integrationOrchestrator.resolveNodeNameWithFallback(
              dto.getCounterpartNodeId(), dto.getCounterpartNodeName()));
    }
    if (dto.getRequestedByPersonId() != null) {
      dto.setRequestedByPersonName(
          integrationOrchestrator.resolvePersonNameWithFallback(
              dto.getRequestedByPersonId(), dto.getRequestedByPersonName()));
    }
    if (dto.getApprovedByPersonId() != null) {
      dto.setApprovedByPersonName(
          integrationOrchestrator.resolvePersonNameWithFallback(
              dto.getApprovedByPersonId(), dto.getApprovedByPersonName()));
    }
    return dto;
  }

  /** Sao chép BudgetTransaction. */
  @Transactional
  public BudgetTransactionData cloneById(Long id) {
    BudgetTransaction source =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    guard().assertNodeAccess(source.getNodeId(), source.getNodeCode());
    BudgetTransactionData clonedData = budgetTransactionMapper.toData(source);
    clonedData.setId(null);
    clonedData.setCode(source.getCode() + "_COPY_" + System.currentTimeMillis() % 10000);
    clonedData.setName(source.getName() + " (Bản sao)");
    return create(clonedData);
  }

  /** Khôi phục phiên bản lịch sử JaVers (chặn rollback bút toán đã chốt & tính lại số dư). */
  @Transactional
  public BudgetTransactionData rollback(Long id, Long version) {
    BudgetTransaction existing =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    guard().assertNodeAccess(existing.getNodeId(), existing.getNodeCode());
    sm().assertMutableTransaction(
        existing.getApprovalStatus(), "khôi phục phiên bản", existing.getCode());

    BudgetTransactionData rolledBack =
        auditRollbackHelper.executeRollback(
            BudgetTransaction.class,
            id,
            version,
            budgetTransactionService::findById,
            budgetTransactionService::save,
            budgetTransactionMapper::toData,
            budgetTransactionMapper::updateEntityFromData,
            BudgetTransactionData::setId,
            this::validateData);
    recalculateAffectedAllocations(existing.getAllocationId(), existing.getCounterpartAllocationId());
    return rolledBack;
  }

  /** Cập nhật thứ tự sắp xếp hàng loạt. */
  @Transactional
  public void bulkUpdateSortOrder(BulkSortOrderData data) {
    if (data == null || data.getUpdates() == null || data.getUpdates().isEmpty()) {
      return;
    }
    for (BulkSortOrderData.SortOrderUpdate item : data.getUpdates()) {
      budgetTransactionService
          .findById(item.getId())
          .ifPresent(
              entity -> {
                guard().assertNodeAccess(entity.getNodeId(), entity.getNodeCode());
                entity.setSortOrder(item.getSortOrder());
                budgetTransactionService.save(entity);
              });
    }
  }

  private void validateData(BudgetTransactionData data) {
    BaseBusinessHelper.validateOrThrow(budgetTransactionValidator, data, "budgetTransactionData");
  }

  private void validateLedgerBalanceBeforeTransferLocked(BudgetTransactionData data) {
    if (data.getAllocationId() == null || data.getAmount() == null) {
      return;
    }
    String type = StrUtil.nullToEmpty(data.getTxType()).trim().toUpperCase();
    boolean isDebitType =
        "CARRY_OVER_OUT".equals(type)
            || "INTERNAL_TRANSFER".equals(type)
            || "TRANSFER_OUT".equals(type)
            || "REDUCTION".equals(type)
            || "WITHDRAWAL".equals(type)
            || "EARMARK_HOLD".equals(type)
            || "AUTONOMY_FUND_DISTRIBUTION".equals(type)
            || ("ADJUSTMENT".equals(type) && data.getAmount().compareTo(BigDecimal.ZERO) < 0);
    if (!isDebitType) {
      return;
    }
    Map<Long, BudgetAllocation> locked =
        workflowEngineHelper.lockAllocationsInDeterministicOrder(
            data.getAllocationId(), data.getCounterpartAllocationId());
    BudgetAllocation alloc = locked.get(data.getAllocationId());
    if (alloc != null) {
      BigDecimal avail =
          alloc.getAvailableAmount() != null
              ? alloc.getAvailableAmount()
              : workflowEngineHelper.safe(alloc.getEffectiveAmount());
      if (data.getAmount().abs().compareTo(avail) > 0) {
        throw new BaseException(
            "Số tiền biến động / điều chuyển ("
                + data.getAmount().abs().toPlainString()
                + " VNĐ) vượt quá số dư dự toán khả dụng ("
                + avail.toPlainString()
                + " VNĐ) của phân bổ ["
                + alloc.getCode()
                + "].");
      }
    }
  }

  /**
   * Phê duyệt giao dịch biến động nguồn kinh phí và kích hoạt bút toán kép.
   *
   * <p>Cưỡng chế kiểm soát 4 mắt SoD ({@code creator/requester != approver}), ABAC cây đơn vị,
   * khóa bi quan theo thứ tự ID tăng dần trước khi trừ số dư và trích danh tính chỉ từ Sa-Token.
   */
  public BudgetTransactionData approveTransaction(Long id, Map<String, Object> body) {
    BudgetTransaction tx =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    guard().assertNodeAccess(tx.getNodeId(), tx.getNodeCode());
    if (tx.getCounterpartNodeId() != null || StrUtil.isNotBlank(tx.getCounterpartNodeCode())) {
      guard().assertNodeAccess(tx.getCounterpartNodeId(), tx.getCounterpartNodeCode());
    }
    if ("APPROVED".equalsIgnoreCase(tx.getApprovalStatus())) {
      return enrichData(budgetTransactionMapper.toData(tx));
    }
    sm().assertTransactionTransition(tx.getApprovalStatus(), "APPROVED", tx.getCode());
    guard()
        .assertFourEyesApproval(
            "bút toán sổ cái", tx.getCode(), tx.getRequestedByPersonId(), tx.getCreatedBy(), null);

    tx.setApprovalStatus("APPROVED");
    tx.setApprovedDate(LocalDateTime.now());
    Long actorId = resolveAuthenticatedPersonId();
    if (actorId != null) {
      tx.setApprovedByPersonId(actorId);
    }
    tx.setApprovedByPersonName(resolveAuthenticatedActorName());

    BudgetTransaction saved = budgetTransactionService.save(tx);
    workflowEngineHelper.executeDoubleEntryLedger(saved);
    syncAffectedAllocationsToPlan(saved);
    if (integrationOrchestrator != null) {
      integrationOrchestrator.executeWorkflowAction(getAppCode(), saved.getId(), "APPROVE", "");
      integrationOrchestrator.notifyTransactionEvent(saved, "APPROVED");
    }
    return enrichData(budgetTransactionMapper.toData(saved));
  }

  private void syncAffectedAllocationsToPlan(BudgetTransaction tx) {
    if (integrationOrchestrator == null || tx == null) {
      return;
    }
    if (tx.getAllocationId() != null) {
      budgetAllocationRepo
          .findById(tx.getAllocationId())
          .ifPresent(
              alloc ->
                  integrationOrchestrator.syncAllocationToLinkedPlan(
                      alloc,
                      tx.getTxType(),
                      tx.getAmount(),
                      tx.getCode(),
                      tx.getName()));
    }
    if (tx.getCounterpartAllocationId() != null) {
      budgetAllocationRepo
          .findById(tx.getCounterpartAllocationId())
          .ifPresent(
              targetAlloc ->
                  integrationOrchestrator.syncAllocationToLinkedPlan(
                      targetAlloc,
                      "CARRY_OVER_OUT".equalsIgnoreCase(tx.getTxType())
                          ? "CARRY_OVER_IN"
                          : tx.getTxType(),
                      tx.getAmount(),
                      tx.getCode(),
                      "Nhận điều chuyển / chuyển nguồn từ [" + tx.getCode() + "]"));
    }
  }

  /** Từ chối giao dịch biến động nguồn kinh phí (chặn từ chối bút toán đã phê duyệt/quyết toán). */
  public BudgetTransactionData rejectTransaction(Long id, Map<String, Object> body) {
    BudgetTransaction tx =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    guard().assertNodeAccess(tx.getNodeId(), tx.getNodeCode());
    sm().assertTransactionTransition(tx.getApprovalStatus(), "REJECTED", tx.getCode());

    tx.setApprovalStatus("REJECTED");
    String reason = "";
    if (body != null && body.get("reason") != null) {
      reason = String.valueOf(body.get("reason")).trim();
      if (StrUtil.isNotBlank(reason)) {
        tx.setRejectReason(reason);
        tx.setDescription(
            StrUtil.blankToDefault(tx.getDescription(), "") + " | Lý do từ chối: " + reason);
      }
    }
    BudgetTransaction saved = budgetTransactionService.save(tx);
    Set<Long> affectedIds = new LinkedHashSet<>();
    if (saved.getAllocationId() != null) affectedIds.add(saved.getAllocationId());
    if (saved.getCounterpartAllocationId() != null) {
      affectedIds.add(saved.getCounterpartAllocationId());
    }
    affectedIds.addAll(
        syncCounterpartTransactions(saved, false, true, StrUtil.isNotBlank(reason) ? reason : null));
    recalculateAllocationIds(affectedIds);
    if (integrationOrchestrator != null) {
      integrationOrchestrator.executeWorkflowAction(getAppCode(), saved.getId(), "REJECT", reason);
      integrationOrchestrator.notifyTransactionEvent(saved, "REJECTED");
    }
    return enrichData(budgetTransactionMapper.toData(saved));
  }

  /** Trả về chỉnh sửa (REWORK) giao dịch từ luồng BPMN Workflow. */
  public BudgetTransactionData reworkTransaction(Long id, String comment) {
    BudgetTransaction tx =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    guard().assertNodeAccess(tx.getNodeId(), tx.getNodeCode());
    sm().assertTransactionTransition(tx.getApprovalStatus(), "REWORK", tx.getCode());

    tx.setApprovalStatus("REWORK");
    if (StrUtil.isNotBlank(comment)) {
      tx.setRejectReason(comment);
    }
    return enrichData(budgetTransactionMapper.toData(budgetTransactionService.save(tx)));
  }

  /**
   * Thực thi Điều chuyển Dự toán Thông minh 1-Click (Smart 1-Click Reallocation - UPG-05).
   *
   * <p>Tạo giao dịch ở trạng thái {@code PENDING_APPROVAL} chờ cấp có thẩm quyền thứ 2 phê duyệt
   * theo nguyên tắc 4 mắt SoD (không tự duyệt 1 bước).
   */
  public Map<String, Object> executeSmartReallocation(Map<String, Object> body) {
    int year = parseStrictIntOrDefault(body, "budgetYear", LocalDate.now().getYear());
    String sourceUnitCode =
        extractString(body, "sourceUnitCode", "fromUnitCode", "");
    String targetUnitCode =
        extractString(body, "targetUnitCode", "toUnitCode", "");
    Long sourceAllocId = parseStrictLongOrNull(body, "sourceAllocationId");
    Long targetAllocId = parseStrictLongOrNull(body, "targetAllocationId");
    BigDecimal rawAmount =
        parseStrictBigDecimalOrDefault(
            body,
            "amount",
            parseStrictBigDecimalOrDefault(body, "suggestedTransferAmount", BigDecimal.ZERO));

    List<BudgetAllocation> yearAllocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);
    BudgetAllocation sourceAlloc =
        sourceAllocId != null
            ? budgetAllocationRepo.findById(sourceAllocId).orElse(null)
            : null;
    BudgetAllocation targetAlloc =
        targetAllocId != null
            ? budgetAllocationRepo.findById(targetAllocId).orElse(null)
            : null;

    for (BudgetAllocation a : yearAllocs) {
      if ("REVENUE".equalsIgnoreCase(a.getItemType())) {
        continue;
      }
      if (sourceAlloc == null
          && StrUtil.isNotBlank(sourceUnitCode)
          && sourceUnitCode.equalsIgnoreCase(a.getNodeCode())) {
        sourceAlloc = a;
      } else if (sourceAlloc != null
          && StrUtil.isNotBlank(sourceUnitCode)
          && sourceUnitCode.equalsIgnoreCase(a.getNodeCode())
          && workflowEngineHelper
                  .safe(a.getAvailableAmount())
                  .compareTo(workflowEngineHelper.safe(sourceAlloc.getAvailableAmount()))
              > 0) {
        sourceAlloc = a;
      }
      if (targetAlloc == null
          && StrUtil.isNotBlank(targetUnitCode)
          && targetUnitCode.equalsIgnoreCase(a.getNodeCode())) {
        targetAlloc = a;
      }
    }

    if (sourceAlloc == null) {
      throw new BaseException("Không tìm thấy phân bổ dự toán nguồn để thực hiện điều chuyển.");
    }
    guard().assertNodeAccess(sourceAlloc.getNodeId(), sourceAlloc.getNodeCode());
    if (targetAlloc != null) {
      guard().assertNodeAccess(targetAlloc.getNodeId(), targetAlloc.getNodeCode());
    }

    BigDecimal avail = workflowEngineHelper.safe(sourceAlloc.getAvailableAmount());
    BigDecimal transferAmount =
        rawAmount.compareTo(BigDecimal.ZERO) > 0
            ? rawAmount.min(avail)
            : avail.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);
    if (transferAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException(
          "Số dư dự toán khả dụng của đơn vị nguồn ["
              + sourceAlloc.getCode()
              + "] không đủ để điều chuyển.");
    }

    Map<String, Object> autonomy =
        workflowEngineHelper.checkUnitAutonomy(
            sourceAlloc.getNodeId(), sourceAlloc.getCategoryCode(), transferAmount);
    if (Boolean.FALSE.equals(autonomy.get("canSelfTransfer")) && !hasTransactionApproveOverride()) {
      throw new BaseException(
          "Đơn vị không đủ thẩm quyền tự điều chuyển nội bộ hoặc vượt hạn mức tự chủ — bắt buộc cấp có quyền BudgetTransactionApprove.");
    }

    String txCode = "TX-SMART-" + year + "-" + (System.currentTimeMillis() % 100000);
    String reason =
        body != null && body.get("reason") != null
            ? String.valueOf(body.get("reason"))
            : ("Điều chuyển dự toán thông minh 1-Click từ "
                + sourceAlloc.getNodeName()
                + (targetAlloc != null ? " sang " + targetAlloc.getNodeName() : ""));

    BudgetTransactionData txData =
        BudgetTransactionData.builder()
            .code(txCode)
            .name(reason)
            .allocationId(sourceAlloc.getId())
            .allocationCode(sourceAlloc.getCode())
            .counterpartAllocationId(targetAlloc != null ? targetAlloc.getId() : null)
            .counterpartAllocationCode(targetAlloc != null ? targetAlloc.getCode() : null)
            .nodeId(sourceAlloc.getNodeId())
            .nodeName(sourceAlloc.getNodeName())
            .budgetYear(year)
            .txType("INTERNAL_TRANSFER")
            .entryDirection("DEBIT")
            .txDate(LocalDate.now())
            .amount(transferAmount.abs().negate())
            .decisionNo("QĐ-ĐCNB-" + year + "/STC")
            .decisionDate(LocalDate.now())
            .approvalStatus("PENDING_APPROVAL")
            .description(reason)
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx = createInternal(txData, "PENDING_APPROVAL", false);
    BudgetAllocation updatedSource =
        budgetAllocationRepo.findById(sourceAlloc.getId()).orElse(sourceAlloc);
    BudgetAllocation updatedTarget =
        targetAlloc != null
            ? budgetAllocationRepo.findById(targetAlloc.getId()).orElse(targetAlloc)
            : null;

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("executed", true);
    res.put("approvalStatus", "PENDING_APPROVAL");
    res.put("transaction", createdTx);
    res.put("transferAmount", transferAmount);
    res.put("sourceAllocationCode", updatedSource.getCode());
    res.put("sourceAvailableAfter", updatedSource.getAvailableAmount());
    res.put("targetAllocationCode", updatedTarget != null ? updatedTarget.getCode() : "N/A");
    res.put(
        "targetEffectiveAfter",
        updatedTarget != null ? updatedTarget.getEffectiveAmount() : BigDecimal.ZERO);
    return res;
  }

  /**
   * Nghiệp vụ phụ NV-01: Bổ sung nội dung nhiệm vụ chi hoặc bổ sung dự toán kinh phí đa lĩnh vực
   * trong năm ({@code POST /v1/budget/transactions/supplement-content}). Hỗ trợ phân bổ chi tiết
   * theo từng cột Lĩnh vực động ({@code domainBreakdowns}) hoặc tự động khởi tạo dòng phân bổ mới
   * nếu là nhiệm vụ phát sinh mới hoàn toàn.
   */
  public Map<String, Object> supplementBudgetContent(Map<String, Object> body) {
    int year = parseIntOrDefault(body, "budgetYear", LocalDate.now().getYear());
    String contentTitle =
        extractString(
            body,
            "contentTitle",
            "name",
            "Bổ sung kinh phí nhiệm vụ phát sinh năm " + year);
    String reason = extractString(body, "reason", "description", contentTitle);
    String decisionNo = extractString(body, "decisionNo", "referenceDocumentNo", "QĐ-BS-" + year + "/UBND");
    LocalDate txDate = parseLocalDateOrDefault(body, "txDate", LocalDate.now());
    String domainCode = extractString(body, "domainCode", "domain", "CDS");
    String domainName = extractString(body, "domainName", "domainLabel", "Chuyển đổi số");
    String domainGroupCode = extractString(body, "domainGroupCode", "groupCode", "GRP_INNOV_DIGITAL");
    String domainGroupName =
        extractString(body, "domainGroupName", "groupName", "Nhóm Đổi mới sáng tạo & Chuyển đổi số");

    BigDecimal rawAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO);
    String dynamicBreakdownJson = extractString(body, "dynamicBreakdownJson", null, "");

    if (body != null && body.get("domainBreakdowns") instanceof List<?> rawList && !rawList.isEmpty()) {
      JSONArray arr = new JSONArray();
      BigDecimal sumAlloc = BigDecimal.ZERO;
      for (Object item : rawList) {
        if (item instanceof Map<?, ?> m) {
          String c = m.get("code") != null ? String.valueOf(m.get("code")).trim() : domainCode;
          String n = m.get("name") != null ? String.valueOf(m.get("name")).trim() : c;
          BigDecimal aVal =
              m.get("allocatedAmount") != null
                  ? new BigDecimal(String.valueOf(m.get("allocatedAmount")))
                  : BigDecimal.ZERO;
          JSONObject obj = new JSONObject();
          obj.set("code", c);
          obj.set("name", n);
          obj.set(
              "groupCode",
              m.get("groupCode") != null ? String.valueOf(m.get("groupCode")) : domainGroupCode);
          obj.set(
              "groupName",
              m.get("groupName") != null ? String.valueOf(m.get("groupName")) : domainGroupName);
          obj.set("allocatedAmount", aVal);
          arr.add(obj);
          sumAlloc = sumAlloc.add(aVal);
        }
      }
      dynamicBreakdownJson = arr.toString();
      if (rawAmount.compareTo(BigDecimal.ZERO) == 0) {
        rawAmount = sumAlloc;
      }
    }

    if (rawAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException("Số tiền bổ sung nội dung / dự toán phải lớn hơn 0.");
    }

    BudgetAllocation targetAlloc = resolveOrCreateTargetAllocation(body, year, contentTitle, domainCode, domainName, domainGroupCode, domainGroupName);
    String txCode =
        extractString(
            body,
            "code",
            null,
            "TX-SUPP-" + year + "-" + (System.currentTimeMillis() % 100000));

    Map<String, Object> roleMeta = evaluateOperationRoleGate("NV-01", body, rawAmount);
    BudgetTransactionData txData =
        BudgetTransactionData.builder()
            .code(txCode)
            .name(contentTitle)
            .allocationId(targetAlloc != null ? targetAlloc.getId() : null)
            .allocationCode(targetAlloc != null ? targetAlloc.getCode() : null)
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : resolveRequiredNodeId(body))
            .nodeCode(
                targetAlloc != null
                    ? targetAlloc.getNodeCode()
                    : extractString(body, "nodeCode", "unitNodeCode", null))
            .nodeName(
                targetAlloc != null
                    ? targetAlloc.getNodeName()
                    : extractString(body, "nodeName", "unitNodeName", null))
            .domainCode(domainCode)
            .domainName(domainName)
            .domainGroupCode(domainGroupCode)
            .domainGroupName(domainGroupName)
            .dynamicBreakdownJson(StrUtil.isNotBlank(dynamicBreakdownJson) ? dynamicBreakdownJson : null)
            .budgetYear(year)
            .txType("SUPPLEMENT")
            .entryDirection("CREDIT")
            .txDate(txDate)
            .amount(rawAmount)
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(contentTitle + " (" + decisionNo + ")")
            .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx =
        createInternal(txData, String.valueOf(roleMeta.get("effectiveApprovalStatus")), false);
    BudgetAllocation refreshed =
        targetAlloc != null && targetAlloc.getId() != null
            ? budgetAllocationRepo.findById(targetAlloc.getId()).orElse(targetAlloc)
            : targetAlloc;

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("supplemented", true);
    res.put("operationType", "SUPPLEMENT_CONTENT");
    res.put("contentTitle", contentTitle);
    res.put("supplementedAmount", rawAmount);
    res.put("allocationCode", refreshed != null ? refreshed.getCode() : "N/A");
    res.put(
        "effectiveAmountAfter",
        refreshed != null ? workflowEngineHelper.safe(refreshed.getEffectiveAmount()) : rawAmount);
    res.put(
        "availableAmountAfter",
        refreshed != null ? workflowEngineHelper.safe(refreshed.getAvailableAmount()) : rawAmount);
    res.put("roleExecutionMeta", roleMeta);
    res.put("transaction", createdTx);
    return res;
  }

  /**
   * Nghiệp vụ phụ NV-02: Chuyển nguồn kinh phí liên năm ({@code fromYear -> toYear}, ví dụ {@code
   * 2025 -> 2026}) có kiểm tra điều kiện tự chủ NĐ 60/2021 và tự động ánh xạ mã Lĩnh vực năm cũ
   * sang mã Lĩnh vực năm mới qua {@code previousYearDomainCode} ({@code POST
   * /v1/budget/transactions/carry-over-year}).
   */
  public Map<String, Object> executeYearCarryOver(Map<String, Object> body) {
    int fromYear =
        parseIntOrDefault(
            body, "fromYear", parseIntOrDefault(body, "fromFiscalYear", LocalDate.now().getYear() - 1));
    int toYear =
        parseIntOrDefault(
            body, "toYear", parseIntOrDefault(body, "toFiscalYear", fromYear + 1));
    if (toYear <= fromYear) {
      throw new BaseException(
          "Năm đích chuyển nguồn ("
              + toYear
              + ") phải lớn hơn năm ngân sách nguồn ("
              + fromYear
              + ").");
    }
    String decisionNo =
        extractString(body, "decisionNo", "referenceDocumentNo", "TB-CN-" + toYear + "/KBNN");
    LocalDate txDate = parseLocalDateOrDefault(body, "txDate", LocalDate.now());

    BudgetAllocation sourceAlloc = resolveExistingAllocation(body, fromYear);
    if (sourceAlloc == null) {
      throw new BaseException(
          "Không tìm thấy phân bổ dự toán nguồn năm "
              + fromYear
              + " để thực hiện chuyển nguồn liên năm (cấm tự khởi tạo phân bổ nguồn giả).");
    }
    guard().assertNodeAccess(sourceAlloc.getNodeId(), sourceAlloc.getNodeCode());
    sourceAlloc = workflowEngineHelper.lockAllocation(sourceAlloc.getId()).orElse(sourceAlloc);

    String sourceDomainCode =
        extractString(
            body,
            "sourceDomainCode",
            "domainCode",
            StrUtil.isNotBlank(sourceAlloc.getDomainCode()) ? sourceAlloc.getDomainCode() : "TCDLCL");
    String sourceDomainName =
        extractString(
            body,
            "sourceDomainName",
            "domainName",
            StrUtil.isNotBlank(sourceAlloc.getDomainName())
                ? sourceAlloc.getDomainName()
                : "Tiêu chuẩn Đo lường Chất lượng");

    // Tự động tra cứu cấu hình Lĩnh vực năm đích (toYear) để ánh xạ previousYearDomainCode -> domainCode mới
    String mappedDomainCode = sourceDomainCode;
    String mappedDomainName = sourceDomainName;
    String mappedGroupCode =
        StrUtil.isNotBlank(sourceAlloc.getDomainGroupCode())
            ? sourceAlloc.getDomainGroupCode()
            : "GRP_CORE_TECH";
    String mappedGroupName =
        StrUtil.isNotBlank(sourceAlloc.getDomainGroupName())
            ? sourceAlloc.getDomainGroupName()
            : "Nhóm KHCN & Hạ tầng";

    if (budgetCategoryRepo != null) {
      List<BudgetCategory> targetYearConfigs =
          budgetCategoryRepo.findByBudgetYearAndCategoryGroupInOrderBySortOrderAscIdAsc(
              toYear, List.of("DOMAIN", "DOMAIN_GROUP"));
      for (BudgetCategory cfg : targetYearConfigs) {
        if ("DOMAIN".equalsIgnoreCase(cfg.getCategoryGroup())) {
          String dCode =
              StrUtil.isNotBlank(cfg.getDomainCode()) ? cfg.getDomainCode().trim() : cfg.getCode();
          if (sourceDomainCode.equalsIgnoreCase(
                  StrUtil.nullToEmpty(cfg.getPreviousYearDomainCode()).trim())
              || sourceDomainCode.equalsIgnoreCase(dCode)) {
            mappedDomainCode = dCode;
            mappedDomainName = cfg.getName();
            if (StrUtil.isNotBlank(cfg.getDomainGroupCode())) {
              mappedGroupCode = cfg.getDomainGroupCode();
            }
            if (StrUtil.isNotBlank(cfg.getDomainGroupName())) {
              mappedGroupName = cfg.getDomainGroupName();
            }
            break;
          }
        }
      }
    }

    BigDecimal carryAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO);
    if (carryAmount.compareTo(BigDecimal.ZERO) <= 0) {
      carryAmount = workflowEngineHelper.safe(sourceAlloc.getAvailableAmount());
    }
    if (carryAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException("Số tiền chuyển nguồn liên năm (amount) phải lớn hơn 0.");
    }

    // Kiểm tra điều kiện được phép chuyển nguồn theo NĐ 60/2021 và Mục lục NSNN (TD-C07 / NV-02)
    Map<String, Object> autonomyCheck =
        workflowEngineHelper.checkUnitAutonomy(
            sourceAlloc.getNodeId(), sourceAlloc.getCategoryCode(), carryAmount);
    if (Boolean.FALSE.equals(autonomyCheck.get("canCarryOver"))) {
      throw new BaseException(
          "Đơn vị hoặc mục lục ngân sách ["
              + StrUtil.blankToDefault(sourceAlloc.getCategoryCode(), sourceAlloc.getCode())
              + "] không được phép chuyển nguồn liên năm theo cấu hình tự chủ / Luật NSNN.");
    }

    BigDecimal sourceAvail = workflowEngineHelper.safe(sourceAlloc.getAvailableAmount());
    if (carryAmount.compareTo(sourceAvail) > 0) {
      throw new BaseException(
          "Số tiền chuyển nguồn ("
              + carryAmount.toPlainString()
              + " VNĐ) vượt quá số dư khả dụng ("
              + sourceAvail.toPlainString()
              + " VNĐ) của phân bổ nguồn ["
              + sourceAlloc.getCode()
              + "].");
    }

    String reason =
        extractString(
            body,
            "reason",
            "description",
            "Chuyển nguồn kinh phí năm "
                + fromYear
                + " sang năm "
                + toYear
                + " theo Nghị định 60/2021/NĐ-CP ("
                + sourceDomainCode
                + " -> "
                + mappedDomainCode
                + ")");
    String txCode =
        extractString(
            body,
            "code",
            null,
            "TX-CO-" + fromYear + "-" + toYear + "-" + (System.currentTimeMillis() % 100000));

    Map<String, Object> roleMeta = evaluateOperationRoleGate("NV-02", body, carryAmount);
    BudgetTransactionData txData =
        BudgetTransactionData.builder()
            .code(txCode)
            .name("Chuyển nguồn năm " + fromYear + " sang năm " + toYear + " (" + sourceAlloc.getNodeName() + ")")
            .allocationId(sourceAlloc.getId())
            .allocationCode(sourceAlloc.getCode())
            .nodeId(sourceAlloc.getNodeId())
            .nodeCode(sourceAlloc.getNodeCode())
            .nodeName(sourceAlloc.getNodeName())
            .domainCode(mappedDomainCode)
            .domainName(mappedDomainName)
            .domainGroupCode(mappedGroupCode)
            .domainGroupName(mappedGroupName)
            .budgetYear(fromYear)
            .targetBudgetYear(toYear)
            .txType("CARRY_OVER_OUT")
            .entryDirection("DEBIT")
            .txDate(txDate)
            .amount(carryAmount)
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(reason)
            .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx =
        createInternal(txData, String.valueOf(roleMeta.get("effectiveApprovalStatus")), false);

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("carriedOver", true);
    res.put("operationType", "CARRY_OVER_YEAR");
    res.put("fromYear", fromYear);
    res.put("toYear", toYear);
    res.put("sourceAllocationCode", sourceAlloc.getCode());
    res.put("targetAllocationCode", createdTx.getCounterpartAllocationCode());
    res.put("sourceDomainCode", sourceDomainCode);
    res.put("mappedTargetDomainCode", mappedDomainCode);
    res.put("mappedTargetDomainName", mappedDomainName);
    res.put("carryOverAmount", carryAmount);
    res.put("roleExecutionMeta", roleMeta);
    res.put("transaction", createdTx);
    return res;
  }

  /**
   * Nghiệp vụ phụ NV-03: Điều chuyển nội bộ chéo giữa các Lĩnh vực trong cùng một đơn vị với Tổng
   * biến động đơn vị = 0 ({@code POST /v1/budget/transactions/cross-domain-transfer}), ví dụ: điều
   * chuyển {@code -2,655} từ {@code KHCN} sang {@code +2,655} ở {@code DMST}.
   */
  public Map<String, Object> executeCrossDomainTransfer(Map<String, Object> body) {
    int year = parseIntOrDefault(body, "budgetYear", LocalDate.now().getYear());
    String fromDomainCode = extractString(body, "fromDomainCode", "sourceDomainCode", "KHCN");
    String fromDomainName =
        extractString(body, "fromDomainName", "sourceDomainName", "Khoa học công nghệ");
    String toDomainCode = extractString(body, "toDomainCode", "targetDomainCode", "DMST");
    String toDomainName =
        extractString(body, "toDomainName", "targetDomainName", "Đổi mới sáng tạo");
    BigDecimal transferAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO).abs();
    if (transferAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException("Số tiền điều chuyển chéo giữa các lĩnh vực phải lớn hơn 0.");
    }
    if (fromDomainCode.equalsIgnoreCase(toDomainCode)) {
      throw new BaseException("Lĩnh vực nguồn và Lĩnh vực đích phải khác nhau khi điều chuyển chéo.");
    }

    String decisionNo =
        extractString(body, "decisionNo", "referenceDocumentNo", "QĐ-ĐCLV-" + year + "/SKHCN");
    LocalDate txDate = parseLocalDateOrDefault(body, "txDate", LocalDate.now());
    String reason =
        extractString(
            body,
            "reason",
            "description",
            "Điều chuyển nội bộ giữa các lĩnh vực ("
                + fromDomainCode
                + " -> "
                + toDomainCode
                + ", Tổng biến động = 0)");

    JSONArray breakdownArr = new JSONArray();
    JSONObject fromObj = new JSONObject();
    fromObj.set("code", fromDomainCode);
    fromObj.set("name", fromDomainName);
    fromObj.set("allocatedAmount", transferAmount.negate());
    fromObj.set("disbursedAmount", BigDecimal.ZERO);
    breakdownArr.add(fromObj);

    JSONObject toObj = new JSONObject();
    toObj.set("code", toDomainCode);
    toObj.set("name", toDomainName);
    toObj.set("allocatedAmount", transferAmount);
    toObj.set("disbursedAmount", BigDecimal.ZERO);
    breakdownArr.add(toObj);

    BudgetAllocation unitAlloc = resolveExistingAllocation(body, year);
    if (unitAlloc == null) {
      throw new BaseException(
          "Không tìm thấy phân bổ dự toán của đơn vị năm "
              + year
              + " để thực hiện điều chuyển chéo giữa các lĩnh vực.");
    }
    guard().assertNodeAccess(unitAlloc.getNodeId(), unitAlloc.getNodeCode());
    unitAlloc = workflowEngineHelper.lockAllocation(unitAlloc.getId()).orElse(unitAlloc);
    BigDecimal sourceAvail = workflowEngineHelper.safe(unitAlloc.getAvailableAmount());
    if (transferAmount.compareTo(sourceAvail) > 0) {
      throw new BaseException(
          "Số tiền điều chuyển chéo ("
              + transferAmount.toPlainString()
              + " VNĐ) vượt quá số dư khả dụng ("
              + sourceAvail.toPlainString()
              + " VNĐ) của phân bổ ["
              + unitAlloc.getCode()
              + "].");
    }

    String txCode =
        extractString(
            body,
            "code",
            null,
            "TX-XDOM-" + year + "-" + (System.currentTimeMillis() % 100000));

    Map<String, Object> roleMeta = evaluateOperationRoleGate("NV-03", body, transferAmount);
    BudgetTransactionData txData =
        BudgetTransactionData.builder()
            .code(txCode)
            .name("Điều chuyển nội bộ giữa các lĩnh vực (" + fromDomainCode + " -> " + toDomainCode + ")")
            .allocationId(unitAlloc != null ? unitAlloc.getId() : null)
            .allocationCode(unitAlloc != null ? unitAlloc.getCode() : null)
            .nodeId(unitAlloc != null ? unitAlloc.getNodeId() : resolveRequiredNodeId(body))
            .nodeCode(
                unitAlloc != null
                    ? unitAlloc.getNodeCode()
                    : extractString(body, "nodeCode", "unitNodeCode", null))
            .nodeName(
                unitAlloc != null
                    ? unitAlloc.getNodeName()
                    : extractString(body, "nodeName", "unitNodeName", null))
            .domainCode(toDomainCode)
            .domainName(toDomainName)
            .dynamicBreakdownJson(breakdownArr.toString())
            .budgetYear(year)
            .txType("INTERNAL_TRANSFER")
            .txDate(txDate)
            .amount(BigDecimal.ZERO)
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(reason)
            .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx =
        createInternal(txData, String.valueOf(roleMeta.get("effectiveApprovalStatus")), false);

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("transferred", true);
    res.put("operationType", "CROSS_DOMAIN_TRANSFER");
    res.put("fromDomainCode", fromDomainCode);
    res.put("toDomainCode", toDomainCode);
    res.put("transferAmount", transferAmount);
    res.put("netUnitChange", BigDecimal.ZERO);
    res.put("dynamicBreakdownJson", breakdownArr.toString());
    res.put("roleExecutionMeta", roleMeta);
    res.put("transaction", createdTx);
    return res;
  }

  /**
   * Nghiệp vụ phụ NV-04: Tiết kiệm chi thường xuyên theo Nghị quyết (ví dụ NQ 135), Điều chỉnh giảm
   * theo Phê duyệt Phương án tự chủ, Điều chỉnh giảm do thừa hoặc Giảm kinh phí CNTT ({@code POST
   * /v1/budget/transactions/saving-reduction}).
   */
  public Map<String, Object> executeSavingAndReduction(Map<String, Object> body) {
    int year = parseIntOrDefault(body, "budgetYear", LocalDate.now().getYear());
    String reductionSubtype =
        extractString(body, "reductionSubtype", "subtype", "SAVING_NQ135").toUpperCase();
    String defaultLabel =
        switch (reductionSubtype) {
          case "AUTONOMY_PLAN" -> "Điều chỉnh giảm theo Phê duyệt Phương án tự chủ";
          case "SURPLUS_RETURN" -> "Điều chỉnh giảm do thừa dự toán";
          case "IT_REDUCTION" -> "Điều chỉnh giảm kinh phí CNTT";
          default -> "Tiết kiệm chi thường xuyên theo Nghị quyết 135";
        };
    String title = extractString(body, "contentTitle", "name", defaultLabel);
    String reason = extractString(body, "reason", "description", title);
    String decisionNo =
        extractString(body, "decisionNo", "referenceDocumentNo", "NQ-135/CP-" + year);
    LocalDate txDate = parseLocalDateOrDefault(body, "txDate", LocalDate.now());
    String domainCode = extractString(body, "domainCode", "domain", "KHCN");
    String domainName = extractString(body, "domainName", "domainLabel", "Khoa học công nghệ");

    BudgetAllocation targetAlloc =
        resolveOrCreateTargetAllocation(
            body, year, title, domainCode, domainName, "GRP_CORE_TECH", "Nhóm KHCN & Hạ tầng");

    BigDecimal savingRatePercent =
        parseBigDecimalOrDefault(body, "savingRatePercent", BigDecimal.ZERO);
    BigDecimal rawAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO).abs();
    String dynamicBreakdownJson = null;

    if (body != null && body.get("domainBreakdowns") instanceof List<?> rawList && !rawList.isEmpty()) {
      JSONArray arr = new JSONArray();
      BigDecimal sumAbs = BigDecimal.ZERO;
      for (Object item : rawList) {
        if (item instanceof Map<?, ?> m) {
          String c = m.get("code") != null ? String.valueOf(m.get("code")).trim() : domainCode;
          String n = m.get("name") != null ? String.valueOf(m.get("name")).trim() : c;
          BigDecimal aVal =
              m.get("allocatedAmount") != null
                  ? new BigDecimal(String.valueOf(m.get("allocatedAmount"))).abs().negate()
                  : BigDecimal.ZERO;
          JSONObject obj = new JSONObject();
          obj.set("code", c);
          obj.set("name", n);
          obj.set("allocatedAmount", aVal);
          obj.set("disbursedAmount", BigDecimal.ZERO);
          arr.add(obj);
          sumAbs = sumAbs.add(aVal.abs());
        }
      }
      dynamicBreakdownJson = arr.toString();
      if (sumAbs.compareTo(BigDecimal.ZERO) > 0) {
        rawAmount = sumAbs;
      }
    } else if (rawAmount.compareTo(BigDecimal.ZERO) == 0
        && savingRatePercent.compareTo(BigDecimal.ZERO) > 0
        && targetAlloc != null) {
      BigDecimal base =
          workflowEngineHelper.safe(targetAlloc.getInitialAmount()).compareTo(BigDecimal.ZERO) > 0
              ? targetAlloc.getInitialAmount()
              : workflowEngineHelper.safe(targetAlloc.getEffectiveAmount());
      rawAmount =
          base.multiply(savingRatePercent)
              .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    if (rawAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException("Số tiền tiết kiệm / điều chỉnh giảm dự toán phải lớn hơn 0.");
    }

    String txCode =
        extractString(
            body,
            "code",
            null,
            "TX-RED-" + year + "-" + (System.currentTimeMillis() % 100000));

    Map<String, Object> roleMeta = evaluateOperationRoleGate("NV-04", body, rawAmount);
    BudgetTransactionData txData =
        BudgetTransactionData.builder()
            .code(txCode)
            .name(title)
            .allocationId(targetAlloc != null ? targetAlloc.getId() : null)
            .allocationCode(targetAlloc != null ? targetAlloc.getCode() : null)
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : resolveRequiredNodeId(body))
            .nodeCode(
                targetAlloc != null
                    ? targetAlloc.getNodeCode()
                    : extractString(body, "nodeCode", "unitNodeCode", null))
            .nodeName(
                targetAlloc != null
                    ? targetAlloc.getNodeName()
                    : extractString(body, "nodeName", "unitNodeName", null))
            .domainCode(domainCode)
            .domainName(domainName)
            .dynamicBreakdownJson(dynamicBreakdownJson)
            .budgetYear(year)
            .txType("ADJUSTMENT")
            .txDate(txDate)
            .amount(rawAmount.negate())
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(title + " (" + decisionNo + ")")
            .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx =
        createInternal(txData, String.valueOf(roleMeta.get("effectiveApprovalStatus")), false);
    BudgetAllocation refreshed =
        targetAlloc != null && targetAlloc.getId() != null
            ? budgetAllocationRepo.findById(targetAlloc.getId()).orElse(targetAlloc)
            : targetAlloc;

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("reduced", true);
    res.put("operationType", "SAVING_AND_REDUCTION");
    res.put("reductionSubtype", reductionSubtype);
    res.put("totalReducedAmount", rawAmount);
    res.put("signedAdjustmentAmount", rawAmount.negate());
    res.put("allocationCode", refreshed != null ? refreshed.getCode() : "N/A");
    res.put(
        "effectiveAmountAfter",
        refreshed != null ? workflowEngineHelper.safe(refreshed.getEffectiveAmount()) : BigDecimal.ZERO);
    res.put(
        "availableAmountAfter",
        refreshed != null ? workflowEngineHelper.safe(refreshed.getAvailableAmount()) : BigDecimal.ZERO);
    res.put("roleExecutionMeta", roleMeta);
    res.put("transaction", createdTx);
    return res;
  }

  /**
   * Nghiệp vụ phụ NV-05: Thu hồi / Hủy dự toán hết niên độ nộp trả NSNN đối với kinh phí không
   * được phép chuyển nguồn ({@code POST /v1/budget/transactions/cancel-expired}).
   */
  public Map<String, Object> cancelExpiredAllocation(Map<String, Object> body) {
    int year = parseIntOrDefault(body, "budgetYear", LocalDate.now().getYear());
    BudgetAllocation targetAlloc = resolveExistingAllocation(body, year);
    if (targetAlloc == null) {
      throw new BaseException(
          "Không tìm thấy phân bổ dự toán năm "
              + year
              + " để thực hiện hủy / thu hồi dự toán hết niên độ.");
    }
    guard().assertNodeAccess(targetAlloc.getNodeId(), targetAlloc.getNodeCode());
    targetAlloc = workflowEngineHelper.lockAllocation(targetAlloc.getId()).orElse(targetAlloc);

    BigDecimal cancelAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO).abs();
    if (cancelAmount.compareTo(BigDecimal.ZERO) <= 0) {
      cancelAmount = workflowEngineHelper.safe(targetAlloc.getAvailableAmount());
    }
    if (cancelAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException("Số tiền hủy / thu hồi dự toán hết niên độ phải lớn hơn 0.");
    }
    BigDecimal avail = workflowEngineHelper.safe(targetAlloc.getAvailableAmount());
    if (cancelAmount.compareTo(avail) > 0) {
      throw new BaseException(
          "Số tiền hủy hết niên độ ("
              + cancelAmount.toPlainString()
              + " VNĐ) vượt quá số dư khả dụng còn lại ("
              + avail.toPlainString()
              + " VNĐ) của phân bổ ["
              + targetAlloc.getCode()
              + "].");
    }

    Map<String, Object> roleMeta = evaluateOperationRoleGate("NV-05", body, cancelAmount);

    String decisionNo =
        extractString(body, "decisionNo", "referenceDocumentNo", "TB-HUY-" + year + "/KBNN");
    LocalDate txDate = parseLocalDateOrDefault(body, "txDate", LocalDate.now());
    String reason =
        extractString(
            body,
            "reason",
            "description",
            "Hủy dự toán còn dư hết thời hạn chỉnh lý quyết toán nộp trả NSNN (" + decisionNo + ")");
    String txCode =
        extractString(
            body,
            "code",
            null,
            "TX-EXP-" + year + "-" + (System.currentTimeMillis() % 100000));

    BudgetTransactionData txData =
        BudgetTransactionData.builder()
            .code(txCode)
            .name("Hủy / Thu hồi dự toán hết niên độ năm " + year)
            .allocationId(targetAlloc != null ? targetAlloc.getId() : null)
            .allocationCode(targetAlloc != null ? targetAlloc.getCode() : null)
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : resolveRequiredNodeId(body))
            .nodeCode(
                targetAlloc != null
                    ? targetAlloc.getNodeCode()
                    : extractString(body, "nodeCode", "unitNodeCode", null))
            .nodeName(
                targetAlloc != null
                    ? targetAlloc.getNodeName()
                    : extractString(body, "nodeName", "unitNodeName", null))
            .budgetYear(year)
            .txType("WITHDRAWAL")
            .txDate(txDate)
            .amount(cancelAmount.negate())
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(reason)
            .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx =
        createInternal(txData, String.valueOf(roleMeta.get("effectiveApprovalStatus")), false);
    BudgetAllocation refreshed =
        targetAlloc != null && targetAlloc.getId() != null
            ? budgetAllocationRepo.findById(targetAlloc.getId()).orElse(targetAlloc)
            : targetAlloc;

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("cancelled", true);
    res.put("operationType", "CANCEL_EXPIRED");
    res.put("cancelledAmount", cancelAmount);
    res.put("allocationCode", refreshed != null ? refreshed.getCode() : "N/A");
    res.put(
        "expiredCancelledTotal",
        refreshed != null
            ? workflowEngineHelper.safe(refreshed.getExpiredCancelledAmount())
            : cancelAmount);
    res.put(
        "availableAmountAfter",
        refreshed != null
            ? workflowEngineHelper.safe(refreshed.getAvailableAmount())
            : BigDecimal.ZERO);
    res.put("roleExecutionMeta", roleMeta);
    res.put("transaction", createdTx);
    return res;
  }

  /**
   * Nghiệp vụ phụ nâng cao DX-01: Tạm giữ / Phong tỏa hoặc Giải tỏa dự toán chờ duyệt chủ trương
   * ({@code POST /v1/budget/transactions/earmark-hold}).
   */
  public Map<String, Object> executeEarmarkHold(Map<String, Object> body) {
    int year = parseIntOrDefault(body, "budgetYear", LocalDate.now().getYear());
    String holdAction =
        extractString(body, "holdAction", "actionMode", "HOLD").toUpperCase();
    boolean isRelease = "RELEASE".equals(holdAction) || "EARMARK_RELEASE".equals(holdAction);
    String txType = isRelease ? "EARMARK_RELEASE" : "EARMARK_HOLD";

    BudgetAllocation targetAlloc = resolveExistingAllocation(body, year);
    if (targetAlloc == null) {
      throw new BaseException(
          "Không tìm thấy phân bổ dự toán năm "
              + year
              + " để thực hiện tạm giữ / giải tỏa phong tỏa.");
    }
    guard().assertNodeAccess(targetAlloc.getNodeId(), targetAlloc.getNodeCode());
    targetAlloc = workflowEngineHelper.lockAllocation(targetAlloc.getId()).orElse(targetAlloc);

    BigDecimal holdAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO).abs();
    if (holdAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException("Số tiền tạm giữ / giải tỏa dự toán phải lớn hơn 0.");
    }
    if (isRelease) {
      BigDecimal currentlyHeld =
          workflowEngineHelper.computeCurrentEarmarkHeldAmount(targetAlloc.getId());
      if (currentlyHeld.compareTo(BigDecimal.ZERO) <= 0 || holdAmount.compareTo(currentlyHeld) > 0) {
        throw new BaseException(
            "Số tiền giải tỏa ("
                + holdAmount.toPlainString()
                + " VNĐ) vượt quá số dư đang bị tạm giữ phong tỏa ("
                + currentlyHeld.toPlainString()
                + " VNĐ) của phân bổ ["
                + targetAlloc.getCode()
                + "].");
      }
    } else {
      BigDecimal avail = workflowEngineHelper.safe(targetAlloc.getAvailableAmount());
      if (holdAmount.compareTo(avail) > 0) {
        throw new BaseException(
            "Số tiền tạm giữ phong tỏa ("
                + holdAmount.toPlainString()
                + " VNĐ) vượt quá số dư khả dụng ("
                + avail.toPlainString()
                + " VNĐ) của phân bổ ["
                + targetAlloc.getCode()
                + "].");
      }
    }

    String decisionNo =
        extractString(
            body,
            "decisionNo",
            "referenceDocumentNo",
            (isRelease ? "CV-GTPT-" : "CV-PTDT-") + year + "/STC");
    LocalDate txDate = parseLocalDateOrDefault(body, "txDate", LocalDate.now());
    String defaultReason =
        isRelease
            ? "Giải tỏa dự toán tạm giữ sau khi có quyết định phê duyệt đề án (" + decisionNo + ")"
            : "Tạm giữ / Phong tỏa dự toán chờ thẩm định chủ trương đề án (" + decisionNo + ")";
    String reason = extractString(body, "reason", "description", defaultReason);
    String txCode =
        extractString(
            body,
            "code",
            null,
            (isRelease ? "TX-REL-" : "TX-HOLD-") + year + "-" + (System.currentTimeMillis() % 100000));

    Map<String, Object> roleMeta = evaluateOperationRoleGate("DX-01", body, holdAmount);
    BudgetTransactionData txData =
        BudgetTransactionData.builder()
            .code(txCode)
            .name((isRelease ? "Giải tỏa phong tỏa: " : "Tạm giữ / Phong tỏa: ") + reason)
            .allocationId(targetAlloc != null ? targetAlloc.getId() : null)
            .allocationCode(targetAlloc != null ? targetAlloc.getCode() : null)
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : resolveRequiredNodeId(body))
            .nodeCode(
                targetAlloc != null
                    ? targetAlloc.getNodeCode()
                    : extractString(body, "nodeCode", "unitNodeCode", null))
            .nodeName(
                targetAlloc != null
                    ? targetAlloc.getNodeName()
                    : extractString(body, "nodeName", "unitNodeName", null))
            .domainCode(targetAlloc != null ? targetAlloc.getDomainCode() : "CDS")
            .domainName(targetAlloc != null ? targetAlloc.getDomainName() : "Chuyển đổi số")
            .budgetYear(year)
            .txType(txType)
            .txDate(txDate)
            .amount(isRelease ? holdAmount.negate() : holdAmount)
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(reason)
            .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx =
        createInternal(txData, String.valueOf(roleMeta.get("effectiveApprovalStatus")), false);
    BudgetAllocation refreshed =
        targetAlloc != null && targetAlloc.getId() != null
            ? budgetAllocationRepo.findById(targetAlloc.getId()).orElse(targetAlloc)
            : targetAlloc;

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("executed", true);
    res.put("operationType", "EARMARK_HOLD");
    res.put("holdAction", isRelease ? "RELEASE" : "HOLD");
    res.put("txType", txType);
    res.put("holdAmount", holdAmount);
    res.put("allocationCode", refreshed != null ? refreshed.getCode() : "N/A");
    res.put(
        "committedAmountAfter",
        refreshed != null
            ? workflowEngineHelper.safe(refreshed.getCommittedAmount())
            : (isRelease ? BigDecimal.ZERO : holdAmount));
    res.put(
        "availableAmountAfter",
        refreshed != null
            ? workflowEngineHelper.safe(refreshed.getAvailableAmount())
            : BigDecimal.ZERO);
    res.put("roleExecutionMeta", roleMeta);
    res.put("transaction", createdTx);
    return res;
  }

  /**
   * Nghiệp vụ phụ nâng cao DX-02: Giải tỏa / Cấp phát dự toán giao đầu năm theo đợt hoặc theo tiến
   * độ thu sự nghiệp ({@code POST /v1/budget/transactions/tranche-release}).
   */
  public Map<String, Object> executeTrancheRelease(Map<String, Object> body) {
    int year = parseIntOrDefault(body, "budgetYear", LocalDate.now().getYear());
    String trancheCode =
        extractString(body, "trancheCode", "tranche", "TRANCHE_Q3").toUpperCase();
    String trancheName =
        switch (trancheCode) {
          case "TRANCHE_Q1" -> "Đợt 1 (Quý I)";
          case "TRANCHE_Q2" -> "Đợt 2 (Quý II)";
          case "TRANCHE_Q3" -> "Đợt 3 (Quý III)";
          case "TRANCHE_Q4" -> "Đợt 4 (6 tháng cuối năm / Quý IV)";
          case "REVENUE_PROGRESS" -> "Giải tỏa theo tiến độ thực thu phí/lệ phí sự nghiệp";
          default -> "Giải tỏa dự toán theo đợt (" + trancheCode + ")";
        };

    BudgetAllocation targetAlloc = resolveExistingAllocation(body, year);
    if (targetAlloc == null) {
      throw new BaseException(
          "Không tìm thấy phân bổ dự toán năm " + year + " để giải tỏa hạn mức theo đợt.");
    }
    guard().assertNodeAccess(targetAlloc.getNodeId(), targetAlloc.getNodeCode());
    targetAlloc = workflowEngineHelper.lockAllocation(targetAlloc.getId()).orElse(targetAlloc);

    BigDecimal releaseRatePercent =
        parseBigDecimalOrDefault(body, "releaseRatePercent", new BigDecimal("25"));
    BigDecimal releaseAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO).abs();
    if (releaseAmount.compareTo(BigDecimal.ZERO) == 0) {
      BigDecimal base =
          workflowEngineHelper.safe(targetAlloc.getInitialAmount()).compareTo(BigDecimal.ZERO) > 0
              ? targetAlloc.getInitialAmount()
              : workflowEngineHelper.safe(targetAlloc.getEffectiveAmount());
      releaseAmount =
          base.multiply(releaseRatePercent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
    if (releaseAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException("Số tiền giải tỏa dự toán theo đợt phải lớn hơn 0.");
    }
    BigDecimal heldBalance =
        workflowEngineHelper.computeCurrentEarmarkHeldAmount(targetAlloc.getId());
    if (heldBalance.compareTo(BigDecimal.ZERO) <= 0 || releaseAmount.compareTo(heldBalance) > 0) {
      throw new BaseException(
          "Không thể giải tỏa theo đợt ("
              + releaseAmount.toPlainString()
              + " VNĐ) vì số dư đang tạm giữ/phong tỏa hiện tại chỉ còn "
              + heldBalance.toPlainString()
              + " VNĐ trên phân bổ ["
              + targetAlloc.getCode()
              + "].");
    }

    String decisionNo =
        extractString(body, "decisionNo", "referenceDocumentNo", "TB-GT-" + trancheCode + "-" + year);
    LocalDate txDate = parseLocalDateOrDefault(body, "txDate", LocalDate.now());
    String reason =
        extractString(
            body,
            "reason",
            "description",
            "Giải tỏa hạn mức dự toán "
                + trancheName
                + " ("
                + releaseRatePercent
                + "%) theo "
                + decisionNo);
    String txCode =
        extractString(
            body,
            "code",
            null,
            "TX-TRN-" + year + "-" + (System.currentTimeMillis() % 100000));

    Map<String, Object> roleMeta = evaluateOperationRoleGate("DX-02", body, releaseAmount);
    BudgetTransactionData txData =
        BudgetTransactionData.builder()
            .code(txCode)
            .name("Giải tỏa dự toán " + trancheName)
            .allocationId(targetAlloc != null ? targetAlloc.getId() : null)
            .allocationCode(targetAlloc != null ? targetAlloc.getCode() : null)
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : resolveRequiredNodeId(body))
            .nodeCode(
                targetAlloc != null
                    ? targetAlloc.getNodeCode()
                    : extractString(body, "nodeCode", "unitNodeCode", null))
            .nodeName(
                targetAlloc != null
                    ? targetAlloc.getNodeName()
                    : extractString(body, "nodeName", "unitNodeName", null))
            .domainCode(targetAlloc != null ? targetAlloc.getDomainCode() : "KHCN")
            .domainName(targetAlloc != null ? targetAlloc.getDomainName() : "Khoa học công nghệ")
            .budgetYear(year)
            .txType("TRANCHE_RELEASE")
            .txDate(txDate)
            .amount(releaseAmount)
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(reason)
            .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx =
        createInternal(txData, String.valueOf(roleMeta.get("effectiveApprovalStatus")), false);
    BudgetAllocation refreshed =
        targetAlloc != null && targetAlloc.getId() != null
            ? budgetAllocationRepo.findById(targetAlloc.getId()).orElse(targetAlloc)
            : targetAlloc;

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("released", true);
    res.put("operationType", "TRANCHE_RELEASE");
    res.put("trancheCode", trancheCode);
    res.put("trancheName", trancheName);
    res.put("releaseRatePercent", releaseRatePercent);
    res.put("releasedAmount", releaseAmount);
    res.put("allocationCode", refreshed != null ? refreshed.getCode() : "N/A");
    res.put(
        "effectiveAmountAfter",
        refreshed != null
            ? workflowEngineHelper.safe(refreshed.getEffectiveAmount())
            : releaseAmount);
    res.put(
        "availableAmountAfter",
        refreshed != null
            ? workflowEngineHelper.safe(refreshed.getAvailableAmount())
            : releaseAmount);
    res.put("roleExecutionMeta", roleMeta);
    res.put("transaction", createdTx);
    return res;
  }

  /**
   * Nghiệp vụ phụ nâng cao DX-03: Điều chỉnh dự toán hàng loạt theo biến động mức lương cơ sở &
   * biên chế giao ({@code POST /v1/budget/transactions/batch-payroll-indexation}).
   */
  public Map<String, Object> executeBatchPayrollIndexation(Map<String, Object> body) {
    int year = parseIntOrDefault(body, "budgetYear", LocalDate.now().getYear());
    BigDecimal oldBaseSalary =
        parseBigDecimalOrDefault(body, "oldBaseSalary", new BigDecimal("1800000"));
    BigDecimal newBaseSalary =
        parseBigDecimalOrDefault(body, "newBaseSalary", new BigDecimal("2340000"));
    int staffChangeCount = parseIntOrDefault(body, "staffChangeCount", 0);
    int monthsRemaining = parseIntOrDefault(body, "monthsRemaining", 6);
    BigDecimal avgCoefficient =
        parseBigDecimalOrDefault(body, "averageSalaryCoefficient", new BigDecimal("3.65"));
    BigDecimal insuranceContributionFactor = new BigDecimal("1.235"); // 23.5% các khoản đóng góp theo lương

    List<BudgetAllocation> targetAllocs = new ArrayList<>();
    OrgSubtreeAuthorizationHelper.OrgSubtreeScope callerScope = guard().resolveCurrentScope();
    BudgetAllocation explicitAlloc = resolveExistingAllocation(body, year);
    if (explicitAlloc != null) {
      guard().assertNodeAccess(explicitAlloc.getNodeId(), explicitAlloc.getNodeCode());
      targetAllocs.add(explicitAlloc);
    } else if (budgetAllocationRepo != null) {
      List<BudgetAllocation> yearAllocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);
      for (BudgetAllocation a : yearAllocs) {
        if (!"REVENUE".equalsIgnoreCase(a.getItemType())
            && (callerScope == null
                || !callerScope.restricted()
                || callerScope.allowsTarget(a.getNodeId(), null, a.getNodeCode(), null))) {
          // Không giới hạn cứng 3 đơn vị (TD-C07 / DX-03)
          targetAllocs.add(a);
        }
      }
    }
    if (targetAllocs.isEmpty()) {
      throw new BaseException(
          "Không tìm thấy phân bổ dự toán chi hợp lệ trong năm "
              + year
              + " để thực hiện điều chỉnh quỹ lương hàng loạt.");
    }

    BigDecimal explicitAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO);
    Integer explicitBaseStaff =
        body != null && body.get("baseStaffCount") != null
            ? parseIntOrDefault(body, "baseStaffCount", -1)
            : null;

    String decisionNo =
        extractString(body, "decisionNo", "referenceDocumentNo", "QĐ-ĐCTL-" + year + "/UBND");
    LocalDate txDate = parseLocalDateOrDefault(body, "txDate", LocalDate.now());

    BigDecimal salaryDeltaPerPersonMonth =
        newBaseSalary
            .subtract(oldBaseSalary)
            .multiply(avgCoefficient)
            .multiply(insuranceContributionFactor);
    BigDecimal indexationFromStaffChange =
        newBaseSalary
            .multiply(avgCoefficient)
            .multiply(insuranceContributionFactor)
            .multiply(BigDecimal.valueOf(staffChangeCount))
            .multiply(BigDecimal.valueOf(monthsRemaining))
            .setScale(2, RoundingMode.HALF_UP);

    // Tính trước mức điều chỉnh cho từng đơn vị (lấy biên chế riêng của từng nodeId)
    List<BigDecimal> perUnitAmounts = new ArrayList<>();
    BigDecimal totalAbsEvaluated = BigDecimal.ZERO;
    for (BudgetAllocation alloc : targetAllocs) {
      BigDecimal unitAmt;
      if (explicitAmount.compareTo(BigDecimal.ZERO) != 0) {
        unitAmt = explicitAmount.setScale(2, RoundingMode.HALF_UP);
      } else {
        Integer unitStaff = explicitBaseStaff;
        if ((unitStaff == null || unitStaff < 0)
            && alloc.getNodeId() != null
            && integrationOrchestrator != null) {
          unitStaff = integrationOrchestrator.resolveActiveStaffCount(alloc.getNodeId());
        }
        if (unitStaff == null || unitStaff <= 0) {
          throw new BaseException(
              "Thiếu số lượng biên chế (baseStaffCount) hoặc số tiền điều chỉnh quỹ lương (amount) cho đơn vị ["
                  + StrUtil.blankToDefault(alloc.getNodeCode(), String.valueOf(alloc.getNodeId()))
                  + "] — không lấy được dữ liệu nhân sự từ Directory Service.");
        }
        BigDecimal indexFromBase =
            salaryDeltaPerPersonMonth
                .multiply(BigDecimal.valueOf(unitStaff))
                .multiply(BigDecimal.valueOf(monthsRemaining))
                .setScale(2, RoundingMode.HALF_UP);
        unitAmt = indexFromBase.add(indexationFromStaffChange).setScale(2, RoundingMode.HALF_UP);
      }
      if (unitAmt.compareTo(BigDecimal.ZERO) == 0) {
        throw new BaseException("Số tiền điều chỉnh dự toán quỹ lương phải khác 0.");
      }
      perUnitAmounts.add(unitAmt);
      totalAbsEvaluated = totalAbsEvaluated.add(unitAmt.abs());
    }

    Map<String, Object> roleMeta = evaluateOperationRoleGate("DX-03", body, totalAbsEvaluated);
    List<BudgetTransactionData> createdTxs = new ArrayList<>();
    BigDecimal totalIndexedAmount = BigDecimal.ZERO;

    for (int i = 0; i < targetAllocs.size(); i++) {
      BudgetAllocation alloc = targetAllocs.get(i);
      BigDecimal unitAdjustmentAmount = perUnitAmounts.get(i);
      String txCode =
          "TX-PAY-" + year + "-" + (i + 1) + "-" + (System.currentTimeMillis() % 100000);
      String reason =
          "Điều chỉnh dự toán quỹ lương cơ sở ("
              + oldBaseSalary.toPlainString()
              + " -> "
              + newBaseSalary.toPlainString()
              + " VNĐ, biến động biên chế: "
              + (staffChangeCount >= 0 ? "+" + staffChangeCount : staffChangeCount)
              + " người) cho "
              + alloc.getNodeName();
      BudgetTransactionData txData =
          BudgetTransactionData.builder()
              .code(txCode)
              .name("Điều chỉnh dự toán lương cơ sở & biên chế (" + alloc.getNodeName() + ")")
              .allocationId(alloc.getId())
              .allocationCode(alloc.getCode())
              .nodeId(alloc.getNodeId())
              .nodeCode(alloc.getNodeCode())
              .nodeName(alloc.getNodeName())
              .domainCode(alloc.getDomainCode() != null ? alloc.getDomainCode() : "KHCN")
              .domainName(alloc.getDomainName() != null ? alloc.getDomainName() : "Khoa học công nghệ")
              .budgetYear(year)
              .txType("BATCH_PAYROLL_INDEXATION")
              .entryDirection(unitAdjustmentAmount.compareTo(BigDecimal.ZERO) < 0 ? "DEBIT" : "CREDIT")
              .txDate(txDate)
              .amount(unitAdjustmentAmount)
              .decisionNo(decisionNo)
              .decisionDate(txDate)
              .reason(reason)
              .description(reason)
              .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
              .status(LifecycleStatus.ACTIVE)
              .build();
      createdTxs.add(
          createInternal(txData, String.valueOf(roleMeta.get("effectiveApprovalStatus")), false));
      totalIndexedAmount = totalIndexedAmount.add(unitAdjustmentAmount);
    }

    BigDecimal firstUnitAmount = perUnitAmounts.isEmpty() ? BigDecimal.ZERO : perUnitAmounts.getFirst();
    BigDecimal increaseRate =
        oldBaseSalary.compareTo(BigDecimal.ZERO) > 0
            ? newBaseSalary
                .subtract(oldBaseSalary)
                .multiply(new BigDecimal("100"))
                .divide(oldBaseSalary, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("indexed", true);
    res.put("operationType", "BATCH_PAYROLL_INDEXATION");
    res.put("oldBaseSalary", oldBaseSalary);
    res.put("newBaseSalary", newBaseSalary);
    res.put("salaryIncreaseRatePercent", increaseRate);
    res.put("staffChangeCount", staffChangeCount);
    res.put("monthsRemaining", monthsRemaining);
    res.put("affectedUnitCount", createdTxs.size());
    res.put("unitAdjustmentAmount", firstUnitAmount);
    res.put("totalIndexedAmount", totalIndexedAmount.setScale(2, RoundingMode.HALF_UP));
    res.put("roleExecutionMeta", roleMeta);
    res.put("transactions", createdTxs);
    res.put("transaction", createdTxs.isEmpty() ? null : createdTxs.getFirst());
    return res;
  }

  /**
   * Nghiệp vụ phụ nâng cao DX-04: Trích lập 4 Quỹ tự chủ tài chính cuối năm theo Nghị định
   * 60/2021/NĐ-CP ({@code POST /v1/budget/transactions/autonomy-fund-distribution}).
   */
  public Map<String, Object> executeAutonomyFundDistribution(Map<String, Object> body) {
    int year = parseIntOrDefault(body, "budgetYear", LocalDate.now().getYear());
    String autonomyGroup =
        extractString(body, "autonomyGroup", "financialAutonomyGroup", "GROUP_2").toUpperCase();
    BudgetAllocation targetAlloc = resolveExistingAllocation(body, year);
    if (targetAlloc == null) {
      throw new BaseException(
          "Không tìm thấy phân bổ dự toán năm "
              + year
              + " để trích lập 4 Quỹ tự chủ tài chính.");
    }
    guard().assertNodeAccess(targetAlloc.getNodeId(), targetAlloc.getNodeCode());
    targetAlloc = workflowEngineHelper.lockAllocation(targetAlloc.getId()).orElse(targetAlloc);

    BigDecimal surplusAmount = parseBigDecimalOrDefault(body, "surplusAmount", BigDecimal.ZERO).abs();
    if (surplusAmount.compareTo(BigDecimal.ZERO) == 0) {
      surplusAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO).abs();
    }
    if (surplusAmount.compareTo(BigDecimal.ZERO) == 0) {
      surplusAmount = workflowEngineHelper.safe(targetAlloc.getAvailableAmount());
    }
    if (surplusAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException(
          "Số dư trích lập Quỹ tự chủ tài chính (surplusAmount / amount) phải lớn hơn 0.");
    }
    BigDecimal avail = workflowEngineHelper.safe(targetAlloc.getAvailableAmount());
    if (surplusAmount.compareTo(avail) > 0) {
      throw new BaseException(
          "Số dư trích lập quỹ ("
              + surplusAmount.toPlainString()
              + " VNĐ) vượt quá số dư khả dụng còn lại ("
              + avail.toPlainString()
              + " VNĐ) của phân bổ ["
              + targetAlloc.getCode()
              + "].");
    }

    // Tỷ lệ trích lập 4 Quỹ theo Điều 14, Điều 18 Nghị định 60/2021/NĐ-CP (chặn tổng > 100% gây quỹ âm)
    BigDecimal pthdsnRate =
        parseBigDecimalOrDefault(body, "pthdsnRatePercent", new BigDecimal("25"));
    BigDecimal bstnRate = parseBigDecimalOrDefault(body, "bstnRatePercent", new BigDecimal("45"));
    BigDecimal ktplRate = parseBigDecimalOrDefault(body, "ktplRatePercent", new BigDecimal("20"));
    if (pthdsnRate.compareTo(BigDecimal.ZERO) < 0
        || bstnRate.compareTo(BigDecimal.ZERO) < 0
        || ktplRate.compareTo(BigDecimal.ZERO) < 0) {
      throw new InvalidDataException("Tỷ lệ trích lập các quỹ tự chủ tài chính không được âm.", null);
    }
    BigDecimal sumThreeRates = pthdsnRate.add(bstnRate).add(ktplRate);
    if (sumThreeRates.compareTo(new BigDecimal("100")) > 0) {
      throw new InvalidDataException(
          "Tổng tỷ lệ trích lập 3 quỹ (PTHDSN + BSTN + KTPL = "
              + sumThreeRates.toPlainString()
              + "%) không được vượt quá 100%.",
          null);
    }
    if (("GROUP_1".equals(autonomyGroup) || "GROUP_2".equals(autonomyGroup))
        && pthdsnRate.compareTo(new BigDecimal("25")) < 0) {
      throw new InvalidDataException(
          "Đơn vị tự chủ "
              + autonomyGroup
              + " bắt buộc trích tối thiểu 25% vào Quỹ Phát triển hoạt động sự nghiệp theo Nghị định 60/2021/NĐ-CP.",
          null);
    }

    BigDecimal dpodtnRate = new BigDecimal("100").subtract(sumThreeRates);
    surplusAmount = surplusAmount.setScale(2, RoundingMode.HALF_UP);

    BigDecimal pthdsnAmount =
        surplusAmount.multiply(pthdsnRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    BigDecimal bstnAmount =
        surplusAmount.multiply(bstnRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    BigDecimal ktplAmount =
        surplusAmount.multiply(ktplRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    BigDecimal dpodtnAmount =
        surplusAmount
            .subtract(pthdsnAmount)
            .subtract(bstnAmount)
            .subtract(ktplAmount)
            .setScale(2, RoundingMode.HALF_UP);
    if (dpodtnAmount.compareTo(BigDecimal.ZERO) < 0) {
      throw new InvalidDataException(
          "Số tiền trích Quỹ Dự phòng ổn định thu nhập không hợp lệ (< 0).", null);
    }

    JSONArray fundArr = new JSONArray();
    fundArr.add(
        new JSONObject()
            .set("code", "FUND_PTHDSN")
            .set("name", "Quỹ Phát triển hoạt động sự nghiệp")
            .set("ratePercent", pthdsnRate)
            .set("allocatedAmount", pthdsnAmount));
    fundArr.add(
        new JSONObject()
            .set("code", "FUND_BSTN")
            .set("name", "Quỹ Bổ sung thu nhập")
            .set("ratePercent", bstnRate)
            .set("allocatedAmount", bstnAmount));
    fundArr.add(
        new JSONObject()
            .set("code", "FUND_KTPL")
            .set("name", "Quỹ Khen thưởng và Phúc lợi")
            .set("ratePercent", ktplRate)
            .set("allocatedAmount", ktplAmount));
    fundArr.add(
        new JSONObject()
            .set("code", "FUND_DPODTN")
            .set("name", "Quỹ Dự phòng ổn định thu nhập")
            .set("ratePercent", dpodtnRate)
            .set("allocatedAmount", dpodtnAmount));

    String decisionNo =
        extractString(body, "decisionNo", "referenceDocumentNo", "QĐ-TLQ-" + year + "/ĐVSN");
    LocalDate txDate = parseLocalDateOrDefault(body, "txDate", LocalDate.now());
    String reason =
        extractString(
            body,
            "reason",
            "description",
            "Kết chuyển chênh lệch thu - chi và kinh phí tiết kiệm trích lập 4 Quỹ tự chủ theo NĐ 60/2021/NĐ-CP ("
                + autonomyGroup
                + ")");
    String txCode =
        extractString(
            body,
            "code",
            null,
            "TX-FUND-" + year + "-" + (System.currentTimeMillis() % 100000));

    Map<String, Object> roleMeta = evaluateOperationRoleGate("DX-04", body, surplusAmount);
    BudgetTransactionData txData =
        BudgetTransactionData.builder()
            .code(txCode)
            .name("Trích lập 4 Quỹ tự chủ tài chính cuối năm " + year + " (" + autonomyGroup + ")")
            .allocationId(targetAlloc != null ? targetAlloc.getId() : null)
            .allocationCode(targetAlloc != null ? targetAlloc.getCode() : null)
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : resolveRequiredNodeId(body))
            .nodeCode(
                targetAlloc != null
                    ? targetAlloc.getNodeCode()
                    : extractString(body, "nodeCode", "unitNodeCode", null))
            .nodeName(
                targetAlloc != null
                    ? targetAlloc.getNodeName()
                    : extractString(body, "nodeName", "unitNodeName", null))
            .domainCode(targetAlloc != null ? targetAlloc.getDomainCode() : "KHCN")
            .domainName(targetAlloc != null ? targetAlloc.getDomainName() : "Khoa học công nghệ")
            .dynamicBreakdownJson(fundArr.toString())
            .budgetYear(year)
            .txType("AUTONOMY_FUND_DISTRIBUTION")
            .txDate(txDate)
            .amount(surplusAmount)
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(reason)
            .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx =
        createInternal(txData, String.valueOf(roleMeta.get("effectiveApprovalStatus")), false);

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("distributed", true);
    res.put("operationType", "AUTONOMY_FUND_DISTRIBUTION");
    res.put("autonomyGroup", autonomyGroup);
    res.put("surplusAmount", surplusAmount);
    res.put("pthdsnAmount", pthdsnAmount);
    res.put("bstnAmount", bstnAmount);
    res.put("ktplAmount", ktplAmount);
    res.put("dpodtnAmount", dpodtnAmount);
    res.put("fundBreakdowns", fundArr);
    res.put("roleExecutionMeta", roleMeta);
    res.put("transaction", createdTx);
    return res;
  }

  /**
   * Trả về Ma trận Phân tách Vai trò & Thẩm quyền Thực hiện cho toàn bộ 9 Nghiệp vụ Phụ Ngân sách
   * ({@code NV-01 .. NV-05} và {@code DX-01 .. DX-04}) theo chuẩn Kiểm soát Nội bộ Tài chính công
   * ({@code GET /v1/budget/transactions/operation-role-matrix}).
   */
  public Map<String, Object> getOperationRoleMatrix() {
    List<Map<String, Object>> roles =
        List.of(
            Map.of(
                "roleCode", "UNIT_ACCOUNTANT",
                "roleName", "Kế toán Đơn vị Dự toán Cấp dưới (Cấp III)",
                "level", "UNIT_LEVEL",
                "saPermissions",
                    List.of(
                        "BudgetTransactionCreate",
                        "BudgetDisbursementCreate",
                        "BudgetAllocationView"),
                "responsibilities",
                    "Lập tờ trình bổ sung nội dung chi (NV-01), lập hồ sơ đề nghị chuyển nguồn (NV-02), lập phiếu điều chuyển nội bộ chéo (NV-03), lập bảng tính trích lập 4 quỹ tự chủ (DX-04). Không được tự phê duyệt."),
            Map.of(
                "roleCode", "UNIT_DIRECTOR",
                "roleName", "Thủ trưởng Đơn vị Sử dụng Ngân sách (Chủ tài khoản)",
                "level", "UNIT_LEVEL",
                "saPermissions",
                    List.of(
                        "BudgetTransactionCreate",
                        "BudgetTransactionEdit",
                        "BudgetDisbursementEdit"),
                "responsibilities",
                    "Tự phê duyệt điều chuyển nội bộ chéo giữa các lĩnh vực trong phạm vi tự chủ NĐ 60/2021 (NV-03) và phê duyệt trích lập 4 quỹ tự chủ của đơn vị (DX-04); ký trình Sở chủ quản các nghiệp vụ NV-01, NV-02."),
            Map.of(
                "roleCode", "PARENT_APPRAISER",
                "roleName", "Chuyên viên Thẩm định Tài chính - Kế hoạch (Đơn vị Dự toán Cấp I / Sở)",
                "level", "DEPARTMENT_LEVEL",
                "saPermissions",
                    List.of(
                        "BudgetTransactionCreate", "BudgetTransactionEdit", "BudgetAllocationEdit"),
                "responsibilities",
                    "Thực hiện tạm giữ / phong tỏa dự toán chờ duyệt chủ trương (DX-01), lập phương án tiết kiệm NQ 135 / giảm theo PA tự chủ (NV-04), tính toán điều chỉnh quỹ lương hàng loạt (DX-03), thẩm định hồ sơ trình từ đơn vị trực thuộc."),
            Map.of(
                "roleCode", "STC_DIRECTOR",
                "roleName", "Lãnh đạo Sở Chủ quản / Sở Tài chính (Cấp Phê duyệt Cao nhất)",
                "level", "EXECUTIVE_LEVEL",
                "saPermissions",
                    List.of(
                        "BudgetTransactionCreate",
                        "BudgetTransactionEdit",
                        "BudgetAllocationCreate",
                        "BudgetAllocationEdit"),
                "responsibilities",
                    "Phê duyệt cuối cùng và thực thi tức thì các quyết định giao bổ sung (NV-01), chuyển nguồn liên năm (NV-02), điều chuyển vượt trần (NV-03), cắt giảm tiết kiệm NQ 135 (NV-04), giải tỏa phong tỏa (DX-01), giải tỏa theo đợt (DX-02) và điều chỉnh lương hàng loạt (DX-03)."),
            Map.of(
                "roleCode", "TREASURY_RECONCILER",
                "roleName", "Kế toán trưởng / Kiểm soát viên Đối soát Kho bạc (KBNN)",
                "level", "TREASURY_LEVEL",
                "saPermissions",
                    List.of(
                        "BudgetTransactionCreate",
                        "BudgetTransactionEdit",
                        "BudgetDisbursementEdit",
                        "BudgetReportView"),
                "responsibilities",
                    "Thực thi thu hồi / hủy dự toán hết niên độ nộp trả NSNN ngày 31/01 (NV-05), xác nhận số dư chuyển nguồn tại KBNN (NV-02) và khóa sổ đối soát mẫu 01a/02-SDKP-ĐVDT."));

    List<Map<String, Object>> operations =
        List.of(
            buildOpRoleRule(
                "NV-01",
                "Bổ sung Nội dung Nhiệm vụ Chi & Dự toán Đa lĩnh vực",
                "/v1/budget/transactions/supplement-content",
                List.of("UNIT_ACCOUNTANT", "UNIT_DIRECTOR", "PARENT_APPRAISER"),
                List.of("PARENT_APPRAISER"),
                List.of("STC_DIRECTOR"),
                "Điều 49, 52 Luật NSNN 2015",
                "Đơn vị lập tờ trình (PENDING) -> Phòng KH-TC thẩm định -> Lãnh đạo Sở / UBND phê duyệt (APPROVED)."),
            buildOpRoleRule(
                "NV-02",
                "Chuyển nguồn Kinh phí Liên năm (N-1 -> N) & Ánh xạ Lĩnh vực",
                "/v1/budget/transactions/carry-over-year",
                List.of("UNIT_ACCOUNTANT", "UNIT_DIRECTOR", "PARENT_APPRAISER"),
                List.of("PARENT_APPRAISER", "TREASURY_RECONCILER"),
                List.of("STC_DIRECTOR", "TREASURY_RECONCILER"),
                "Điều 64 Luật NSNN 2015 & Điều 43 NĐ 163/2016/NĐ-CP",
                "Đơn vị đối chiếu số dư KBNN lập hồ sơ -> Kiểm soát viên KBNN xác nhận -> Lãnh đạo Sở/STC phê duyệt chuyển nguồn sang năm mới."),
            buildOpRoleRule(
                "NV-03",
                "Điều chuyển Nội bộ Chéo giữa các Lĩnh vực (Tổng = 0)",
                "/v1/budget/transactions/cross-domain-transfer",
                List.of("UNIT_ACCOUNTANT", "UNIT_DIRECTOR", "PARENT_APPRAISER"),
                List.of("UNIT_DIRECTOR", "PARENT_APPRAISER"),
                List.of("UNIT_DIRECTOR", "STC_DIRECTOR"),
                "Nghị định 60/2021/NĐ-CP & Thông tư 56/2022/TT-BTC",
                "Thủ trưởng đơn vị (UNIT_DIRECTOR) được tự phê duyệt trong phạm vi kinh phí giao tự chủ; kinh phí không tự chủ trình Lãnh đạo Sở (STC_DIRECTOR)."),
            buildOpRoleRule(
                "NV-04",
                "Tiết kiệm Chi thường xuyên (NQ 135), Giảm PA Tự chủ / Giảm Thừa",
                "/v1/budget/transactions/saving-reduction",
                List.of("PARENT_APPRAISER", "UNIT_DIRECTOR"),
                List.of("PARENT_APPRAISER"),
                List.of("STC_DIRECTOR"),
                "Nghị quyết Chính phủ (NQ 135) & Quyết định phê duyệt PA tự chủ",
                "Chuyên viên KH-TC lập phương án cắt giảm tiết kiệm -> Lãnh đạo Sở / STC phê duyệt điều chỉnh giảm dự toán."),
            buildOpRoleRule(
                "NV-05",
                "Thu hồi / Hủy Dự toán Hết Niên độ Nộp trả NSNN (31/01)",
                "/v1/budget/transactions/cancel-expired",
                List.of("TREASURY_RECONCILER", "PARENT_APPRAISER"),
                List.of("TREASURY_RECONCILER"),
                List.of("TREASURY_RECONCILER", "STC_DIRECTOR"),
                "Khoản 4 Điều 64 Luật NSNN 2015",
                "Kiểm soát viên Đối soát Kho bạc / Kế toán trưởng khóa sổ sau 31/01 và hạch toán hủy dự toán hết niên độ."),
            buildOpRoleRule(
                "DX-01",
                "Tạm giữ / Phong tỏa & Giải tỏa Dự toán Chờ Duyệt Chủ trương",
                "/v1/budget/transactions/earmark-hold",
                List.of("PARENT_APPRAISER", "STC_DIRECTOR"),
                List.of("PARENT_APPRAISER"),
                List.of("PARENT_APPRAISER", "STC_DIRECTOR"),
                "Quy chế Quản lý Tài chính nội bộ & Kiểm soát cam kết chi",
                "Chuyên viên thẩm định (PARENT_APPRAISER) được phép Tạm giữ (HOLD); chỉ Lãnh đạo Sở (STC_DIRECTOR) được phép Giải tỏa (RELEASE)."),
            buildOpRoleRule(
                "DX-02",
                "Giải tỏa / Cấp phát Dự toán Đầu năm theo Đợt hoặc Tiến độ Thu",
                "/v1/budget/transactions/tranche-release",
                List.of("PARENT_APPRAISER", "STC_DIRECTOR"),
                List.of("PARENT_APPRAISER", "TREASURY_RECONCILER"),
                List.of("STC_DIRECTOR"),
                "Thông tư 342/2016/TT-BTC",
                "Chuyên viên KH-TC đối chiếu tiến độ thu phí/lệ phí trình Lãnh đạo Sở giải tỏa hạn mức từng đợt (Q1..Q4)."),
            buildOpRoleRule(
                "DX-03",
                "Điều chỉnh Dự toán Hàng loạt theo Lương cơ sở & Biên chế",
                "/v1/budget/transactions/batch-payroll-indexation",
                List.of("PARENT_APPRAISER", "STC_DIRECTOR"),
                List.of("PARENT_APPRAISER"),
                List.of("STC_DIRECTOR"),
                "Nghị định Chính phủ quy định mức lương cơ sở & Quyết định giao biên chế",
                "Chuyên viên KH-TC chạy công cụ tính toán hàng loạt -> Lãnh đạo Sở phê duyệt phân bổ bổ sung quỹ lương cho các đơn vị."),
            buildOpRoleRule(
                "DX-04",
                "Trích lập 4 Quỹ Tự chủ Tài chính Cuối năm (NĐ 60/2021/NĐ-CP)",
                "/v1/budget/transactions/autonomy-fund-distribution",
                List.of("UNIT_ACCOUNTANT", "UNIT_DIRECTOR"),
                List.of("UNIT_DIRECTOR", "PARENT_APPRAISER"),
                List.of("UNIT_DIRECTOR", "STC_DIRECTOR"),
                "Điều 14, Điều 18 Nghị định 60/2021/NĐ-CP",
                "Kế toán đơn vị lập bảng phân phối chênh lệch thu - chi -> Thủ trưởng đơn vị (UNIT_DIRECTOR) phê duyệt trích lập 4 Quỹ theo Quy chế chi tiêu nội bộ."));

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("roles", roles);
    res.put("operations", operations);
    res.put("totalRoles", roles.size());
    res.put("totalOperations", operations.size());
    return res;
  }

  private Map<String, Object> buildOpRoleRule(
      String operationCode,
      String operationName,
      String endpoint,
      List<String> initiatorRoles,
      List<String> appraiserRoles,
      List<String> approverRoles,
      String legalBasis,
      String segregationNote) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("operationCode", operationCode);
    m.put("operationName", operationName);
    m.put("endpoint", endpoint);
    m.put("initiatorRoles", initiatorRoles);
    m.put("appraiserRoles", appraiserRoles);
    m.put("approverRoles", approverRoles);
    m.put("legalBasis", legalBasis);
    m.put("segregationOfDutiesNote", segregationNote);
    return m;
  }

  private static final Map<String, List<String>> OPERATION_ALLOWED_ROLES =
      Map.of(
          "NV-01",
              List.of(
                  "UNIT_ACCOUNTANT",
                  "UNIT_DIRECTOR",
                  "SPECIALIST",
                  "PARENT_APPRAISER",
                  "STC_DIRECTOR",
                  "TREASURY_RECONCILER"),
          "NV-02",
              List.of(
                  "UNIT_ACCOUNTANT",
                  "UNIT_DIRECTOR",
                  "SPECIALIST",
                  "PARENT_APPRAISER",
                  "STC_DIRECTOR",
                  "TREASURY_RECONCILER"),
          "NV-03",
              List.of(
                  "UNIT_ACCOUNTANT",
                  "UNIT_DIRECTOR",
                  "SPECIALIST",
                  "PARENT_APPRAISER",
                  "STC_DIRECTOR",
                  "TREASURY_RECONCILER"),
          "NV-04", List.of("PARENT_APPRAISER", "UNIT_DIRECTOR", "STC_DIRECTOR"),
          "NV-05", List.of("TREASURY_RECONCILER", "PARENT_APPRAISER", "STC_DIRECTOR"),
          "DX-01", List.of("PARENT_APPRAISER", "STC_DIRECTOR"),
          "DX-02", List.of("PARENT_APPRAISER", "STC_DIRECTOR"),
          "DX-03", List.of("PARENT_APPRAISER", "STC_DIRECTOR"),
          "DX-04",
              List.of("UNIT_ACCOUNTANT", "UNIT_DIRECTOR", "PARENT_APPRAISER", "STC_DIRECTOR"));

  private Map<String, Object> evaluateOperationRoleGate(
      String operationCode, Map<String, Object> body, BigDecimal amount) {
    boolean authenticatedSession = false;
    String actorRole = null;
    try {
      if (LoginHelper.isSuperAdmin()) {
        authenticatedSession = true;
        actorRole = "STC_DIRECTOR";
      } else if (StpUtil.isLogin()) {
        authenticatedSession = true;
        actorRole = resolveBudgetPersonaRole(StpUtil.getRoleList(), StpUtil.getPermissionList());
      }
    } catch (Exception ignored) {
      authenticatedSession = false;
    }

    if (!authenticatedSession || StrUtil.isBlank(actorRole)) {
      throw new BaseException(
          "Chưa xác thực phiên đăng nhập hợp lệ hoặc tài khoản chưa được gán vai trò nghiệp vụ Ngân sách để thực hiện ["
              + operationCode
              + "].");
    }

    String actorRoleName =
        switch (actorRole) {
          case "UNIT_ACCOUNTANT" -> "Kế toán Đơn vị Dự toán Cấp dưới (Cấp III)";
          case "UNIT_DIRECTOR" -> "Thủ trưởng Đơn vị Sử dụng Ngân sách";
          case "SPECIALIST" -> "Chuyên viên Tổng hợp Kế hoạch - Tài chính";
          case "PARENT_APPRAISER" -> "Chuyên viên Thẩm định Tài chính - Kế hoạch (Cấp I)";
          case "TREASURY_RECONCILER" -> "Kiểm soát viên Đối soát Kho bạc (KBNN)";
          case "STC_DIRECTOR" -> "Lãnh đạo Sở Chủ quản / Sở Tài chính (STC_DIRECTOR)";
          default -> "Vai trò chưa được cấp phép (" + actorRole + ")";
        };

    List<String> allowedInitiators =
        OPERATION_ALLOWED_ROLES.getOrDefault(operationCode, List.of("STC_DIRECTOR"));

    List<String> allowedApprovers =
        switch (operationCode) {
          case "NV-01", "NV-04", "DX-02", "DX-03" -> List.of("STC_DIRECTOR");
          case "NV-02", "NV-05" -> List.of("STC_DIRECTOR", "TREASURY_RECONCILER");
          case "NV-03", "DX-04" -> List.of("UNIT_DIRECTOR", "STC_DIRECTOR");
          case "DX-01" -> {
            String holdAction = extractString(body, "holdAction", "actionMode", "HOLD").toUpperCase();
            yield "RELEASE".equals(holdAction) || "EARMARK_RELEASE".equals(holdAction)
                ? List.of("STC_DIRECTOR")
                : List.of("PARENT_APPRAISER", "STC_DIRECTOR");
          }
          default -> List.of("STC_DIRECTOR");
        };

    boolean canInitiate = allowedInitiators.contains(actorRole);
    if (!canInitiate) {
      throw new BaseException(
          "Vai trò ["
              + actorRoleName
              + "] không có thẩm quyền khởi tạo nghiệp vụ ["
              + operationCode
              + "]. Yêu cầu một trong các vai trò: "
              + String.join(", ", allowedInitiators));
    }

    // Cưỡng chế kiểm soát 4 mắt SoD: hủy bỏ auto-approve 1 bước — luôn chuyển PENDING_APPROVAL (TD-C04, TD-C07)
    boolean canAutoApprove = false;
    String effectiveApprovalStatus = "PENDING_APPROVAL";
    String requiredNextApprover =
        allowedApprovers.isEmpty() ? "STC_DIRECTOR" : allowedApprovers.getFirst();

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("operationCode", operationCode);
    meta.put("actorRole", actorRole);
    meta.put("actorRoleName", actorRoleName);
    meta.put("authenticatedSession", authenticatedSession);
    meta.put("canInitiate", canInitiate);
    meta.put("canAutoApprove", canAutoApprove);
    meta.put("effectiveApprovalStatus", effectiveApprovalStatus);
    meta.put("requiredApproverRole", requiredNextApprover);
    meta.put("allowedInitiatorRoles", allowedInitiators);
    meta.put("allowedApproverRoles", allowedApprovers);
    meta.put("evaluatedAmount", amount);
    return meta;
  }

  private String resolveBudgetPersonaRole(List<String> sessionRoles, List<String> sessionPerms) {
    if (sessionRoles != null) {
      Set<String> normalized = new LinkedHashSet<>();
      for (String r : sessionRoles) {
        if (r != null) {
          normalized.add(r.trim().toUpperCase());
        }
      }
      if (normalized.contains("ROLE_BUDGET_DIRECTORATE") || normalized.contains("STC_DIRECTOR")) {
        return "STC_DIRECTOR";
      }
      if (normalized.contains("TREASURY_RECONCILER")) {
        return "TREASURY_RECONCILER";
      }
      if (normalized.contains("ROLE_BUDGET_FINANCE_APPRAISER")
          || normalized.contains("PARENT_APPRAISER")) {
        return "PARENT_APPRAISER";
      }
      if (normalized.contains("ROLE_BUDGET_UNIT_HEAD") || normalized.contains("UNIT_DIRECTOR")) {
        return "UNIT_DIRECTOR";
      }
      if (normalized.contains("ROLE_BUDGET_UNIT_ACCOUNTANT")
          || normalized.contains("UNIT_ACCOUNTANT")) {
        return "UNIT_ACCOUNTANT";
      }
      if (normalized.contains("ROLE_BUDGET_SPECIALIST") || normalized.contains("SPECIALIST")) {
        return "SPECIALIST";
      }
    }
    if (sessionPerms != null) {
      if (sessionPerms.contains("BudgetTransactionApprove")) {
        return "PARENT_APPRAISER";
      }
      if (sessionPerms.contains("BudgetTransactionSubmit")
          || sessionPerms.contains("BudgetTransactionCreate")) {
        return "UNIT_ACCOUNTANT";
      }
    }
    return null;
  }

  private boolean hasTransactionApproveOverride() {
    try {
      return LoginHelper.isSuperAdmin()
          || (StpUtil.isLogin() && StpUtil.hasPermission("BudgetTransactionApprove"));
    } catch (Exception ignored) {
      return false;
    }
  }

  private BudgetAllocation resolveExistingAllocation(Map<String, Object> body, int year) {
    if (budgetAllocationRepo == null) {
      return null;
    }
    Long allocId = parseLongOrNull(body, "allocationId");
    if (allocId != null) {
      return budgetAllocationRepo.findById(allocId).orElse(null);
    }
    String allocCode = extractString(body, "allocationCode", null, "");
    if (StrUtil.isNotBlank(allocCode)) {
      List<BudgetAllocation> byCode =
          budgetAllocationRepo.findAllByCodeIgnoreCaseIncludingDeletedSorted(allocCode.trim());
      return byCode.isEmpty() ? null : byCode.getFirst();
    }
    Long nodeId = parseLongOrNull(body, "nodeId");
    if (nodeId != null) {
      List<BudgetAllocation> byNode =
          budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(nodeId, year);
      for (BudgetAllocation a : byNode) {
        if (!"REVENUE".equalsIgnoreCase(a.getItemType())) {
          return a;
        }
      }
      return null;
    }
    String nodeCode = extractString(body, "nodeCode", "unitNodeCode", "");
    if (StrUtil.isNotBlank(nodeCode)) {
      List<BudgetAllocation> yearAllocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);
      for (BudgetAllocation a : yearAllocs) {
        if (!"REVENUE".equalsIgnoreCase(a.getItemType())
            && nodeCode.equalsIgnoreCase(a.getNodeCode())) {
          return a;
        }
      }
    }
    // Cấm fallback ngầm sang phân bổ đầu tiên của đơn vị bất kỳ (chống trừ nhầm tiền đơn vị khác)
    return null;
  }

  private BudgetAllocation resolveOrCreateTargetAllocation(
      Map<String, Object> body,
      int year,
      String defaultTitle,
      String domainCode,
      String domainName,
      String domainGroupCode,
      String domainGroupName) {
    boolean forceNew =
        body != null && Boolean.parseBoolean(String.valueOf(body.get("createNewAllocation")));
    if (!forceNew) {
      BudgetAllocation existing = resolveExistingAllocation(body, year);
      if (existing != null) {
        return existing;
      }
    }
    if (budgetAllocationRepo == null) {
      return null;
    }
    Long nodeId = resolveRequiredNodeId(body);
    String newCode =
        "PB-" + year + "-" + domainCode + "-" + (System.currentTimeMillis() % 100000);
    String nodeCode = extractString(body, "nodeCode", "unitNodeCode", "NODE-" + nodeId);
    String nodeName = extractString(body, "nodeName", "unitNodeName", "Đơn vị #" + nodeId);
    return budgetAllocationRepo.save(
        BudgetAllocation.builder()
            .code(newCode)
            .name(defaultTitle)
            .budgetYear(year)
            .periodType("ANNUAL")
            .nodeId(nodeId)
            .nodeCode(nodeCode)
            .nodeName(nodeName)
            .itemType("EXPENDITURE")
            .categoryCode("253")
            .categoryName("Chi sự nghiệp chuyên ngành")
            .domainCode(domainCode)
            .domainName(domainName)
            .domainGroupCode(domainGroupCode)
            .domainGroupName(domainGroupName)
            .workflowStatus("DRAFT")
            .currentVersion(1)
            .initialAmount(BigDecimal.ZERO)
            .effectiveAmount(BigDecimal.ZERO)
            .availableAmount(BigDecimal.ZERO)
            .status(LifecycleStatus.ACTIVE)
            .build());
  }

  private String extractString(
      Map<String, Object> body, String primaryKey, String fallbackKey, String defaultVal) {
    if (body != null) {
      if (primaryKey != null && body.get(primaryKey) != null) {
        String s = String.valueOf(body.get(primaryKey)).trim();
        if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
          return s;
        }
      }
      if (fallbackKey != null && body.get(fallbackKey) != null) {
        String s = String.valueOf(body.get(fallbackKey)).trim();
        if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
          return s;
        }
      }
    }
    return defaultVal;
  }

  private int parseIntOrDefault(Map<String, Object> body, String key, int defaultVal) {
    if (body != null && body.get(key) != null) {
      try {
        return Integer.parseInt(String.valueOf(body.get(key)).trim());
      } catch (NumberFormatException ignored) {
        return defaultVal;
      }
    }
    return defaultVal;
  }

  private Long parseLongOrNull(Map<String, Object> body, String key) {
    if (body != null && body.get(key) != null) {
      try {
        return Long.valueOf(String.valueOf(body.get(key)).trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private BigDecimal parseBigDecimalOrDefault(
      Map<String, Object> body, String key, BigDecimal defaultVal) {
    if (body != null && body.get(key) != null) {
      try {
        return new BigDecimal(String.valueOf(body.get(key)).trim());
      } catch (NumberFormatException ignored) {
        return defaultVal;
      }
    }
    return defaultVal;
  }

  private LocalDate parseLocalDateOrDefault(
      Map<String, Object> body, String key, LocalDate defaultVal) {
    if (body != null && body.get(key) != null) {
      try {
        return LocalDate.parse(String.valueOf(body.get(key)).trim());
      } catch (Exception ignored) {
        return defaultVal;
      }
    }
    return defaultVal;
  }

  private Long resolveRequiredNodeId(Map<String, Object> body) {
    Long nodeId = parseLongOrNull(body, "nodeId");
    if (nodeId != null) {
      return nodeId;
    }
    throw new BaseException("Thiếu thông tin Đơn vị dự toán (nodeId) cho giao dịch.");
  }

  /**
   * Ghi nhận giao dịch Sổ cái phát sinh từ Kế hoạch công tác ({@code tr-planning-service}) qua S2S
   * {@link vn.tr.common.feign.budget.service.BudgetInternalClient#recordPlanLedgerTransaction}.
   */
  public Map<String, Object> recordFromPlan(Map<String, Object> body) {
    if (body == null || body.isEmpty()) {
      return Map.of("recorded", false, "reason", "EMPTY_REQUEST");
    }
    String planCode = extractString(body, "planCode", "linkedPlanCode", "");
    String rawCode = extractString(body, "code", null, "");
    String txCode =
        StrUtil.isNotBlank(rawCode)
            ? rawCode
            : ("PLNTX-" + StrUtil.blankToDefault(planCode, "AUTO") + "-" + (System.currentTimeMillis() % 100000));
    Optional<BudgetTransaction> existingOpt =
        budgetTransactionService.findByCodeIgnoreCaseIncludingDeleted(txCode);
    if (existingOpt != null && existingOpt.isPresent()) {
      BudgetTransaction existing = existingOpt.get();
      return Map.of(
          "recorded", true,
          "idempotent", true,
          "transactionId", existing.getId(),
          "transactionCode", existing.getCode(),
          "allocationId", existing.getAllocationId() != null ? existing.getAllocationId() : 0L);
    }

    int year = parseIntOrDefault(body, "budgetYear", LocalDate.now().getYear());
    BudgetAllocation targetAlloc = resolveExistingAllocation(body, year);
    if (targetAlloc == null && StrUtil.isNotBlank(planCode) && budgetAllocationRepo != null) {
      List<BudgetAllocation> byPlan =
          budgetAllocationRepo.findByLinkedPlanCodeIgnoreCaseOrderByIdAsc(planCode);
      if (!byPlan.isEmpty()) {
        targetAlloc = byPlan.getFirst();
      }
    }

    BigDecimal amount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO);
    String txType = extractString(body, "txType", null, "SUPPLEMENT").toUpperCase();
    String direction =
        ("REDUCTION".equals(txType)
                || "CARRY_OVER_OUT".equals(txType)
                || "TRANSFER_OUT".equals(txType)
                || "DISBURSEMENT".equals(txType)
                || amount.compareTo(BigDecimal.ZERO) < 0)
            ? "DEBIT"
            : "CREDIT";

    BudgetTransactionData txData =
        BudgetTransactionData.builder()
            .code(txCode)
            .name(
                extractString(
                    body,
                    "name",
                    null,
                    "Ghi nhận biến động kinh phí từ Kế hoạch [" + planCode + "]"))
            .allocationId(targetAlloc != null ? targetAlloc.getId() : parseLongOrNull(body, "allocationId"))
            .allocationCode(targetAlloc != null ? targetAlloc.getCode() : extractString(body, "allocationCode", null, null))
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : parseLongOrNull(body, "nodeId"))
            .nodeCode(targetAlloc != null ? targetAlloc.getNodeCode() : extractString(body, "nodeCode", null, null))
            .nodeName(targetAlloc != null ? targetAlloc.getNodeName() : extractString(body, "nodeName", null, null))
            .budgetYear(year)
            .txType("DISBURSEMENT".equals(txType) ? "ADJUSTMENT" : txType)
            .entryDirection(direction)
            .txDate(parseLocalDateOrDefault(body, "txDate", LocalDate.now()))
            .amount("DISBURSEMENT".equals(txType) && amount.compareTo(BigDecimal.ZERO) > 0 ? amount.negate() : amount)
            .decisionNo(extractString(body, "decisionNo", "planCode", planCode))
            .reason(extractString(body, "reason", "description", "Đồng bộ từ tr-planning-service"))
            .approvalStatus(extractString(body, "approvalStatus", null, "APPROVED"))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData created =
        createInternal(
            txData,
            sm().normalize(extractString(body, "approvalStatus", null, "APPROVED")),
            true);
    return Map.of(
        "recorded", true,
        "idempotent", false,
        "transactionId", created.getId() != null ? created.getId() : 0L,
        "transactionCode", created.getCode(),
        "allocationId", created.getAllocationId() != null ? created.getAllocationId() : 0L);
  }

  private int parseStrictIntOrDefault(Map<String, Object> body, String key, int defaultVal) {
    if (body == null || body.get(key) == null || StrUtil.isBlank(String.valueOf(body.get(key)))) {
      return defaultVal;
    }
    try {
      return Integer.parseInt(String.valueOf(body.get(key)).trim());
    } catch (NumberFormatException e) {
      throw new InvalidDataException("Tham số '" + key + "' không phải số nguyên hợp lệ.", null);
    }
  }

  private Long parseStrictLongOrNull(Map<String, Object> body, String key) {
    if (body == null || body.get(key) == null || StrUtil.isBlank(String.valueOf(body.get(key)))) {
      return null;
    }
    try {
      return Long.valueOf(String.valueOf(body.get(key)).trim());
    } catch (NumberFormatException e) {
      throw new InvalidDataException("Tham số '" + key + "' không phải định danh số hợp lệ.", null);
    }
  }

  private BigDecimal parseStrictBigDecimalOrDefault(
      Map<String, Object> body, String key, BigDecimal defaultVal) {
    if (body == null || body.get(key) == null || StrUtil.isBlank(String.valueOf(body.get(key)))) {
      return defaultVal;
    }
    try {
      return new BigDecimal(String.valueOf(body.get(key)).trim());
    } catch (NumberFormatException e) {
      throw new InvalidDataException(
          "Tham số số tiền '" + key + "' không đúng định dạng số.", null);
    }
  }

  private Long resolveAuthenticatedPersonId() {
    Long id = guard().resolveAuthenticatedPersonId();
    if (id == null && integrationOrchestrator != null) {
      id = integrationOrchestrator.resolveCurrentPersonId(null);
    }
    return id;
  }

  private String resolveAuthenticatedActorName() {
    if (integrationOrchestrator != null) {
      return integrationOrchestrator.resolveCurrentActorName(null);
    }
    return guard().resolveAuthenticatedActorName();
  }

  /** Lấy danh sách rút gọn (Lookup Options) Giao dịch Sổ cái Ngân sách. */
  @Transactional(readOnly = true)
  public PagedData<RefData<Long>> getOptions(BudgetTransactionSearchCriteria criteria) {
    BudgetTransactionSearchCriteria effective =
        LookupOptionsHelper.prepare(
            criteria != null ? criteria : new BudgetTransactionSearchCriteria());
    Pageable pageable = CoreUtils.getPageRequest(effective);
    Page<BudgetTransaction> page = budgetTransactionService.findAll(effective, pageable);
    return PagedData.from(
        page,
        e ->
            RefData.of(
                e.getId(),
                e.getCode(),
                e.getName(),
                LookupOptionsHelper.normalizeStatus(e.getStatus())));
  }

  @Override
  public String getAppCode() {
    return "BudgetTransaction";
  }

  @Override
  public String getConfigNamespace() {
    return "budget";
  }

  @Override
  public String getProcessCode() {
    return BudgetIntegrationOrchestrator.WF_PROCESS_TRANSACTION;
  }

  @Override
  public String getCallbackUrl() {
    return "/workflow/callback";
  }

  @Override
  public void submitWorkflow(Long id) {
    BudgetTransaction tx =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    guard().assertNodeAccess(tx.getNodeId(), tx.getNodeCode());
    sm().assertTransactionTransition(tx.getApprovalStatus(), "PENDING_APPROVAL", tx.getCode());

    tx.setApprovalStatus("PENDING_APPROVAL");
    Long actorId = resolveAuthenticatedPersonId();
    if (actorId != null && tx.getRequestedByPersonId() == null) {
      tx.setRequestedByPersonId(actorId);
      tx.setRequestedByPersonName(resolveAuthenticatedActorName());
    }
    BudgetTransaction saved = budgetTransactionService.save(tx);
    if (integrationOrchestrator != null) {
      Long wfId = integrationOrchestrator.startTransactionWorkflow(saved);
      if (wfId != null) {
        saved.setWfInstanceId(wfId);
        if (StrUtil.isBlank(saved.getWfProcessCode())) {
          saved.setWfProcessCode(BudgetIntegrationOrchestrator.WF_PROCESS_TRANSACTION);
        }
        budgetTransactionService.save(saved);
      }
    }
  }

  @Override
  public void cancelWorkflow(Long id) {
    BudgetTransaction tx =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    guard().assertNodeAccess(tx.getNodeId(), tx.getNodeCode());
    sm().assertTransactionTransition(tx.getApprovalStatus(), "DRAFT", tx.getCode());

    tx.setApprovalStatus("DRAFT");
    budgetTransactionService.save(tx);
    recalculateAffectedAllocations(tx.getAllocationId(), tx.getCounterpartAllocationId());
    if (integrationOrchestrator != null) {
      integrationOrchestrator.executeWorkflowAction(getAppCode(), id, "WITHDRAW", "Hủy trình duyệt");
    }
  }

  @Override
  public void onWorkflowCompleted(WorkflowCallbackPayload payload) {
    if (payload == null || payload.getEntityId() == null) {
      return;
    }
    String decision = StrUtil.blankToDefault(payload.getDecision(), "").toUpperCase();
    String comment = StrUtil.nullToEmpty(payload.getComment());
    if ("APPROVE".equals(decision) || "APPROVED".equals(decision)) {
      approveTransaction(payload.getEntityId(), Map.of("comment", comment));
    } else if ("REWORK".equals(decision) || "RETURN".equals(decision)) {
      reworkTransaction(payload.getEntityId(), comment);
    } else if ("REJECT".equals(decision) || "REJECTED".equals(decision)) {
      rejectTransaction(payload.getEntityId(), Map.of("reason", comment));
    }
  }

  @Override
  public BudgetTransaction changeEntityStatus(
      Long id, LifecycleStatus targetStatus, String comment) {
    BudgetTransaction tx =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    guard().assertNodeAccess(tx.getNodeId(), tx.getNodeCode());
    tx.setStatus(targetStatus);
    if (StrUtil.isNotBlank(comment)) {
      tx.setDescription(comment);
    }
    return budgetTransactionService.save(tx);
  }

}
