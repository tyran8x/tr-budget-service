package vn.tr.budget.business;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetReport;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetCategoryRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetReportService;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.dao.service.BudgetUnitConfigRepo;
import vn.tr.budget.data.criteria.BudgetReportSearchCriteria;
import vn.tr.budget.data.dto.BudgetReportData;
import vn.tr.budget.data.mapper.BudgetReportMapper;
import vn.tr.budget.data.validator.BudgetReportValidator;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.domain.data.RefData;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.core.exception.base.EntityNotFoundException;
import vn.tr.common.core.exception.base.InvalidDataException;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.security.abac.OrgSubtreeAuthorizationHelper;
import vn.tr.common.web.core.BaseBusinessHelper;
import vn.tr.common.web.data.dto.BulkOperationResult;
import vn.tr.common.web.data.dto.BulkSortOrderData;
import vn.tr.common.web.utils.BusinessLogicHelper;
import vn.tr.common.web.utils.CoreUtils;
import vn.tr.common.web.utils.LookupOptionsHelper;
import vn.tr.common.web.workflow.WorkflowBusinessSupport;
import vn.tr.common.web.workflow.WorkflowCallbackPayload;

/**
 * Lớp điều phối nghiệp vụ cho BudgetReport.
 *
 * @author tyran8x
 */
@Service("budgetReportBusiness")
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BudgetReportBusiness implements WorkflowBusinessSupport<BudgetReport> {

  private final BudgetReportService budgetReportService;
  private final BudgetReportMapper budgetReportMapper;
  private final BudgetReportValidator budgetReportValidator;
  private final GenericUpsertHelper genericUpsertHelper;
  private final AuditRollbackHelper auditRollbackHelper;
  private final BusinessLogicHelper businessLogicHelper;
  private final BudgetAllocationRepo budgetAllocationRepo;

  @Autowired(required = false)
  private BudgetCategoryRepo budgetCategoryRepo;

  @Autowired(required = false)
  private BudgetDisbursementRepo budgetDisbursementRepo;

  @Autowired(required = false)
  private BudgetTransactionRepo budgetTransactionRepo;

  @Autowired(required = false)
  private BudgetUnitConfigRepo budgetUnitConfigRepo;

  @Autowired(required = false)
  private BudgetIntegrationOrchestrator integrationOrchestrator;

  @Autowired(required = false)
  private BudgetStateMachine stateMachine;

  @Autowired(required = false)
  private BudgetSecurityGuardHelper securityGuard;

  public void setBudgetTransactionRepo(BudgetTransactionRepo budgetTransactionRepo) {
    this.budgetTransactionRepo = budgetTransactionRepo;
  }

  public void setBudgetDisbursementRepo(BudgetDisbursementRepo budgetDisbursementRepo) {
    this.budgetDisbursementRepo = budgetDisbursementRepo;
  }

  public void setBudgetUnitConfigRepo(BudgetUnitConfigRepo budgetUnitConfigRepo) {
    this.budgetUnitConfigRepo = budgetUnitConfigRepo;
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

  private static final Set<String> EFFECTIVE_TX_STATUSES =
      Set.of("APPROVED", "SELF_APPROVED", "SETTLED", "EXECUTING", "LOCKED_SETTLED");

  private static final Set<String> EFFECTIVE_DISB_STATUSES =
      Set.of("COMMITTED", "APPROVED", "APPROVED_DISBURSED", "SETTLED", "EXECUTING", "LOCKED_SETTLED");

  public JpaRepository<BudgetReport, Long> getInjectedRepo() {
    return budgetReportService.getRepository();
  }

  /** Tạo mới BudgetReport (luôn ép DRAFT, kiểm tra ABAC và khử Mass Assignment). */
  public BudgetReportData create(BudgetReportData data) {
    data.setId(null);
    validateData(data);
    if (data.getStatus() == null) {
      data.setStatus(LifecycleStatus.ACTIVE);
    }
    if (data.getBudgetYear() == null) {
      data.setBudgetYear(LocalDate.now().getYear());
    }
    if (StrUtil.isBlank(data.getReportPeriod())) {
      data.setReportPeriod("QUARTER");
    }
    if (StrUtil.isBlank(data.getReportScope())) {
      data.setReportScope("CONSOLIDATED_REPORT");
    }
    if (data.getReportDate() == null) {
      data.setReportDate(LocalDate.now());
    }
    data.setWorkflowStatus("DRAFT");
    Long authPersonId = resolveAuthenticatedPersonId();
    if (authPersonId != null) {
      data.setPreparedByPersonId(authPersonId);
      data.setPreparedByPersonName(resolveAuthenticatedActorName());
    }
    syncReportSnapshots(data);
    guard().assertNodeAccessIfPresent(data.getNodeId(), data.getNodeCode());
    BudgetReport entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetReportService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> {
              BudgetReport created = budgetReportMapper.toEntity(data);
              guard().sanitizeReportForCreate(created);
              return created;
            },
            (src, target) -> {
              sm().assertMutableReport(target.getWorkflowStatus(), "ghi đè", target.getCode());
              budgetReportMapper.updateEntityFromData(src, target);
              guard().sanitizeReportForCreate(target);
            },
            budgetReportService.getRepository());

    return enrichData(budgetReportMapper.toData(entity));
  }

  /** Cập nhật BudgetReport (chặn sửa báo cáo đã phê duyệt / chốt sổ & khử Mass Assignment). */
  public BudgetReportData update(Long id, BudgetReportData data) {
    BudgetReport existing =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(existing.getNodeId(), existing.getNodeCode());
    sm().assertMutableReport(existing.getWorkflowStatus(), "cập nhật", existing.getCode());

    data.setId(id);
    validateData(data);
    syncReportSnapshots(data);
    guard().assertNodeAccessIfPresent(data.getNodeId(), data.getNodeCode());

    BudgetReport snapshot = existing.toBuilder().build();
    budgetReportMapper.updateEntityFromData(data, existing);
    guard().sanitizeReportForUpdate(existing, snapshot);

    BudgetReport saved = budgetReportService.save(existing);
    return enrichData(budgetReportMapper.toData(saved));
  }

  /** Xoá mềm BudgetReport (chặn xóa báo cáo đã phê duyệt). */
  public void delete(Long id) {
    BudgetReport existing =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(existing.getNodeId(), existing.getNodeCode());
    sm().assertMutableReport(existing.getWorkflowStatus(), "xóa", existing.getCode());
    budgetReportService.delete(id);
  }

  /** Xoá mềm hàng loạt BudgetReport (kiểm tra ABAC và khóa trạng thái đã phê duyệt). */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    if (ids != null && !ids.isEmpty()) {
      for (BudgetReport rpt : budgetReportService.findAllByIds(ids)) {
        guard().assertNodeAccessIfPresent(rpt.getNodeId(), rpt.getNodeCode());
      }
    }
    return businessLogicHelper.executeBulkDelete(
        ids,
        budgetReportService::findAllByIds,
        BudgetReport::getId,
        rpt -> guard().assertNodeAccessIfPresent(rpt.getNodeId(), rpt.getNodeCode()),
        rpt -> sm().assertMutableReport(rpt.getWorkflowStatus(), "xóa", rpt.getCode()),
        budgetReportService::deleteByIds,
        "budget.budgetReport.not.found");
  }

  /** Lấy chi tiết BudgetReport theo ID (kiểm tra ABAC). */
  @Transactional(readOnly = true)
  public BudgetReportData findById(Long id) {
    BudgetReport entity =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(entity.getNodeId(), entity.getNodeCode());
    return enrichData(budgetReportMapper.toData(entity));
  }

  /** Tìm kiếm BudgetReport phân trang. */
  @Transactional(readOnly = true)
  public PagedData<BudgetReportData> findAll(BudgetReportSearchCriteria criteria) {
    Pageable pageable = CoreUtils.getPageRequest(criteria);
    Page<BudgetReport> page = budgetReportService.findAll(criteria, pageable);
    return PagedData.from(page, entity -> enrichData(budgetReportMapper.toData(entity)));
  }

  /**
   * Đồng bộ hai chiều (Hybrid Live-Resolve + Snapshot Fallback) cho Node và Person trong
   * BudgetReport.
   */
  private void syncReportSnapshots(BudgetReportData data) {
    if (data == null || integrationOrchestrator == null) {
      return;
    }
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
    if (data.getSubmittedByPersonId() != null) {
      data.setSubmittedByPersonName(
          integrationOrchestrator.resolvePersonNameWithFallback(
              data.getSubmittedByPersonId(), data.getSubmittedByPersonName()));
    }
    if (data.getReviewedByPersonId() != null) {
      data.setReviewedByPersonName(
          integrationOrchestrator.resolvePersonNameWithFallback(
              data.getReviewedByPersonId(), data.getReviewedByPersonName()));
    }
    if (data.getApprovedByPersonId() != null) {
      data.setApprovedByPersonName(
          integrationOrchestrator.resolvePersonNameWithFallback(
              data.getApprovedByPersonId(), data.getApprovedByPersonName()));
    }
  }

  private BudgetReportData enrichData(BudgetReportData dto) {
    if (dto == null || integrationOrchestrator == null) {
      return dto;
    }
    if (dto.getNodeId() != null) {
      dto.setNodeCode(
          integrationOrchestrator.resolveNodeCodeWithFallback(dto.getNodeId(), dto.getNodeCode()));
      dto.setNodeName(
          integrationOrchestrator.resolveNodeNameWithFallback(dto.getNodeId(), dto.getNodeName()));
    }
    if (dto.getSubmittedByPersonId() != null) {
      dto.setSubmittedByPersonName(
          integrationOrchestrator.resolvePersonNameWithFallback(
              dto.getSubmittedByPersonId(), dto.getSubmittedByPersonName()));
    }
    if (dto.getReviewedByPersonId() != null) {
      dto.setReviewedByPersonName(
          integrationOrchestrator.resolvePersonNameWithFallback(
              dto.getReviewedByPersonId(), dto.getReviewedByPersonName()));
    }
    if (dto.getApprovedByPersonId() != null) {
      dto.setApprovedByPersonName(
          integrationOrchestrator.resolvePersonNameWithFallback(
              dto.getApprovedByPersonId(), dto.getApprovedByPersonName()));
    }
    return dto;
  }

  /** Sao chép BudgetReport. */
  @Transactional
  public BudgetReportData cloneById(Long id) {
    BudgetReport source =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(source.getNodeId(), source.getNodeCode());
    BudgetReportData clonedData = budgetReportMapper.toData(source);
    clonedData.setId(null);
    clonedData.setCode(source.getCode() + "_COPY_" + System.currentTimeMillis() % 10000);
    clonedData.setName(source.getName() + " (Bản sao)");
    return create(clonedData);
  }

  /** Khôi phục phiên bản lịch sử JaVers (chặn rollback báo cáo đã phê duyệt). */
  @Transactional
  public BudgetReportData rollback(Long id, Long version) {
    BudgetReport existing =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(existing.getNodeId(), existing.getNodeCode());
    sm().assertMutableReport(existing.getWorkflowStatus(), "khôi phục phiên bản", existing.getCode());

    return auditRollbackHelper.executeRollback(
        BudgetReport.class,
        id,
        version,
        budgetReportService::findById,
        budgetReportService::save,
        budgetReportMapper::toData,
        budgetReportMapper::updateEntityFromData,
        BudgetReportData::setId,
        this::validateData);
  }

  /** Cập nhật thứ tự sắp xếp hàng loạt. */
  @Transactional
  public void bulkUpdateSortOrder(BulkSortOrderData data) {
    if (data == null || data.getUpdates() == null || data.getUpdates().isEmpty()) {
      return;
    }
    for (BulkSortOrderData.SortOrderUpdate item : data.getUpdates()) {
      budgetReportService
          .findById(item.getId())
          .ifPresent(
              entity -> {
                guard().assertNodeAccessIfPresent(entity.getNodeId(), entity.getNodeCode());
                entity.setSortOrder(item.getSortOrder());
                budgetReportService.save(entity);
              });
    }
  }

  private void validateData(BudgetReportData data) {
    BaseBusinessHelper.validateOrThrow(budgetReportValidator, data, "budgetReportData");
  }

  /** Tổng hợp tự động Báo cáo Thực hiện Thu - Chi & Quyết toán định kỳ từ Sổ cái Phân bổ. */
  public BudgetReportData generateConsolidatedReport(Map<String, Object> body) {
    int year = parseIntParam(body, "budgetYear", LocalDate.now().getYear());
    String reportPeriod =
        body != null && body.get("reportPeriod") != null
            ? String.valueOf(body.get("reportPeriod")).trim().toUpperCase()
            : "QUARTER";
    int periodNumber = parseIntParam(body, "periodNumber", 1);
    Long nodeId = parseLongParam(body, "nodeId");
    if (nodeId != null) {
      guard().assertNodeAccess(nodeId, null);
    }

    LocalDate[] range = resolvePeriodRange(year, reportPeriod, periodNumber, body);
    LocalDate fromDate = range[0];
    LocalDate toDate = range[1];

    OrgSubtreeAuthorizationHelper.OrgSubtreeScope callerScope = guard().resolveCurrentScope();
    List<BudgetAllocation> rawAllocs =
        nodeId != null
            ? budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(nodeId, year)
            : budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);
    List<BudgetAllocation> allocs = filterAllocationsByScope(rawAllocs, callerScope);

    List<BudgetTransaction> yearTxs =
        budgetTransactionRepo != null
            ? (nodeId != null
                ? budgetTransactionRepo.findByBudgetYearAndNodeIdOrderByIdDesc(year, nodeId)
                : budgetTransactionRepo.findByBudgetYearOrderByIdDesc(year))
            : List.of();
    List<BudgetDisbursement> yearDisbs =
        budgetDisbursementRepo != null
            ? (nodeId != null
                ? budgetDisbursementRepo.findByBudgetYearAndNodeIdOrderByIdDesc(year, nodeId)
                : budgetDisbursementRepo.findByBudgetYearOrderByIdDesc(year))
            : List.of();

    List<BudgetTransaction> periodTxs = new ArrayList<>();
    for (BudgetTransaction tx : yearTxs) {
      if (nodeId != null && !nodeId.equals(tx.getNodeId())) {
        continue;
      }
      if (!isAllowedByScope(callerScope, tx.getNodeId(), tx.getNodeCode())) {
        continue;
      }
      String txStatus = StrUtil.nullToEmpty(tx.getApprovalStatus()).trim().toUpperCase();
      if (!EFFECTIVE_TX_STATUSES.contains(txStatus)) {
        continue;
      }
      LocalDate txDate =
          tx.getTxDate() != null
              ? tx.getTxDate()
              : (tx.getCreatedAt() != null ? tx.getCreatedAt().toLocalDate() : null);
      if (txDate == null || (!txDate.isBefore(fromDate) && !txDate.isAfter(toDate))) {
        periodTxs.add(tx);
      }
    }

    List<BudgetDisbursement> periodDisbs = new ArrayList<>();
    for (BudgetDisbursement d : yearDisbs) {
      if (nodeId != null && !nodeId.equals(d.getNodeId())) {
        continue;
      }
      if (!isAllowedByScope(callerScope, d.getNodeId(), d.getNodeCode())) {
        continue;
      }
      String dStatus = StrUtil.nullToEmpty(d.getApprovalStatus()).trim().toUpperCase();
      if (!EFFECTIVE_DISB_STATUSES.contains(dStatus)) {
        continue;
      }
      LocalDate dDate =
          d.getVoucherDate() != null
              ? d.getVoucherDate()
              : (d.getSettledDate() != null
                  ? d.getSettledDate().toLocalDate()
                  : (d.getApprovedDate() != null ? d.getApprovedDate().toLocalDate() : null));
      if (dDate == null || (!dDate.isBefore(fromDate) && !dDate.isAfter(toDate))) {
        periodDisbs.add(d);
      }
    }

    BigDecimal revEst = BigDecimal.ZERO;
    BigDecimal revAct = BigDecimal.ZERO;
    BigDecimal expInit = BigDecimal.ZERO;
    BigDecimal expAdj = BigDecimal.ZERO;
    BigDecimal expEff = BigDecimal.ZERO;
    BigDecimal expComm = BigDecimal.ZERO;
    BigDecimal expAdv = BigDecimal.ZERO;
    BigDecimal expAct = BigDecimal.ZERO;
    BigDecimal expSettled = BigDecimal.ZERO;
    BigDecimal carryOver = BigDecimal.ZERO;
    BigDecimal remaining = BigDecimal.ZERO;
    BigDecimal expiredCancelled = BigDecimal.ZERO;

    boolean hasPeriodLedgerActivity = !periodTxs.isEmpty() || !periodDisbs.isEmpty();
    for (BudgetAllocation a : allocs) {
      if ("QUARTER".equals(reportPeriod)
          && a.getBudgetQuarter() != null
          && !Integer.valueOf(periodNumber).equals(a.getBudgetQuarter())) {
        continue;
      }
      BigDecimal init = safeDec(a.getInitialAmount());
      BigDecimal eff = safeDec(a.getEffectiveAmount());
      BigDecimal expCanc = safeDec(a.getExpiredCancelledAmount());
      BigDecimal avail = safeDec(a.getAvailableAmount());
      if ("REVENUE".equalsIgnoreCase(a.getItemType())) {
        revEst = revEst.add(eff);
        revAct = revAct.add(safeDec(a.getDisbursedAmount()));
      } else {
        expInit = expInit.add(init);
        expEff = expEff.add(eff);
        remaining = remaining.add(avail);
        expiredCancelled = expiredCancelled.add(expCanc);
        if (!hasPeriodLedgerActivity) {
          expAdj =
              expAdj.add(
                  safeDec(a.getSupplementAmount())
                      .add(safeDec(a.getTransferNetAmount()))
                      .subtract(safeDec(a.getReductionAmount())));
          // Chuyển nguồn ròng = nhận chuyển nguồn (in) - chuyển nguồn sang năm sau (out) (TD-H06)
          carryOver =
              carryOver.add(
                  safeDec(a.getCarryOverInAmount()).subtract(safeDec(a.getCarryOverOutAmount())));
          expComm = expComm.add(safeDec(a.getCommittedAmount()));
          expAdv = expAdv.add(safeDec(a.getAdvancedAmount()));
          expAct = expAct.add(safeDec(a.getDisbursedAmount()));
          expSettled = expSettled.add(safeDec(a.getSettledAmount()));
        }
      }
    }

    if (hasPeriodLedgerActivity) {
      for (BudgetTransaction tx : periodTxs) {
        String type = StrUtil.blankToDefault(tx.getTxType(), "").toUpperCase();
        BigDecimal amt = safeDec(tx.getAmount());
        switch (type) {
          case "SUPPLEMENT", "ADDITIONAL", "BATCH_PAYROLL_INDEXATION" -> expAdj = expAdj.add(amt);
          case "TRANSFER_IN" -> expAdj = expAdj.add(amt.abs());
          case "REDUCTION", "TRANSFER_OUT" -> expAdj = expAdj.subtract(amt.abs());
          case "INTERNAL_TRANSFER" ->
              expAdj =
                  "DEBIT".equalsIgnoreCase(tx.getEntryDirection())
                      ? expAdj.subtract(amt.abs())
                      : expAdj.add(amt.abs());
          case "CARRY_OVER_IN" -> carryOver = carryOver.add(amt.abs());
          case "CARRY_OVER_OUT" -> carryOver = carryOver.subtract(amt.abs());
          default -> {}
        }
      }
      BigDecimal advSettlementDeduction = BigDecimal.ZERO;
      for (BudgetDisbursement d : periodDisbs) {
        BigDecimal amt = safeDec(d.getAmount());
        BigDecimal sAmt =
            d.getSettledAmount() != null && d.getSettledAmount().compareTo(BigDecimal.ZERO) > 0
                ? d.getSettledAmount()
                : amt;
        String status = StrUtil.blankToDefault(d.getApprovalStatus(), "").toUpperCase();
        String dtype = StrUtil.blankToDefault(d.getDisbursementType(), "").toUpperCase();
        if ("COMMITMENT".equals(dtype) || "COMMITTED".equals(status)) {
          expComm = expComm.add(amt);
        } else if ("ADVANCE".equals(dtype)) {
          expAct = expAct.add(amt);
          if ("SETTLED".equals(status)) {
            expSettled = expSettled.add(sAmt);
            if (amt.compareTo(sAmt) > 0) {
              expAdv = expAdv.add(amt.subtract(sAmt));
            }
          } else {
            expAdv = expAdv.add(amt);
          }
        } else if ("ADVANCE_SETTLEMENT".equals(dtype)) {
          // Không cộng trùng vào expAct; ghi nhận quyết toán và khấu trừ dư tạm ứng
          expSettled = expSettled.add(sAmt);
          advSettlementDeduction = advSettlementDeduction.add(sAmt);
        } else if ("SETTLED".equals(status)) {
          expSettled = expSettled.add(sAmt);
          expAct = expAct.add(amt);
        } else {
          expAct = expAct.add(amt);
        }
      }
      if (advSettlementDeduction.compareTo(BigDecimal.ZERO) > 0) {
        expAdv = expAdv.subtract(advSettlementDeduction).max(BigDecimal.ZERO);
      }
    }

    revEst = scaleMoney(revEst);
    revAct = scaleMoney(revAct);
    expInit = scaleMoney(expInit);
    expAdj = scaleMoney(expAdj);
    expEff = scaleMoney(expEff);
    expComm = scaleMoney(expComm);
    expAdv = scaleMoney(expAdv);
    expAct = scaleMoney(expAct);
    expSettled = scaleMoney(expSettled);
    carryOver = scaleMoney(carryOver);
    remaining = scaleMoney(remaining);
    expiredCancelled = scaleMoney(expiredCancelled);

    BigDecimal rate =
        expEff.compareTo(BigDecimal.ZERO) > 0
            ? expAct.multiply(new BigDecimal("100")).divide(expEff, 2, RoundingMode.HALF_UP)
            : scaleMoney(BigDecimal.ZERO);
    BigDecimal saving = scaleMoney(expEff.subtract(expAct).max(BigDecimal.ZERO));

    String code =
        "BCKP-"
            + year
            + "-"
            + reportPeriod
            + periodNumber
            + "-"
            + (nodeId != null ? "DV" + nodeId : "TOANSO");

    String actorName = resolveAuthenticatedActorName();
    Long actorPersonId = resolveAuthenticatedPersonId();

    BudgetReportData data =
        BudgetReportData.builder()
            .code(code)
            .name(
                "Báo cáo Tổng hợp Thực hiện Dự toán Thu - Chi & Giải ngân "
                    + reportPeriod
                    + " "
                    + periodNumber
                    + "/"
                    + year)
            .description(
                "Tổng hợp tự động từ Sổ cái Phân bổ và Giải ngân Kho bạc ("
                    + fromDate
                    + " -> "
                    + toDate
                    + ")")
            .budgetYear(year)
            .reportPeriod(reportPeriod)
            .periodNumber(periodNumber)
            .periodLabel(reportPeriod + " " + periodNumber + "/" + year)
            .reportScope(nodeId != null ? "UNIT_REPORT" : "CONSOLIDATED_REPORT")
            .nodeId(nodeId)
            .nodeCode(nodeId != null ? "NODE-" + nodeId : "TOAN-SO")
            .nodeName(nodeId != null ? "Đơn vị dự toán #" + nodeId : "Toàn Sở / Đơn vị dự toán cấp I")
            .reportDate(LocalDate.now())
            .dataCutoffDate(toDate)
            .totalRevenueEstimate(revEst)
            .totalRevenueActual(revAct)
            .totalRevenueCumulative(revAct)
            .totalExpenditureInitial(expInit)
            .totalExpenditureAdjustment(expAdj)
            .totalExpenditureEffective(expEff)
            .totalExpenditureCommitted(expComm)
            .totalExpenditureAdvanced(expAdv)
            .totalExpenditureActual(expAct)
            .totalExpenditureSettled(expSettled)
            .totalExpenditureCumulative(expAct)
            .totalCarryOverAmount(carryOver)
            .totalSurplusOrSaving(saving)
            .totalRemainingBalance(remaining)
            .totalExpiredCancelled(expiredCancelled)
            .disbursementRatePercent(rate)
            .preparedByPersonId(actorPersonId)
            .preparedByPersonName(actorName)
            .workflowStatus("DRAFT")
            .status(LifecycleStatus.ACTIVE)
            .build();
    return create(data);
  }

  private LocalDate[] resolvePeriodRange(
      int year, String reportPeriod, int periodNumber, Map<String, Object> body) {
    try {
      if (body != null && body.get("fromDate") != null && body.get("toDate") != null) {
        return new LocalDate[] {
          LocalDate.parse(body.get("fromDate").toString().trim()),
          LocalDate.parse(body.get("toDate").toString().trim())
        };
      }
      if ("MONTH".equalsIgnoreCase(reportPeriod)) {
        int m = Math.max(1, Math.min(12, periodNumber));
        YearMonth ym = YearMonth.of(year, m);
        return new LocalDate[] {ym.atDay(1), ym.atEndOfMonth()};
      }
      if ("QUARTER".equalsIgnoreCase(reportPeriod)) {
        int q = Math.max(1, Math.min(4, periodNumber));
        int startMonth = (q - 1) * 3 + 1;
        YearMonth start = YearMonth.of(year, startMonth);
        YearMonth end = YearMonth.of(year, startMonth + 2);
        return new LocalDate[] {start.atDay(1), end.atEndOfMonth()};
      }
      if ("SIX_MONTH".equalsIgnoreCase(reportPeriod) || "HALF_YEAR".equalsIgnoreCase(reportPeriod)) {
        boolean secondHalf = periodNumber >= 2;
        return secondHalf
            ? new LocalDate[] {LocalDate.of(year, 7, 1), LocalDate.of(year, 12, 31)}
            : new LocalDate[] {LocalDate.of(year, 1, 1), LocalDate.of(year, 6, 30)};
      }
      LocalDate cutoff =
          body != null && body.get("dataCutoffDate") != null
              ? LocalDate.parse(body.get("dataCutoffDate").toString().trim())
              : LocalDate.of(year, 12, 31);
      return new LocalDate[] {LocalDate.of(year, 1, 1), cutoff};
    } catch (DateTimeParseException e) {
      throw new InvalidDataException(
          "Định dạng ngày kỳ báo cáo không hợp lệ (chuẩn yyyy-MM-dd).", null);
    }
  }

  /** Trình duyệt báo cáo quyết toán định kỳ (kích hoạt BPMN Workflow). */
  public BudgetReportData submitReport(Long id, Map<String, Object> body) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(rpt.getNodeId(), rpt.getNodeCode());
    sm().assertReportTransition(rpt.getWorkflowStatus(), "PENDING_APPROVAL", rpt.getCode());

    rpt.setWorkflowStatus("PENDING_APPROVAL");
    rpt.setSubmittedDate(LocalDateTime.now());
    String actorName = resolveAuthenticatedActorName();
    Long actorId = resolveAuthenticatedPersonId();
    if (actorId != null) {
      rpt.setSubmittedByPersonId(actorId);
    }
    rpt.setSubmittedByPersonName(actorName);
    BudgetReport saved = budgetReportService.save(rpt);
    if (integrationOrchestrator != null) {
      Long wfId = integrationOrchestrator.startReportWorkflow(saved);
      if (wfId != null) {
        saved.setWfInstanceId(wfId);
        if (StrUtil.isBlank(saved.getWfProcessCode())) {
          saved.setWfProcessCode(BudgetIntegrationOrchestrator.WF_PROCESS_REPORT);
        }
        saved = budgetReportService.save(saved);
      }
    }
    return enrichData(budgetReportMapper.toData(saved));
  }

  /** Từ chối / Trả lại báo cáo quyết toán định kỳ. */
  public BudgetReportData rejectReport(Long id, Map<String, Object> body) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(rpt.getNodeId(), rpt.getNodeCode());
    sm().assertReportTransition(rpt.getWorkflowStatus(), "REJECTED", rpt.getCode());

    rpt.setWorkflowStatus("REJECTED");
    String comment = "";
    if (body != null && body.get("comment") != null) {
      comment = String.valueOf(body.get("comment"));
      rpt.setReviewComment(comment);
    } else if (body != null && body.get("reason") != null) {
      comment = String.valueOf(body.get("reason"));
      rpt.setReviewComment(comment);
    }
    BudgetReport saved = budgetReportService.save(rpt);
    if (integrationOrchestrator != null) {
      integrationOrchestrator.executeWorkflowAction(getAppCode(), saved.getId(), "REJECT", comment);
    }
    return enrichData(budgetReportMapper.toData(saved));
  }

  /** Trả về chỉnh sửa (REWORK) báo cáo từ luồng BPMN Workflow. */
  public BudgetReportData reworkReport(Long id, String comment) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(rpt.getNodeId(), rpt.getNodeCode());
    sm().assertReportTransition(rpt.getWorkflowStatus(), "REWORK", rpt.getCode());

    rpt.setWorkflowStatus("REWORK");
    if (StrUtil.isNotBlank(comment)) {
      rpt.setReviewComment(comment);
    }
    return enrichData(budgetReportMapper.toData(budgetReportService.save(rpt)));
  }

  /**
   * Lưu kết quả xác nhận đối chiếu số dư Kho bạc Nhà nước cho báo cáo kỳ (yêu cầu quyền
   * {@code BudgetReportReconcile} và cưỡng chế SoD 4 mắt: người đối chiếu/thẩm định != người lập/trình).
   */
  public BudgetReportData confirmTreasuryReconciliation(Long id, Map<String, Object> body) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(rpt.getNodeId(), rpt.getNodeCode());
    Long creatorOrSubmitterId =
        rpt.getSubmittedByPersonId() != null
            ? rpt.getSubmittedByPersonId()
            : rpt.getPreparedByPersonId();
    guard()
        .assertFourEyesReview(
            "báo cáo quyết toán", rpt.getCode(), creatorOrSubmitterId, rpt.getCreatedBy());

    Integer q = rpt.getPeriodNumber() != null ? rpt.getPeriodNumber() : 4;
    Map<String, Object> statement =
        generateKbnnReconciliationStatement(rpt.getBudgetYear(), q, rpt.getNodeId());
    rpt.setReconciliationStatementJson(JSONUtil.toJsonStr(statement));
    String confirmCode =
        body != null && body.get("treasuryConfirmationCode") != null
            ? String.valueOf(body.get("treasuryConfirmationCode"))
            : "KBNN-" + rpt.getBudgetYear() + "-Q" + q + "-" + rpt.getId();
    rpt.setTreasuryConfirmationCode(confirmCode);
    rpt.setTreasuryConfirmedDate(LocalDate.now());
    Long reviewerId = resolveAuthenticatedPersonId();
    if (reviewerId != null) {
      rpt.setReviewedByPersonId(reviewerId);
    }
    rpt.setReviewedByPersonName(resolveAuthenticatedActorName());
    return enrichData(budgetReportMapper.toData(budgetReportService.save(rpt)));
  }

  /**
   * Phê duyệt báo cáo quyết toán định kỳ (cưỡng chế SoD 4 mắt: {@code creator/submitter !=
   * reviewer != approver}, tuyệt đối không ghi đè {@code reviewedByPersonId} bằng {@code
   * approvedByPersonId}).
   */
  public BudgetReportData approveReport(Long id, Map<String, Object> body) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(rpt.getNodeId(), rpt.getNodeCode());
    sm().assertReportTransition(rpt.getWorkflowStatus(), "APPROVED", rpt.getCode());

    Long creatorOrSubmitterId =
        rpt.getSubmittedByPersonId() != null
            ? rpt.getSubmittedByPersonId()
            : rpt.getPreparedByPersonId();
    guard()
        .assertFourEyesApproval(
            "báo cáo quyết toán",
            rpt.getCode(),
            creatorOrSubmitterId,
            rpt.getCreatedBy(),
            rpt.getReviewedByPersonId());

    rpt.setWorkflowStatus("APPROVED");
    rpt.setApprovedDate(LocalDateTime.now());
    String actorName = resolveAuthenticatedActorName();
    Long actorId = resolveAuthenticatedPersonId();
    if (actorId != null) {
      rpt.setApprovedByPersonId(actorId);
    }
    rpt.setApprovedByPersonName(actorName);
    BudgetReport saved = budgetReportService.save(rpt);
    if (integrationOrchestrator != null) {
      integrationOrchestrator.executeWorkflowAction(getAppCode(), saved.getId(), "APPROVE", "");
    }
    return enrichData(budgetReportMapper.toData(saved));
  }

  /**
   * Kết xuất Biểu mẫu Đối chiếu Dự toán & Tình hình Sử dụng Kinh phí tại Kho bạc Nhà nước (Mẫu số
   * 01a/SDKP-ĐVDT & Mẫu số 02/SDKP-ĐVDT). Đã tối ưu prefetch {@link BudgetUnitConfig} & gom nhóm
   * {@link BudgetDisbursement} theo Map để triệt tiêu N+1 Query (TD-H12).
   */
  @Transactional(readOnly = true)
  public Map<String, Object> generateKbnnReconciliationStatement(
      Integer budgetYear, Integer quarter, Long nodeId) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    int q = quarter != null && quarter >= 1 && quarter <= 4 ? quarter : 4;
    if (nodeId != null) {
      guard().assertNodeAccess(nodeId, null);
    }
    OrgSubtreeAuthorizationHelper.OrgSubtreeScope callerScope = guard().resolveCurrentScope();

    List<BudgetAllocation> allocs =
        filterAllocationsByScope(
            nodeId != null
                ? budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(nodeId, year)
                : budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year),
            callerScope);
    List<BudgetDisbursement> disbs =
        budgetDisbursementRepo != null
            ? (nodeId != null
                ? budgetDisbursementRepo.findByBudgetYearAndNodeIdOrderByIdDesc(year, nodeId)
                : budgetDisbursementRepo.findByBudgetYearOrderByIdDesc(year))
            : List.of();

    // Prefetch BudgetUnitConfig một lần cho toàn bộ nodeIds (chống N+1 query)
    Set<Long> nodeIds = new LinkedHashSet<>();
    if (nodeId != null) {
      nodeIds.add(nodeId);
    }
    for (BudgetAllocation a : allocs) {
      if (a.getNodeId() != null) {
        nodeIds.add(a.getNodeId());
      }
    }
    Map<Long, BudgetUnitConfig> unitConfigByNodeId = new HashMap<>();
    if (budgetUnitConfigRepo != null && !nodeIds.isEmpty()) {
      List<BudgetUnitConfig> configs = budgetUnitConfigRepo.findByNodeIdIn(nodeIds);
      if (configs != null) {
        for (BudgetUnitConfig cfg : configs) {
          if (cfg.getNodeId() != null) {
            unitConfigByNodeId.merge(
                cfg.getNodeId(),
                cfg,
                (prev, curr) ->
                    (curr.getId() != null
                            && (prev.getId() == null || curr.getId() > prev.getId()))
                        ? curr
                        : prev);
          }
        }
      }
    }

    // Gom nhóm giải ngân theo allocationId & allocationCode một lần (O(N+M) thay vì O(N*M))
    Map<Long, List<BudgetDisbursement>> disbsByAllocId = new HashMap<>();
    Map<String, List<BudgetDisbursement>> disbsByAllocCode = new HashMap<>();
    int matchedVouchers = 0;
    int pendingVouchers = 0;
    for (BudgetDisbursement d : disbs) {
      if (!isAllowedByScope(callerScope, d.getNodeId(), d.getNodeCode())) {
        continue;
      }
      if ("REJECTED".equalsIgnoreCase(d.getApprovalStatus())
          || "CANCELLED".equalsIgnoreCase(d.getApprovalStatus())
          || "DRAFT".equalsIgnoreCase(d.getApprovalStatus())) {
        continue;
      }
      if (quarter != null && !matchesDisbursementQuarter(d, quarter)) {
        continue;
      }
      if (d.getAllocationId() != null) {
        disbsByAllocId.computeIfAbsent(d.getAllocationId(), k -> new ArrayList<>()).add(d);
      } else if (StrUtil.isNotBlank(d.getAllocationCode())) {
        disbsByAllocCode
            .computeIfAbsent(d.getAllocationCode().trim().toUpperCase(), k -> new ArrayList<>())
            .add(d);
      }
      if (nodeId == null || nodeId.equals(d.getNodeId())) {
        if (StrUtil.isNotBlank(d.getTreasuryRefCode())) {
          matchedVouchers++;
        } else {
          pendingVouchers++;
        }
      }
    }

    List<Map<String, Object>> form01aRows = new ArrayList<>();
    BigDecimal totalInit = BigDecimal.ZERO;
    BigDecimal totalSupplement = BigDecimal.ZERO;
    BigDecimal totalCarryOver = BigDecimal.ZERO;
    BigDecimal totalEffective = BigDecimal.ZERO;
    BigDecimal totalCommitted = BigDecimal.ZERO;
    BigDecimal totalAdvanced = BigDecimal.ZERO;
    BigDecimal totalDisbursed = BigDecimal.ZERO;
    BigDecimal totalSettled = BigDecimal.ZERO;
    BigDecimal totalAvailable = BigDecimal.ZERO;

    Long resolvedUnitNodeId = nodeId;
    for (BudgetAllocation a : allocs) {
      if ("REVENUE".equalsIgnoreCase(a.getItemType())) {
        continue;
      }
      if (quarter != null
          && a.getBudgetQuarter() != null
          && !quarter.equals(a.getBudgetQuarter())) {
        continue;
      }
      if (resolvedUnitNodeId == null && a.getNodeId() != null) {
        resolvedUnitNodeId = a.getNodeId();
      }
      BigDecimal init = scaleMoney(safeDec(a.getInitialAmount()));
      BigDecimal supp =
          scaleMoney(
              safeDec(a.getSupplementAmount())
                  .add(safeDec(a.getTransferNetAmount()))
                  .subtract(safeDec(a.getReductionAmount())));
      BigDecimal co =
          scaleMoney(
              safeDec(a.getCarryOverInAmount()).subtract(safeDec(a.getCarryOverOutAmount())));
      BigDecimal eff = scaleMoney(safeDec(a.getEffectiveAmount()));
      BigDecimal comm = scaleMoney(safeDec(a.getCommittedAmount()));
      BigDecimal adv = scaleMoney(safeDec(a.getAdvancedAmount()));
      BigDecimal disb = scaleMoney(safeDec(a.getDisbursedAmount()));
      BigDecimal settled =
          scaleMoney(
              safeDec(a.getSettledAmount()).compareTo(BigDecimal.ZERO) > 0
                  ? safeDec(a.getSettledAmount())
                  : disb);
      BigDecimal avail = scaleMoney(safeDec(a.getAvailableAmount()));

      totalInit = totalInit.add(init);
      totalSupplement = totalSupplement.add(supp);
      totalCarryOver = totalCarryOver.add(co);
      totalEffective = totalEffective.add(eff);
      totalCommitted = totalCommitted.add(comm);
      totalAdvanced = totalAdvanced.add(adv);
      totalDisbursed = totalDisbursed.add(disb);
      totalSettled = totalSettled.add(settled);
      totalAvailable = totalAvailable.add(avail);

      String unitChapterCode = "423";
      BudgetUnitConfig uCfg = a.getNodeId() != null ? unitConfigByNodeId.get(a.getNodeId()) : null;
      if (uCfg == null && budgetUnitConfigRepo != null && a.getNodeId() != null) {
        uCfg = budgetUnitConfigRepo.findFirstByNodeIdOrderByIdDesc(a.getNodeId()).orElse(null);
      }
      if (uCfg != null && StrUtil.isNotBlank(uCfg.getDefaultChapterCode())) {
        unitChapterCode = uCfg.getDefaultChapterCode();
      }

      List<BudgetDisbursement> allocDisbs = new ArrayList<>();
      if (a.getId() != null && disbsByAllocId.containsKey(a.getId())) {
        allocDisbs.addAll(disbsByAllocId.get(a.getId()));
      }
      if (StrUtil.isNotBlank(a.getCode())) {
        List<BudgetDisbursement> byCode =
            disbsByAllocCode.get(a.getCode().trim().toUpperCase());
        if (byCode != null) {
          allocDisbs.addAll(byCode);
        }
      }
      int allocMatched = 0;
      int allocPending = 0;
      for (BudgetDisbursement d : allocDisbs) {
        if (StrUtil.isNotBlank(d.getTreasuryRefCode())) {
          allocMatched++;
        } else {
          allocPending++;
        }
      }
      String rowReconciledStatus =
          (allocMatched + allocPending) == 0
              ? "NO_DISBURSEMENT"
              : (allocPending == 0 ? "MATCHED_KBNN" : "PENDING_RECONCILIATION");

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("allocationCode", a.getCode());
      row.put("unitCode", a.getNodeCode());
      row.put("unitName", a.getNodeName());
      row.put("nodeCode", a.getNodeCode());
      row.put("nodeName", a.getNodeName());
      row.put("chapterCode", unitChapterCode);
      row.put("clauseCode", StrUtil.blankToDefault(a.getCategoryCode(), "281"));
      row.put("categoryCode", StrUtil.blankToDefault(a.getCategoryCode(), "6000"));
      row.put("categoryName", StrUtil.blankToDefault(a.getCategoryName(), "Chi thường xuyên"));
      row.put(
          "fundSource",
          StrUtil.blankToDefault(a.getFundingSourceCode(), "STATE_BUDGET_AUTONOMY"));
      row.put("initialEstimate", init);
      row.put("supplementAndAdjustment", supp);
      row.put("carryOverNet", co);
      row.put("effectiveEstimate", eff);
      row.put("committedAmount", comm);
      row.put("disbursedAdvanceBalance", adv);
      row.put("disbursedAtTreasury", disb);
      row.put("settledActualExpenditure", settled);
      row.put("remainingBalance", avail);
      row.put("remainingEstimateBalance", avail);
      row.put("matchedVoucherCount", allocMatched);
      row.put("pendingVoucherCount", allocPending);
      row.put("reconciledStatus", rowReconciledStatus);
      form01aRows.add(row);
    }

    String treasuryAgencyName = "Kho bạc Nhà nước Thành phố Đà Nẵng";
    BudgetUnitConfig headerCfg =
        resolvedUnitNodeId != null ? unitConfigByNodeId.get(resolvedUnitNodeId) : null;
    if (headerCfg == null && budgetUnitConfigRepo != null && resolvedUnitNodeId != null) {
      headerCfg =
          budgetUnitConfigRepo.findFirstByNodeIdOrderByIdDesc(resolvedUnitNodeId).orElse(null);
    }
    if (headerCfg != null && StrUtil.isNotBlank(headerCfg.getTreasuryBranchName())) {
      treasuryAgencyName = headerCfg.getTreasuryBranchName();
    }

    Map<String, Object> statement = new LinkedHashMap<>();
    statement.put("formCode", "01a/SDKP-ĐVDT & 02/SDKP-ĐVDT");
    statement.put("circularRef", "Nghị định 60/2021/NĐ-CP & Thông tư 342/2016/TT-BTC");
    statement.put("budgetYear", year);
    statement.put("quarter", q);
    statement.put("generatedDate", LocalDate.now().toString());
    statement.put("treasuryAgency", treasuryAgencyName);
    statement.put("treasuryAgencyName", treasuryAgencyName);
    statement.put("totalInitialEstimate", scaleMoney(totalInit));
    statement.put("totalSupplementAndAdjustment", scaleMoney(totalSupplement));
    statement.put("totalCarryOverNet", scaleMoney(totalCarryOver));
    statement.put("totalEffectiveEstimate", scaleMoney(totalEffective));
    statement.put("totalCommittedAmount", scaleMoney(totalCommitted));
    statement.put("totalDisbursedAdvanceBalance", scaleMoney(totalAdvanced));
    statement.put("totalDisbursedAtTreasury", scaleMoney(totalDisbursed));
    statement.put("totalSettledActualExpenditure", scaleMoney(totalSettled));
    statement.put("totalRemainingBalance", scaleMoney(totalAvailable));
    statement.put("matchedVoucherCount", matchedVouchers);
    statement.put("pendingVoucherCount", pendingVouchers);
    statement.put("rows", form01aRows);
    return statement;
  }

  private boolean matchesDisbursementQuarter(BudgetDisbursement d, Integer quarter) {
    if (quarter == null) {
      return true;
    }
    if (d.getBudgetQuarter() != null) {
      return quarter.equals(d.getBudgetQuarter());
    }
    if (d.getVoucherDate() != null) {
      return ((d.getVoucherDate().getMonthValue() - 1) / 3 + 1) == quarter;
    }
    return true;
  }

  /**
   * Kết xuất Ma trận Động Đa chiều theo Lĩnh vực / Nhóm lĩnh vực / Loại-Khoản (Dynamic Domain/Group
   * Matrix). Bỏ nhánh hardcode {@code isSnapshotOct23}: mặc định dùng {@code CUMULATIVE_EFFECTIVE}
   * trừ khi caller truyền {@code viewMode} tường minh (TD-H06).
   */
  @Transactional(readOnly = true)
  public Map<String, Object> generateDynamicDomainMatrix(
      Integer budgetYear,
      String asOfDate,
      String groupByDimension,
      String viewMode,
      Long nodeId) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    if (nodeId != null) {
      guard().assertNodeAccess(nodeId, null);
    }
    OrgSubtreeAuthorizationHelper.OrgSubtreeScope callerScope = guard().resolveCurrentScope();
    String dimension =
        StrUtil.isNotBlank(groupByDimension) ? groupByDimension.trim().toUpperCase() : "DOMAIN";
    String resolvedDate =
        StrUtil.isNotBlank(asOfDate) ? asOfDate.trim() : LocalDate.now().toString();
    String resolvedViewMode =
        StrUtil.isNotBlank(viewMode) ? viewMode.trim().toUpperCase() : "CUMULATIVE_EFFECTIVE";

    List<BudgetAllocation> allocs =
        filterAllocationsByScope(
            nodeId != null
                ? budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(nodeId, year)
                : budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year),
            callerScope);
    List<BudgetTransaction> rawTxs =
        budgetTransactionRepo != null
            ? (nodeId != null
                ? budgetTransactionRepo.findByBudgetYearAndNodeIdOrderByIdDesc(year, nodeId)
                : budgetTransactionRepo.findByBudgetYearOrderByIdDesc(year))
            : List.of();
    List<BudgetTransaction> txs = new ArrayList<>();
    for (BudgetTransaction tx : rawTxs) {
      if (isAllowedByScope(callerScope, tx.getNodeId(), tx.getNodeCode())) {
        txs.add(tx);
      }
    }

    LocalDate cutoffDate = null;
    try {
      if (resolvedDate.contains("-")) {
        cutoffDate = LocalDate.parse(resolvedDate);
      }
    } catch (Exception ignored) {
      // Ignore parse error, keep null cutoffDate
    }

    // Tải cấu hình Lĩnh vực / Nhóm lĩnh vực theo đúng Năm ngân sách (budgetYear)
    List<BudgetCategory> allYearConfigs =
        budgetCategoryRepo != null
            ? budgetCategoryRepo.findByBudgetYearAndCategoryGroupInOrderBySortOrderAscIdAsc(
                year, List.of("DOMAIN_GROUP", "DOMAIN"))
            : List.of();
    List<BudgetCategory> yearDomainConfigs = allYearConfigs;
    if (nodeId != null && !allYearConfigs.isEmpty()) {
      List<BudgetCategory> unitSpecific =
          allYearConfigs.stream()
              .filter(c -> c.getNodeId() != null && nodeId.equals(c.getNodeId()))
              .toList();
      if (!unitSpecific.isEmpty()) {
        yearDomainConfigs = unitSpecific;
      } else if (!allocs.isEmpty()) {
        // Đơn vị có dữ liệu phân bổ riêng (ví dụ kiểm thử theo nodeId độc lập) -> chỉ lấy cấu hình khớp hoặc tự phát hiện từ phân bổ
        yearDomainConfigs = List.of();
      }
    }

    return buildDynamicMatrixFromDatabase(
        year,
        resolvedDate,
        cutoffDate,
        dimension,
        resolvedViewMode,
        allocs,
        txs,
        yearDomainConfigs);
  }

  private Map<String, Object> buildDynamicMatrixFromDatabase(
      int year,
      String asOfDate,
      LocalDate cutoffDate,
      String dimension,
      String viewMode,
      List<BudgetAllocation> allocs,
      List<BudgetTransaction> txs,
      List<BudgetCategory> yearDomainConfigs) {
    Map<String, Map<String, Object>> colMetaMap = new LinkedHashMap<>();
    Map<String, String> prevDomainToCurrentMap = new LinkedHashMap<>();
    Map<String, String> domainToGroupMap = new LinkedHashMap<>();

    if (yearDomainConfigs != null && !yearDomainConfigs.isEmpty()) {
      for (BudgetCategory cfg : yearDomainConfigs) {
        String dCode =
            StrUtil.isNotBlank(cfg.getDomainCode()) ? cfg.getDomainCode().trim() : cfg.getCode();
        if (StrUtil.isNotBlank(cfg.getPreviousYearDomainCode()) && StrUtil.isNotBlank(dCode)) {
          prevDomainToCurrentMap.put(cfg.getPreviousYearDomainCode().trim(), dCode);
        }
        if ("DOMAIN".equalsIgnoreCase(cfg.getCategoryGroup())
            && StrUtil.isNotBlank(dCode)
            && StrUtil.isNotBlank(cfg.getDomainGroupCode())) {
          domainToGroupMap.put(dCode, cfg.getDomainGroupCode().trim());
        }
      }

      if ("DOMAIN_GROUP".equalsIgnoreCase(dimension)) {
        for (BudgetCategory cfg : yearDomainConfigs) {
          if ("DOMAIN_GROUP".equalsIgnoreCase(cfg.getCategoryGroup())) {
            String gCode =
                StrUtil.isNotBlank(cfg.getDomainCode())
                    ? cfg.getDomainCode().trim()
                    : cfg.getCode();
            Map<String, Object> meta =
                createColMeta(gCode, cfg.getName(), gCode, cfg.getName());
            meta.put("budgetYear", year);
            meta.put("previousYearDomainCode", cfg.getPreviousYearDomainCode());
            colMetaMap.putIfAbsent(gCode, meta);
          }
        }
      } else if (!"CATEGORY".equalsIgnoreCase(dimension)) {
        for (BudgetCategory cfg : yearDomainConfigs) {
          if ("DOMAIN".equalsIgnoreCase(cfg.getCategoryGroup())) {
            String dCode =
                StrUtil.isNotBlank(cfg.getDomainCode())
                    ? cfg.getDomainCode().trim()
                    : cfg.getCode();
            String gCode = StrUtil.blankToDefault(cfg.getDomainGroupCode(), "RECURRENT");
            String gName = StrUtil.blankToDefault(cfg.getDomainGroupName(), "Chi thường xuyên");
            Map<String, Object> meta = createColMeta(dCode, cfg.getName(), gCode, gName);
            meta.put("budgetYear", year);
            meta.put("previousYearDomainCode", cfg.getPreviousYearDomainCode());
            meta.put("clauseCode", cfg.getClauseCode());
            meta.put("annualLimitAmount", cfg.getAnnualLimitAmount());
            colMetaMap.putIfAbsent(dCode, meta);
          }
        }
      }
    }

    for (BudgetAllocation a : allocs) {
      if ("REVENUE".equalsIgnoreCase(a.getItemType())) {
        continue;
      }
      registerDynamicColumnsFromAllocation(a, dimension, colMetaMap);
    }
    for (BudgetTransaction tx : txs) {
      if (cutoffDate != null && tx.getTxDate() != null && tx.getTxDate().isAfter(cutoffDate)) {
        continue;
      }
      registerDynamicColumnsFromTransaction(tx, dimension, colMetaMap);
    }

    if (colMetaMap.isEmpty()) {
      Map<String, Object> defaultCol = new LinkedHashMap<>();
      defaultCol.put("code", "GENERAL");
      defaultCol.put("name", "Chi thường xuyên chung");
      defaultCol.put("groupCode", "RECURRENT");
      defaultCol.put("groupName", "Chi thường xuyên");
      colMetaMap.put("GENERAL", defaultCol);
    }

    List<String> colCodes = new ArrayList<>(colMetaMap.keySet());
    List<Map<String, Object>> rows = new ArrayList<>();

    // Khối 1: Chuyển nguồn, Giao đầu năm (hoặc Lũy kế), Bổ sung điều chỉnh
    Map<String, BigDecimal> agencyTotalAllocByCol = initColDecimalMap(colCodes);
    Map<String, BigDecimal> agencyTotalDisbByCol = initColDecimalMap(colCodes);
    Map<String, BigDecimal> carryTotalAllocByCol = initColDecimalMap(colCodes);
    Map<String, BigDecimal> carryTotalDisbByCol = initColDecimalMap(colCodes);
    Map<String, BigDecimal> initTotalAllocByCol = initColDecimalMap(colCodes);
    Map<String, BigDecimal> initTotalDisbByCol = initColDecimalMap(colCodes);
    Map<String, BigDecimal> adjTotalAllocByCol = initColDecimalMap(colCodes);
    Map<String, BigDecimal> adjTotalDisbByCol = initColDecimalMap(colCodes);

    List<Map<String, Object>> carryUnitRows = new ArrayList<>();
    List<Map<String, Object>> initUnitRows = new ArrayList<>();

    Map<String, List<BudgetAllocation>> allocsByUnit = new LinkedHashMap<>();
    for (BudgetAllocation a : allocs) {
      if ("REVENUE".equalsIgnoreCase(a.getItemType())) {
        continue;
      }
      String uKey = StrUtil.blankToDefault(a.getNodeCode(), "UNIT_" + a.getNodeId());
      allocsByUnit.computeIfAbsent(uKey, k -> new ArrayList<>()).add(a);
    }

    Map<String, List<BudgetTransaction>> txByUnit = new LinkedHashMap<>();
    for (BudgetTransaction tx : txs) {
      if ("INITIAL_ALLOCATION".equalsIgnoreCase(tx.getTxType())
          || "CARRY_OVER_IN".equalsIgnoreCase(tx.getTxType())
          || "REJECTED".equalsIgnoreCase(tx.getApprovalStatus())) {
        continue;
      }
      if (cutoffDate != null && tx.getTxDate() != null && tx.getTxDate().isAfter(cutoffDate)) {
        continue;
      }
      String uKey = StrUtil.blankToDefault(tx.getNodeCode(), "UNIT_" + tx.getNodeId());
      txByUnit.computeIfAbsent(uKey, k -> new ArrayList<>()).add(tx);
    }

    for (Map.Entry<String, List<BudgetAllocation>> entry : allocsByUnit.entrySet()) {
      String uKey = entry.getKey();
      List<BudgetAllocation> uAllocs = entry.getValue();
      String uCode = uAllocs.getFirst().getNodeCode();
      String uName = StrUtil.blankToDefault(uAllocs.getFirst().getNodeName(), uCode);

      Map<String, BigDecimal> uCarryAlloc = initColDecimalMap(colCodes);
      Map<String, BigDecimal> uCarryDisb = initColDecimalMap(colCodes);
      Map<String, BigDecimal> uInitAlloc = initColDecimalMap(colCodes);
      Map<String, BigDecimal> uInitDisb = initColDecimalMap(colCodes);
      Map<String, BigDecimal> uAdjAlloc = initColDecimalMap(colCodes);
      Map<String, BigDecimal> uAdjDisb = initColDecimalMap(colCodes);

      for (BudgetAllocation a : uAllocs) {
        String cCode =
            resolveColumnKeyForAllocation(
                a, dimension, colCodes.getFirst(), prevDomainToCurrentMap, domainToGroupMap);
        BigDecimal coIn = safeDec(a.getCarryOverInAmount());
        BigDecimal init = safeDec(a.getInitialAmount());
        BigDecimal adj =
            safeDec(a.getSupplementAmount())
                .add(safeDec(a.getTransferNetAmount()))
                .subtract(safeDec(a.getReductionAmount()));
        BigDecimal disb = safeDec(a.getDisbursedAmount());

        addColValue(uCarryAlloc, cCode, coIn);
        addColValue(uInitAlloc, cCode, init);
        addColValue(uAdjAlloc, cCode, adj);
        if (coIn.compareTo(BigDecimal.ZERO) > 0 && init.compareTo(BigDecimal.ZERO) == 0) {
          addColValue(uCarryDisb, cCode, disb);
        } else {
          addColValue(uInitDisb, cCode, disb);
        }

        addColValue(carryTotalAllocByCol, cCode, coIn);
        addColValue(initTotalAllocByCol, cCode, init);
        addColValue(adjTotalAllocByCol, cCode, adj);
        addColValue(agencyTotalAllocByCol, cCode, coIn.add(init).add(adj));
        addColValue(agencyTotalDisbByCol, cCode, disb);
      }

      if (sumColMap(uAdjAlloc).compareTo(BigDecimal.ZERO) == 0 && txByUnit.containsKey(uKey)) {
        for (BudgetTransaction tx : txByUnit.get(uKey)) {
          Map<String, BigDecimal> txAllocMap = extractTxBreakdownAlloc(tx, dimension, colCodes);
          Map<String, BigDecimal> txDisbMap = extractTxBreakdownDisb(tx, dimension, colCodes);
          for (String c : colCodes) {
            BigDecimal aDelta = txAllocMap.getOrDefault(c, BigDecimal.ZERO);
            BigDecimal dDelta = txDisbMap.getOrDefault(c, BigDecimal.ZERO);
            addColValue(uAdjAlloc, c, aDelta);
            addColValue(uAdjDisb, c, dDelta);
            addColValue(adjTotalAllocByCol, c, aDelta);
            addColValue(adjTotalDisbByCol, c, dDelta);
            addColValue(agencyTotalAllocByCol, c, aDelta);
            addColValue(agencyTotalDisbByCol, c, dDelta);
          }
        }
      }

      BigDecimal sumCarry = sumColMap(uCarryAlloc);
      if (sumCarry.compareTo(BigDecimal.ZERO) != 0) {
        for (String c : colCodes) {
          addColValue(carryTotalDisbByCol, c, uCarryDisb.get(c));
        }
        carryUnitRows.add(
            buildMatrixRow("-", "UNIT_SUMMARY", uCode, uName, uName, uCarryAlloc, uCarryDisb, ""));
      }

      Map<String, BigDecimal> displayInitAlloc = initColDecimalMap(colCodes);
      Map<String, BigDecimal> displayInitDisb = initColDecimalMap(colCodes);
      for (String c : colCodes) {
        if ("CUMULATIVE_EFFECTIVE".equalsIgnoreCase(viewMode)) {
          displayInitAlloc.put(c, uCarryAlloc.get(c).add(uInitAlloc.get(c)).add(uAdjAlloc.get(c)));
          displayInitDisb.put(c, uCarryDisb.get(c).add(uInitDisb.get(c)).add(uAdjDisb.get(c)));
        } else {
          displayInitAlloc.put(c, uInitAlloc.get(c));
          displayInitDisb.put(c, uInitDisb.get(c));
        }
        addColValue(initTotalDisbByCol, c, displayInitDisb.get(c));
      }
      initUnitRows.add(
          buildMatrixRow(
              "-", "UNIT_SUMMARY", uCode, uName, uName, displayInitAlloc, displayInitDisb, ""));
    }

    rows.add(
        buildMatrixRow(
            "1",
            "AGENCY_HEADER",
            "AGENCY_ROOT",
            "Tổng hợp Toàn Cơ quan / Đơn vị dự toán cấp I",
            "Tổng hợp Toàn Cơ quan / Đơn vị dự toán cấp I",
            agencyTotalAllocByCol,
            agencyTotalDisbByCol,
            ""));
    rows.add(
        buildMatrixRow(
            "-",
            "BLOCK_HEADER",
            "BLOCK_CARRY_OVER",
            "Chuyển nguồn " + year,
            "Chuyển nguồn " + year,
            carryTotalAllocByCol,
            carryTotalDisbByCol,
            ""));
    rows.addAll(carryUnitRows);

    String initHeaderTitle =
        "CUMULATIVE_EFFECTIVE".equalsIgnoreCase(viewMode)
            ? "Dự toán giao đầu năm (kể cả chuyển nguồn, điều chỉnh bổ sung trong năm)"
            : "Dự toán giao đầu năm";
    rows.add(
        buildMatrixRow(
            "-",
            "BLOCK_HEADER",
            "BLOCK_INITIAL",
            initHeaderTitle,
            initHeaderTitle,
            "CUMULATIVE_EFFECTIVE".equalsIgnoreCase(viewMode)
                ? agencyTotalAllocByCol
                : initTotalAllocByCol,
            "CUMULATIVE_EFFECTIVE".equalsIgnoreCase(viewMode)
                ? agencyTotalDisbByCol
                : initTotalDisbByCol,
            ""));
    rows.addAll(initUnitRows);

    rows.add(
        buildMatrixRow(
            "-",
            "BLOCK_HEADER",
            "BLOCK_ADJUSTMENT",
            "Dự toán bổ sung điều chỉnh trong năm",
            "Dự toán bổ sung điều chỉnh trong năm",
            adjTotalAllocByCol,
            adjTotalDisbByCol,
            ""));

    // Phần II: Chi tiết từng đợt biến động/điều chỉnh theo đơn vị từ BudgetTransaction
    int sectionIndex = 2;
    List<Map<String, Object>> unitSections = new ArrayList<>();
    for (Map.Entry<String, List<BudgetTransaction>> entry : txByUnit.entrySet()) {
      List<BudgetTransaction> uTxs = entry.getValue();
      String uCode = uTxs.getFirst().getNodeCode();
      String uName = StrUtil.blankToDefault(uTxs.getFirst().getNodeName(), uCode);
      List<Map<String, Object>> unitItems = new ArrayList<>();
      for (int i = 0; i < uTxs.size(); i++) {
        BudgetTransaction tx = uTxs.get(i);
        Map<String, BigDecimal> txAllocMap = extractTxBreakdownAlloc(tx, dimension, colCodes);
        Map<String, BigDecimal> txDisbMap = extractTxBreakdownDisb(tx, dimension, colCodes);
        Map<String, Object> itemRow =
            buildMatrixRow(
                i == 0 ? String.valueOf(sectionIndex) : "",
                i == 0 ? "UNIT_ADJUSTMENT_HEADER" : "ADJUSTMENT_ITEM",
                uCode,
                uName,
                i == 0 ? uName : StrUtil.blankToDefault(tx.getName(), ""),
                txAllocMap,
                txDisbMap,
                StrUtil.blankToDefault(tx.getReason(), ""));
        rows.add(itemRow);
        unitItems.add(itemRow);
      }
      Map<String, Object> sec = new LinkedHashMap<>();
      sec.put("sectionNo", sectionIndex);
      sec.put("nodeCode", uCode);
      sec.put("nodeName", uName);
      sec.put("items", unitItems);
      unitSections.add(sec);
      sectionIndex++;
    }

    List<Map<String, Object>> columns =
        finalizeColumnSummaries(colMetaMap, agencyTotalAllocByCol, agencyTotalDisbByCol);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("reportTitle", "BÁO CÁO MA TRẬN KINH PHÍ ĐA CHIỀU THEO LĨNH VỰC / NHÓM LĨNH VỰC NĂM " + year);
    result.put("subtitle", "(Giải ngân tính đến ngày " + asOfDate + ")");
    result.put("budgetYear", year);
    result.put("asOfDate", asOfDate);
    result.put("groupByDimension", dimension);
    result.put("viewMode", viewMode);
    result.put("configuredForYear", yearDomainConfigs != null && !yearDomainConfigs.isEmpty());
    result.put("columns", columns);
    result.put("rows", rows);
    result.put("unitAdjustmentSections", unitSections);
    result.put("totalInitial", sumColMap(initTotalAllocByCol));
    result.put("totalCarryOver", sumColMap(carryTotalAllocByCol));
    result.put("totalAdjustment", sumColMap(adjTotalAllocByCol));
    result.put("totalAllocated", sumColMap(agencyTotalAllocByCol));
    result.put("totalDisbursed", sumColMap(agencyTotalDisbByCol));
    result.put(
        "overallDisbursementRatePercent",
        calcPercent(sumColMap(agencyTotalDisbByCol), sumColMap(agencyTotalAllocByCol)));
    return result;
  }

  private Map<String, Object> createColMeta(
      String code, String name, String groupCode, String groupName) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("code", code);
    m.put("name", name);
    m.put("groupCode", groupCode);
    m.put("groupName", groupName);
    return m;
  }

  private Map<String, Object> buildMatrixRow(
      String rowNo,
      String rowType,
      String nodeCode,
      String nodeName,
      String title,
      Map<String, BigDecimal> allocatedByCol,
      Map<String, BigDecimal> disbursedByCol,
      String note) {
    BigDecimal totalAlloc = sumColMap(allocatedByCol);
    BigDecimal totalDisb = sumColMap(disbursedByCol);
    BigDecimal ratio =
        totalAlloc.compareTo(BigDecimal.ZERO) > 0
            ? totalDisb.divide(totalAlloc, 6, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
    BigDecimal ratePercent = calcPercent(totalDisb, totalAlloc);

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("rowNo", rowNo);
    row.put("rowType", rowType);
    row.put("nodeCode", nodeCode);
    row.put("nodeName", nodeName);
    row.put("title", title);
    row.put("totalAllocated", totalAlloc);
    row.put("allocatedByColumn", allocatedByCol);
    row.put("totalDisbursed", totalDisb);
    row.put("disbursedByColumn", disbursedByCol);
    row.put("disbursementRatio", ratio);
    row.put("disbursementRatePercent", ratePercent);
    row.put("note", note != null ? note : "");
    return row;
  }

  private List<Map<String, Object>> finalizeColumnSummaries(
      Map<String, Map<String, Object>> colMetaMap,
      Map<String, BigDecimal> totalAllocByCol,
      Map<String, BigDecimal> totalDisbByCol) {
    List<Map<String, Object>> cols = new ArrayList<>();
    for (Map.Entry<String, Map<String, Object>> entry : colMetaMap.entrySet()) {
      String c = entry.getKey();
      Map<String, Object> meta = new LinkedHashMap<>(entry.getValue());
      BigDecimal alloc = totalAllocByCol.getOrDefault(c, BigDecimal.ZERO);
      BigDecimal disb = totalDisbByCol.getOrDefault(c, BigDecimal.ZERO);
      meta.put("totalAllocated", alloc);
      meta.put("totalDisbursed", disb);
      meta.put("disbursementRatePercent", calcPercent(disb, alloc));
      cols.add(meta);
    }
    return cols;
  }

  private void registerDynamicColumnsFromAllocation(
      BudgetAllocation a, String dimension, Map<String, Map<String, Object>> colMetaMap) {
    if (StrUtil.isNotBlank(a.getDynamicBreakdownJson())
        && JSONUtil.isTypeJSONArray(a.getDynamicBreakdownJson())) {
      JSONArray arr = JSONUtil.parseArray(a.getDynamicBreakdownJson());
      for (int i = 0; i < arr.size(); i++) {
        JSONObject obj = arr.getJSONObject(i);
        String code = obj.getStr("code");
        String name = obj.getStr("name", code);
        String gCode = obj.getStr("groupCode", "RECURRENT");
        String gName = obj.getStr("groupName", "Chi thường xuyên");
        if (StrUtil.isNotBlank(code)) {
          colMetaMap.putIfAbsent(code, createColMeta(code, name, gCode, gName));
        }
      }
      return;
    }
    String code;
    String name;
    if ("DOMAIN_GROUP".equalsIgnoreCase(dimension)) {
      code = StrUtil.blankToDefault(a.getDomainGroupCode(), a.getDomainCode());
      name = StrUtil.blankToDefault(a.getDomainGroupName(), a.getDomainName());
    } else if ("CATEGORY".equalsIgnoreCase(dimension)) {
      code = a.getCategoryCode();
      name = a.getCategoryName();
    } else {
      code = StrUtil.blankToDefault(a.getDomainCode(), a.getCategoryCode());
      name = StrUtil.blankToDefault(a.getDomainName(), a.getCategoryName());
    }
    if (StrUtil.isNotBlank(code)) {
      colMetaMap.putIfAbsent(
          code,
          createColMeta(
              code,
              StrUtil.blankToDefault(name, code),
              StrUtil.blankToDefault(a.getDomainGroupCode(), "RECURRENT"),
              StrUtil.blankToDefault(a.getDomainGroupName(), "Chi thường xuyên")));
    }
  }

  private void registerDynamicColumnsFromTransaction(
      BudgetTransaction tx, String dimension, Map<String, Map<String, Object>> colMetaMap) {
    if (StrUtil.isNotBlank(tx.getDynamicBreakdownJson())
        && JSONUtil.isTypeJSONArray(tx.getDynamicBreakdownJson())) {
      JSONArray arr = JSONUtil.parseArray(tx.getDynamicBreakdownJson());
      for (int i = 0; i < arr.size(); i++) {
        JSONObject obj = arr.getJSONObject(i);
        String code = obj.getStr("code");
        String name = obj.getStr("name", code);
        String gCode = obj.getStr("groupCode", "RECURRENT");
        String gName = obj.getStr("groupName", "Chi thường xuyên");
        if (StrUtil.isNotBlank(code)) {
          colMetaMap.putIfAbsent(code, createColMeta(code, name, gCode, gName));
        }
      }
    }
  }

  private String resolveColumnKeyForAllocation(
      BudgetAllocation a,
      String dimension,
      String fallbackKey,
      Map<String, String> prevDomainToCurrentMap,
      Map<String, String> domainToGroupMap) {
    String rawDomain = StrUtil.nullToEmpty(a.getDomainCode()).trim();
    if (!rawDomain.isEmpty()
        && prevDomainToCurrentMap != null
        && prevDomainToCurrentMap.containsKey(rawDomain)) {
      rawDomain = prevDomainToCurrentMap.get(rawDomain);
    }
    if ("DOMAIN_GROUP".equalsIgnoreCase(dimension)) {
      String gCode = StrUtil.nullToEmpty(a.getDomainGroupCode()).trim();
      if (!gCode.isEmpty()
          && prevDomainToCurrentMap != null
          && prevDomainToCurrentMap.containsKey(gCode)) {
        gCode = prevDomainToCurrentMap.get(gCode);
      }
      if (gCode.isEmpty() && !rawDomain.isEmpty() && domainToGroupMap != null) {
        gCode = domainToGroupMap.getOrDefault(rawDomain, "");
      }
      return StrUtil.blankToDefault(gCode, StrUtil.blankToDefault(rawDomain, fallbackKey));
    }
    if ("CATEGORY".equalsIgnoreCase(dimension)) {
      return StrUtil.blankToDefault(a.getCategoryCode(), fallbackKey);
    }
    return StrUtil.blankToDefault(
        rawDomain, StrUtil.blankToDefault(a.getCategoryCode(), fallbackKey));
  }

  private Map<String, BigDecimal> extractTxBreakdownAlloc(
      BudgetTransaction tx, String dimension, List<String> colCodes) {
    Map<String, BigDecimal> map = initColDecimalMap(colCodes);
    if (StrUtil.isNotBlank(tx.getDynamicBreakdownJson())
        && JSONUtil.isTypeJSONArray(tx.getDynamicBreakdownJson())) {
      JSONArray arr = JSONUtil.parseArray(tx.getDynamicBreakdownJson());
      for (int i = 0; i < arr.size(); i++) {
        JSONObject obj = arr.getJSONObject(i);
        String c = obj.getStr("code");
        BigDecimal val = obj.getBigDecimal("allocatedAmount", BigDecimal.ZERO);
        if (c != null && map.containsKey(c)) {
          addColValue(map, c, val);
        }
      }
      return map;
    }
    String key =
        "CATEGORY".equalsIgnoreCase(dimension)
            ? StrUtil.blankToDefault(tx.getCategoryCode(), colCodes.getFirst())
            : StrUtil.blankToDefault(
                tx.getDomainCode(),
                StrUtil.blankToDefault(tx.getCategoryCode(), colCodes.getFirst()));
    BigDecimal amt = safeDec(tx.getAmount());
    if ("REDUCTION".equalsIgnoreCase(tx.getTxType()) && amt.compareTo(BigDecimal.ZERO) > 0) {
      amt = amt.negate();
    }
    addColValue(map, map.containsKey(key) ? key : colCodes.getFirst(), amt);
    return map;
  }

  private Map<String, BigDecimal> extractTxBreakdownDisb(
      BudgetTransaction tx, String dimension, List<String> colCodes) {
    Map<String, BigDecimal> map = initColDecimalMap(colCodes);
    if (StrUtil.isNotBlank(tx.getDynamicBreakdownJson())
        && JSONUtil.isTypeJSONArray(tx.getDynamicBreakdownJson())) {
      JSONArray arr = JSONUtil.parseArray(tx.getDynamicBreakdownJson());
      for (int i = 0; i < arr.size(); i++) {
        JSONObject obj = arr.getJSONObject(i);
        String c = obj.getStr("code");
        BigDecimal val = obj.getBigDecimal("disbursedAmount", BigDecimal.ZERO);
        if (c != null && map.containsKey(c)) {
          addColValue(map, c, val);
        }
      }
      return map;
    }
    String key =
        "CATEGORY".equalsIgnoreCase(dimension)
            ? StrUtil.blankToDefault(tx.getCategoryCode(), colCodes.getFirst())
            : StrUtil.blankToDefault(
                tx.getDomainCode(),
                StrUtil.blankToDefault(tx.getCategoryCode(), colCodes.getFirst()));
    addColValue(map, map.containsKey(key) ? key : colCodes.getFirst(), BigDecimal.ZERO);
    return map;
  }

  private Map<String, BigDecimal> initColDecimalMap(List<String> colCodes) {
    Map<String, BigDecimal> m = new LinkedHashMap<>();
    for (String c : colCodes) {
      m.put(c, BigDecimal.ZERO);
    }
    return m;
  }

  private void addColValue(Map<String, BigDecimal> map, String colCode, BigDecimal val) {
    if (colCode == null || val == null) {
      return;
    }
    map.put(colCode, map.getOrDefault(colCode, BigDecimal.ZERO).add(val));
  }

  private BigDecimal sumColMap(Map<String, BigDecimal> map) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal v : map.values()) {
      if (v != null) {
        sum = sum.add(v);
      }
    }
    return sum;
  }

  private BigDecimal safeDec(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  private BigDecimal scaleMoney(BigDecimal v) {
    return safeDec(v).setScale(2, RoundingMode.HALF_UP);
  }

  private int parseIntParam(Map<String, Object> body, String key, int defaultValue) {
    if (body == null || body.get(key) == null || StrUtil.isBlank(body.get(key).toString())) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(body.get(key).toString().trim());
    } catch (NumberFormatException e) {
      throw new InvalidDataException("Tham số '" + key + "' không phải số nguyên hợp lệ.", null);
    }
  }

  private Long parseLongParam(Map<String, Object> body, String key) {
    if (body == null || body.get(key) == null || StrUtil.isBlank(body.get(key).toString())) {
      return null;
    }
    try {
      return Long.parseLong(body.get(key).toString().trim());
    } catch (NumberFormatException e) {
      throw new InvalidDataException("Tham số '" + key + "' không phải định danh số hợp lệ.", null);
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

  private List<BudgetAllocation> filterAllocationsByScope(
      List<BudgetAllocation> allocs, OrgSubtreeAuthorizationHelper.OrgSubtreeScope scope) {
    if (allocs == null || allocs.isEmpty() || scope == null || !scope.restricted()) {
      return allocs != null ? allocs : List.of();
    }
    List<BudgetAllocation> filtered = new ArrayList<>();
    for (BudgetAllocation a : allocs) {
      if (isAllowedByScope(scope, a.getNodeId(), a.getNodeCode())) {
        filtered.add(a);
      }
    }
    return filtered;
  }

  private boolean isAllowedByScope(
      OrgSubtreeAuthorizationHelper.OrgSubtreeScope scope, Long nodeId, String nodeCode) {
    if (scope == null || !scope.restricted()) {
      return true;
    }
    return scope.allowsTarget(nodeId, null, nodeCode, null);
  }

  private BigDecimal calcPercent(BigDecimal numerator, BigDecimal denominator) {
    if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0 || numerator == null) {
      return BigDecimal.ZERO;
    }
    return numerator
        .multiply(new BigDecimal("100"))
        .divide(denominator, 2, RoundingMode.HALF_UP);
  }

  /** Lấy danh sách rút gọn (Lookup Options) Báo cáo Kinh phí Định kỳ. */
  @Transactional(readOnly = true)
  public PagedData<RefData<Long>> getOptions(BudgetReportSearchCriteria criteria) {
    BudgetReportSearchCriteria effective =
        LookupOptionsHelper.prepare(
            criteria != null ? criteria : new BudgetReportSearchCriteria());
    Pageable pageable = CoreUtils.getPageRequest(effective);
    Page<BudgetReport> page = budgetReportService.findAll(effective, pageable);
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
    return "BudgetReport";
  }

  @Override
  public String getConfigNamespace() {
    return "budget";
  }

  @Override
  public String getProcessCode() {
    return BudgetIntegrationOrchestrator.WF_PROCESS_REPORT;
  }

  @Override
  public String getCallbackUrl() {
    return "/workflow/callback";
  }

  @Override
  public void submitWorkflow(Long id) {
    submitReport(id, Map.of());
  }

  @Override
  public void cancelWorkflow(Long id) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(rpt.getNodeId(), rpt.getNodeCode());
    sm().assertReportTransition(rpt.getWorkflowStatus(), "DRAFT", rpt.getCode());
    rpt.setWorkflowStatus("DRAFT");
    budgetReportService.save(rpt);
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
      approveReport(payload.getEntityId(), Map.of("comment", comment));
    } else if ("REWORK".equals(decision) || "RETURN".equals(decision)) {
      reworkReport(payload.getEntityId(), comment);
    } else if ("REJECT".equals(decision) || "REJECTED".equals(decision)) {
      rejectReport(payload.getEntityId(), Map.of("comment", comment));
    }
  }

  @Override
  public BudgetReport changeEntityStatus(Long id, LifecycleStatus targetStatus, String comment) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    guard().assertNodeAccessIfPresent(rpt.getNodeId(), rpt.getNodeCode());
    rpt.setStatus(targetStatus);
    if (StrUtil.isNotBlank(comment)) {
      rpt.setReviewComment(comment);
    }
    return budgetReportService.save(rpt);
  }

}
