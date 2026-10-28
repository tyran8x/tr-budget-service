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
import org.springframework.beans.factory.annotation.Autowired;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
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
    if (data.getNetPaymentAmount() == null && data.getAmount() != null) {
      BigDecimal tax = data.getTaxAmount() != null ? data.getTaxAmount() : BigDecimal.ZERO;
      data.setNetPaymentAmount(data.getAmount().subtract(tax));
    }
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
    return budgetDisbursementMapper.toData(entity);
  }

  /** Cập nhật BudgetDisbursement. */
  public BudgetDisbursementData update(Long id, BudgetDisbursementData data) {
    BudgetDisbursement existing =
        budgetDisbursementService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    data.setId(id);
    validateData(data);
    budgetDisbursementMapper.updateEntityFromData(data, existing);
    BudgetDisbursement saved = budgetDisbursementService.save(existing);
    return budgetDisbursementMapper.toData(saved);
  }

  /** Xoá mềm BudgetDisbursement. */
  public void delete(Long id) {
    budgetDisbursementService
        .findById(id)
        .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
    budgetDisbursementService.delete(id);
  }

  /** Xoá mềm hàng loạt BudgetDisbursement. */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    return businessLogicHelper.executeBulkDelete(
        ids,
        budgetDisbursementService::findAllByIds,
        BudgetDisbursement::getId,
        null,
        null,
        budgetDisbursementService::deleteByIds,
        "budget.budgetDisbursement.not.found");
  }

  /** Lấy chi tiết BudgetDisbursement theo ID. */
  @Transactional(readOnly = true)
  public BudgetDisbursementData findById(Long id) {
    return budgetDisbursementService
        .findById(id)
        .map(budgetDisbursementMapper::toData)
        .orElseThrow(() -> new EntityNotFoundException(BudgetDisbursement.class, id));
  }

  /** Tìm kiếm BudgetDisbursement phân trang. */
  @Transactional(readOnly = true)
  public PagedData<BudgetDisbursementData> findAll(BudgetDisbursementSearchCriteria criteria) {
    Pageable pageable = CoreUtils.getPageRequest(criteria);
    Page<BudgetDisbursement> page = budgetDisbursementService.findAll(criteria, pageable);
    return PagedData.from(page, budgetDisbursementMapper::toData);
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
    if (("DISBURSEMENT".equals(dtype) || "SETTLEMENT".equals(dtype))
        && workflowEngineHelper.isVoucherRequired(data.getEstimateItemId())
        && StrUtil.isBlank(data.getVoucherNo())) {
      throw new BaseException(
          "Đầu mục dự toán này bắt buộc phải có Số chứng từ / Hóa đơn / Giấy rút dự toán Kho bạc (voucherNo) khi thực chi hoặc quyết toán.");
    }
    if (data.getAllocationId() != null
        && data.getAmount() != null
        && !"SETTLEMENT".equals(dtype)) {
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
    disb.setApprovalStatus("APPROVED_DISBURSED");
    if (StrUtil.isBlank(disb.getApprovedByPersonName())) {
      disb.setApprovedByPersonName("Đinh Quang Huy");
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
    return budgetDisbursementMapper.toData(saved);
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
    disb.setDisbursementType("SETTLEMENT");
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
    return budgetDisbursementMapper.toData(saved);
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
    BudgetDisbursement saved = budgetDisbursementService.save(disb);
    if (saved.getAllocationId() != null) {
      workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    return budgetDisbursementMapper.toData(saved);
  }

}
