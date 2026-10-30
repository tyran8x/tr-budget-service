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
            .approvalStatus("APPROVED")
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx = create(txData);
    BudgetAllocation refreshed =
        targetAlloc != null && targetAlloc.getId() != null
            ? budgetAllocationRepo.findById(targetAlloc.getId()).orElse(targetAlloc)
            : targetAlloc;

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
            .approvalStatus("APPROVED")
            .status(LifecycleStatus.ACTIVE)
            .build();

    BudgetTransactionData createdTx = create(txData);

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
            .approvalStatus("APPROVED")
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
            .approvalStatus("APPROVED")
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

    if (targetAlloc != null) {
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
            .approvalStatus("APPROVED")
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
    res.put("transaction", createdTx);
    return res;
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
