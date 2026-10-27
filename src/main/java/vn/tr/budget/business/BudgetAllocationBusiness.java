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
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.service.BudgetAllocationService;
import vn.tr.budget.data.criteria.BudgetAllocationSearchCriteria;
import vn.tr.budget.data.dto.BudgetAllocationData;
import vn.tr.budget.data.mapper.BudgetAllocationMapper;
import vn.tr.budget.data.validator.BudgetAllocationValidator;
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
import vn.tr.common.core.exception.base.BaseException;

/**
 * Lớp điều phối nghiệp vụ cho BudgetAllocation.
 *
 * @author tyran8x
 */
@Service("budgetAllocationBusiness")
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BudgetAllocationBusiness {

  private final BudgetAllocationService budgetAllocationService;
  private final BudgetAllocationMapper budgetAllocationMapper;
  private final BudgetAllocationValidator budgetAllocationValidator;
  private final GenericUpsertHelper genericUpsertHelper;
  private final AuditRollbackHelper auditRollbackHelper;
  private final BusinessLogicHelper businessLogicHelper;
  private final BudgetWorkflowEngineHelper workflowEngineHelper;

  @Autowired(required = false)
  private BudgetIntegrationOrchestrator integrationOrchestrator;

  public void setIntegrationOrchestrator(BudgetIntegrationOrchestrator integrationOrchestrator) {
    this.integrationOrchestrator = integrationOrchestrator;
  }

  public JpaRepository<BudgetAllocation, Long> getInjectedRepo() {
    return budgetAllocationService.getRepository();
  }

  /** Tạo mới BudgetAllocation. */
  public BudgetAllocationData create(BudgetAllocationData data) {
    data.setId(null);
    validateData(data);
    if (data.getStatus() == null) {
      data.setStatus(LifecycleStatus.ACTIVE);
    }
    if (data.getBudgetYear() == null) {
      data.setBudgetYear(LocalDate.now().getYear());
    }
    if (StrUtil.isBlank(data.getPeriodType())) {
      data.setPeriodType("ANNUAL");
    }
    if (StrUtil.isBlank(data.getItemType())) {
      data.setItemType("EXPENDITURE");
    }
    if (StrUtil.isBlank(data.getWorkflowStatus())) {
      data.setWorkflowStatus("DRAFT");
    }
    if (data.getCurrentVersion() == null) {
      data.setCurrentVersion(1);
    }
    if (StrUtil.isBlank(data.getApprovalScope())) {
      Map<String, Object> auto =
          workflowEngineHelper.checkUnitAutonomy(
              data.getNodeId(), data.getCategoryCode(), data.getInitialAmount());
      data.setApprovalScope(String.valueOf(auto.getOrDefault("recommendedApprovalScope", "DIRECTORATE")));
    }
    BudgetAllocation entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetAllocationService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> budgetAllocationMapper.toEntity(data),
            budgetAllocationMapper::updateEntityFromData,
            budgetAllocationService.getRepository());
    entity = workflowEngineHelper.recalculateAllocationMetrics(entity.getId());
    return budgetAllocationMapper.toData(entity);
  }

  /** Cập nhật BudgetAllocation. */
  public BudgetAllocationData update(Long id, BudgetAllocationData data) {
    BudgetAllocation existing =
        budgetAllocationService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetAllocation.class, id));
    data.setId(id);
    validateData(data);
    budgetAllocationMapper.updateEntityFromData(data, existing);
    BudgetAllocation saved = budgetAllocationService.save(existing);
    return budgetAllocationMapper.toData(saved);
  }

  /** Xoá mềm BudgetAllocation. */
  public void delete(Long id) {
    budgetAllocationService
        .findById(id)
        .orElseThrow(() -> new EntityNotFoundException(BudgetAllocation.class, id));
    budgetAllocationService.delete(id);
  }

  /** Xoá mềm hàng loạt BudgetAllocation. */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    return businessLogicHelper.executeBulkDelete(
        ids,
        budgetAllocationService::findAllByIds,
        BudgetAllocation::getId,
        null,
        null,
        budgetAllocationService::deleteByIds,
        "budget.budgetAllocation.not.found");
  }

  /** Lấy chi tiết BudgetAllocation theo ID. */
  @Transactional(readOnly = true)
  public BudgetAllocationData findById(Long id) {
    return budgetAllocationService
        .findById(id)
        .map(budgetAllocationMapper::toData)
        .orElseThrow(() -> new EntityNotFoundException(BudgetAllocation.class, id));
  }

  /** Tìm kiếm BudgetAllocation phân trang. */
  @Transactional(readOnly = true)
  public PagedData<BudgetAllocationData> findAll(BudgetAllocationSearchCriteria criteria) {
    Pageable pageable = CoreUtils.getPageRequest(criteria);
    Page<BudgetAllocation> page = budgetAllocationService.findAll(criteria, pageable);
    return PagedData.from(page, budgetAllocationMapper::toData);
  }

  /** Sao chép BudgetAllocation. */
  @Transactional
  public BudgetAllocationData cloneById(Long id) {
    BudgetAllocation source =
        budgetAllocationService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetAllocation.class, id));
    BudgetAllocationData clonedData = budgetAllocationMapper.toData(source);
    clonedData.setId(null);
    clonedData.setCode(source.getCode() + "_COPY_" + System.currentTimeMillis() % 10000);
    clonedData.setName(source.getName() + " (Bản sao)");
    return create(clonedData);
  }

  /** Khôi phục phiên bản lịch sử JaVers. */
  @Transactional
  public BudgetAllocationData rollback(Long id, Long version) {
    return auditRollbackHelper.executeRollback(
        BudgetAllocation.class,
        id,
        version,
        budgetAllocationService::findById,
        budgetAllocationService::save,
        budgetAllocationMapper::toData,
        budgetAllocationMapper::updateEntityFromData,
        BudgetAllocationData::setId,
        this::validateData);
  }

  /** Cập nhật thứ tự sắp xếp hàng loạt. */
  @Transactional
  public void bulkUpdateSortOrder(BulkSortOrderData data) {
    if (data == null || data.getUpdates() == null || data.getUpdates().isEmpty()) {
      return;
    }
    for (BulkSortOrderData.SortOrderUpdate item : data.getUpdates()) {
      budgetAllocationService
          .findById(item.getId())
          .ifPresent(
              entity -> {
                entity.setSortOrder(item.getSortOrder());
                budgetAllocationService.save(entity);
              });
    }
  }

  private void validateData(BudgetAllocationData data) {
    vn.tr.common.web.core.BaseBusinessHelper.validateOrThrow(
        budgetAllocationValidator, data, "budgetAllocationData");
  }

  /** Trình thẩm định / phê duyệt Phân bổ Dự toán Kinh phí. */
  public BudgetAllocationData submitAllocation(Long id, Map<String, Object> body) {
    BudgetAllocation alloc =
        budgetAllocationService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetAllocation.class, id));
    alloc.setWorkflowStatus("SUBMITTED");
    BudgetAllocation saved = budgetAllocationService.save(alloc);
    workflowEngineHelper.recalculateAllocationMetrics(saved.getId());
    if (integrationOrchestrator != null) {
      integrationOrchestrator.startAllocationWorkflow(saved);
      integrationOrchestrator.notifyAllocationLifecycleEvent(
          saved, "SUBMITTED", extractActor(body), extractComment(body));
    }
    return findById(saved.getId());
  }

  /** Đơn vị tự phê duyệt phân bổ chi tiết trong trần tự chủ tài chính. */
  public BudgetAllocationData selfApproveAllocation(Long id, Map<String, Object> body) {
    BudgetAllocation alloc =
        budgetAllocationService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetAllocation.class, id));
    Map<String, Object> auto =
        workflowEngineHelper.checkUnitAutonomy(
            alloc.getNodeId(), alloc.getCategoryCode(), alloc.getInitialAmount());
    if (!Boolean.TRUE.equals(auto.get("canSelfApprove"))) {
      throw new BaseException(
          "Kinh phí vượt hạn mức tự phê duyệt hoặc đơn vị chưa được cấp quyền tự chủ phân bổ, bắt buộc trình Phòng KHTC / Ban Giám đốc Sở.");
    }
    alloc.setApprovalScope("UNIT_SELF");
    alloc.setWorkflowStatus("APPROVED");
    BudgetAllocation saved = budgetAllocationService.save(alloc);
    workflowEngineHelper.recalculateAllocationMetrics(saved.getId());
    if (integrationOrchestrator != null) {
      integrationOrchestrator.notifyAllocationLifecycleEvent(
          saved, "SELF_APPROVED", extractActor(body), extractComment(body));
    }
    return findById(saved.getId());
  }

  /** Phê duyệt & Ban hành Quyết định Giao Dự toán Kinh phí. */
  public BudgetAllocationData approveAllocation(Long id, Map<String, Object> body) {
    BudgetAllocation alloc =
        budgetAllocationService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetAllocation.class, id));
    if (body != null && body.get("decisionNo") != null) {
      alloc.setDecisionNo(String.valueOf(body.get("decisionNo")));
    }
    if (alloc.getDecisionDate() == null) {
      alloc.setDecisionDate(LocalDate.now());
    }
    alloc.setWorkflowStatus("APPROVED");
    BudgetAllocation saved = budgetAllocationService.save(alloc);
    workflowEngineHelper.recalculateAllocationMetrics(saved.getId());
    if (integrationOrchestrator != null) {
      integrationOrchestrator.executeWorkflowAction(
          "BudgetAllocation", saved.getId(), "APPROVE", extractComment(body));
      integrationOrchestrator.notifyAllocationLifecycleEvent(
          saved, "APPROVED", extractActor(body), extractComment(body));
    }
    return findById(saved.getId());
  }

  /** Trả lại yêu cầu rà soát / bổ sung thuyết minh dự toán. */
  public BudgetAllocationData rejectAllocation(Long id, Map<String, Object> body) {
    BudgetAllocation alloc =
        budgetAllocationService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetAllocation.class, id));
    alloc.setWorkflowStatus("REJECTED");
    BudgetAllocation saved = budgetAllocationService.save(alloc);
    if (integrationOrchestrator != null) {
      integrationOrchestrator.executeWorkflowAction(
          "BudgetAllocation", saved.getId(), "REJECT", extractComment(body));
      integrationOrchestrator.notifyAllocationLifecycleEvent(
          saved, "REJECTED", extractActor(body), extractComment(body));
    }
    return budgetAllocationMapper.toData(saved);
  }

  /** Rút tờ trình dự toán về Dự thảo (DRAFT). */
  public BudgetAllocationData withdrawAllocation(Long id, Map<String, Object> body) {
    BudgetAllocation alloc =
        budgetAllocationService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetAllocation.class, id));
    alloc.setWorkflowStatus("DRAFT");
    BudgetAllocation saved = budgetAllocationService.save(alloc);
    if (integrationOrchestrator != null) {
      integrationOrchestrator.executeWorkflowAction(
          "BudgetAllocation", saved.getId(), "WITHDRAW", extractComment(body));
    }
    return budgetAllocationMapper.toData(saved);
  }

  /** Tính toán lại số dư Sổ cái Kinh phí và tỷ lệ giải ngân. */
  public BudgetAllocationData recalculate(Long id) {
    BudgetAllocation updated = workflowEngineHelper.recalculateAllocationMetrics(id);
    if (updated == null) {
      throw new EntityNotFoundException(BudgetAllocation.class, id);
    }
    return budgetAllocationMapper.toData(updated);
  }

  /** Lấy tiến trình phê duyệt Workflow của Phân bổ Dự toán. */
  @Transactional(readOnly = true)
  public Map<String, Object> getWorkflowTimeline(Long id) {
    BudgetAllocation alloc =
        budgetAllocationService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetAllocation.class, id));
    if (integrationOrchestrator != null) {
      return integrationOrchestrator.getAllocationWorkflowTimeline(alloc);
    }
    return Map.of("allocationId", alloc.getId(), "workflowStatus", alloc.getWorkflowStatus());
  }

  /** Quét cảnh báo SLA hồ sơ giải ngân / tạm ứng sắp quá hạn quyết toán. */
  public Map<String, Object> scanAndDispatchSlaAlerts() {
    if (integrationOrchestrator != null) {
      return integrationOrchestrator.scanAndDispatchDisbursementSlaAlerts();
    }
    return Map.of("totalScanned", 0, "overdueCount", 0, "dueSoonCount", 0);
  }

  private String extractActor(Map<String, Object> body) {
    return body != null && body.get("actorName") != null
        ? String.valueOf(body.get("actorName"))
        : "Đinh Quang Huy";
  }

  private String extractComment(Map<String, Object> body) {
    return body != null && body.get("comment") != null ? String.valueOf(body.get("comment")) : "";
  }

}
