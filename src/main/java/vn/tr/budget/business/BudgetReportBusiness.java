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
import vn.tr.budget.dao.model.BudgetReport;
import vn.tr.budget.dao.service.BudgetReportService;
import vn.tr.budget.data.criteria.BudgetReportSearchCriteria;
import vn.tr.budget.data.dto.BudgetReportData;
import vn.tr.budget.data.mapper.BudgetReportMapper;
import vn.tr.budget.data.validator.BudgetReportValidator;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;

/**
 * Lớp điều phối nghiệp vụ cho BudgetReport.
 *
 * @author tyran8x
 */
@Service("budgetReportBusiness")
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BudgetReportBusiness {

  private final BudgetReportService budgetReportService;
  private final BudgetReportMapper budgetReportMapper;
  private final BudgetReportValidator budgetReportValidator;
  private final GenericUpsertHelper genericUpsertHelper;
  private final AuditRollbackHelper auditRollbackHelper;
  private final BusinessLogicHelper businessLogicHelper;
  private final BudgetAllocationRepo budgetAllocationRepo;

  @Autowired(required = false)
  private BudgetDisbursementRepo budgetDisbursementRepo;

  @Autowired(required = false)
  private BudgetIntegrationOrchestrator integrationOrchestrator;

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
      data.setWorkflowStatus("APPROVED");
    }
    BudgetReport entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetReportService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> budgetReportMapper.toEntity(data),
            budgetReportMapper::updateEntityFromData,
            budgetReportService.getRepository());

    return budgetReportMapper.toData(entity);
  }

  /** Cập nhật BudgetReport. */
  public BudgetReportData update(Long id, BudgetReportData data) {
    BudgetReport existing =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    data.setId(id);
    validateData(data);
    budgetReportMapper.updateEntityFromData(data, existing);
    BudgetReport saved = budgetReportService.save(existing);
    return budgetReportMapper.toData(saved);
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
        .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
  }

  /** Tìm kiếm BudgetReport phân trang. */
  @Transactional(readOnly = true)
  public PagedData<BudgetReportData> findAll(BudgetReportSearchCriteria criteria) {
    Pageable pageable = CoreUtils.getPageRequest(criteria);
    Page<BudgetReport> page = budgetReportService.findAll(criteria, pageable);
    return PagedData.from(page, budgetReportMapper::toData);
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
            ? String.valueOf(body.get("reportPeriod"))
            : "QUARTER";
    Integer periodNumber =
        body != null && body.get("periodNumber") != null
            ? Integer.parseInt(body.get("periodNumber").toString())
            : 1;
    Long nodeId =
        body != null && body.get("nodeId") != null
            ? Long.parseLong(body.get("nodeId").toString())
            : null;

    List<BudgetAllocation> allocs =
        nodeId != null
            ? budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(nodeId, year)
            : budgetAllocationRepo.findByBudgetYearOrderByIdAsc(year);

    BigDecimal revEst = BigDecimal.ZERO;
    BigDecimal revAct = BigDecimal.ZERO;
    BigDecimal expInit = BigDecimal.ZERO;
    BigDecimal expEff = BigDecimal.ZERO;
    BigDecimal expAct = BigDecimal.ZERO;
    BigDecimal carryOver = BigDecimal.ZERO;

    for (BudgetAllocation a : allocs) {
      BigDecimal init = a.getInitialAmount() != null ? a.getInitialAmount() : BigDecimal.ZERO;
      BigDecimal eff = a.getEffectiveAmount() != null ? a.getEffectiveAmount() : BigDecimal.ZERO;
      BigDecimal disb = a.getDisbursedAmount() != null ? a.getDisbursedAmount() : BigDecimal.ZERO;
      BigDecimal co =
          (a.getCarryOverInAmount() != null ? a.getCarryOverInAmount() : BigDecimal.ZERO)
              .add(a.getCarryOverOutAmount() != null ? a.getCarryOverOutAmount() : BigDecimal.ZERO);
      if ("REVENUE".equalsIgnoreCase(a.getItemType())) {
        revEst = revEst.add(eff);
        revAct = revAct.add(disb);
      } else {
        expInit = expInit.add(init);
        expEff = expEff.add(eff);
        expAct = expAct.add(disb);
        carryOver = carryOver.add(co);
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
            .description("Tổng hợp tự động từ Sổ cái Phân bổ và Giải ngân Kho bạc")
            .budgetYear(year)
            .reportPeriod(reportPeriod)
            .periodNumber(periodNumber)
            .reportScope(nodeId != null ? "UNIT_REPORT" : "CONSOLIDATED_REPORT")
            .nodeId(nodeId)
            .nodeCode(nodeId != null ? "NODE-" + nodeId : "TOAN-SO")
            .nodeName(nodeId != null ? "Đơn vị dự toán #" + nodeId : "Toàn Sở / Đơn vị dự toán cấp I")
            .reportDate(LocalDate.now())
            .totalRevenueEstimate(revEst)
            .totalRevenueActual(revAct)
            .totalRevenueCumulative(revAct)
            .totalExpenditureInitial(expInit)
            .totalExpenditureEffective(expEff)
            .totalExpenditureActual(expAct)
            .totalExpenditureCumulative(expAct)
            .totalCarryOverAmount(carryOver)
            .totalSurplusOrSaving(saving)
            .disbursementRatePercent(rate)
            .submittedByPersonId(actorPersonId)
            .submittedByPersonName(actorName)
            .reviewedByPersonId(actorPersonId)
            .reviewedByPersonName(actorName)
            .workflowStatus("APPROVED")
            .status(LifecycleStatus.ACTIVE)
            .build();
    return create(data);
  }

  /** Phê duyệt báo cáo quyết toán định kỳ. */
  public BudgetReportData approveReport(Long id, Map<String, Object> body) {
    BudgetReport rpt =
        budgetReportService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetReport.class, id));
    rpt.setWorkflowStatus("APPROVED");
    rpt.setReviewedByPersonName(
        integrationOrchestrator != null
            ? integrationOrchestrator.resolveCurrentActorName(
                body, "actorName", "reviewedByPersonName")
            : "Cán bộ Tài chính - Kế toán");
    return budgetReportMapper.toData(budgetReportService.save(rpt));
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

    List<Map<String, Object>> form01aRows = new ArrayList<>();
    BigDecimal totalInit = BigDecimal.ZERO;
    BigDecimal totalSupplement = BigDecimal.ZERO;
    BigDecimal totalCarryOver = BigDecimal.ZERO;
    BigDecimal totalEffective = BigDecimal.ZERO;
    BigDecimal totalCommitted = BigDecimal.ZERO;
    BigDecimal totalDisbursed = BigDecimal.ZERO;
    BigDecimal totalAvailable = BigDecimal.ZERO;

    for (BudgetAllocation a : allocs) {
      if ("REVENUE".equalsIgnoreCase(a.getItemType())) {
        continue;
      }
      BigDecimal init = a.getInitialAmount() != null ? a.getInitialAmount() : BigDecimal.ZERO;
      BigDecimal supp =
          (a.getSupplementAmount() != null ? a.getSupplementAmount() : BigDecimal.ZERO)
              .add(a.getTransferNetAmount() != null ? a.getTransferNetAmount() : BigDecimal.ZERO)
              .subtract(a.getReductionAmount() != null ? a.getReductionAmount() : BigDecimal.ZERO);
      BigDecimal co =
          (a.getCarryOverInAmount() != null ? a.getCarryOverInAmount() : BigDecimal.ZERO)
              .subtract(
                  a.getCarryOverOutAmount() != null ? a.getCarryOverOutAmount() : BigDecimal.ZERO);
      BigDecimal eff = a.getEffectiveAmount() != null ? a.getEffectiveAmount() : BigDecimal.ZERO;
      BigDecimal comm = a.getCommittedAmount() != null ? a.getCommittedAmount() : BigDecimal.ZERO;
      BigDecimal disb = a.getDisbursedAmount() != null ? a.getDisbursedAmount() : BigDecimal.ZERO;
      BigDecimal avail = a.getAvailableAmount() != null ? a.getAvailableAmount() : BigDecimal.ZERO;

      totalInit = totalInit.add(init);
      totalSupplement = totalSupplement.add(supp);
      totalCarryOver = totalCarryOver.add(co);
      totalEffective = totalEffective.add(eff);
      totalCommitted = totalCommitted.add(comm);
      totalDisbursed = totalDisbursed.add(disb);
      totalAvailable = totalAvailable.add(avail);

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("allocationCode", a.getCode());
      row.put("unitCode", a.getNodeCode());
      row.put("unitName", a.getNodeName());
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
      row.put("disbursedAtTreasury", disb);
      row.put("remainingBalance", avail);
      row.put("reconciledStatus", "MATCHED_KBNN");
      form01aRows.add(row);
    }

    int matchedVouchers = 0;
    int pendingVouchers = 0;
    for (BudgetDisbursement d : disbs) {
      if (nodeId != null && !nodeId.equals(d.getNodeId())) {
        continue;
      }
      if (StrUtil.isNotBlank(d.getTreasuryRefCode())) {
        matchedVouchers++;
      } else {
        pendingVouchers++;
      }
    }

    Map<String, Object> statement = new LinkedHashMap<>();
    statement.put("formCode", "01a/SDKP-ĐVDT & 02/SDKP-ĐVDT");
    statement.put("circularRef", "Nghị định 60/2021/NĐ-CP & Thông tư 342/2016/TT-BTC");
    statement.put("budgetYear", year);
    statement.put("quarter", q);
    statement.put("generatedDate", LocalDate.now().toString());
    statement.put("treasuryAgency", "Kho bạc Nhà nước Thành phố Đà Nẵng");
    statement.put("totalInitialEstimate", totalInit);
    statement.put("totalSupplementAndAdjustment", totalSupplement);
    statement.put("totalCarryOverNet", totalCarryOver);
    statement.put("totalEffectiveEstimate", totalEffective);
    statement.put("totalCommittedAmount", totalCommitted);
    statement.put("totalDisbursedAtTreasury", totalDisbursed);
    statement.put("totalRemainingBalance", totalAvailable);
    statement.put("matchedVoucherCount", matchedVouchers);
    statement.put("pendingVoucherCount", pendingVouchers);
    statement.put("rows", form01aRows);
    return statement;
  }

}
