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
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.service.BudgetTransactionService;
import vn.tr.budget.data.criteria.BudgetTransactionSearchCriteria;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.budget.data.mapper.BudgetTransactionMapper;
import vn.tr.budget.data.validator.BudgetTransactionValidator;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.common.core.exception.base.BaseException;

/**
 * Lớp điều phối nghiệp vụ cho BudgetTransaction.
 *
 * @author tyran8x
 */
@Service("budgetTransactionBusiness")
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BudgetTransactionBusiness {

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
  private BudgetIntegrationOrchestrator integrationOrchestrator;

  public JpaRepository<BudgetTransaction, Long> getInjectedRepo() {
    return budgetTransactionService.getRepository();
  }

  /** Tạo mới BudgetTransaction. */
  public BudgetTransactionData create(BudgetTransactionData data) {
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
    if (StrUtil.isBlank(data.getApprovalStatus())) {
      data.setApprovalStatus("APPROVED");
    }
    syncTransactionSnapshots(data);
    validateLedgerBalanceBeforeTransfer(data);
    BudgetTransaction entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetTransactionService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> budgetTransactionMapper.toEntity(data),
            budgetTransactionMapper::updateEntityFromData,
            budgetTransactionService.getRepository());
    if ("APPROVED".equalsIgnoreCase(entity.getApprovalStatus())) {
      if (entity.getApprovedDate() == null) {
        entity.setApprovedDate(LocalDateTime.now());
      }
      workflowEngineHelper.executeDoubleEntryLedger(entity);
      syncAffectedAllocationsToPlan(entity);
    }
    return enrichData(budgetTransactionMapper.toData(entity));
  }

  /** Cập nhật BudgetTransaction. */
  public BudgetTransactionData update(Long id, BudgetTransactionData data) {
    BudgetTransaction existing =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    Long oldAllocId = existing.getAllocationId();
    Long oldCounterpartId = existing.getCounterpartAllocationId();
    data.setId(id);
    validateData(data);
    syncTransactionSnapshots(data);
    budgetTransactionMapper.updateEntityFromData(data, existing);
    BudgetTransaction saved = budgetTransactionService.save(existing);
    recalculateAffectedAllocations(oldAllocId, oldCounterpartId);
    recalculateAffectedAllocations(saved.getAllocationId(), saved.getCounterpartAllocationId());
    return enrichData(budgetTransactionMapper.toData(saved));
  }

  /** Xoá mềm BudgetTransaction. */
  public void delete(Long id) {
    BudgetTransaction existing =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    Long allocId = existing.getAllocationId();
    Long counterpartId = existing.getCounterpartAllocationId();
    budgetTransactionService.delete(id);
    recalculateAffectedAllocations(allocId, counterpartId);
  }

  /** Xoá mềm hàng loạt BudgetTransaction. */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    List<BudgetTransaction> toDelete =
        ids != null && !ids.isEmpty() ? budgetTransactionService.findAllByIds(ids) : List.of();
    BulkOperationResult<Long> result =
        businessLogicHelper.executeBulkDelete(
            ids,
            budgetTransactionService::findAllByIds,
            BudgetTransaction::getId,
            null,
            null,
            budgetTransactionService::deleteByIds,
            "budget.budgetTransaction.not.found");
    for (BudgetTransaction tx : toDelete) {
      recalculateAffectedAllocations(tx.getAllocationId(), tx.getCounterpartAllocationId());
    }
    return result;
  }

  private void recalculateAffectedAllocations(Long allocId, Long counterpartId) {
    if (allocId != null) {
      workflowEngineHelper.recalculateAllocationMetrics(allocId);
    }
    if (counterpartId != null && !counterpartId.equals(allocId)) {
      workflowEngineHelper.recalculateAllocationMetrics(counterpartId);
    }
  }

  /** Lấy chi tiết BudgetTransaction theo ID. */
  @Transactional(readOnly = true)
  public BudgetTransactionData findById(Long id) {
    return budgetTransactionService
        .findById(id)
        .map(budgetTransactionMapper::toData)
        .map(this::enrichData)
        .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
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
    BudgetTransactionData clonedData = budgetTransactionMapper.toData(source);
    clonedData.setId(null);
    clonedData.setCode(source.getCode() + "_COPY_" + System.currentTimeMillis() % 10000);
    clonedData.setName(source.getName() + " (Bản sao)");
    return create(clonedData);
  }

  /** Khôi phục phiên bản lịch sử JaVers. */
  @Transactional
  public BudgetTransactionData rollback(Long id, Long version) {
    return auditRollbackHelper.executeRollback(
        BudgetTransaction.class,
        id,
        version,
        budgetTransactionService::findById,
        budgetTransactionService::save,
        budgetTransactionMapper::toData,
        budgetTransactionMapper::updateEntityFromData,
        BudgetTransactionData::setId,
        this::validateData);
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
                entity.setSortOrder(item.getSortOrder());
                budgetTransactionService.save(entity);
              });
    }
  }

  private void validateData(BudgetTransactionData data) {
    vn.tr.common.web.core.BaseBusinessHelper.validateOrThrow(
        budgetTransactionValidator, data, "budgetTransactionData");
  }

  private void validateLedgerBalanceBeforeTransfer(BudgetTransactionData data) {
    if (data.getAllocationId() == null || data.getAmount() == null) {
      return;
    }
    String type = StrUtil.nullToEmpty(data.getTxType()).toUpperCase();
    if ("CARRY_OVER_OUT".equals(type)
        || "INTERNAL_TRANSFER".equals(type)
        || "REDUCTION".equals(type)) {
      BudgetAllocation alloc =
          budgetAllocationRepo.findById(data.getAllocationId()).orElse(null);
      if (alloc != null) {
        BigDecimal avail =
            alloc.getAvailableAmount() != null
                ? alloc.getAvailableAmount()
                : workflowEngineHelper.safe(alloc.getEffectiveAmount());
        if (data.getAmount().abs().compareTo(avail) > 0) {
          throw new BaseException(
              "Số tiền điều chuyển / chuyển nguồn ("
                  + data.getAmount().abs().toPlainString()
                  + " VNĐ) vượt quá số dư dự toán khả dụng ("
                  + avail.toPlainString()
                  + " VNĐ) của phân bổ ["
                  + alloc.getCode()
                  + "].");
        }
      }
    }
  }

  /** Phê duyệt giao dịch biến động nguồn kinh phí và kích hoạt bút toán kép. */
  public BudgetTransactionData approveTransaction(Long id, Map<String, Object> body) {
    BudgetTransaction tx =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    tx.setApprovalStatus("APPROVED");
    tx.setApprovedDate(LocalDateTime.now());
    if (integrationOrchestrator != null) {
      Long actorId =
          integrationOrchestrator.resolveCurrentPersonId(body, "approvedByPersonId", "actorId");
      if (actorId != null) {
        tx.setApprovedByPersonId(actorId);
      }
    }
    if (body != null && body.get("approvedByPersonName") != null) {
      tx.setApprovedByPersonName(String.valueOf(body.get("approvedByPersonName")));
    } else if (StrUtil.isBlank(tx.getApprovedByPersonName())) {
      tx.setApprovedByPersonName(
          integrationOrchestrator != null
              ? integrationOrchestrator.resolveCurrentActorName(
                  body, "actorName", "approvedByPersonName")
              : "Cán bộ Tài chính - Kế toán");
    }
    BudgetTransaction saved = budgetTransactionService.save(tx);
    workflowEngineHelper.executeDoubleEntryLedger(saved);
    syncAffectedAllocationsToPlan(saved);
    if (integrationOrchestrator != null) {
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

  /** Từ chối giao dịch biến động nguồn kinh phí. */
  public BudgetTransactionData rejectTransaction(Long id, Map<String, Object> body) {
    BudgetTransaction tx =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    tx.setApprovalStatus("REJECTED");
    if (body != null && body.get("reason") != null) {
      String reason = String.valueOf(body.get("reason")).trim();
      if (StrUtil.isNotBlank(reason)) {
        tx.setRejectReason(reason);
        tx.setDescription(
            StrUtil.blankToDefault(tx.getDescription(), "") + " | Lý do từ chối: " + reason);
      }
    }
    BudgetTransaction saved = budgetTransactionService.save(tx);
    recalculateAffectedAllocations(saved.getAllocationId(), saved.getCounterpartAllocationId());
    if (integrationOrchestrator != null) {
      integrationOrchestrator.notifyTransactionEvent(saved, "REJECTED");
    }
    return enrichData(budgetTransactionMapper.toData(saved));
  }

  /**
   * Thực thi Điều chuyển Dự toán Thông minh 1-Click (Smart 1-Click Reallocation - UPG-05) từ đề
   * xuất của AI / Burn-rate Projection, tự động chọn dòng dự toán nguồn - đích và kích hoạt bút
   * toán kép {@code INTERNAL_TRANSFER}.
   */
  public Map<String, Object> executeSmartReallocation(Map<String, Object> body) {
    int year =
        body != null && body.get("budgetYear") != null
            ? Integer.parseInt(String.valueOf(body.get("budgetYear")))
            : LocalDate.now().getYear();
    String sourceUnitCode =
        body != null && body.get("sourceUnitCode") != null
            ? String.valueOf(body.get("sourceUnitCode")).trim()
            : (body != null && body.get("fromUnitCode") != null
                ? String.valueOf(body.get("fromUnitCode")).trim()
                : "");
    String targetUnitCode =
        body != null && body.get("targetUnitCode") != null
            ? String.valueOf(body.get("targetUnitCode")).trim()
            : (body != null && body.get("toUnitCode") != null
                ? String.valueOf(body.get("toUnitCode")).trim()
                : "");
    Long sourceAllocId =
        body != null && body.get("sourceAllocationId") != null
            ? Long.valueOf(String.valueOf(body.get("sourceAllocationId")))
            : null;
    Long targetAllocId =
        body != null && body.get("targetAllocationId") != null
            ? Long.valueOf(String.valueOf(body.get("targetAllocationId")))
            : null;
    BigDecimal rawAmount =
        body != null && body.get("amount") != null
            ? new BigDecimal(String.valueOf(body.get("amount")))
            : (body != null && body.get("suggestedTransferAmount") != null
                ? new BigDecimal(String.valueOf(body.get("suggestedTransferAmount")))
                : BigDecimal.ZERO);

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

    if (sourceAlloc == null && !yearAllocs.isEmpty()) {
      sourceAlloc = yearAllocs.get(0);
    }
    if (targetAlloc == null && yearAllocs.size() > 1) {
      for (BudgetAllocation a : yearAllocs) {
        if (sourceAlloc != null && !a.getId().equals(sourceAlloc.getId())) {
          targetAlloc = a;
          break;
        }
      }
    }
    if (sourceAlloc == null) {
      throw new BaseException("Không tìm thấy phân bổ dự toán nguồn để thực hiện điều chuyển.");
    }

    BigDecimal avail = workflowEngineHelper.safe(sourceAlloc.getAvailableAmount());
    BigDecimal transferAmount =
        rawAmount.compareTo(BigDecimal.ZERO) > 0
            ? rawAmount.min(avail)
            : avail.multiply(new BigDecimal("0.20")).setScale(0, java.math.RoundingMode.HALF_UP);
    if (transferAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException(
          "Số dư dự toán khả dụng của đơn vị nguồn ["
              + sourceAlloc.getCode()
              + "] không đủ để điều chuyển.");
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
            .txDate(LocalDate.now())
            .amount(transferAmount)
            .decisionNo("QĐ-ĐCNB-" + year + "/STC")
            .decisionDate(LocalDate.now())
            .approvalStatus("APPROVED")
            .description(reason)
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx = create(txData);
    BudgetAllocation updatedSource =
        budgetAllocationRepo.findById(sourceAlloc.getId()).orElse(sourceAlloc);
    BudgetAllocation updatedTarget =
        targetAlloc != null
            ? budgetAllocationRepo.findById(targetAlloc.getId()).orElse(targetAlloc)
            : null;

    Map<String, Object> res = new java.util.LinkedHashMap<>();
    res.put("executed", true);
    res.put("transaction", createdTx);
    res.put("transferAmount", transferAmount);
    res.put("sourceAllocationCode", updatedSource.getCode());
    res.put("sourceAvailableAfter", updatedSource.getAvailableAmount());
    res.put(
        "targetAllocationCode", updatedTarget != null ? updatedTarget.getCode() : "N/A");
    res.put(
        "targetEffectiveAfter",
        updatedTarget != null ? updatedTarget.getEffectiveAmount() : BigDecimal.ZERO);
    return res;
  }

}
