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
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.service.BudgetDisbursementService;
import vn.tr.budget.data.criteria.BudgetDisbursementSearchCriteria;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.data.validator.BudgetDisbursementValidator;
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
 * Lớp điều phối nghiệp vụ cho BudgetDisbursement.
 *
 * @author tyran8x
 */
@Service("budgetDisbursementBusiness")
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BudgetDisbursementBusiness {

  private final BudgetDisbursementService budgetDisbursementService;
  private final BudgetDisbursementMapper budgetDisbursementMapper;
  private final BudgetDisbursementValidator budgetDisbursementValidator;
  private final GenericUpsertHelper genericUpsertHelper;
  private final AuditRollbackHelper auditRollbackHelper;
  private final BusinessLogicHelper businessLogicHelper;
  private final BudgetWorkflowEngineHelper workflowEngineHelper;
  private final BudgetAllocationRepo budgetAllocationRepo;

  @Autowired(required = false)
  private BudgetEstimateItemRepo budgetEstimateItemRepo;

  @Autowired(required = false)
  private BudgetIntegrationOrchestrator integrationOrchestrator;

  public JpaRepository<BudgetDisbursement, Long> getInjectedRepo() {
    return budgetDisbursementService.getRepository();
  }

  /** Tạo mới BudgetDisbursement. */
  public BudgetDisbursementData create(BudgetDisbursementData data) {
    data.setId(null);
    validateData(data);
    if (data.getStatus() == null) {
      data.setStatus(LifecycleStatus.ACTIVE);
    }
    if (data.getBudgetYear() == null) {
      data.setBudgetYear(LocalDate.now().getYear());
    }
    if (StrUtil.isBlank(data.getDisbursementType())) {
      data.setDisbursementType("DISBURSEMENT");
    }
    if (StrUtil.isBlank(data.getApprovalStatus())) {
      data.setApprovalStatus("APPROVED_DISBURSED");
    }
    if (data.getRequestedAmount() == null && data.getAmount() != null) {
      data.setRequestedAmount(data.getAmount());
    }
    if (data.getNetPaymentAmount() == null && data.getAmount() != null) {
      BigDecimal tax = data.getTaxAmount() != null ? data.getTaxAmount() : BigDecimal.ZERO;
      data.setNetPaymentAmount(data.getAmount().subtract(tax));
    }
    syncDisbursementSnapshots(data);
    validateOverBudgetAndVoucherGates(data);
    BudgetDisbursement entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetDisbursementService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> budgetDisbursementMapper.toEntity(data),
            budgetDisbursementMapper::updateEntityFromData,
            budgetDisbursementService.getRepository());
    if (entity.getAllocationId() != null) {
      workflowEngineHelper.recalculateAllocationMetrics(entity.getAllocationId());
    }
    return enrichData(budgetDisbursementMapper.toData(entity));
  }

  /** Cập nhật BudgetDisbursement. */
  public BudgetDisbursementData update(Long id, BudgetDisbursementData data) {
    BudgetDisbursement existing =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    Long oldAllocId = existing.getAllocationId();
    data.setId(id);
    validateData(data);
    syncDisbursementSnapshots(data);
    budgetDisbursementMapper.updateEntityFromData(data, existing);
    BudgetDisbursement saved = budgetDisbursementService.save(existing);
    if (oldAllocId != null) {
      workflowEngineHelper.recalculateAllocationMetrics(oldAllocId);
    }
    if (saved.getAllocationId() != null && !saved.getAllocationId().equals(oldAllocId)) {
      workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    return enrichData(budgetDisbursementMapper.toData(saved));
  }

  /** Xoá mềm BudgetDisbursement. */
  public void delete(Long id) {
    BudgetDisbursement existing =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    Long allocId = existing.getAllocationId();
    budgetDisbursementService.delete(id);
    if (allocId != null) {
      workflowEngineHelper.recalculateAllocationMetrics(allocId);
    }
  }

  /** Xoá mềm hàng loạt BudgetDisbursement. */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    List<BudgetDisbursement> toDelete =
        ids != null && !ids.isEmpty() ? budgetDisbursementService.findAllByIds(ids) : List.of();
    BulkOperationResult<Long> result =
        businessLogicHelper.executeBulkDelete(
            ids,
            budgetDisbursementService::findAllByIds,
            BudgetDisbursement::getId,
            null,
            null,
            budgetDisbursementService::deleteByIds,
            "budget.budgetDisbursement.not.found");
    for (BudgetDisbursement disb : toDelete) {
      if (disb.getAllocationId() != null) {
        workflowEngineHelper.recalculateAllocationMetrics(disb.getAllocationId());
      }
    }
    return result;
  }

  /** Lấy chi tiết BudgetDisbursement theo ID. */
  @Transactional(readOnly = true)
  public BudgetDisbursementData findById(Long id) {
    return budgetDisbursementService
        .findById(id)
        .map(budgetDisbursementMapper::toData)
        .map(this::enrichData)
        .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
  }

  /** Tìm kiếm BudgetDisbursement phân trang. */
  @Transactional(readOnly = true)
  public PagedData<BudgetDisbursementData> findAll(BudgetDisbursementSearchCriteria criteria) {
    Pageable pageable = CoreUtils.getPageRequest(criteria);
    Page<BudgetDisbursement> page = budgetDisbursementService.findAll(criteria, pageable);
    return PagedData.from(
        page, entity -> enrichData(budgetDisbursementMapper.toData(entity)));
  }

  /**
   * Đồng bộ hai chiều (Hybrid Live-Resolve + Snapshot Fallback) cho Allocation, Node, EstimateItem
   * và Person.
   */
  private void syncDisbursementSnapshots(BudgetDisbursementData data) {
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
    }
    if (budgetEstimateItemRepo != null) {
      if (data.getEstimateItemId() != null) {
        BudgetEstimateItem item =
            budgetEstimateItemRepo.findById(data.getEstimateItemId()).orElse(null);
        if (item != null) {
          data.setEstimateItemCode(item.getCode());
          data.setEstimateItemName(item.getName());
        }
      } else if (StrUtil.isNotBlank(data.getEstimateItemCode())) {
        List<BudgetEstimateItem> items =
            budgetEstimateItemRepo.findAllByCodeIgnoreCaseIncludingDeletedSorted(
                data.getEstimateItemCode().trim());
        if (!items.isEmpty()) {
          BudgetEstimateItem item = items.get(0);
          data.setEstimateItemId(item.getId());
          data.setEstimateItemCode(item.getCode());
          if (StrUtil.isBlank(data.getEstimateItemName())) {
            data.setEstimateItemName(item.getName());
          }
        }
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

  private BudgetDisbursementData enrichData(BudgetDisbursementData dto) {
    if (dto == null || integrationOrchestrator == null) {
      return dto;
    }
    if (dto.getNodeId() != null) {
      dto.setNodeCode(
          integrationOrchestrator.resolveNodeCodeWithFallback(dto.getNodeId(), dto.getNodeCode()));
      dto.setNodeName(
          integrationOrchestrator.resolveNodeNameWithFallback(dto.getNodeId(), dto.getNodeName()));
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

  /** Sao chép BudgetDisbursement. */
  @Transactional
  public BudgetDisbursementData cloneById(Long id) {
    BudgetDisbursement source =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    BudgetDisbursementData clonedData = budgetDisbursementMapper.toData(source);
    clonedData.setId(null);
    clonedData.setCode(source.getCode() + "_COPY_" + System.currentTimeMillis() % 10000);
    clonedData.setName(source.getName() + " (Bản sao)");
    return create(clonedData);
  }

  /** Khôi phục phiên bản lịch sử JaVers. */
  @Transactional
  public BudgetDisbursementData rollback(Long id, Long version) {
    return auditRollbackHelper.executeRollback(
        BudgetDisbursement.class,
        id,
        version,
        budgetDisbursementService::findById,
        budgetDisbursementService::save,
        budgetDisbursementMapper::toData,
        budgetDisbursementMapper::updateEntityFromData,
        BudgetDisbursementData::setId,
        this::validateData);
  }

  /** Cập nhật thứ tự sắp xếp hàng loạt. */
  @Transactional
  public void bulkUpdateSortOrder(BulkSortOrderData data) {
    if (data == null || data.getUpdates() == null || data.getUpdates().isEmpty()) {
      return;
    }
    for (BulkSortOrderData.SortOrderUpdate item : data.getUpdates()) {
      budgetDisbursementService
          .findById(item.getId())
          .ifPresent(
              entity -> {
                entity.setSortOrder(item.getSortOrder());
                budgetDisbursementService.save(entity);
              });
    }
  }

  private void validateData(BudgetDisbursementData data) {
    vn.tr.common.web.core.BaseBusinessHelper.validateOrThrow(
        budgetDisbursementValidator, data, "budgetDisbursementData");
  }

  private void validateOverBudgetAndVoucherGates(BudgetDisbursementData data) {
    String dtype = StrUtil.nullToEmpty(data.getDisbursementType()).toUpperCase();
    if (("DISBURSEMENT".equals(dtype)
            || "DIRECT_PAYMENT".equals(dtype)
            || "SETTLEMENT".equals(dtype)
            || "ADVANCE_SETTLEMENT".equals(dtype))
        && workflowEngineHelper.isVoucherRequired(data.getEstimateItemId())
        && StrUtil.isBlank(data.getVoucherNo())) {
      throw new BaseException(
          "Đầu mục dự toán này bắt buộc phải có Số chứng từ / Hóa đơn / Giấy rút dự toán Kho bạc (voucherNo) khi thực chi hoặc quyết toán.");
    }
    if (data.getAllocationId() != null
        && data.getAmount() != null
        && !"SETTLEMENT".equals(dtype)
        && !"ADVANCE_SETTLEMENT".equals(dtype)) {
      BudgetAllocation alloc =
          budgetAllocationRepo.findById(data.getAllocationId()).orElse(null);
      if (alloc != null && alloc.getAvailableAmount() != null) {
        if (data.getAmount().compareTo(alloc.getAvailableAmount()) > 0) {
          throw new BaseException(
              "Số tiền đề nghị giải ngân ("
                  + data.getAmount().toPlainString()
                  + " VNĐ) vượt quá dự toán khả dụng còn lại ("
                  + alloc.getAvailableAmount().toPlainString()
                  + " VNĐ) của phân bổ ["
                  + alloc.getCode()
                  + "].");
        }
      }
    }
  }

  /** Phê duyệt thực chi / giải ngân Kho bạc Nhà nước. */
  public BudgetDisbursementData approveDisbursement(Long id, Map<String, Object> body) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    if (body != null && body.get("treasuryRefCode") != null) {
      disb.setTreasuryRefCode(String.valueOf(body.get("treasuryRefCode")));
    }
    if (disb.getApprovedAmount() == null) {
      disb.setApprovedAmount(disb.getAmount());
    }
    disb.setApprovedDate(LocalDateTime.now());
    disb.setApprovalStatus("APPROVED_DISBURSED");
    if (integrationOrchestrator != null) {
      Long actorId =
          integrationOrchestrator.resolveCurrentPersonId(body, "approvedByPersonId", "actorId");
      if (actorId != null) {
        disb.setApprovedByPersonId(actorId);
      }
    }
    if (StrUtil.isBlank(disb.getApprovedByPersonName())) {
      disb.setApprovedByPersonName(
          integrationOrchestrator != null
              ? integrationOrchestrator.resolveCurrentActorName(
                  body, "actorName", "approvedByPersonName")
              : "Cán bộ Tài chính - Kế toán");
    }
    if (integrationOrchestrator != null && StrUtil.isBlank(disb.getPaymentTxCode())) {
      disb.setPaymentTxCode(integrationOrchestrator.syncDisbursementWithPaymentService(disb));
    }
    BudgetDisbursement saved = budgetDisbursementService.save(disb);
    BudgetAllocation updatedAlloc = null;
    if (saved.getAllocationId() != null) {
      updatedAlloc = workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    if (integrationOrchestrator != null) {
      integrationOrchestrator.notifyDisbursementEvent(saved, "APPROVED");
      if (updatedAlloc != null) {
        integrationOrchestrator.syncAllocationToLinkedPlan(
            updatedAlloc,
            "DISBURSEMENT_APPROVED",
            saved.getAmount(),
            saved.getCode(),
            "Giải ngân dự toán [" + saved.getCode() + "] " + saved.getName());
      }
    }
    return enrichData(budgetDisbursementMapper.toData(saved));
  }

  /** Nghiệm thu quyết toán / hoàn ứng chứng từ Kho bạc. */
  public BudgetDisbursementData settleDisbursement(Long id, Map<String, Object> body) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    if (body != null && body.get("voucherNo") != null) {
      disb.setVoucherNo(String.valueOf(body.get("voucherNo")));
    }
    if (workflowEngineHelper.isVoucherRequired(disb.getEstimateItemId())
        && StrUtil.isBlank(disb.getVoucherNo())) {
      throw new BaseException(
          "Bắt buộc bổ sung Số chứng từ / Hóa đơn (voucherNo) trước khi nghiệm thu quyết toán.");
    }
    BigDecimal settledAmt =
        body != null && body.get("settledAmount") != null
            ? new BigDecimal(String.valueOf(body.get("settledAmount")))
            : disb.getAmount();
    disb.setSettledAmount(settledAmt);
    disb.setSettledDate(LocalDateTime.now());
    if ("ADVANCE".equalsIgnoreCase(disb.getDisbursementType())) {
      disb.setDisbursementType("ADVANCE_SETTLEMENT");
    } else {
      disb.setDisbursementType("SETTLEMENT");
    }
    disb.setApprovalStatus("SETTLED");
    BudgetDisbursement saved = budgetDisbursementService.save(disb);
    BudgetAllocation updatedAlloc = null;
    if (saved.getAllocationId() != null) {
      updatedAlloc = workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    if (integrationOrchestrator != null) {
      integrationOrchestrator.notifyDisbursementEvent(saved, "SETTLED");
      if (updatedAlloc != null) {
        integrationOrchestrator.syncAllocationToLinkedPlan(
            updatedAlloc,
            "DISBURSEMENT_SETTLED",
            saved.getAmount(),
            saved.getCode(),
            "Quyết toán chứng từ Kho bạc [" + saved.getVoucherNo() + "]");
      }
    }
    return enrichData(budgetDisbursementMapper.toData(saved));
  }

  /** Khởi tạo nhanh hồ sơ Cam kết chi (COMMITMENT) từ Nhiệm vụ Kế hoạch hoặc Công việc. */
  public Map<String, Object> reserveCommitment(Map<String, Object> body) {
    String planCode = body != null && body.get("planCode") != null ? String.valueOf(body.get("planCode")) : "";
    Long allocationId =
        body != null && body.get("allocationId") != null
            ? Long.valueOf(String.valueOf(body.get("allocationId")))
            : null;
    BudgetAllocation alloc = null;
    if (allocationId != null) {
      alloc = budgetAllocationRepo.findById(allocationId).orElse(null);
    } else if (StrUtil.isNotBlank(planCode)) {
      List<BudgetAllocation> list =
          budgetAllocationRepo.findByLinkedPlanCodeIgnoreCaseOrderByIdAsc(planCode);
      if (!list.isEmpty()) {
        alloc = list.get(0);
      }
    }
    BigDecimal amount =
        body != null && body.get("amount") != null
            ? new BigDecimal(String.valueOf(body.get("amount")))
            : BigDecimal.ZERO;
    String code =
        body != null && body.get("code") != null
            ? String.valueOf(body.get("code"))
            : ("CKC-" + (StrUtil.isNotBlank(planCode) ? planCode : "AUTO") + "-" + System.currentTimeMillis() % 10000);
    String name =
        body != null && body.get("name") != null
            ? String.valueOf(body.get("name"))
            : ("Cam kết chi thực hiện nhiệm vụ kế hoạch " + planCode);

    BudgetDisbursementData data =
        BudgetDisbursementData.builder()
            .code(code)
            .name(name)
            .allocationId(alloc != null ? alloc.getId() : null)
            .allocationCode(alloc != null ? alloc.getCode() : null)
            .allocationName(alloc != null ? alloc.getName() : null)
            .nodeId(alloc != null ? alloc.getNodeId() : 101L)
            .nodeCode(alloc != null ? alloc.getNodeCode() : "VAN_PHONG_SO")
            .nodeName(alloc != null ? alloc.getNodeName() : "Văn phòng Sở")
            .budgetYear(alloc != null ? alloc.getBudgetYear() : LocalDate.now().getYear())
            .disbursementType("COMMITMENT")
            .amount(amount)
            .requestedAmount(amount)
            .netPaymentAmount(amount)
            .linkedPlanCode(planCode)
            .approvalStatus("COMMITTED")
            .status(LifecycleStatus.ACTIVE)
            .build();
    BudgetDisbursementData saved = create(data);
    return Map.of(
        "reserved", true,
        "disbursementId", saved.getId(),
        "disbursementCode", saved.getCode(),
        "amount", saved.getAmount(),
        "allocationCode", StrUtil.nullToEmpty(saved.getAllocationCode()));
  }

  /** Từ chối hồ sơ giải ngân. */
  public BudgetDisbursementData rejectDisbursement(Long id, Map<String, Object> body) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    disb.setApprovalStatus("REJECTED");
    if (body != null && body.get("reason") != null) {
      String reason = String.valueOf(body.get("reason")).trim();
      if (StrUtil.isNotBlank(reason)) {
        disb.setRejectReason(reason);
        disb.setDescription(
            StrUtil.blankToDefault(disb.getDescription(), "") + " | Lý do từ chối: " + reason);
      }
    }
    BudgetDisbursement saved = budgetDisbursementService.save(disb);
    if (saved.getAllocationId() != null) {
      workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    return enrichData(budgetDisbursementMapper.toData(saved));
  }

  /**
   * Kết xuất Gói Hồ sơ Giao dịch Điện tử Dịch vụ công Kho bạc Nhà nước (DVC KBNN / TABMIS - UPG-07)
   * theo Mẫu C2-02a/NS (Giấy rút dự toán ngân sách nhà nước) & C2-03/NS (Nghị định 11/2020/NĐ-CP).
   */
  @Transactional(readOnly = true)
  public Map<String, Object> exportKbnnDvcPackage(Long id) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    BudgetAllocation alloc =
        disb.getAllocationId() != null
            ? budgetAllocationRepo.findById(disb.getAllocationId()).orElse(null)
            : null;

    String formCode =
        "ADVANCE".equalsIgnoreCase(disb.getDisbursementType())
            ? "C2-03/NS (Giấy đề nghị thanh toán tạm ứng)"
            : "C2-02a/NS (Giấy rút dự toán ngân sách nhà nước)";
    String chapterCode = "423";
    String clauseCode =
        alloc != null && StrUtil.isNotBlank(alloc.getCategoryCode())
            ? alloc.getCategoryCode()
            : "281";
    String subItemCode = StrUtil.blankToDefault(disb.getSubItemCode(), "6551");
    String voucherNo =
        StrUtil.blankToDefault(disb.getVoucherNo(), "C2-02A-" + disb.getCode());
    String treasuryRefCode =
        StrUtil.blankToDefault(disb.getTreasuryRefCode(), "DVC-KBNN-" + disb.getId());
    BigDecimal amount = workflowEngineHelper.safe(disb.getAmount());
    BigDecimal taxAmount = workflowEngineHelper.safe(disb.getTaxAmount());
    BigDecimal netPaymentAmount =
        disb.getNetPaymentAmount() != null
            ? disb.getNetPaymentAmount()
            : amount.subtract(taxAmount);

    String xmlPayloadPreview =
        "<DVC_KBNN_PACKAGE version=\"3.5\" standard=\"ND11-2020-ND-CP\">"
            + "<HEADER><FORM_CODE>"
            + formCode
            + "</FORM_CODE><TREASURY_REF>"
            + treasuryRefCode
            + "</TREASURY_REF><VOUCHER_NO>"
            + voucherNo
            + "</VOUCHER_NO></HEADER>"
            + "<BUDGET_UNIT><CODE>"
            + StrUtil.blankToDefault(disb.getNodeCode(), "VAN_PHONG_SO")
            + "</CODE><NAME>"
            + StrUtil.blankToDefault(disb.getNodeName(), "Văn phòng Sở")
            + "</NAME><CHAPTER>"
            + chapterCode
            + "</CHAPTER><CLAUSE>"
            + clauseCode
            + "</CLAUSE><SUB_ITEM>"
            + subItemCode
            + "</SUB_ITEM></BUDGET_UNIT>"
            + "<PAYMENT><GROSS_AMOUNT>"
            + amount.toPlainString()
            + "</GROSS_AMOUNT><TAX_WITHHOLDING>"
            + taxAmount.toPlainString()
            + "</TAX_WITHHOLDING><NET_AMOUNT>"
            + netPaymentAmount.toPlainString()
            + "</NET_AMOUNT><BENEFICIARY>"
            + StrUtil.blankToDefault(disb.getBeneficiaryName(), "Đơn vị thụ hưởng hợp đồng")
            + "</BENEFICIARY><BANK_ACCOUNT>"
            + StrUtil.blankToDefault(disb.getBeneficiaryBankAccount(), "118000123456")
            + "</BANK_ACCOUNT><BANK_NAME>"
            + StrUtil.blankToDefault(disb.getBeneficiaryBankName(), "VietinBank Chi nhánh Đà Nẵng")
            + "</BANK_NAME></PAYMENT></DVC_KBNN_PACKAGE>";

    Map<String, Object> pkg = new java.util.LinkedHashMap<>();
    pkg.put("packageId", "PKG-KBNN-" + disb.getId() + "-" + LocalDate.now().getYear());
    pkg.put("formCode", formCode);
    pkg.put("dvcPortalStandard", "DVC-KBNN-v3.5 / TABMIS (Nghị định 11/2020/NĐ-CP)");
    pkg.put("disbursementId", disb.getId());
    pkg.put("disbursementCode", disb.getCode());
    pkg.put("disbursementName", disb.getName());
    pkg.put("voucherNo", voucherNo);
    pkg.put("treasuryRefCode", treasuryRefCode);
    pkg.put("budgetYear", disb.getBudgetYear());
    pkg.put("unitCode", StrUtil.blankToDefault(disb.getNodeCode(), "VAN_PHONG_SO"));
    pkg.put("unitName", StrUtil.blankToDefault(disb.getNodeName(), "Văn phòng Sở"));
    pkg.put("chapterCode", chapterCode);
    pkg.put("clauseCode", clauseCode);
    pkg.put("subItemCode", subItemCode);
    pkg.put("amount", amount);
    pkg.put("taxAmount", taxAmount);
    pkg.put("netPaymentAmount", netPaymentAmount);
    pkg.put(
        "beneficiaryName",
        StrUtil.blankToDefault(disb.getBeneficiaryName(), "Đơn vị thụ hưởng hợp đồng"));
    pkg.put(
        "beneficiaryAccount",
        StrUtil.blankToDefault(disb.getBeneficiaryBankAccount(), "118000123456"));
    pkg.put(
        "beneficiaryBank",
        StrUtil.blankToDefault(disb.getBeneficiaryBankName(), "VietinBank Chi nhánh Đà Nẵng"));
    pkg.put("digitalSignatureStatus", "READY_FOR_VGCA_TOKEN_SIGNING");
    pkg.put("exportedAt", LocalDate.now().toString());
    pkg.put("xmlPayloadPreview", xmlPayloadPreview);
    return pkg;
  }

  /**
   * Tự động đối soát và nghiệm thu quyết toán hồ sơ giải ngân khi nhận Webhook xác nhận thanh toán
   * thành công từ {@code tr-payment-service} (UPG-01).
   */
  public Map<String, Object> settleFromPaymentWebhook(Long id, Map<String, Object> body) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));

    String paymentTxCode =
        body != null && body.get("paymentTxCode") != null
            ? String.valueOf(body.get("paymentTxCode")).trim()
            : ("PAY-WH-" + disb.getCode());
    String treasuryRefCode =
        body != null && body.get("treasuryRefCode") != null
            ? String.valueOf(body.get("treasuryRefCode")).trim()
            : (StrUtil.isNotBlank(disb.getTreasuryRefCode())
                ? disb.getTreasuryRefCode()
                : "KBNN-AUTO-" + disb.getId());
    String voucherNo =
        body != null && body.get("voucherNo") != null
            ? String.valueOf(body.get("voucherNo")).trim()
            : (StrUtil.isNotBlank(disb.getVoucherNo())
                ? disb.getVoucherNo()
                : "UNC-WH-" + disb.getCode());

    disb.setPaymentTxCode(paymentTxCode);
    disb.setTreasuryRefCode(treasuryRefCode);
    disb.setVoucherNo(voucherNo);
    disb.setSettledAmount(disb.getAmount());
    disb.setSettledDate(LocalDateTime.now());
    if ("ADVANCE".equalsIgnoreCase(disb.getDisbursementType())) {
      disb.setDisbursementType("ADVANCE_SETTLEMENT");
    } else {
      disb.setDisbursementType("SETTLEMENT");
    }
    disb.setApprovalStatus("SETTLED");
    if (StrUtil.isBlank(disb.getApprovedByPersonName())) {
      disb.setApprovedByPersonName(
          integrationOrchestrator != null
              ? integrationOrchestrator.resolveCurrentActorName(
                  body, "actorName", "approvedByPersonName")
              : "Hệ thống Đối soát Thanh toán Tự động");
    }

    BudgetDisbursement saved = budgetDisbursementService.save(disb);
    BudgetAllocation updatedAlloc = null;
    if (saved.getAllocationId() != null) {
      updatedAlloc = workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    if (integrationOrchestrator != null) {
      integrationOrchestrator.notifyDisbursementEvent(saved, "WEBHOOK_SETTLED");
      if (updatedAlloc != null) {
        integrationOrchestrator.syncAllocationToLinkedPlan(
            updatedAlloc,
            "DISBURSEMENT_WEBHOOK_SETTLED",
            saved.getAmount(),
            saved.getCode(),
            "Đối soát tự động qua Webhook Thanh toán [" + paymentTxCode + "]");
      }
    }

    return Map.of(
        "settled", true,
        "disbursementId", saved.getId(),
        "disbursementCode", saved.getCode(),
        "paymentTxCode", paymentTxCode,
        "treasuryRefCode", treasuryRefCode,
        "voucherNo", voucherNo,
        "approvalStatus", saved.getApprovalStatus(),
        "data", enrichData(budgetDisbursementMapper.toData(saved)));
  }

}
