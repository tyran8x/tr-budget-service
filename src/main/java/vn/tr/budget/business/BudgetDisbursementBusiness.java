package vn.tr.budget.business;

import cn.hutool.core.util.StrUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.model.BudgetProcessedEvent;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetCategoryRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetDisbursementService;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetProcessedEventRepo;
import vn.tr.budget.dao.service.BudgetUnitConfigRepo;
import vn.tr.budget.data.criteria.BudgetDisbursementSearchCriteria;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.data.validator.BudgetDisbursementValidator;
import vn.tr.budget.kafka.BudgetFactMetricPublisher;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.domain.data.RefData;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.core.event.payment.PaymentCompletedEvent;
import vn.tr.common.core.exception.base.BaseException;
import vn.tr.common.core.exception.base.EntityNotFoundException;
import vn.tr.common.core.exception.base.InvalidDataException;
import vn.tr.common.feign.budget.data.BudgetPaymentSettleData;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.web.core.BaseBusinessHelper;
import vn.tr.common.web.data.dto.BulkOperationResult;
import vn.tr.common.web.data.dto.BulkSortOrderData;
import vn.tr.common.web.utils.BusinessLogicHelper;
import vn.tr.common.web.utils.CoreUtils;
import vn.tr.common.web.utils.LookupOptionsHelper;
import vn.tr.common.web.workflow.WorkflowBusinessSupport;
import vn.tr.common.web.workflow.WorkflowCallbackPayload;

/**
 * Lớp điều phối nghiệp vụ cho BudgetDisbursement.
 *
 * <p>Cưỡng chế khóa bi quan {@code findByIdForUpdate} trước khi kiểm tra vượt dự toán ở cả {@code
 * create}, {@code update} và {@code approveDisbursement}; kiểm soát 4 mắt SoD; ABAC cây đơn vị;
 * bất biến hồ sơ đã duyệt/quyết toán; đối soát Payment Webhook theo kiểu chặt {@link
 * BudgetPaymentSettleData} kết hợp idempotency qua {@code budget_processed_event}; và xuất gói DVC
 * KBNN bắt buộc cấu hình {@link BudgetUnitConfig} thực.
 *
 * @author tyran8x
 */
@Service("budgetDisbursementBusiness")
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BudgetDisbursementBusiness implements WorkflowBusinessSupport<BudgetDisbursement> {

  private static final Set<String> SETTLEABLE_WEBHOOK_STATUSES =
      Set.of("APPROVED", "APPROVED_DISBURSED", "COMMITTED");

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
  private BudgetUnitConfigRepo budgetUnitConfigRepo;

  @Autowired(required = false)
  private BudgetCategoryRepo budgetCategoryRepo;

  @Autowired(required = false)
  private BudgetIntegrationOrchestrator integrationOrchestrator;

  @Autowired(required = false)
  private BudgetDisbursementRepo budgetDisbursementRepo;

  @Autowired(required = false)
  private BudgetFactMetricPublisher factMetricPublisher;

  @Autowired(required = false)
  private BudgetStateMachine stateMachine;

  @Autowired(required = false)
  private BudgetSecurityGuardHelper securityGuard;

  @Autowired(required = false)
  private BudgetProcessedEventRepo processedEventRepo;

  public void setBudgetDisbursementRepo(BudgetDisbursementRepo budgetDisbursementRepo) {
    this.budgetDisbursementRepo = budgetDisbursementRepo;
  }

  public void setFactMetricPublisher(BudgetFactMetricPublisher factMetricPublisher) {
    this.factMetricPublisher = factMetricPublisher;
  }

  public void setBudgetUnitConfigRepo(BudgetUnitConfigRepo budgetUnitConfigRepo) {
    this.budgetUnitConfigRepo = budgetUnitConfigRepo;
  }

  public void setBudgetCategoryRepo(BudgetCategoryRepo budgetCategoryRepo) {
    this.budgetCategoryRepo = budgetCategoryRepo;
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

  public void setProcessedEventRepo(BudgetProcessedEventRepo processedEventRepo) {
    this.processedEventRepo = processedEventRepo;
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

  public JpaRepository<BudgetDisbursement, Long> getInjectedRepo() {
    return budgetDisbursementService.getRepository();
  }

  /** Tạo mới BudgetDisbursement (ép cứng DRAFT và khóa bi quan kiểm tra số dư). */
  public BudgetDisbursementData create(BudgetDisbursementData data) {
    return createInternal(data, "DRAFT", false);
  }

  private BudgetDisbursementData createInternal(
      BudgetDisbursementData data, String forcedStatus, boolean trustedS2sCommitment) {
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
    data.setApprovalStatus(forcedStatus);
    data.setApprovedByPersonId(null);
    data.setApprovedByPersonName(null);
    data.setApprovedDate(null);
    data.setSettledDate(null);
    data.setSettledAmount(BigDecimal.ZERO);
    data.setApprovedAmount(BigDecimal.ZERO);
    data.setIsOverdueWarningSent(Boolean.FALSE);

    if (data.getRequestedAmount() == null && data.getAmount() != null) {
      data.setRequestedAmount(data.getAmount());
    }
    if (data.getNetPaymentAmount() == null && data.getAmount() != null) {
      BigDecimal tax = data.getTaxAmount() != null ? data.getTaxAmount() : BigDecimal.ZERO;
      data.setNetPaymentAmount(data.getAmount().subtract(tax).setScale(2, RoundingMode.HALF_UP));
    }
    Long authPersonId = resolveAuthenticatedPersonId();
    if (authPersonId != null) {
      data.setRequestedByPersonId(authPersonId);
      data.setRequestedByPersonName(resolveAuthenticatedActorName());
    }

    syncDisbursementSnapshots(data);
    if (!trustedS2sCommitment) {
      guard().assertNodeAccess(data.getNodeId(), data.getNodeCode());
    }
    validateOverBudgetAndVoucherGatesWithLock(data, null);

    BudgetDisbursement entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetDisbursementService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> {
              BudgetDisbursement created = budgetDisbursementMapper.toEntity(data);
              guard().sanitizeDisbursementForCreate(created);
              created.setApprovalStatus(forcedStatus);
              return created;
            },
            (src, target) -> {
              sm().assertMutableDisbursement(target.getApprovalStatus(), "ghi đè", target.getCode());
              budgetDisbursementMapper.updateEntityFromData(src, target);
              guard().sanitizeDisbursementForCreate(target);
              target.setApprovalStatus(forcedStatus);
            },
            budgetDisbursementService.getRepository());
    if (entity.getAllocationId() != null && !"DRAFT".equalsIgnoreCase(entity.getApprovalStatus())) {
      workflowEngineHelper.recalculateAllocationMetrics(entity.getAllocationId());
    }
    return enrichData(budgetDisbursementMapper.toData(entity));
  }

  /** Cập nhật BudgetDisbursement (chặn sửa chứng từ đã duyệt/quyết toán, khóa bi quan số dư). */
  public BudgetDisbursementData update(Long id, BudgetDisbursementData data) {
    BudgetDisbursement existing =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    guard().assertNodeAccess(existing.getNodeId(), existing.getNodeCode());
    sm().assertMutableDisbursement(existing.getApprovalStatus(), "cập nhật", existing.getCode());

    Long oldAllocId = existing.getAllocationId();
    data.setId(id);
    validateData(data);
    syncDisbursementSnapshots(data);
    guard().assertNodeAccess(data.getNodeId(), data.getNodeCode());

    workflowEngineHelper.lockAllocationsInDeterministicOrder(oldAllocId, data.getAllocationId());
    validateOverBudgetAndVoucherGatesWithLock(data, existing);

    BudgetDisbursement snapshot = existing.toBuilder().build();
    budgetDisbursementMapper.updateEntityFromData(data, existing);
    guard().sanitizeDisbursementForUpdate(existing, snapshot);

    BudgetDisbursement saved = budgetDisbursementService.save(existing);
    if (oldAllocId != null) {
      workflowEngineHelper.recalculateAllocationMetrics(oldAllocId);
    }
    if (saved.getAllocationId() != null && !saved.getAllocationId().equals(oldAllocId)) {
      workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    return enrichData(budgetDisbursementMapper.toData(saved));
  }

  /** Xoá mềm BudgetDisbursement (chặn xóa hồ sơ đã duyệt/quyết toán). */
  public void delete(Long id) {
    BudgetDisbursement existing =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    guard().assertNodeAccess(existing.getNodeId(), existing.getNodeCode());
    sm().assertMutableDisbursement(existing.getApprovalStatus(), "xóa", existing.getCode());

    Long allocId = existing.getAllocationId();
    budgetDisbursementService.delete(id);
    if (allocId != null) {
      workflowEngineHelper.recalculateAllocationMetrics(allocId);
    }
  }

  /** Xoá mềm hàng loạt BudgetDisbursement (kiểm tra ABAC & khóa trạng thái). */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    List<BudgetDisbursement> toDelete =
        ids != null && !ids.isEmpty() ? budgetDisbursementService.findAllByIds(ids) : List.of();
    for (BudgetDisbursement disb : toDelete) {
      guard().assertNodeAccess(disb.getNodeId(), disb.getNodeCode());
    }
    BulkOperationResult<Long> result =
        businessLogicHelper.executeBulkDelete(
            ids,
            budgetDisbursementService::findAllByIds,
            BudgetDisbursement::getId,
            disb -> guard().assertNodeAccess(disb.getNodeId(), disb.getNodeCode()),
            disb -> sm().assertMutableDisbursement(disb.getApprovalStatus(), "xóa", disb.getCode()),
            budgetDisbursementService::deleteByIds,
            "budget.budgetDisbursement.not.found");
    for (BudgetDisbursement disb : toDelete) {
      if (disb.getAllocationId() != null && !sm().isLockedDisbursement(disb.getApprovalStatus())) {
        workflowEngineHelper.recalculateAllocationMetrics(disb.getAllocationId());
      }
    }
    return result;
  }

  /** Lấy chi tiết BudgetDisbursement theo ID (kiểm tra ABAC). */
  @Transactional(readOnly = true)
  public BudgetDisbursementData findById(Long id) {
    BudgetDisbursement entity =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    guard().assertNodeAccess(entity.getNodeId(), entity.getNodeCode());
    return enrichData(budgetDisbursementMapper.toData(entity));
  }

  /** Tìm kiếm BudgetDisbursement phân trang. */
  @Transactional(readOnly = true)
  public PagedData<BudgetDisbursementData> findAll(BudgetDisbursementSearchCriteria criteria) {
    Pageable pageable = CoreUtils.getPageRequest(criteria);
    Page<BudgetDisbursement> page = budgetDisbursementService.findAll(criteria, pageable);
    return PagedData.from(page, entity -> enrichData(budgetDisbursementMapper.toData(entity)));
  }

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
    guard().assertNodeAccess(source.getNodeId(), source.getNodeCode());
    BudgetDisbursementData clonedData = budgetDisbursementMapper.toData(source);
    clonedData.setId(null);
    clonedData.setCode(source.getCode() + "_COPY_" + System.currentTimeMillis() % 10000);
    clonedData.setName(source.getName() + " (Bản sao)");
    return create(clonedData);
  }

  /** Khôi phục phiên bản lịch sử JaVers (chặn rollback hồ sơ đã duyệt/quyết toán & tính lại số dư). */
  @Transactional
  public BudgetDisbursementData rollback(Long id, Long version) {
    BudgetDisbursement existing =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    guard().assertNodeAccess(existing.getNodeId(), existing.getNodeCode());
    sm().assertMutableDisbursement(
        existing.getApprovalStatus(), "khôi phục phiên bản", existing.getCode());

    BudgetDisbursementData rolledBack =
        auditRollbackHelper.executeRollback(
            BudgetDisbursement.class,
            id,
            version,
            budgetDisbursementService::findById,
            budgetDisbursementService::save,
            budgetDisbursementMapper::toData,
            budgetDisbursementMapper::updateEntityFromData,
            BudgetDisbursementData::setId,
            this::validateData);
    if (existing.getAllocationId() != null) {
      workflowEngineHelper.recalculateAllocationMetrics(existing.getAllocationId());
    }
    return rolledBack;
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
                guard().assertNodeAccess(entity.getNodeId(), entity.getNodeCode());
                entity.setSortOrder(item.getSortOrder());
                budgetDisbursementService.save(entity);
              });
    }
  }

  private void validateData(BudgetDisbursementData data) {
    BaseBusinessHelper.validateOrThrow(budgetDisbursementValidator, data, "budgetDisbursementData");
  }

  /**
   * Khóa bi quan {@code findByIdForUpdate} đối với {@link BudgetAllocation} TRƯỚC khi kiểm tra
   * vượt dự toán khả dụng nhằm chặn triệt để TOCTOU double-spend (TD-C05).
   */
  private void validateOverBudgetAndVoucherGatesWithLock(
      BudgetDisbursementData data, BudgetDisbursement existing) {
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
    assertSufficientAllocationBalanceLocked(
        data.getAllocationId(), data.getAmount(), dtype, existing);
  }

  private void assertSufficientAllocationBalanceLocked(
      Long allocationId,
      BigDecimal requestedAmount,
      String disbursementType,
      BudgetDisbursement existing) {
    if (allocationId == null
        || requestedAmount == null
        || "SETTLEMENT".equalsIgnoreCase(disbursementType)
        || "ADVANCE_SETTLEMENT".equalsIgnoreCase(disbursementType)) {
      return;
    }
    BudgetAllocation alloc = workflowEngineHelper.lockAllocation(allocationId).orElse(null);
    if (alloc == null) {
      return;
    }
    BigDecimal effectiveAvailable =
        alloc.getAvailableAmount() != null
            ? alloc.getAvailableAmount()
            : workflowEngineHelper.safe(alloc.getEffectiveAmount());
    if (existing != null
        && existing.getAllocationId() != null
        && existing.getAllocationId().equals(allocationId)
        && existing.getAmount() != null
        && sm().isLockedDisbursement(existing.getApprovalStatus())) {
      effectiveAvailable = effectiveAvailable.add(existing.getAmount());
    }
    if (requestedAmount.compareTo(effectiveAvailable) > 0) {
      throw new BaseException(
          "Số tiền đề nghị giải ngân ("
              + requestedAmount.toPlainString()
              + " VNĐ) vượt quá dự toán khả dụng còn lại ("
              + effectiveAvailable.toPlainString()
              + " VNĐ) của phân bổ ["
              + alloc.getCode()
              + "].");
    }
  }

  /**
   * Phê duyệt thực chi / giải ngân Kho bạc Nhà nước.
   *
   * <p>Khóa bi quan {@link BudgetAllocation} và đối chiếu lại số dư khả dụng TRƯỚC khi chuyển sang
   * {@code APPROVED_DISBURSED}; cưỡng chế kiểm soát 4 mắt SoD; đẩy lời gọi Payment Feign ra sau
   * commit để không giữ khóa dòng DB.
   */
  public BudgetDisbursementData approveDisbursement(Long id, Map<String, Object> body) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    guard().assertNodeAccess(disb.getNodeId(), disb.getNodeCode());
    sm().assertDisbursementTransition(disb.getApprovalStatus(), "APPROVED_DISBURSED", disb.getCode());
    guard()
        .assertFourEyesApproval(
            "hồ sơ giải ngân",
            disb.getCode(),
            disb.getRequestedByPersonId(),
            disb.getCreatedBy(),
            null);

    // Khóa bi quan & kiểm tra lại số dư khả dụng trước khi giải ngân (chống TOCTOU double-spend)
    assertSufficientAllocationBalanceLocked(
        disb.getAllocationId(), disb.getAmount(), disb.getDisbursementType(), null);

    if (body != null && body.get("treasuryRefCode") != null) {
      disb.setTreasuryRefCode(String.valueOf(body.get("treasuryRefCode")));
    }
    if (disb.getApprovedAmount() == null
        || disb.getApprovedAmount().compareTo(BigDecimal.ZERO) <= 0) {
      disb.setApprovedAmount(disb.getAmount());
    }
    disb.setApprovedDate(LocalDateTime.now());
    disb.setApprovalStatus("APPROVED_DISBURSED");

    Long actorId = resolveAuthenticatedPersonId();
    if (actorId != null) {
      disb.setApprovedByPersonId(actorId);
    }
    disb.setApprovedByPersonName(resolveAuthenticatedActorName());

    BudgetDisbursement saved = budgetDisbursementService.save(disb);
    BudgetAllocation updatedAlloc = null;
    if (saved.getAllocationId() != null) {
      updatedAlloc = workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    if (integrationOrchestrator != null) {
      if (StrUtil.isBlank(saved.getPaymentTxCode())) {
        BudgetDisbursement finalSaved = saved;
        integrationOrchestrator.runAfterCommitOrNow(
            () -> {
              String payCode =
                  integrationOrchestrator.syncDisbursementWithPaymentService(finalSaved);
              if (StrUtil.isNotBlank(payCode)) {
                finalSaved.setPaymentTxCode(payCode);
                budgetDisbursementService.save(finalSaved);
              }
            });
      }
      integrationOrchestrator.executeWorkflowAction(
          "BudgetDisbursement", saved.getId(), "APPROVE", "");
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
    if (factMetricPublisher != null) {
      factMetricPublisher.publishDisbursementSnapshot(saved, updatedAlloc, "DISBURSEMENT_APPROVED");
    }
    return enrichData(budgetDisbursementMapper.toData(saved));
  }

  /** Nghiệm thu quyết toán / hoàn ứng chứng từ Kho bạc. */
  public BudgetDisbursementData settleDisbursement(Long id, Map<String, Object> body) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    guard().assertNodeAccess(disb.getNodeId(), disb.getNodeCode());
    sm().assertDisbursementTransition(disb.getApprovalStatus(), "SETTLED", disb.getCode());

    if (body != null && body.get("voucherNo") != null) {
      disb.setVoucherNo(String.valueOf(body.get("voucherNo")));
    }
    if (workflowEngineHelper.isVoucherRequired(disb.getEstimateItemId())
        && StrUtil.isBlank(disb.getVoucherNo())) {
      throw new BaseException(
          "Bắt buộc bổ sung Số chứng từ / Hóa đơn (voucherNo) trước khi nghiệm thu quyết toán.");
    }
    BigDecimal settledAmt = parseOptionalAmount(body, "settledAmount", disb.getAmount());
    if (settledAmt == null || settledAmt.compareTo(BigDecimal.ZERO) <= 0) {
      throw new InvalidDataException("Số tiền quyết toán phải lớn hơn 0.", null);
    }
    if (disb.getAmount() != null && settledAmt.compareTo(disb.getAmount()) > 0) {
      throw new InvalidDataException(
          "Số tiền nghiệm thu quyết toán không được vượt quá số tiền đã giải ngân.", null);
    }
    disb.setSettledAmount(settledAmt.setScale(2, RoundingMode.HALF_UP));
    disb.setSettledDate(LocalDateTime.now());
    if (!"ADVANCE".equalsIgnoreCase(disb.getDisbursementType())) {
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
            saved.getSettledAmount(),
            saved.getCode(),
            "Quyết toán chứng từ Kho bạc [" + saved.getVoucherNo() + "]");
      }
    }
    if (factMetricPublisher != null) {
      factMetricPublisher.publishDisbursementSnapshot(saved, updatedAlloc, "DISBURSEMENT_SETTLED");
    }
    return enrichData(budgetDisbursementMapper.toData(saved));
  }

  /** Khởi tạo nhanh hồ sơ Cam kết chi (COMMITMENT) từ Nhiệm vụ Kế hoạch hoặc Công việc (S2S). */
  public Map<String, Object> reserveCommitment(Map<String, Object> body) {
    String planCode =
        body != null && body.get("planCode") != null ? String.valueOf(body.get("planCode")) : "";
    Long allocationId = parseOptionalLong(body, "allocationId");
    BudgetAllocation alloc = null;
    if (allocationId != null) {
      alloc = workflowEngineHelper.lockAllocation(allocationId).orElse(null);
    } else if (StrUtil.isNotBlank(planCode)) {
      List<BudgetAllocation> list =
          budgetAllocationRepo.findByLinkedPlanCodeIgnoreCaseOrderByIdAsc(planCode);
      if (!list.isEmpty()) {
        alloc = workflowEngineHelper.lockAllocation(list.get(0).getId()).orElse(list.get(0));
      }
    }
    BigDecimal amount = parseOptionalAmount(body, "amount", BigDecimal.ZERO);
    String code =
        body != null && body.get("code") != null
            ? String.valueOf(body.get("code"))
            : ("CKC-"
                + (StrUtil.isNotBlank(planCode) ? planCode : "AUTO")
                + "-"
                + System.currentTimeMillis() % 10000);
    String name =
        body != null && body.get("name") != null
            ? String.valueOf(body.get("name"))
            : ("Cam kết chi thực hiện nhiệm vụ kế hoạch " + planCode);

    Long resolvedNodeId = alloc != null ? alloc.getNodeId() : parseOptionalLong(body, "nodeId");
    if (resolvedNodeId == null) {
      throw new BaseException("Thiếu thông tin Đơn vị dự toán (nodeId) để lập cam kết chi.");
    }
    String resolvedNodeCode =
        alloc != null
            ? alloc.getNodeCode()
            : (body != null && body.get("nodeCode") != null
                ? String.valueOf(body.get("nodeCode"))
                : null);
    String resolvedNodeName =
        alloc != null
            ? alloc.getNodeName()
            : (body != null && body.get("nodeName") != null
                ? String.valueOf(body.get("nodeName"))
                : null);

    BudgetDisbursementData data =
        BudgetDisbursementData.builder()
            .code(code)
            .name(name)
            .allocationId(alloc != null ? alloc.getId() : null)
            .allocationCode(alloc != null ? alloc.getCode() : null)
            .allocationName(alloc != null ? alloc.getName() : null)
            .nodeId(resolvedNodeId)
            .nodeCode(resolvedNodeCode)
            .nodeName(resolvedNodeName)
            .budgetYear(alloc != null ? alloc.getBudgetYear() : LocalDate.now().getYear())
            .disbursementType("COMMITMENT")
            .amount(amount)
            .requestedAmount(amount)
            .netPaymentAmount(amount)
            .linkedPlanCode(planCode)
            .approvalStatus("COMMITTED")
            .status(LifecycleStatus.ACTIVE)
            .build();
    BudgetDisbursementData saved = createInternal(data, "COMMITTED", true);
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
    guard().assertNodeAccess(disb.getNodeId(), disb.getNodeCode());
    sm().assertDisbursementTransition(disb.getApprovalStatus(), "REJECTED", disb.getCode());

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
    if (integrationOrchestrator != null) {
      integrationOrchestrator.executeWorkflowAction(
          "BudgetDisbursement", saved.getId(), "REJECT", StrUtil.nullToEmpty(saved.getRejectReason()));
    }
    return enrichData(budgetDisbursementMapper.toData(saved));
  }

  /** Trả về chỉnh sửa (REWORK) hồ sơ giải ngân từ luồng BPMN Workflow. */
  public BudgetDisbursementData reworkDisbursement(Long id, String comment) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    guard().assertNodeAccess(disb.getNodeId(), disb.getNodeCode());
    sm().assertDisbursementTransition(disb.getApprovalStatus(), "REWORK", disb.getCode());

    disb.setApprovalStatus("REWORK");
    if (StrUtil.isNotBlank(comment)) {
      disb.setRejectReason(comment);
    }
    BudgetDisbursement saved = budgetDisbursementService.save(disb);
    return enrichData(budgetDisbursementMapper.toData(saved));
  }

  /**
   * Kết xuất Gói Hồ sơ Giao dịch Điện tử DVC KBNN (POST — phát sinh và lưu số hồ sơ {@code
   * dvcKbnnDossierNo}, bắt buộc cấu hình {@link BudgetUnitConfig} thực, cấm hardcode tài khoản giả).
   */
  @Transactional
  public Map<String, Object> exportKbnnDvcPackage(Long id) {
    return buildKbnnDvcPackage(id, true);
  }

  /**
   * Xem trước Gói Hồ sơ DVC KBNN chỉ đọc (an toàn cho HTTP GET — không mutate DB, không tự tạo
   * tài khoản KBNN giả).
   */
  @Transactional(readOnly = true)
  public Map<String, Object> previewKbnnDvcPackage(Long id) {
    return buildKbnnDvcPackage(id, false);
  }

  private Map<String, Object> buildKbnnDvcPackage(Long id, boolean persistDossierNo) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    guard().assertNodeAccess(disb.getNodeId(), disb.getNodeCode());

    BudgetAllocation alloc =
        disb.getAllocationId() != null
            ? budgetAllocationRepo.findById(disb.getAllocationId()).orElse(null)
            : null;

    Long targetNodeId =
        disb.getNodeId() != null ? disb.getNodeId() : (alloc != null ? alloc.getNodeId() : null);
    String targetNodeCode =
        StrUtil.isNotBlank(disb.getNodeCode())
            ? disb.getNodeCode()
            : (alloc != null ? alloc.getNodeCode() : null);

    BudgetUnitConfig unitCfg = null;
    if (budgetUnitConfigRepo != null) {
      if (targetNodeId != null) {
        unitCfg = budgetUnitConfigRepo.findFirstByNodeIdOrderByIdDesc(targetNodeId).orElse(null);
      }
      if (unitCfg == null && StrUtil.isNotBlank(targetNodeCode)) {
        unitCfg =
            budgetUnitConfigRepo
                .findFirstByNodeCodeIgnoreCaseOrderByIdDesc(targetNodeCode.trim())
                .orElse(null);
      }
    }

    // Bắt buộc có cấu hình BudgetUnitConfig thực với số tài khoản KBNN hợp lệ (TD-M02)
    if (unitCfg == null || StrUtil.isBlank(unitCfg.getTreasuryAccountNo())) {
      throw new BaseException(
          "Đơn vị dự toán ["
              + StrUtil.blankToDefault(targetNodeCode, String.valueOf(targetNodeId))
              + "] chưa được cấu hình Tài khoản Kho bạc Nhà nước (treasuryAccountNo) trong BudgetUnitConfig. Không thể kết xuất gói DVC KBNN.");
    }

    String treasuryAccountNo = unitCfg.getTreasuryAccountNo().trim();
    String beneficiaryBankAccount =
        StrUtil.isNotBlank(disb.getBeneficiaryBankAccount())
            ? disb.getBeneficiaryBankAccount().trim()
            : (StrUtil.isNotBlank(unitCfg.getBankAccountNo())
                ? unitCfg.getBankAccountNo().trim()
                : null);
    String beneficiaryBankName =
        StrUtil.isNotBlank(disb.getBeneficiaryBankName())
            ? disb.getBeneficiaryBankName().trim()
            : (StrUtil.isNotBlank(unitCfg.getBankName()) ? unitCfg.getBankName().trim() : null);

    if (StrUtil.isBlank(beneficiaryBankAccount) || StrUtil.isBlank(beneficiaryBankName)) {
      throw new BaseException(
          "Hồ sơ giải ngân ["
              + disb.getCode()
              + "] thiếu thông tin Tài khoản hoặc Ngân hàng đơn vị thụ hưởng (beneficiaryBankAccount / beneficiaryBankName). Không thể kết xuất gói DVC KBNN.");
    }

    Long targetCatId =
        disb.getCategoryId() != null
            ? disb.getCategoryId()
            : (alloc != null ? alloc.getCategoryId() : null);
    String targetCatCode =
        StrUtil.isNotBlank(disb.getCategoryCode())
            ? disb.getCategoryCode()
            : (alloc != null ? alloc.getCategoryCode() : null);

    BudgetCategory catCfg = null;
    if (budgetCategoryRepo != null) {
      if (targetCatId != null) {
        catCfg = budgetCategoryRepo.findById(targetCatId).orElse(null);
      }
      if (catCfg == null && StrUtil.isNotBlank(targetCatCode)) {
        List<BudgetCategory> cats =
            budgetCategoryRepo.findAllByCodeIgnoreCaseIncludingDeletedSorted(targetCatCode.trim());
        if (cats != null && !cats.isEmpty()) {
          catCfg = cats.getFirst();
        }
      }
    }

    String formCode =
        "ADVANCE".equalsIgnoreCase(disb.getDisbursementType())
            ? "C2-03/NS (Giấy đề nghị thanh toán tạm ứng)"
            : "C2-02a/NS (Giấy rút dự toán ngân sách nhà nước)";
    String chapterCode =
        StrUtil.isNotBlank(unitCfg.getDefaultChapterCode())
            ? unitCfg.getDefaultChapterCode()
            : (catCfg != null && StrUtil.isNotBlank(catCfg.getChapterCode())
                ? catCfg.getChapterCode()
                : "");
    String clauseCode =
        catCfg != null && StrUtil.isNotBlank(catCfg.getClauseCode())
            ? catCfg.getClauseCode()
            : StrUtil.nullToEmpty(targetCatCode);
    String subItemCode =
        StrUtil.isNotBlank(disb.getSubItemCode())
            ? disb.getSubItemCode()
            : (catCfg != null && StrUtil.isNotBlank(catCfg.getSubItemCode())
                ? catCfg.getSubItemCode()
                : "");
    String voucherNo = StrUtil.blankToDefault(disb.getVoucherNo(), "C2-02A-" + disb.getCode());
    String treasuryRefCode =
        StrUtil.blankToDefault(disb.getTreasuryRefCode(), "DVC-KBNN-" + disb.getId());

    BigDecimal amount = scaleMoney(disb.getAmount());
    BigDecimal taxAmount = scaleMoney(disb.getTaxAmount());
    BigDecimal netPaymentAmount =
        scaleMoney(
            disb.getNetPaymentAmount() != null
                ? disb.getNetPaymentAmount()
                : amount.subtract(taxAmount));

    String dossierNo =
        StrUtil.isNotBlank(disb.getDvcKbnnDossierNo())
            ? disb.getDvcKbnnDossierNo()
            : ("DVC-"
                + (disb.getBudgetYear() != null ? disb.getBudgetYear() : LocalDate.now().getYear())
                + "-"
                + disb.getId());
    if (persistDossierNo && StrUtil.isBlank(disb.getDvcKbnnDossierNo())) {
      disb.setDvcKbnnDossierNo(dossierNo);
      budgetDisbursementService.save(disb);
    }

    String unitCodeSafe =
        StrUtil.blankToDefault(disb.getNodeCode(), StrUtil.nullToEmpty(unitCfg.getNodeCode()));
    String unitNameSafe =
        StrUtil.blankToDefault(
            disb.getNodeName(), StrUtil.blankToDefault(unitCfg.getNodeName(), "Đơn vị dự toán"));
    String beneficiarySafe =
        StrUtil.blankToDefault(disb.getBeneficiaryName(), "Đơn vị thụ hưởng hợp đồng");

    String xmlPayloadPreview =
        "<DVC_KBNN_PACKAGE version=\"3.5\" standard=\"ND11-2020-ND-CP\" dossierNo=\""
            + escapeXml(dossierNo)
            + "\">"
            + "<HEADER><FORM_CODE>"
            + escapeXml(formCode)
            + "</FORM_CODE><TREASURY_REF>"
            + escapeXml(treasuryRefCode)
            + "</TREASURY_REF><VOUCHER_NO>"
            + escapeXml(voucherNo)
            + "</VOUCHER_NO></HEADER>"
            + "<BUDGET_UNIT><CODE>"
            + escapeXml(unitCodeSafe)
            + "</CODE><NAME>"
            + escapeXml(unitNameSafe)
            + "</NAME><TREASURY_ACCOUNT>"
            + escapeXml(treasuryAccountNo)
            + "</TREASURY_ACCOUNT><CHAPTER>"
            + escapeXml(chapterCode)
            + "</CHAPTER><CLAUSE>"
            + escapeXml(clauseCode)
            + "</CLAUSE><SUB_ITEM>"
            + escapeXml(subItemCode)
            + "</SUB_ITEM></BUDGET_UNIT>"
            + "<PAYMENT><GROSS_AMOUNT>"
            + amount.toPlainString()
            + "</GROSS_AMOUNT><TAX_WITHHOLDING>"
            + taxAmount.toPlainString()
            + "</TAX_WITHHOLDING><NET_AMOUNT>"
            + netPaymentAmount.toPlainString()
            + "</NET_AMOUNT><BENEFICIARY>"
            + escapeXml(beneficiarySafe)
            + "</BENEFICIARY><BANK_ACCOUNT>"
            + escapeXml(beneficiaryBankAccount)
            + "</BANK_ACCOUNT><BANK_NAME>"
            + escapeXml(beneficiaryBankName)
            + "</BANK_NAME></PAYMENT></DVC_KBNN_PACKAGE>";

    Map<String, Object> pkg = new LinkedHashMap<>();
    pkg.put("packageId", "PKG-KBNN-" + disb.getId() + "-" + LocalDate.now().getYear());
    pkg.put("dvcKbnnDossierNo", dossierNo);
    pkg.put("persisted", persistDossierNo);
    pkg.put("formCode", formCode);
    pkg.put("dvcPortalStandard", "DVC-KBNN-v3.5 / TABMIS (Nghị định 11/2020/NĐ-CP)");
    pkg.put("disbursementId", disb.getId());
    pkg.put("disbursementCode", disb.getCode());
    pkg.put("disbursementName", disb.getName());
    pkg.put("voucherNo", voucherNo);
    pkg.put("treasuryRefCode", treasuryRefCode);
    pkg.put("treasuryAccountNo", treasuryAccountNo);
    pkg.put("budgetYear", disb.getBudgetYear());
    pkg.put("unitCode", unitCodeSafe);
    pkg.put("unitName", unitNameSafe);
    pkg.put("chapterCode", chapterCode);
    pkg.put("clauseCode", clauseCode);
    pkg.put("subItemCode", subItemCode);
    pkg.put("amount", amount);
    pkg.put("taxAmount", taxAmount);
    pkg.put("netPaymentAmount", netPaymentAmount);
    pkg.put("beneficiaryName", beneficiarySafe);
    pkg.put("beneficiaryAccount", beneficiaryBankAccount);
    pkg.put("beneficiaryBank", beneficiaryBankName);
    pkg.put("digitalSignatureStatus", "READY_FOR_VGCA_TOKEN_SIGNING");
    pkg.put("exportedAt", LocalDate.now().toString());
    pkg.put("xmlPayloadPreview", xmlPayloadPreview);
    return pkg;
  }

  private String escapeXml(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  /**
   * Đối soát và nghiệm thu tự động hồ sơ giải ngân khi nhận sự kiện {@link PaymentCompletedEvent}
   * từ Apache Kafka ({@code tr-payment-service}). Hỗ trợ đa tenant động và chống xử lý lặp bền
   * vững qua bảng {@code budget_processed_event}.
   */
  public Map<String, Object> settleFromPaymentEvent(PaymentCompletedEvent event) {
    if (event == null) {
      return Map.of("settled", false, "reason", "EMPTY_EVENT");
    }
    // Lọc động theo sourceModule (không khóa cứng appCode="budget" để hỗ trợ đa tenant ict-dng/sct/sgtvt)
    if (StrUtil.isNotBlank(event.getSourceModule())
        && !StrUtil.containsIgnoreCase(event.getSourceModule(), "BUDGET")) {
      return Map.of(
          "settled", false,
          "ignored", true,
          "sourceModule", event.getSourceModule());
    }

    String eventKey =
        "KAFKA_PAY:"
            + StrUtil.blankToDefault(
                event.getTransactionCode(),
                StrUtil.blankToDefault(
                    event.getBankReferenceNo(), StrUtil.nullToEmpty(event.getReferenceId())));
    if (processedEventRepo != null && processedEventRepo.existsByEventKey(eventKey)) {
      return Map.of(
          "settled", true,
          "idempotent", true,
          "eventKey", eventKey);
    }

    BudgetDisbursementRepo repo = resolveDisbursementRepo();
    BudgetDisbursement disb = null;
    if (StrUtil.isNotBlank(event.getReferenceId())) {
      try {
        disb =
            budgetDisbursementService
                .findById(Long.parseLong(event.getReferenceId().trim()))
                .orElse(null);
      } catch (NumberFormatException ignored) {
        // Fallback sang các khóa tham chiếu khác
      }
    }
    if (disb == null
        && event.getMetadata() != null
        && event.getMetadata().get("disbursementId") != null) {
      try {
        disb =
            budgetDisbursementService
                .findById(
                    Long.parseLong(String.valueOf(event.getMetadata().get("disbursementId")).trim()))
                .orElse(null);
      } catch (NumberFormatException ignored) {
        // Bỏ qua
      }
    }
    if (disb == null && repo != null && StrUtil.isNotBlank(event.getTransactionCode())) {
      disb = repo.findFirstByPaymentTxCodeIgnoreCase(event.getTransactionCode().trim()).orElse(null);
    }
    if (disb == null && repo != null && StrUtil.isNotBlank(event.getReferenceCode())) {
      List<BudgetDisbursement> list =
          repo.findAllByCodeIgnoreCaseIncludingDeletedSorted(event.getReferenceCode().trim());
      for (BudgetDisbursement cand : list) {
        if (cand.getDeletedAt() == null) {
          disb = cand;
          break;
        }
      }
    }
    if (disb == null) {
      return Map.of(
          "settled", false,
          "ignored", true,
          "transactionCode", StrUtil.nullToEmpty(event.getTransactionCode()));
    }
    if ("SETTLED".equalsIgnoreCase(disb.getApprovalStatus())) {
      recordProcessedEvent(eventKey, "KAFKA_PAYMENT_SETTLE", disb.getAppCode(), disb.getId());
      return Map.of(
          "settled", true,
          "idempotent", true,
          "disbursementId", disb.getId(),
          "disbursementCode", StrUtil.nullToEmpty(disb.getCode()),
          "approvalStatus", "SETTLED");
    }

    String currentStatus = sm().normalize(disb.getApprovalStatus());
    if (!SETTLEABLE_WEBHOOK_STATUSES.contains(currentStatus)) {
      throw new BaseException(
          "Hồ sơ giải ngân ["
              + disb.getCode()
              + "] ở trạng thái ["
              + currentStatus
              + "] không hợp lệ để tự động quyết toán qua Kafka.");
    }
    if (event.getPaidAmount() != null) {
      verifyPaidAmountMatchesDisbursement(disb, event.getPaidAmount());
    }

    String paymentTxCode =
        StrUtil.blankToDefault(
            event.getTransactionCode(),
            StrUtil.blankToDefault(disb.getPaymentTxCode(), "PAY-" + disb.getCode()));
    String treasuryRefCode =
        StrUtil.blankToDefault(
            event.getBankReferenceNo(),
            StrUtil.blankToDefault(disb.getTreasuryRefCode(), "KBNN-KFK-" + disb.getId()));
    String voucherNo =
        StrUtil.blankToDefault(
            event.getReceiptNo(),
            StrUtil.blankToDefault(disb.getVoucherNo(), "UNC-KFK-" + disb.getCode()));

    disb.setPaymentTxCode(paymentTxCode);
    disb.setTreasuryRefCode(treasuryRefCode);
    disb.setVoucherNo(voucherNo);
    disb.setSettledAmount(
        scaleMoney(
            event.getPaidAmount() != null ? event.getPaidAmount() : disb.getAmount()));
    disb.setSettledDate(event.getPaidAt() != null ? event.getPaidAt() : LocalDateTime.now());
    if (!"ADVANCE".equalsIgnoreCase(disb.getDisbursementType())) {
      disb.setDisbursementType("SETTLEMENT");
    }
    disb.setApprovalStatus("SETTLED");

    BudgetDisbursement saved = budgetDisbursementService.save(disb);
    recordProcessedEvent(eventKey, "KAFKA_PAYMENT_SETTLE", saved.getAppCode(), saved.getId());

    BudgetAllocation updatedAlloc = null;
    if (saved.getAllocationId() != null) {
      updatedAlloc = workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    if (integrationOrchestrator != null) {
      integrationOrchestrator.notifyDisbursementEvent(saved, "SETTLED");
      if (updatedAlloc != null) {
        integrationOrchestrator.syncAllocationToLinkedPlan(
            updatedAlloc,
            "DISBURSEMENT_KAFKA_SETTLED",
            saved.getSettledAmount(),
            saved.getCode(),
            "Đối soát tự động qua Kafka payment-events [" + paymentTxCode + "]");
      }
    }
    if (factMetricPublisher != null) {
      factMetricPublisher.publishDisbursementSnapshot(
          saved, updatedAlloc, "DISBURSEMENT_KAFKA_SETTLED");
    }
    return Map.of(
        "settled", true,
        "idempotent", false,
        "disbursementId", saved.getId(),
        "disbursementCode", StrUtil.nullToEmpty(saved.getCode()),
        "paymentTxCode", paymentTxCode,
        "treasuryRefCode", treasuryRefCode,
        "voucherNo", voucherNo,
        "approvalStatus", saved.getApprovalStatus());
  }

  private BudgetDisbursementRepo resolveDisbursementRepo() {
    if (budgetDisbursementRepo != null) {
      return budgetDisbursementRepo;
    }
    if (budgetDisbursementService.getRepository() instanceof BudgetDisbursementRepo repo) {
      return repo;
    }
    return null;
  }

  /**
   * Tự động đối soát và nghiệm thu quyết toán hồ sơ giải ngân từ Webhook Thanh toán (S2S) theo
   * hợp đồng kiểu chặt {@link BudgetPaymentSettleData} (TD-C11):
   *
   * <ol>
   *   <li>Bắt buộc {@code paymentStatus == PAID}.
   *   <li>Đối khớp chính xác {@code paidAmount} với số tiền thanh toán ({@code netPaymentAmount}
   *       hoặc {@code amount}).
   *   <li>Chống xử lý lặp bền vững qua bảng {@code budget_processed_event} và trạng thái {@code
   *       SETTLED}.
   *   <li>Không cho phép quyết toán hồ sơ ở trạng thái {@code DRAFT}/{@code REJECTED}/{@code
   *       CANCELLED}.
   * </ol>
   */
  public Map<String, Object> settleFromPaymentWebhook(Long id, BudgetPaymentSettleData request) {
    if (request == null) {
      throw new InvalidDataException(
          "Payload xác nhận thanh toán Webhook không được để trống.", null);
    }
    String paymentStatus = StrUtil.nullToEmpty(request.getPaymentStatus()).trim().toUpperCase();
    if (!"PAID".equals(paymentStatus)) {
      throw new InvalidDataException(
          "Từ chối quyết toán Webhook vì trạng thái thanh toán không hợp lệ: ["
              + paymentStatus
              + "] (bắt buộc PAID).",
          null);
    }
    if (StrUtil.isBlank(request.getTransactionCode())) {
      throw new InvalidDataException(
          "Thiếu mã giao dịch thanh toán (transactionCode) từ Webhook.", null);
    }
    if (request.getPaidAmount() == null || request.getPaidAmount().compareTo(BigDecimal.ZERO) <= 0) {
      throw new InvalidDataException(
          "Số tiền đã thanh toán (paidAmount) từ Webhook phải lớn hơn 0.", null);
    }

    String eventKey = "WEBHOOK_SETTLE:" + id + ":" + request.getTransactionCode().trim().toUpperCase();
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));

    if ((processedEventRepo != null && processedEventRepo.existsByEventKey(eventKey))
        || "SETTLED".equalsIgnoreCase(disb.getApprovalStatus())) {
      return Map.of(
          "settled", true,
          "idempotent", true,
          "disbursementId", disb.getId(),
          "disbursementCode", StrUtil.nullToEmpty(disb.getCode()),
          "paymentTxCode", StrUtil.nullToEmpty(disb.getPaymentTxCode()),
          "approvalStatus", StrUtil.nullToEmpty(disb.getApprovalStatus()),
          "data", enrichData(budgetDisbursementMapper.toData(disb)));
    }

    String currentStatus = sm().normalize(disb.getApprovalStatus());
    if (!SETTLEABLE_WEBHOOK_STATUSES.contains(currentStatus)) {
      throw new BaseException(
          "Hồ sơ giải ngân ["
              + disb.getCode()
              + "] đang ở trạng thái ["
              + currentStatus
              + "] chưa được duyệt giải ngân, không thể quyết toán qua Webhook.");
    }

    verifyPaidAmountMatchesDisbursement(disb, request.getPaidAmount());

    String paymentTxCode = request.getTransactionCode().trim();
    String treasuryRefCode =
        StrUtil.isNotBlank(disb.getTreasuryRefCode())
            ? disb.getTreasuryRefCode()
            : "KBNN-WH-" + disb.getId();
    String voucherNo =
        StrUtil.isNotBlank(disb.getVoucherNo())
            ? disb.getVoucherNo()
            : "UNC-WH-" + disb.getCode();

    disb.setPaymentTxCode(paymentTxCode);
    if (StrUtil.isNotBlank(request.getPaymentOrderId())) {
      try {
        disb.setPayTransactionId(Long.valueOf(request.getPaymentOrderId().trim()));
      } catch (NumberFormatException ignored) {
        // PaymentOrderId dạng chuỗi phi số, đã lưu paymentTxCode
      }
    }
    disb.setTreasuryRefCode(treasuryRefCode);
    disb.setVoucherNo(voucherNo);
    disb.setSettledAmount(scaleMoney(request.getPaidAmount()));
    disb.setSettledDate(request.getPaidAt() != null ? request.getPaidAt() : LocalDateTime.now());
    if (!"ADVANCE".equalsIgnoreCase(disb.getDisbursementType())) {
      disb.setDisbursementType("SETTLEMENT");
    }
    disb.setApprovalStatus("SETTLED");
    if (StrUtil.isBlank(disb.getApprovedByPersonName())) {
      disb.setApprovedByPersonName("Hệ thống Đối soát Thanh toán Tự động");
    }

    BudgetDisbursement saved = budgetDisbursementService.save(disb);
    recordProcessedEvent(eventKey, "WEBHOOK_PAYMENT_SETTLE", saved.getAppCode(), saved.getId());

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
            saved.getSettledAmount(),
            saved.getCode(),
            "Đối soát tự động qua Webhook Thanh toán [" + paymentTxCode + "]");
      }
    }
    if (factMetricPublisher != null) {
      factMetricPublisher.publishDisbursementSnapshot(
          saved, updatedAlloc, "DISBURSEMENT_WEBHOOK_SETTLED");
    }

    return Map.of(
        "settled", true,
        "idempotent", false,
        "disbursementId", saved.getId(),
        "disbursementCode", saved.getCode(),
        "paymentTxCode", paymentTxCode,
        "treasuryRefCode", treasuryRefCode,
        "voucherNo", voucherNo,
        "approvalStatus", saved.getApprovalStatus(),
        "data", enrichData(budgetDisbursementMapper.toData(saved)));
  }

  private void verifyPaidAmountMatchesDisbursement(BudgetDisbursement disb, BigDecimal paidAmount) {
    BigDecimal paid = scaleMoney(paidAmount);
    BigDecimal gross = disb.getAmount() != null ? scaleMoney(disb.getAmount()) : null;
    BigDecimal net =
        disb.getNetPaymentAmount() != null
            ? scaleMoney(disb.getNetPaymentAmount())
            : gross;
    boolean matchesNet = net != null && paid.compareTo(net) == 0;
    boolean matchesGross = gross != null && paid.compareTo(gross) == 0;
    if (!matchesNet && !matchesGross) {
      throw new InvalidDataException(
          "Số tiền thanh toán ("
              + paid.toPlainString()
              + " VNĐ) không khớp với giá trị hồ sơ giải ngân ["
              + disb.getCode()
              + "] ("
              + (net != null ? net.toPlainString() : "0")
              + " VNĐ).",
          null);
    }
  }

  private static BigDecimal scaleMoney(BigDecimal value) {
    return (value != null ? value : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
  }

  private void recordProcessedEvent(
      String eventKey, String eventType, String appCode, Long referenceId) {
    if (processedEventRepo == null || StrUtil.isBlank(eventKey)) {
      return;
    }
    if (!processedEventRepo.existsByEventKey(eventKey)) {
      processedEventRepo.save(
          BudgetProcessedEvent.builder()
              .eventKey(eventKey)
              .eventType(eventType)
              .appCode(StrUtil.blankToDefault(appCode, "ADMIN"))
              .referenceId(referenceId)
              .processedAt(LocalDateTime.now())
              .build());
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

  private Long parseOptionalLong(Map<String, Object> body, String key) {
    if (body == null || body.get(key) == null || StrUtil.isBlank(String.valueOf(body.get(key)))) {
      return null;
    }
    try {
      return Long.valueOf(String.valueOf(body.get(key)).trim());
    } catch (NumberFormatException e) {
      throw new InvalidDataException(
          "Giá trị trường '" + key + "' không phải số nguyên hợp lệ.", null);
    }
  }

  private BigDecimal parseOptionalAmount(
      Map<String, Object> body, String key, BigDecimal defaultValue) {
    if (body == null || body.get(key) == null || StrUtil.isBlank(String.valueOf(body.get(key)))) {
      return defaultValue;
    }
    try {
      return new BigDecimal(String.valueOf(body.get(key)).trim());
    } catch (NumberFormatException e) {
      throw new InvalidDataException(
          "Giá trị số tiền '" + key + "' không đúng định dạng số.", null);
    }
  }

  /**
   * Lấy danh sách rút gọn (Lookup Options) Hồ sơ Giải ngân — ẩn thông tin tài khoản thụ hưởng và
   * mã số thuế.
   */
  @Transactional(readOnly = true)
  public PagedData<RefData<Long>> getOptions(BudgetDisbursementSearchCriteria criteria) {
    BudgetDisbursementSearchCriteria effective =
        LookupOptionsHelper.prepare(
            criteria != null ? criteria : new BudgetDisbursementSearchCriteria());
    Pageable pageable = CoreUtils.getPageRequest(effective);
    Page<BudgetDisbursement> page = budgetDisbursementService.findAll(effective, pageable);
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
    return "BudgetDisbursement";
  }

  @Override
  public String getConfigNamespace() {
    return "budget";
  }

  @Override
  public String getProcessCode() {
    return BudgetIntegrationOrchestrator.WF_PROCESS_DISBURSEMENT;
  }

  @Override
  public String getCallbackUrl() {
    return "/workflow/callback";
  }

  @Override
  public void submitWorkflow(Long id) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    guard().assertNodeAccess(disb.getNodeId(), disb.getNodeCode());
    sm().assertDisbursementTransition(disb.getApprovalStatus(), "PENDING_APPROVAL", disb.getCode());

    disb.setApprovalStatus("PENDING_APPROVAL");
    Long actorId = resolveAuthenticatedPersonId();
    if (actorId != null && disb.getRequestedByPersonId() == null) {
      disb.setRequestedByPersonId(actorId);
      disb.setRequestedByPersonName(resolveAuthenticatedActorName());
    }
    BudgetDisbursement saved = budgetDisbursementService.save(disb);
    if (integrationOrchestrator != null) {
      Long wfId = integrationOrchestrator.startDisbursementWorkflow(saved);
      if (wfId != null) {
        saved.setWfInstanceId(wfId);
        if (StrUtil.isBlank(saved.getWfProcessCode())) {
          saved.setWfProcessCode(BudgetIntegrationOrchestrator.WF_PROCESS_DISBURSEMENT);
        }
        budgetDisbursementService.save(saved);
      }
    }
  }

  @Override
  public void cancelWorkflow(Long id) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    guard().assertNodeAccess(disb.getNodeId(), disb.getNodeCode());
    sm().assertDisbursementTransition(disb.getApprovalStatus(), "DRAFT", disb.getCode());

    disb.setApprovalStatus("DRAFT");
    budgetDisbursementService.save(disb);
    if (disb.getAllocationId() != null) {
      workflowEngineHelper.recalculateAllocationMetrics(disb.getAllocationId());
    }
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
      approveDisbursement(payload.getEntityId(), Map.of("comment", comment));
    } else if ("REWORK".equals(decision) || "RETURN".equals(decision)) {
      reworkDisbursement(payload.getEntityId(), comment);
    } else if ("REJECT".equals(decision) || "REJECTED".equals(decision)) {
      rejectDisbursement(payload.getEntityId(), Map.of("reason", comment));
    }
  }

  @Override
  public BudgetDisbursement changeEntityStatus(
      Long id, LifecycleStatus targetStatus, String comment) {
    BudgetDisbursement disb =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    guard().assertNodeAccess(disb.getNodeId(), disb.getNodeCode());
    disb.setStatus(targetStatus);
    if (StrUtil.isNotBlank(comment)) {
      disb.setDescription(comment);
    }
    return budgetDisbursementService.save(disb);
  }
}
