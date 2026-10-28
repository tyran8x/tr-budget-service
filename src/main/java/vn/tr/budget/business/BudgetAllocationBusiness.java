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
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.common.core.exception.base.BaseException;
import vn.tr.common.feign.budget.data.BudgetPlanSyncRequestData;
import vn.tr.common.feign.budget.data.BudgetPlanSyncResultData;

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
  private final BudgetAllocationRepo budgetAllocationRepo;

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
    saved = workflowEngineHelper.recalculateAllocationMetrics(saved.getId());
    if (integrationOrchestrator != null) {
      integrationOrchestrator.notifyAllocationLifecycleEvent(
          saved, "SELF_APPROVED", extractActor(body), extractComment(body));
      integrationOrchestrator.syncAllocationToLinkedPlan(
          saved,
          "ALLOCATION_APPROVED",
          saved.getEffectiveAmount(),
          saved.getCode(),
          "Đơn vị tự phê duyệt phân bổ kinh phí");
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
    saved = workflowEngineHelper.recalculateAllocationMetrics(saved.getId());
    if (integrationOrchestrator != null) {
      integrationOrchestrator.executeWorkflowAction(
          "BudgetAllocation", saved.getId(), "APPROVE", extractComment(body));
      integrationOrchestrator.notifyAllocationLifecycleEvent(
          saved, "APPROVED", extractActor(body), extractComment(body));
      integrationOrchestrator.syncAllocationToLinkedPlan(
          saved,
          "ALLOCATION_APPROVED",
          saved.getEffectiveAmount(),
          saved.getCode(),
          "Phê duyệt giao dự toán kinh phí theo QĐ " + StrUtil.nullToEmpty(saved.getDecisionNo()));
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

  /** Đồng bộ phương án phân bổ kinh phí từ Kế hoạch công tác (S2S Feign từ tr-planning-service). */
  public BudgetPlanSyncResultData syncFromPlan(BudgetPlanSyncRequestData req) {
    if (req == null || StrUtil.isBlank(req.getPlanCode())) {
      throw new BaseException("Mã kế hoạch công tác (planCode) không được để trống khi đồng bộ kinh phí.");
    }
    List<BudgetAllocation> existingList =
        budgetAllocationRepo.findByLinkedPlanCodeIgnoreCaseOrderByIdAsc(req.getPlanCode());
    BudgetAllocation alloc;
    String syncStatus;
    if (!existingList.isEmpty()) {
      alloc = existingList.get(0);
      if (req.getPlanId() != null) {
        alloc.setLinkedPlanId(req.getPlanId());
      }
      if (StrUtil.isNotBlank(req.getPlanName())) {
        alloc.setLinkedPlanName(req.getPlanName());
      }
      if (req.getInitialBudgetAmount() != null
          && (alloc.getInitialAmount() == null
              || alloc.getInitialAmount().compareTo(BigDecimal.ZERO) == 0)) {
        alloc.setInitialAmount(req.getInitialBudgetAmount());
        alloc.setProposedAmount(req.getInitialBudgetAmount());
      }
      alloc = budgetAllocationService.save(alloc);
      alloc = workflowEngineHelper.recalculateAllocationMetrics(alloc.getId());
      syncStatus = "UPDATED_ALLOCATION";
    } else {
      BigDecimal initAmt =
          req.getInitialBudgetAmount() != null
              ? req.getInitialBudgetAmount()
              : (req.getEffectiveBudgetAmount() != null
                  ? req.getEffectiveBudgetAmount()
                  : BigDecimal.ZERO);
      BudgetAllocation newAlloc =
          BudgetAllocation.builder()
              .code("PB-" + req.getPlanCode())
              .name("Dự toán kinh phí thực hiện: " + StrUtil.blankToDefault(req.getPlanName(), req.getPlanCode()))
              .budgetYear(
                  req.getBudgetYear() != null ? req.getBudgetYear() : LocalDate.now().getYear())
              .periodType(StrUtil.blankToDefault(req.getPeriodType(), "ANNUAL"))
              .budgetQuarter(req.getBudgetQuarter())
              .budgetMonth(req.getBudgetMonth())
              .nodeId(req.getNodeId() != null ? req.getNodeId() : 101L)
              .nodeCode(StrUtil.blankToDefault(req.getNodeCode(), "VAN_PHONG_SO"))
              .nodeName(StrUtil.blankToDefault(req.getNodeName(), "Văn phòng Sở"))
              .itemType("EXPENDITURE")
              .categoryCode(StrUtil.blankToDefault(req.getCategoryCode(), "LK_340_345"))
              .categoryName("Chi sự nghiệp kinh tế & Quản lý chuyên ngành")
              .fundingSourceCode("NSNN_TX")
              .fundingSourceName("Ngân sách Nhà nước - Kinh phí thường xuyên")
              .decisionNo(req.getDecisionNo())
              .decisionDate(LocalDate.now())
              .linkedPlanId(req.getPlanId())
              .linkedPlanCode(req.getPlanCode())
              .linkedPlanName(req.getPlanName())
              .approvalScope("DIRECTORATE")
              .workflowStatus("APPROVED")
              .currentVersion(1)
              .proposedAmount(initAmt)
              .initialAmount(initAmt)
              .supplementAmount(
                  req.getSupplementBudgetAmount() != null
                      ? req.getSupplementBudgetAmount()
                      : BigDecimal.ZERO)
              .carryOverInAmount(
                  req.getCarryOverBudgetAmount() != null
                      ? req.getCarryOverBudgetAmount()
                      : BigDecimal.ZERO)
              .status(LifecycleStatus.ACTIVE)
              .build();
      alloc = budgetAllocationService.save(newAlloc);
      alloc = workflowEngineHelper.recalculateAllocationMetrics(alloc.getId());
      syncStatus = "CREATED_ALLOCATION";
    }
    return toPlanSyncResult(alloc, syncStatus);
  }

  /** Tra cứu nhanh bức tranh kinh phí của một Mã kế hoạch công tác. */
  @Transactional(readOnly = true)
  public BudgetPlanSyncResultData getBudgetSnapshotByPlanCode(String planCode) {
    List<BudgetAllocation> list =
        budgetAllocationRepo.findByLinkedPlanCodeIgnoreCaseOrderByIdAsc(planCode);
    if (list.isEmpty()) {
      return BudgetPlanSyncResultData.builder()
          .linkedPlanCode(planCode)
          .initialAmount(BigDecimal.ZERO)
          .supplementAmount(BigDecimal.ZERO)
          .carryOverInAmount(BigDecimal.ZERO)
          .effectiveAmount(BigDecimal.ZERO)
          .committedAmount(BigDecimal.ZERO)
          .disbursedAmount(BigDecimal.ZERO)
          .settledAmount(BigDecimal.ZERO)
          .availableAmount(BigDecimal.ZERO)
          .disbursementRatePercent(BigDecimal.ZERO)
          .syncStatus("NO_ALLOCATION_FOUND")
          .build();
    }
    return toPlanSyncResult(list.get(0), "REMOTE_BUDGET_SERVICE");
  }

  /** Đồng bộ chủ động từ một Phân bổ Kinh phí sang Kế hoạch công tác liên kết. */
  public Map<String, Object> syncAllocationToPlanById(Long id) {
    BudgetAllocation alloc = workflowEngineHelper.recalculateAllocationMetrics(id);
    if (alloc == null) {
      throw new EntityNotFoundException(BudgetAllocation.class, id);
    }
    if (integrationOrchestrator != null) {
      return integrationOrchestrator.syncAllocationToLinkedPlan(
          alloc,
          "MANUAL_SYNC",
          alloc.getEffectiveAmount(),
          alloc.getCode(),
          "Đồng bộ chủ động số liệu dự toán sang Kế hoạch công tác");
    }
    return Map.of("synced", false, "allocationCode", alloc.getCode());
  }

  private BudgetPlanSyncResultData toPlanSyncResult(BudgetAllocation alloc, String syncStatus) {
    return BudgetPlanSyncResultData.builder()
        .allocationId(alloc.getId())
        .allocationCode(alloc.getCode())
        .linkedPlanId(alloc.getLinkedPlanId())
        .linkedPlanCode(alloc.getLinkedPlanCode())
        .linkedPlanName(alloc.getLinkedPlanName())
        .budgetYear(alloc.getBudgetYear())
        .nodeCode(alloc.getNodeCode())
        .nodeName(alloc.getNodeName())
        .workflowStatus(alloc.getWorkflowStatus())
        .initialAmount(alloc.getInitialAmount())
        .supplementAmount(alloc.getSupplementAmount())
        .carryOverInAmount(alloc.getCarryOverInAmount())
        .effectiveAmount(alloc.getEffectiveAmount())
        .committedAmount(alloc.getCommittedAmount())
        .disbursedAmount(alloc.getDisbursedAmount())
        .settledAmount(alloc.getSettledAmount())
        .availableAmount(alloc.getAvailableAmount())
        .disbursementRatePercent(alloc.getDisbursementRatePercent())
        .syncStatus(syncStatus)
        .build();
  }

  private String extractActor(Map<String, Object> body) {
    if (integrationOrchestrator != null) {
      return integrationOrchestrator.resolveCurrentActorName(
          body, "actorName", "approvedByPersonName", "submittedByPersonName");
    }
    return body != null && body.get("actorName") != null
        ? String.valueOf(body.get("actorName"))
        : "Cán bộ Tài chính - Kế toán";
  }

  private String extractComment(Map<String, Object> body) {
    return body != null && body.get("comment") != null ? String.valueOf(body.get("comment")) : "";
  }

}
