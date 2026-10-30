package vn.tr.budget.business;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetCategoryRepo;
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
  private BudgetCategoryRepo budgetCategoryRepo;

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
    BigDecimal rawDisbursed = parseBigDecimalOrDefault(body, "disbursedAmount", BigDecimal.ZERO);
    String dynamicBreakdownJson = extractString(body, "dynamicBreakdownJson", null, "");

    if (body != null && body.get("domainBreakdowns") instanceof List<?> rawList && !rawList.isEmpty()) {
      JSONArray arr = new JSONArray();
      BigDecimal sumAlloc = BigDecimal.ZERO;
      BigDecimal sumDisb = BigDecimal.ZERO;
      for (Object item : rawList) {
        if (item instanceof Map<?, ?> m) {
          String c = m.get("code") != null ? String.valueOf(m.get("code")).trim() : domainCode;
          String n = m.get("name") != null ? String.valueOf(m.get("name")).trim() : c;
          BigDecimal aVal =
              m.get("allocatedAmount") != null
                  ? new BigDecimal(String.valueOf(m.get("allocatedAmount")))
                  : BigDecimal.ZERO;
          BigDecimal dVal =
              m.get("disbursedAmount") != null
                  ? new BigDecimal(String.valueOf(m.get("disbursedAmount")))
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
          obj.set("disbursedAmount", dVal);
          arr.add(obj);
          sumAlloc = sumAlloc.add(aVal);
          sumDisb = sumDisb.add(dVal);
        }
      }
      dynamicBreakdownJson = arr.toString();
      if (rawAmount.compareTo(BigDecimal.ZERO) == 0) {
        rawAmount = sumAlloc;
      }
      if (rawDisbursed.compareTo(BigDecimal.ZERO) == 0) {
        rawDisbursed = sumDisb;
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

    BudgetTransactionData txData =
        BudgetTransactionData.builder()
            .code(txCode)
            .name(contentTitle)
            .allocationId(targetAlloc != null ? targetAlloc.getId() : null)
            .allocationCode(targetAlloc != null ? targetAlloc.getCode() : null)
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : parseLongOrNull(body, "nodeId"))
            .nodeCode(
                targetAlloc != null
                    ? targetAlloc.getNodeCode()
                    : extractString(body, "nodeCode", "unitNodeCode", "VAN_PHONG_SO"))
            .nodeName(
                targetAlloc != null
                    ? targetAlloc.getNodeName()
                    : extractString(body, "nodeName", "unitNodeName", "Văn phòng Sở"))
            .domainCode(domainCode)
            .domainName(domainName)
            .domainGroupCode(domainGroupCode)
            .domainGroupName(domainGroupName)
            .dynamicBreakdownJson(StrUtil.isNotBlank(dynamicBreakdownJson) ? dynamicBreakdownJson : null)
            .budgetYear(year)
            .txType("SUPPLEMENT")
            .txDate(txDate)
            .amount(rawAmount)
            .disbursedAmount(rawDisbursed)
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(contentTitle + " (" + decisionNo + ")")
            .approvalStatus(
                String.valueOf(
                    evaluateOperationRoleGate("NV-01", body, rawAmount)
                        .get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx = create(txData);
    BudgetAllocation refreshed =
        targetAlloc != null && targetAlloc.getId() != null
            ? budgetAllocationRepo.findById(targetAlloc.getId()).orElse(targetAlloc)
            : targetAlloc;

    Map<String, Object> roleMeta = evaluateOperationRoleGate("NV-01", body, rawAmount);
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("supplemented", true);
    res.put("operationType", "SUPPLEMENT_CONTENT");
    res.put("contentTitle", contentTitle);
    res.put("supplementedAmount", rawAmount);
    res.put("disbursedAmount", rawDisbursed);
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
    String decisionNo =
        extractString(body, "decisionNo", "referenceDocumentNo", "TB-CN-" + toYear + "/KBNN");
    LocalDate txDate = parseLocalDateOrDefault(body, "txDate", LocalDate.now());

    BudgetAllocation sourceAlloc = resolveExistingAllocation(body, fromYear);
    String sourceDomainCode =
        extractString(
            body,
            "sourceDomainCode",
            "domainCode",
            sourceAlloc != null && StrUtil.isNotBlank(sourceAlloc.getDomainCode())
                ? sourceAlloc.getDomainCode()
                : "TCDLCL");
    String sourceDomainName =
        extractString(
            body,
            "sourceDomainName",
            "domainName",
            sourceAlloc != null && StrUtil.isNotBlank(sourceAlloc.getDomainName())
                ? sourceAlloc.getDomainName()
                : "Tiêu chuẩn Đo lường Chất lượng");

    // Tự động tra cứu cấu hình Lĩnh vực năm đích (toYear) để ánh xạ previousYearDomainCode -> domainCode mới
    String mappedDomainCode = sourceDomainCode;
    String mappedDomainName = sourceDomainName;
    String mappedGroupCode =
        sourceAlloc != null && StrUtil.isNotBlank(sourceAlloc.getDomainGroupCode())
            ? sourceAlloc.getDomainGroupCode()
            : "GRP_CORE_TECH";
    String mappedGroupName =
        sourceAlloc != null && StrUtil.isNotBlank(sourceAlloc.getDomainGroupName())
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
          if (sourceDomainCode.equalsIgnoreCase(StrUtil.nullToEmpty(cfg.getPreviousYearDomainCode()).trim())
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
    if (carryAmount.compareTo(BigDecimal.ZERO) <= 0 && sourceAlloc != null) {
      carryAmount = workflowEngineHelper.safe(sourceAlloc.getAvailableAmount());
    }
    if (carryAmount.compareTo(BigDecimal.ZERO) <= 0) {
      carryAmount = new BigDecimal("1512000000");
    }
    BigDecimal disbursedOnCarry = parseBigDecimalOrDefault(body, "disbursedAmount", BigDecimal.ZERO);

    if (sourceAlloc == null) {
      // Nếu năm nguồn (ví dụ 2025) chưa có bản ghi phân bổ trong DB, tự động khởi tạo bản ghi nguồn để đảm bảo bút toán kép đầy đủ
      String srcAllocCode = "PB-" + fromYear + "-CN-" + (System.currentTimeMillis() % 10000);
      sourceAlloc =
          budgetAllocationRepo.save(
              BudgetAllocation.builder()
                  .code(srcAllocCode)
                  .name("Dự toán năm " + fromYear + " chuyển nguồn sang năm " + toYear)
                  .budgetYear(fromYear)
                  .periodType("ANNUAL")
                  .nodeId(parseLongOrNull(body, "nodeId") != null ? parseLongOrNull(body, "nodeId") : 103L)
                  .nodeCode(extractString(body, "nodeCode", "unitNodeCode", "TT_UDKHCN"))
                  .nodeName(
                      extractString(
                          body,
                          "nodeName",
                          "unitNodeName",
                          "Trung tâm Ứng dụng KHCN và Tiêu chuẩn Đo lường Chất lượng Đà Nẵng"))
                  .itemType("EXPENDITURE")
                  .categoryCode("281")
                  .categoryName("Chi sự nghiệp KHCN")
                  .domainCode(sourceDomainCode)
                  .domainName(sourceDomainName)
                  .domainGroupCode(mappedGroupCode)
                  .domainGroupName(mappedGroupName)
                  .workflowStatus("APPROVED")
                  .currentVersion(1)
                  .initialAmount(carryAmount)
                  .effectiveAmount(carryAmount)
                  .availableAmount(carryAmount)
                  .status(LifecycleStatus.ACTIVE)
                  .build());
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
            .txDate(txDate)
            .amount(carryAmount)
            .disbursedAmount(disbursedOnCarry)
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(reason)
            .approvalStatus(
                String.valueOf(
                    evaluateOperationRoleGate("NV-02", body, carryAmount)
                        .get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx = create(txData);
    Map<String, Object> roleMeta = evaluateOperationRoleGate("NV-02", body, carryAmount);

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

    BudgetAllocation unitAlloc =
        resolveOrCreateTargetAllocation(
            body,
            year,
            "Dự toán điều hành đơn vị năm " + year,
            fromDomainCode,
            fromDomainName,
            "GRP_CORE_TECH",
            "Nhóm KHCN & Hạ tầng");

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
            .nodeId(unitAlloc != null ? unitAlloc.getNodeId() : 101L)
            .nodeCode(unitAlloc != null ? unitAlloc.getNodeCode() : "VAN_PHONG_SO")
            .nodeName(unitAlloc != null ? unitAlloc.getNodeName() : "Văn phòng Sở")
            .domainCode(toDomainCode)
            .domainName(toDomainName)
            .dynamicBreakdownJson(breakdownArr.toString())
            .budgetYear(year)
            .txType("INTERNAL_TRANSFER")
            .txDate(txDate)
            .amount(BigDecimal.ZERO)
            .disbursedAmount(BigDecimal.ZERO)
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(reason)
            .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx = create(txData);

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
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : 101L)
            .nodeCode(targetAlloc != null ? targetAlloc.getNodeCode() : "VAN_PHONG_SO")
            .nodeName(targetAlloc != null ? targetAlloc.getNodeName() : "Văn phòng Sở")
            .domainCode(domainCode)
            .domainName(domainName)
            .dynamicBreakdownJson(dynamicBreakdownJson)
            .budgetYear(year)
            .txType("ADJUSTMENT")
            .txDate(txDate)
            .amount(rawAmount.negate())
            .disbursedAmount(BigDecimal.ZERO)
            .decisionNo(decisionNo)
            .decisionDate(txDate)
            .reason(reason)
            .description(title + " (" + decisionNo + ")")
            .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx = create(txData);
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
      targetAlloc =
          resolveOrCreateTargetAllocation(
              body,
              year,
              "Dự toán thu hồi cuối niên độ " + year,
              "KHCN",
              "Khoa học công nghệ",
              "GRP_CORE_TECH",
              "Nhóm KHCN & Hạ tầng");
    }

    BigDecimal cancelAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO).abs();
    if (cancelAmount.compareTo(BigDecimal.ZERO) <= 0 && targetAlloc != null) {
      cancelAmount = workflowEngineHelper.safe(targetAlloc.getAvailableAmount());
    }
    if (cancelAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException("Số tiền hủy / thu hồi dự toán hết niên độ phải lớn hơn 0.");
    }

    Map<String, Object> roleMeta = evaluateOperationRoleGate("NV-05", body, cancelAmount);
    boolean isApprovedNow =
        "APPROVED".equalsIgnoreCase(String.valueOf(roleMeta.get("effectiveApprovalStatus")));

    if (targetAlloc != null && isApprovedNow) {
      BigDecimal currentExpired = workflowEngineHelper.safe(targetAlloc.getExpiredCancelledAmount());
      targetAlloc.setExpiredCancelledAmount(currentExpired.add(cancelAmount));
      budgetAllocationRepo.save(targetAlloc);
    }

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
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : 101L)
            .nodeCode(targetAlloc != null ? targetAlloc.getNodeCode() : "VAN_PHONG_SO")
            .nodeName(targetAlloc != null ? targetAlloc.getNodeName() : "Văn phòng Sở")
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

    BudgetTransactionData createdTx = create(txData);
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
    boolean isRelease = "RELEASE".equals(holdAction) || "RELEASE_HOLD".equals(holdAction);
    String txType = isRelease ? "RELEASE_HOLD" : "EARMARK_HOLD";

    BudgetAllocation targetAlloc =
        resolveOrCreateTargetAllocation(
            body,
            year,
            "Dự toán kiểm soát phong tỏa năm " + year,
            extractString(body, "domainCode", null, "CDS"),
            extractString(body, "domainName", null, "Chuyển đổi số"),
            "GRP_INNOV_DIGITAL",
            "Nhóm Đổi mới sáng tạo & Chuyển đổi số");

    BigDecimal holdAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO).abs();
    if (holdAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException("Số tiền tạm giữ / giải tỏa dự toán phải lớn hơn 0.");
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
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : 101L)
            .nodeCode(targetAlloc != null ? targetAlloc.getNodeCode() : "VAN_PHONG_SO")
            .nodeName(targetAlloc != null ? targetAlloc.getNodeName() : "Văn phòng Sở")
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

    BudgetTransactionData createdTx = create(txData);
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

    BudgetAllocation targetAlloc =
        resolveOrCreateTargetAllocation(
            body,
            year,
            "Dự toán phân bổ theo đợt năm " + year,
            extractString(body, "domainCode", null, "KHCN"),
            extractString(body, "domainName", null, "Khoa học công nghệ"),
            "GRP_CORE_TECH",
            "Nhóm KHCN & Hạ tầng");

    BigDecimal releaseRatePercent =
        parseBigDecimalOrDefault(body, "releaseRatePercent", new BigDecimal("25"));
    BigDecimal releaseAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO).abs();
    if (releaseAmount.compareTo(BigDecimal.ZERO) == 0 && targetAlloc != null) {
      BigDecimal base =
          workflowEngineHelper.safe(targetAlloc.getInitialAmount()).compareTo(BigDecimal.ZERO) > 0
              ? targetAlloc.getInitialAmount()
              : new BigDecimal("2000000000");
      releaseAmount =
          base.multiply(releaseRatePercent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
    if (releaseAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BaseException("Số tiền giải tỏa dự toán theo đợt phải lớn hơn 0.");
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
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : 101L)
            .nodeCode(targetAlloc != null ? targetAlloc.getNodeCode() : "VAN_PHONG_SO")
            .nodeName(targetAlloc != null ? targetAlloc.getNodeName() : "Văn phòng Sở")
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

    BudgetTransactionData createdTx = create(txData);
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
    int staffChangeCount = parseIntOrDefault(body, "staffChangeCount", 2);
    int baseStaffCount = parseIntOrDefault(body, "baseStaffCount", 35);
    int monthsRemaining = parseIntOrDefault(body, "monthsRemaining", 6);
    BigDecimal avgCoefficient =
        parseBigDecimalOrDefault(body, "averageSalaryCoefficient", new BigDecimal("3.65"));
    BigDecimal insuranceContributionFactor = new BigDecimal("1.235"); // 23.5% các khoản đóng góp theo lương

    BigDecimal salaryDeltaPerPersonMonth =
        newBaseSalary
            .subtract(oldBaseSalary)
            .multiply(avgCoefficient)
            .multiply(insuranceContributionFactor);
    BigDecimal indexationFromBaseSalary =
        salaryDeltaPerPersonMonth
            .multiply(BigDecimal.valueOf(baseStaffCount))
            .multiply(BigDecimal.valueOf(monthsRemaining))
            .setScale(0, RoundingMode.HALF_UP);
    BigDecimal indexationFromStaffChange =
        newBaseSalary
            .multiply(avgCoefficient)
            .multiply(insuranceContributionFactor)
            .multiply(BigDecimal.valueOf(staffChangeCount))
            .multiply(BigDecimal.valueOf(monthsRemaining))
            .setScale(0, RoundingMode.HALF_UP);

    BigDecimal explicitAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO);
    BigDecimal unitAdjustmentAmount =
        explicitAmount.compareTo(BigDecimal.ZERO) != 0
            ? explicitAmount
            : indexationFromBaseSalary.add(indexationFromStaffChange);
    if (unitAdjustmentAmount.compareTo(BigDecimal.ZERO) == 0) {
      unitAdjustmentAmount = new BigDecimal("185000000");
    }

    List<BudgetAllocation> targetAllocs = new ArrayList<>();
    BudgetAllocation explicitAlloc = resolveExistingAllocation(body, year);
    if (explicitAlloc != null) {
      targetAllocs.add(explicitAlloc);
    } else if (budgetAllocationRepo != null) {
      List<BudgetAllocation> yearAllocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);
      for (BudgetAllocation a : yearAllocs) {
        if (!"REVENUE".equalsIgnoreCase(a.getItemType())) {
          targetAllocs.add(a);
          if (targetAllocs.size() >= 3) {
            break;
          }
        }
      }
    }
    if (targetAllocs.isEmpty()) {
      BudgetAllocation fallback =
          resolveOrCreateTargetAllocation(
              body,
              year,
              "Quỹ lương và phụ cấp theo biên chế năm " + year,
              "KHCN",
              "Khoa học công nghệ",
              "GRP_CORE_TECH",
              "Nhóm KHCN & Hạ tầng");
      if (fallback != null) {
        targetAllocs.add(fallback);
      }
    }

    String decisionNo =
        extractString(body, "decisionNo", "referenceDocumentNo", "QĐ-ĐCTL-" + year + "/UBND");
    LocalDate txDate = parseLocalDateOrDefault(body, "txDate", LocalDate.now());
    Map<String, Object> roleMeta =
        evaluateOperationRoleGate("DX-03", body, unitAdjustmentAmount.abs());

    List<BudgetTransactionData> createdTxs = new ArrayList<>();
    BigDecimal totalIndexedAmount = BigDecimal.ZERO;

    int idx = 1;
    for (BudgetAllocation alloc : targetAllocs) {
      String txCode =
          "TX-PAY-" + year + "-" + idx + "-" + (System.currentTimeMillis() % 100000);
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
              .txDate(txDate)
              .amount(unitAdjustmentAmount)
              .decisionNo(decisionNo)
              .decisionDate(txDate)
              .reason(reason)
              .description(reason)
              .approvalStatus(String.valueOf(roleMeta.get("effectiveApprovalStatus")))
              .status(LifecycleStatus.ACTIVE)
              .build();
      createdTxs.add(create(txData));
      totalIndexedAmount = totalIndexedAmount.add(unitAdjustmentAmount);
      idx++;
    }

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
    res.put("unitAdjustmentAmount", unitAdjustmentAmount);
    res.put("totalIndexedAmount", totalIndexedAmount);
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
    BudgetAllocation targetAlloc =
        resolveOrCreateTargetAllocation(
            body,
            year,
            "Kinh phí tự chủ trích lập các quỹ cuối năm " + year,
            "KHCN",
            "Khoa học công nghệ",
            "GRP_CORE_TECH",
            "Nhóm KHCN & Hạ tầng");

    BigDecimal surplusAmount = parseBigDecimalOrDefault(body, "surplusAmount", BigDecimal.ZERO).abs();
    if (surplusAmount.compareTo(BigDecimal.ZERO) == 0) {
      surplusAmount = parseBigDecimalOrDefault(body, "amount", BigDecimal.ZERO).abs();
    }
    if (surplusAmount.compareTo(BigDecimal.ZERO) == 0 && targetAlloc != null) {
      surplusAmount = workflowEngineHelper.safe(targetAlloc.getAvailableAmount());
    }
    if (surplusAmount.compareTo(BigDecimal.ZERO) <= 0) {
      surplusAmount = new BigDecimal("500000000");
    }

    // Tỷ lệ trích lập 4 Quỹ theo Điều 14, Điều 18 Nghị định 60/2021/NĐ-CP
    BigDecimal pthdsnRate =
        parseBigDecimalOrDefault(body, "pthdsnRatePercent", new BigDecimal("25"));
    BigDecimal bstnRate = parseBigDecimalOrDefault(body, "bstnRatePercent", new BigDecimal("45"));
    BigDecimal ktplRate = parseBigDecimalOrDefault(body, "ktplRatePercent", new BigDecimal("20"));
    BigDecimal dpodtnRate =
        new BigDecimal("100")
            .subtract(pthdsnRate)
            .subtract(bstnRate)
            .subtract(ktplRate)
            .max(BigDecimal.ZERO);

    BigDecimal pthdsnAmount =
        surplusAmount.multiply(pthdsnRate).divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
    BigDecimal bstnAmount =
        surplusAmount.multiply(bstnRate).divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
    BigDecimal ktplAmount =
        surplusAmount.multiply(ktplRate).divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
    BigDecimal dpodtnAmount =
        surplusAmount.subtract(pthdsnAmount).subtract(bstnAmount).subtract(ktplAmount);

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
            .nodeId(targetAlloc != null ? targetAlloc.getNodeId() : 103L)
            .nodeCode(targetAlloc != null ? targetAlloc.getNodeCode() : "TT_UDKHCN")
            .nodeName(
                targetAlloc != null
                    ? targetAlloc.getNodeName()
                    : "Trung tâm Ứng dụng KHCN và Tiêu chuẩn Đo lường Chất lượng Đà Nẵng")
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

    BudgetTransactionData createdTx = create(txData);

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

  private Map<String, Object> evaluateOperationRoleGate(
      String operationCode, Map<String, Object> body, BigDecimal amount) {
    String actorRole =
        extractString(body, "actorRole", "roleCode", "STC_DIRECTOR").toUpperCase();
    boolean strictRoleCheck =
        body != null && Boolean.parseBoolean(String.valueOf(body.get("strictRoleCheck")));

    String actorRoleName =
        switch (actorRole) {
          case "UNIT_ACCOUNTANT" -> "Kế toán Đơn vị Dự toán Cấp dưới (Cấp III)";
          case "UNIT_DIRECTOR" -> "Thủ trưởng Đơn vị Sử dụng Ngân sách";
          case "PARENT_APPRAISER" -> "Chuyên viên Thẩm định Tài chính - Kế hoạch (Cấp I)";
          case "TREASURY_RECONCILER" -> "Kiểm soát viên Đối soát Kho bạc (KBNN)";
          default -> "Lãnh đạo Sở Chủ quản / Sở Tài chính (STC_DIRECTOR)";
        };

    List<String> allowedInitiators =
        switch (operationCode) {
          case "NV-01", "NV-02", "NV-03" -> List.of(
              "UNIT_ACCOUNTANT", "UNIT_DIRECTOR", "PARENT_APPRAISER", "STC_DIRECTOR", "TREASURY_RECONCILER");
          case "NV-04" -> List.of("PARENT_APPRAISER", "UNIT_DIRECTOR", "STC_DIRECTOR");
          case "NV-05" -> List.of("TREASURY_RECONCILER", "PARENT_APPRAISER", "STC_DIRECTOR");
          case "DX-01", "DX-02", "DX-03" -> List.of("PARENT_APPRAISER", "STC_DIRECTOR");
          case "DX-04" -> List.of("UNIT_ACCOUNTANT", "UNIT_DIRECTOR", "PARENT_APPRAISER", "STC_DIRECTOR");
          default -> List.of("STC_DIRECTOR");
        };

    List<String> allowedApprovers =
        switch (operationCode) {
          case "NV-01", "NV-04", "DX-02", "DX-03" -> List.of("STC_DIRECTOR");
          case "NV-02", "NV-05" -> List.of("STC_DIRECTOR", "TREASURY_RECONCILER");
          case "NV-03", "DX-04" -> List.of("UNIT_DIRECTOR", "STC_DIRECTOR");
          case "DX-01" -> {
            String holdAction = extractString(body, "holdAction", "actionMode", "HOLD").toUpperCase();
            yield "RELEASE".equals(holdAction) || "RELEASE_HOLD".equals(holdAction)
                ? List.of("STC_DIRECTOR")
                : List.of("PARENT_APPRAISER", "STC_DIRECTOR");
          }
          default -> List.of("STC_DIRECTOR");
        };

    boolean canInitiate = allowedInitiators.contains(actorRole);
    if (!canInitiate && strictRoleCheck) {
      throw new BaseException(
          "Vai trò ["
              + actorRoleName
              + "] không có thẩm quyền khởi tạo nghiệp vụ ["
              + operationCode
              + "]. Yêu cầu một trong các vai trò: "
              + String.join(", ", allowedInitiators));
    }

    boolean canAutoApprove = allowedApprovers.contains(actorRole);
    String effectiveApprovalStatus = canAutoApprove ? "APPROVED" : "PENDING";
    String requiredNextApprover =
        canAutoApprove ? "NONE" : (allowedApprovers.isEmpty() ? "STC_DIRECTOR" : allowedApprovers.getFirst());

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("operationCode", operationCode);
    meta.put("actorRole", actorRole);
    meta.put("actorRoleName", actorRoleName);
    meta.put("canInitiate", canInitiate);
    meta.put("canAutoApprove", canAutoApprove);
    meta.put("effectiveApprovalStatus", effectiveApprovalStatus);
    meta.put("requiredApproverRole", requiredNextApprover);
    meta.put("allowedInitiatorRoles", allowedInitiators);
    meta.put("allowedApproverRoles", allowedApprovers);
    meta.put("evaluatedAmount", amount);
    return meta;
  }

  private BudgetAllocation resolveExistingAllocation(Map<String, Object> body, int year) {
    if (budgetAllocationRepo == null) {
      return null;
    }
    Long allocId = parseLongOrNull(body, "allocationId");
    if (allocId != null) {
      BudgetAllocation found = budgetAllocationRepo.findById(allocId).orElse(null);
      if (found != null) {
        return found;
      }
    }
    String allocCode = extractString(body, "allocationCode", null, "");
    if (StrUtil.isNotBlank(allocCode)) {
      List<BudgetAllocation> byCode =
          budgetAllocationRepo.findAllByCodeIgnoreCaseIncludingDeletedSorted(allocCode.trim());
      if (!byCode.isEmpty()) {
        return byCode.getFirst();
      }
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
    }
    String nodeCode = extractString(body, "nodeCode", "unitNodeCode", "");
    List<BudgetAllocation> yearAllocs = budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);
    if (StrUtil.isNotBlank(nodeCode)) {
      for (BudgetAllocation a : yearAllocs) {
        if (!"REVENUE".equalsIgnoreCase(a.getItemType())
            && nodeCode.equalsIgnoreCase(a.getNodeCode())) {
          return a;
        }
      }
    }
    for (BudgetAllocation a : yearAllocs) {
      if (!"REVENUE".equalsIgnoreCase(a.getItemType())) {
        return a;
      }
    }
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
    String newCode =
        "PB-" + year + "-" + domainCode + "-" + (System.currentTimeMillis() % 100000);
    Long nodeId = parseLongOrNull(body, "nodeId") != null ? parseLongOrNull(body, "nodeId") : 101L;
    String nodeCode = extractString(body, "nodeCode", "unitNodeCode", "VAN_PHONG_SO");
    String nodeName = extractString(body, "nodeName", "unitNodeName", "Văn phòng Sở");
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
            .workflowStatus("APPROVED")
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

}
