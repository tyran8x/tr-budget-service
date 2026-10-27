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
import org.springframework.beans.factory.annotation.Autowired;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
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
    validateLedgerBalanceBeforeTransfer(data);
    BudgetTransaction entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetTransactionService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> budgetTransactionMapper.toEntity(data),
            budgetTransactionMapper::updateEntityFromData,
            budgetTransactionService.getRepository());
    if ("APPROVED".equalsIgnoreCase(entity.getApprovalStatus())) {
      workflowEngineHelper.executeDoubleEntryLedger(entity);
    }
    return budgetTransactionMapper.toData(entity);
  }

  /** Cập nhật BudgetTransaction. */
  public BudgetTransactionData update(Long id, BudgetTransactionData data) {
    BudgetTransaction existing =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    data.setId(id);
    validateData(data);
    budgetTransactionMapper.updateEntityFromData(data, existing);
    BudgetTransaction saved = budgetTransactionService.save(existing);
    return budgetTransactionMapper.toData(saved);
  }

  /** Xoá mềm BudgetTransaction. */
  public void delete(Long id) {
    budgetTransactionService
        .findById(id)
        .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    budgetTransactionService.delete(id);
  }

  /** Xoá mềm hàng loạt BudgetTransaction. */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    return businessLogicHelper.executeBulkDelete(
        ids,
        budgetTransactionService::findAllByIds,
        BudgetTransaction::getId,
        null,
        null,
        budgetTransactionService::deleteByIds,
        "budget.budgetTransaction.not.found");
  }

  /** Lấy chi tiết BudgetTransaction theo ID. */
  @Transactional(readOnly = true)
  public BudgetTransactionData findById(Long id) {
    return budgetTransactionService
        .findById(id)
        .map(budgetTransactionMapper::toData)
        .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
  }

  /** Tìm kiếm BudgetTransaction phân trang. */
  @Transactional(readOnly = true)
  public PagedData<BudgetTransactionData> findAll(BudgetTransactionSearchCriteria criteria) {
    Pageable pageable = CoreUtils.getPageRequest(criteria);
    Page<BudgetTransaction> page = budgetTransactionService.findAll(criteria, pageable);
    return PagedData.from(page, budgetTransactionMapper::toData);
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
    if (body != null && body.get("approvedByPersonName") != null) {
      tx.setApprovedByPersonName(String.valueOf(body.get("approvedByPersonName")));
    } else if (StrUtil.isBlank(tx.getApprovedByPersonName())) {
      tx.setApprovedByPersonName("Đinh Quang Huy");
    }
    BudgetTransaction saved = budgetTransactionService.save(tx);
    workflowEngineHelper.executeDoubleEntryLedger(saved);
    if (integrationOrchestrator != null) {
      integrationOrchestrator.notifyTransactionEvent(saved, "APPROVED");
    }
    return budgetTransactionMapper.toData(saved);
  }

  /** Từ chối giao dịch biến động nguồn kinh phí. */
  public BudgetTransactionData rejectTransaction(Long id, Map<String, Object> body) {
    BudgetTransaction tx =
        budgetTransactionService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetTransaction.class, id));
    tx.setApprovalStatus("REJECTED");
    BudgetTransaction saved = budgetTransactionService.save(tx);
    if (saved.getAllocationId() != null) {
      workflowEngineHelper.recalculateAllocationMetrics(saved.getAllocationId());
    }
    if (integrationOrchestrator != null) {
      integrationOrchestrator.notifyTransactionEvent(saved, "REJECTED");
    }
    return budgetTransactionMapper.toData(saved);
  }

}
