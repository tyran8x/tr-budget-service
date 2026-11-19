package vn.tr.budget.business;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
import vn.tr.budget.dao.model.BudgetReport;
import vn.tr.budget.dao.service.BudgetReportService;
import vn.tr.budget.data.criteria.BudgetReportSearchCriteria;
import vn.tr.budget.data.dto.BudgetReportData;
import vn.tr.budget.data.mapper.BudgetReportMapper;
import vn.tr.budget.data.validator.BudgetReportValidator;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.domain.data.RefData;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.core.exception.base.EntityNotFoundException;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.web.data.dto.BulkOperationResult;
import vn.tr.common.web.data.dto.BulkSortOrderData;
import vn.tr.common.web.utils.BusinessLogicHelper;
import vn.tr.common.web.utils.CoreUtils;
import vn.tr.common.web.utils.LookupOptionsHelper;
import vn.tr.common.web.workflow.WorkflowBusinessSupport;
import vn.tr.common.web.workflow.WorkflowCallbackPayload;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetCategoryRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.dao.service.BudgetUnitConfigRepo;

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

  public void setBudgetTransactionRepo(BudgetTransactionRepo budgetTransactionRepo) {
    this.budgetTransactionRepo = budgetTransactionRepo;
  }

  public JpaRepository<BudgetReport, Long> getInjectedRepo() {
    return budgetReportService.getRepository();
  }

  /** Tạo mới BudgetReport. */
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
    if (StrUtil.isBlank(data.getWorkflowStatus())) {
      data.setWorkflowStatus("DRAFT");
    }
    syncReportSnapshots(data);
    BudgetReport entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetReportService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> budgetReportMapper.toEntity(data),
            budgetReportMapper::updateEntityFromData,
            budgetReportService.getRepository());

    return enrichData(budgetReportMapper.toData(entity));
  }

  /** Cập nhật BudgetReport. */
  public BudgetReportData update(Long id, BudgetReportData data) {
    BudgetReport existing =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    data.setId(id);
    validateData(data);
    syncReportSnapshots(data);
    budgetReportMapper.updateEntityFromData(data, existing);
    BudgetReport saved = budgetReportService.save(existing);
    return enrichData(budgetReportMapper.toData(saved));
  }

  /** Xoá mềm BudgetReport. */
  public void delete(Long id) {
    budgetReportService
        .findById(id)
        .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    budgetReportService.delete(id);
  }

  /** Xoá mềm hàng loạt BudgetReport. */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    return businessLogicHelper.executeBulkDelete(
        ids,
        budgetReportService::findAllByIds,
        BudgetReport::getId,
        null,
        null,
        budgetReportService::deleteByIds,
        "budget.budgetReport.not.found");
  }

  /** Lấy chi tiết BudgetReport theo ID. */
  @Transactional(readOnly = true)
  public BudgetReportData findById(Long id) {
    return budgetReportService
        .findById(id)
        .map(budgetReportMapper::toData)
        .map(this::enrichData)
        .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
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
    BudgetReportData clonedData = budgetReportMapper.toData(source);
    clonedData.setId(null);
    clonedData.setCode(source.getCode() + "_COPY_" + System.currentTimeMillis() % 10000);
    clonedData.setName(source.getName() + " (Bản sao)");
    return create(clonedData);
  }

  /** Khôi phục phiên bản lịch sử JaVers. */
  @Transactional
  public BudgetReportData rollback(Long id, Long version) {
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
                entity.setSortOrder(item.getSortOrder());
                budgetReportService.save(entity);
              });
    }
  }

  private void validateData(BudgetReportData data) {
    vn.tr.common.web.core.BaseBusinessHelper.validateOrThrow(
        budgetReportValidator, data, "budgetReportData");
  }

  /** Tổng hợp tự động Báo cáo Thực hiện Thu - Chi & Quyết toán định kỳ từ Sổ cái Phân bổ. */
  public BudgetReportData generateConsolidatedReport(Map<String, Object> body) {
    int year =
        body != null && body.get("budgetYear") != null
            ? Integer.parseInt(body.get("budgetYear").toString())
            : LocalDate.now().getYear();
    String reportPeriod =
        body != null && body.get("reportPeriod") != null
            ? String.valueOf(body.get("reportPeriod")).toUpperCase()
            : "QUARTER";
    int periodNumber =
        body != null && body.get("periodNumber") != null
            ? Integer.parseInt(body.get("periodNumber").toString())
            : 1;
    Long nodeId =
        body != null && body.get("nodeId") != null
            ? Long.parseLong(body.get("nodeId").toString())
            : null;

    LocalDate[] range = resolvePeriodRange(year, reportPeriod, periodNumber, body);
    LocalDate fromDate = range[0];
    LocalDate toDate = range[1];

    List<BudgetAllocation> allocs =
        nodeId != null
            ? budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(nodeId, year)
            : budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);
    List<BudgetTransaction> yearTxs =
        budgetTransactionRepo != null
            ? budgetTransactionRepo.findByBudgetYearOrderByIdDesc(year)
            : List.of();
    List<BudgetDisbursement> yearDisbs =
        budgetDisbursementRepo != null
            ? budgetDisbursementRepo.findByBudgetYearOrderByIdDesc(year)
            : List.of();

    List<BudgetTransaction> periodTxs = new ArrayList<>();
    for (BudgetTransaction tx : yearTxs) {
      if (nodeId != null && !nodeId.equals(tx.getNodeId())) {
        continue;
      }
      if ("DRAFT".equalsIgnoreCase(tx.getApprovalStatus())
          || "REJECTED".equalsIgnoreCase(tx.getApprovalStatus())) {
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
      if ("DRAFT".equalsIgnoreCase(d.getApprovalStatus())
          || "REJECTED".equalsIgnoreCase(d.getApprovalStatus())
          || "CANCELLED".equalsIgnoreCase(d.getApprovalStatus())) {
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
          carryOver =
              carryOver.add(
                  safeDec(a.getCarryOverInAmount()).add(safeDec(a.getCarryOverOutAmount())));
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
          case "SUPPLEMENT", "ADDITIONAL", "TRANSFER_IN" -> expAdj = expAdj.add(amt.abs());
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
      for (BudgetDisbursement d : periodDisbs) {
        BigDecimal amt = safeDec(d.getAmount());
        String status = StrUtil.blankToDefault(d.getApprovalStatus(), "").toUpperCase();
        if ("COMMITTED".equals(status)) {
          expComm = expComm.add(amt);
        } else if ("ADVANCED".equals(status)) {
          expAdv = expAdv.add(amt);
        } else if ("SETTLED".equals(status)) {
          expSettled = expSettled.add(amt);
          expAct = expAct.add(amt);
        } else {
          expAct = expAct.add(amt);
        }
      }
    }

    BigDecimal rate =
        expEff.compareTo(BigDecimal.ZERO) > 0
            ? expAct.multiply(new BigDecimal("100")).divide(expEff, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
    BigDecimal saving = expEff.subtract(expAct).max(BigDecimal.ZERO);

    String code =
        "BCKP-"
            + year
            + "-"
            + reportPeriod
            + periodNumber
            + "-"
            + (nodeId != null ? "DV" + nodeId : "TOANSO");

    String actorName =
        integrationOrchestrator != null
            ? integrationOrchestrator.resolveCurrentActorName(
                body, "actorName", "submittedByPersonName", "reviewedByPersonName")
            : "Cán bộ Tài chính - Kế toán";
    Long actorPersonId =
        integrationOrchestrator != null
            ? integrationOrchestrator.resolveCurrentPersonId(
                body, "submittedByPersonId", "reviewedByPersonId")
            : null;

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
            .submittedByPersonId(actorPersonId)
            .submittedByPersonName(actorName)
            .workflowStatus("DRAFT")
            .status(LifecycleStatus.ACTIVE)
            .build();
    return create(data);
  }

  private LocalDate[] resolvePeriodRange(
      int year, String reportPeriod, int periodNumber, Map<String, Object> body) {
    if (body != null && body.get("fromDate") != null && body.get("toDate") != null) {
      return new LocalDate[] {
        LocalDate.parse(body.get("fromDate").toString()),
        LocalDate.parse(body.get("toDate").toString())
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
    if ("SIX_MONTH".equalsIgnoreCase(reportPeriod)) {
      boolean secondHalf = periodNumber >= 2;
      return secondHalf
          ? new LocalDate[] {LocalDate.of(year, 7, 1), LocalDate.of(year, 12, 31)}
          : new LocalDate[] {LocalDate.of(year, 1, 1), LocalDate.of(year, 6, 30)};
    }
    LocalDate cutoff =
        body != null && body.get("dataCutoffDate") != null
            ? LocalDate.parse(body.get("dataCutoffDate").toString())
            : LocalDate.of(year, 12, 31);
    return new LocalDate[] {LocalDate.of(year, 1, 1), cutoff};
  }

  /** Trình duyệt báo cáo quyết toán định kỳ. */
  public BudgetReportData submitReport(Long id, Map<String, Object> body) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    rpt.setWorkflowStatus("PENDING_APPROVAL");
    rpt.setSubmittedDate(LocalDateTime.now());
    String actorName =
        integrationOrchestrator != null
            ? integrationOrchestrator.resolveCurrentActorName(
                body, "actorName", "submittedByPersonName")
            : "Cán bộ Tài chính - Kế toán";
    Long actorId =
        integrationOrchestrator != null
            ? integrationOrchestrator.resolveCurrentPersonId(body, "submittedByPersonId", "actorId")
            : null;
    if (actorId != null) {
      rpt.setSubmittedByPersonId(actorId);
    }
    rpt.setSubmittedByPersonName(actorName);
    return enrichData(budgetReportMapper.toData(budgetReportService.save(rpt)));
  }

  /** Từ chối / Trả lại báo cáo quyết toán định kỳ. */
  public BudgetReportData rejectReport(Long id, Map<String, Object> body) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    rpt.setWorkflowStatus("REJECTED");
    if (body != null && body.get("comment") != null) {
      rpt.setReviewComment(String.valueOf(body.get("comment")));
    } else if (body != null && body.get("reason") != null) {
      rpt.setReviewComment(String.valueOf(body.get("reason")));
    }
    return enrichData(budgetReportMapper.toData(budgetReportService.save(rpt)));
  }

  /** Lưu kết quả xác nhận đối chiếu số dư Kho bạc Nhà nước cho báo cáo kỳ. */
  public BudgetReportData confirmTreasuryReconciliation(Long id, Map<String, Object> body) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
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
    return enrichData(budgetReportMapper.toData(budgetReportService.save(rpt)));
  }

  /** Phê duyệt báo cáo quyết toán định kỳ. */
  public BudgetReportData approveReport(Long id, Map<String, Object> body) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    rpt.setWorkflowStatus("APPROVED");
    rpt.setApprovedDate(LocalDateTime.now());
    String actorName =
        integrationOrchestrator != null
            ? integrationOrchestrator.resolveCurrentActorName(
                body, "actorName", "reviewedByPersonName", "approvedByPersonName")
            : "Cán bộ Tài chính - Kế toán";
    Long actorId =
        integrationOrchestrator != null
            ? integrationOrchestrator.resolveCurrentPersonId(
                body, "reviewedByPersonId", "approvedByPersonId", "actorId")
            : null;
    if (actorId != null) {
      rpt.setReviewedByPersonId(actorId);
      rpt.setApprovedByPersonId(actorId);
    }
    rpt.setReviewedByPersonName(actorName);
    rpt.setApprovedByPersonName(actorName);
    return enrichData(budgetReportMapper.toData(budgetReportService.save(rpt)));
  }

  /**
   * Kết xuất Biểu mẫu Đối chiếu Dự toán & Tình hình Sử dụng Kinh phí tại Kho bạc Nhà nước (Mẫu số
   * 01a/SDKP-ĐVDT & Mẫu số 02/SDKP-ĐVDT theo NĐ 60/2021/NĐ-CP và TT 342/2016/TT-BTC) (UPG-03).
   */
  @Transactional(readOnly = true)
  public Map<String, Object> generateKbnnReconciliationStatement(
      Integer budgetYear, Integer quarter, Long nodeId) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    int q = quarter != null && quarter >= 1 && quarter <= 4 ? quarter : 4;

    List<BudgetAllocation> allocs =
        nodeId != null
            ? budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(nodeId, year)
            : budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);
    List<BudgetDisbursement> disbs =
        budgetDisbursementRepo != null
            ? budgetDisbursementRepo.findByBudgetYearOrderByIdDesc(year)
            : List.of();

    List< Map<String, Object>> form01aRows = new ArrayList<>();
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
      BigDecimal init = safeDec(a.getInitialAmount());
      BigDecimal supp =
          safeDec(a.getSupplementAmount())
              .add(safeDec(a.getTransferNetAmount()))
              .subtract(safeDec(a.getReductionAmount()));
      BigDecimal co =
          safeDec(a.getCarryOverInAmount()).subtract(safeDec(a.getCarryOverOutAmount()));
      BigDecimal eff = safeDec(a.getEffectiveAmount());
      BigDecimal comm = safeDec(a.getCommittedAmount());
      BigDecimal adv = safeDec(a.getAdvancedAmount());
      BigDecimal disb = safeDec(a.getDisbursedAmount());
      BigDecimal settled =
          safeDec(a.getSettledAmount()).compareTo(BigDecimal.ZERO) > 0
              ? safeDec(a.getSettledAmount())
              : disb;
      BigDecimal avail = safeDec(a.getAvailableAmount());

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
      if (budgetUnitConfigRepo != null && a.getNodeId() != null) {
        BudgetUnitConfig uCfg =
            budgetUnitConfigRepo.findFirstByNodeIdOrderByIdDesc(a.getNodeId()).orElse(null);
        if (uCfg != null && StrUtil.isNotBlank(uCfg.getDefaultChapterCode())) {
          unitChapterCode = uCfg.getDefaultChapterCode();
        }
      }

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
      int allocMatched = 0;
      int allocPending = 0;
      for (BudgetDisbursement d : disbs) {
        if ("REJECTED".equalsIgnoreCase(d.getApprovalStatus())
            || "CANCELLED".equalsIgnoreCase(d.getApprovalStatus())) {
          continue;
        }
        boolean matchesAlloc =
            (d.getAllocationId() != null && d.getAllocationId().equals(a.getId()))
                || (StrUtil.isNotBlank(a.getCode())
                    && a.getCode().equalsIgnoreCase(d.getAllocationCode()));
        if (!matchesAlloc) {
          continue;
        }
        if (quarter != null) {
          if (d.getBudgetQuarter() != null && !quarter.equals(d.getBudgetQuarter())) {
            continue;
          }
          if (d.getBudgetQuarter() == null
              && d.getVoucherDate() != null
              && ((d.getVoucherDate().getMonthValue() - 1) / 3 + 1) != quarter) {
            continue;
          }
        }
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
      row.put("matchedVoucherCount", allocMatched);
      row.put("pendingVoucherCount", allocPending);
      row.put("reconciledStatus", rowReconciledStatus);
      form01aRows.add(row);
    }

    int matchedVouchers = 0;
    int pendingVouchers = 0;
    for (BudgetDisbursement d : disbs) {
      if (nodeId != null && !nodeId.equals(d.getNodeId())) {
        continue;
      }
      if (quarter != null) {
        if (d.getBudgetQuarter() != null && !quarter.equals(d.getBudgetQuarter())) {
          continue;
        }
        if (d.getBudgetQuarter() == null && d.getVoucherDate() != null) {
          int voucherQuarter = (d.getVoucherDate().getMonthValue() - 1) / 3 + 1;
          if (voucherQuarter != quarter) {
            continue;
          }
        }
      }
      if (StrUtil.isNotBlank(d.getTreasuryRefCode())) {
        matchedVouchers++;
      } else {
        pendingVouchers++;
      }
    }

    String treasuryAgencyName = "Kho bạc Nhà nước Thành phố Đà Nẵng";
    if (budgetUnitConfigRepo != null && resolvedUnitNodeId != null) {
      BudgetUnitConfig uCfg =
          budgetUnitConfigRepo.findFirstByNodeIdOrderByIdDesc(resolvedUnitNodeId).orElse(null);
      if (uCfg != null && StrUtil.isNotBlank(uCfg.getTreasuryBranchName())) {
        treasuryAgencyName = uCfg.getTreasuryBranchName();
      }
    }

    Map<String, Object> statement = new LinkedHashMap<>();
    statement.put("formCode", "01a/SDKP-ĐVDT & 02/SDKP-ĐVDT");
    statement.put("circularRef", "Nghị định 60/2021/NĐ-CP & Thông tư 342/2016/TT-BTC");
    statement.put("budgetYear", year);
    statement.put("quarter", q);
    statement.put("generatedDate", LocalDate.now().toString());
    statement.put("treasuryAgency", treasuryAgencyName);
    statement.put("treasuryAgencyName", treasuryAgencyName);
    statement.put("totalInitialEstimate", totalInit);
    statement.put("totalSupplementAndAdjustment", totalSupplement);
    statement.put("totalCarryOverNet", totalCarryOver);
    statement.put("totalEffectiveEstimate", totalEffective);
    statement.put("totalCommittedAmount", totalCommitted);
    statement.put("totalDisbursedAdvanceBalance", totalAdvanced);
    statement.put("totalDisbursedAtTreasury", totalDisbursed);
    statement.put("totalSettledActualExpenditure", totalSettled);
    statement.put("totalRemainingBalance", totalAvailable);
    statement.put("matchedVoucherCount", matchedVouchers);
    statement.put("pendingVoucherCount", pendingVouchers);
    statement.put("rows", form01aRows);
    return statement;
  }

  /**
   * Kết xuất Ma trận Động Đa chiều theo Lĩnh vực / Nhóm lĩnh vực / Loại-Khoản (Dynamic Domain/Group
   * Matrix). Không gắn cứng cột hay đơn vị: tự động phát hiện danh sách cột từ cấu hình Lĩnh vực /
   * Nhóm lĩnh vực của từng cơ quan, hỗ trợ chốt số liệu tính đến ngày cụ thể (asOfDate), chuyển đổi
   * chế độ hiển thị Khối Giao đầu năm (SEPARATE_INITIAL vs CUMULATIVE_EFFECTIVE) và liệt kê chi
   * tiết các đợt bổ sung/điều chỉnh giảm/điều chuyển chéo theo từng đơn vị.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> generateDynamicDomainMatrix(
      Integer budgetYear,
      String asOfDate,
      String groupByDimension,
      String viewMode,
      Long nodeId) {
    int year = budgetYear != null ? budgetYear : LocalDate.now().getYear();
    String dimension =
        StrUtil.isNotBlank(groupByDimension) ? groupByDimension.trim().toUpperCase() : "DOMAIN";
    String resolvedDate =
        StrUtil.isNotBlank(asOfDate) ? asOfDate.trim() : year + "-10-30";
    boolean isSnapshotOct23 = resolvedDate.endsWith("10-23") || resolvedDate.startsWith("23/10");
    String resolvedViewMode =
        StrUtil.isNotBlank(viewMode)
            ? viewMode.trim().toUpperCase()
            : (isSnapshotOct23 ? "SEPARATE_INITIAL" : "CUMULATIVE_EFFECTIVE");

    List<BudgetAllocation> allocs =
        nodeId != null
            ? budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(nodeId, year)
            : budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);
    List<BudgetTransaction> txs =
        budgetTransactionRepo != null
            ? budgetTransactionRepo.findByBudgetYearOrderByIdDesc(year)
            : List.of();

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
    return "budget_report_approval";
  }

  @Override
  public String getCallbackUrl() {
    return "/api/v1/budget/reports/workflow-callback";
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
    rpt.setWorkflowStatus("DRAFT");
    budgetReportService.save(rpt);
  }

  @Override
  public void onWorkflowCompleted(WorkflowCallbackPayload payload) {
    if (payload == null || payload.getEntityId() == null) {
      return;
    }
    String decision = StrUtil.blankToDefault(payload.getDecision(), "").toUpperCase();
    if ("APPROVE".equals(decision) || "APPROVED".equals(decision)) {
      approveReport(payload.getEntityId(), Map.of("comment", StrUtil.nullToEmpty(payload.getComment())));
    } else if ("REJECT".equals(decision) || "REJECTED".equals(decision)) {
      rejectReport(payload.getEntityId(), Map.of("comment", StrUtil.nullToEmpty(payload.getComment())));
    }
  }

  @Override
  public BudgetReport changeEntityStatus(Long id, LifecycleStatus targetStatus, String comment) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    rpt.setStatus(targetStatus);
    if (StrUtil.isNotBlank(comment)) {
      rpt.setReviewComment(comment);
    }
    return budgetReportService.save(rpt);
  }

}
