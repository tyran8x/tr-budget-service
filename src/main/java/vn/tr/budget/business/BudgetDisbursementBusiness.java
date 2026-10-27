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
    BudgetDisbursement saved = budgetDisbursementService.save(disb);
    if (saved.getAllocationId() != null) {
      workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    if (integrationOrchestrator != null) {
      integrationOrchestrator.notifyDisbursementEvent(saved, "APPROVED");
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
    if (saved.getAllocationId() != null) {
      workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    if (integrationOrchestrator != null) {
      integrationOrchestrator.notifyDisbursementEvent(saved, "SETTLED");
    }
    return budgetDisbursementMapper.toData(saved);
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
