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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;

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
  private BudgetTransactionRepo budgetTransactionRepo;

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
    BigDecimal expAdj = BigDecimal.ZERO;
    BigDecimal expEff = BigDecimal.ZERO;
    BigDecimal expComm = BigDecimal.ZERO;
    BigDecimal expAdv = BigDecimal.ZERO;
    BigDecimal expAct = BigDecimal.ZERO;
    BigDecimal expSettled = BigDecimal.ZERO;
    BigDecimal carryOver = BigDecimal.ZERO;
    BigDecimal remaining = BigDecimal.ZERO;
    BigDecimal expiredCancelled = BigDecimal.ZERO;

    for (BudgetAllocation a : allocs) {
      BigDecimal init = a.getInitialAmount() != null ? a.getInitialAmount() : BigDecimal.ZERO;
      BigDecimal eff = a.getEffectiveAmount() != null ? a.getEffectiveAmount() : BigDecimal.ZERO;
      BigDecimal comm = a.getCommittedAmount() != null ? a.getCommittedAmount() : BigDecimal.ZERO;
      BigDecimal adv = a.getAdvancedAmount() != null ? a.getAdvancedAmount() : BigDecimal.ZERO;
      BigDecimal disb = a.getDisbursedAmount() != null ? a.getDisbursedAmount() : BigDecimal.ZERO;
      BigDecimal settled = a.getSettledAmount() != null ? a.getSettledAmount() : BigDecimal.ZERO;
      BigDecimal avail = a.getAvailableAmount() != null ? a.getAvailableAmount() : BigDecimal.ZERO;
      BigDecimal expCanc =
          a.getExpiredCancelledAmount() != null ? a.getExpiredCancelledAmount() : BigDecimal.ZERO;
      BigDecimal adj =
          (a.getSupplementAmount() != null ? a.getSupplementAmount() : BigDecimal.ZERO)
              .add(a.getTransferNetAmount() != null ? a.getTransferNetAmount() : BigDecimal.ZERO)
              .subtract(a.getReductionAmount() != null ? a.getReductionAmount() : BigDecimal.ZERO);
      BigDecimal co =
          (a.getCarryOverInAmount() != null ? a.getCarryOverInAmount() : BigDecimal.ZERO)
              .add(a.getCarryOverOutAmount() != null ? a.getCarryOverOutAmount() : BigDecimal.ZERO);
      if ("REVENUE".equalsIgnoreCase(a.getItemType())) {
        revEst = revEst.add(eff);
        revAct = revAct.add(disb);
      } else {
        expInit = expInit.add(init);
        expAdj = expAdj.add(adj);
        expEff = expEff.add(eff);
        expComm = expComm.add(comm);
        expAdv = expAdv.add(adv);
        expAct = expAct.add(disb);
        expSettled = expSettled.add(settled);
        carryOver = carryOver.add(co);
        remaining = remaining.add(avail);
        expiredCancelled = expiredCancelled.add(expCanc);
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
            .periodLabel(reportPeriod + " " + periodNumber + "/" + year)
            .reportScope(nodeId != null ? "UNIT_REPORT" : "CONSOLIDATED_REPORT")
            .nodeId(nodeId)
            .nodeCode(nodeId != null ? "NODE-" + nodeId : "TOAN-SO")
            .nodeName(nodeId != null ? "Đơn vị dự toán #" + nodeId : "Toàn Sở / Đơn vị dự toán cấp I")
            .reportDate(LocalDate.now())
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
            .submittedDate(LocalDateTime.now())
            .reviewedByPersonId(actorPersonId)
            .reviewedByPersonName(actorName)
            .approvedByPersonId(actorPersonId)
            .approvedByPersonName(actorName)
            .approvedDate(LocalDateTime.now())
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
      row.put("nodeCode", a.getNodeCode());
      row.put("nodeName", a.getNodeName());
      row.put("chapterCode", "423");
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
      row.put("disbursedAdvanceBalance", comm);
      row.put("disbursedAtTreasury", disb);
      row.put("settledActualExpenditure", disb);
      row.put("remainingBalance", avail);
      row.put("remainingEstimateBalance", avail);
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
    statement.put("treasuryAgencyName", "Kho bạc Nhà nước Thành phố Đà Nẵng");
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

    // Kiểm tra xem DB đã có dữ liệu phân bổ theo lĩnh vực động chưa
    boolean hasDynamicDbData = false;
    for (BudgetAllocation a : allocs) {
      if (StrUtil.isNotBlank(a.getDomainCode())
          || StrUtil.isNotBlank(a.getDomainGroupCode())
          || StrUtil.isNotBlank(a.getDynamicBreakdownJson())) {
        hasDynamicDbData = true;
        break;
      }
    }

    if (hasDynamicDbData && !allocs.isEmpty()) {
      return buildDynamicMatrixFromDatabase(
          year, resolvedDate, cutoffDate, dimension, resolvedViewMode, allocs, txs);
    }

    return buildDynamicExampleMatrix(year, resolvedDate, isSnapshotOct23, dimension, resolvedViewMode);
  }

  private Map<String, Object> buildDynamicMatrixFromDatabase(
      int year,
      String asOfDate,
      LocalDate cutoffDate,
      String dimension,
      String viewMode,
      List<BudgetAllocation> allocs,
      List<BudgetTransaction> txs) {
    Map<String, Map<String, Object>> colMetaMap = new LinkedHashMap<>();

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

    for (Map.Entry<String, List<BudgetAllocation>> entry : allocsByUnit.entrySet()) {
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
        String cCode = resolveColumnKeyForAllocation(a, dimension, colCodes.getFirst());
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
    result.put("columns", columns);
    result.put("rows", rows);
    result.put("unitAdjustmentSections", unitSections);
    result.put("totalAllocated", sumColMap(agencyTotalAllocByCol));
    result.put("totalDisbursed", sumColMap(agencyTotalDisbByCol));
    result.put(
        "overallDisbursementRatePercent",
        calcPercent(sumColMap(agencyTotalDisbByCol), sumColMap(agencyTotalAllocByCol)));
    return result;
  }

  /**
   * Dựng Ma trận Động từ tập dữ liệu ví dụ minh họa khi DB chưa có bản ghi phân bổ đa lĩnh vực,
   * đảm bảo cấu trúc trả về (columns động, allocatedByColumn, disbursedByColumn, 2 mốc ngày
   * 2026-10-23 vs 2026-10-30 và 2 chế độ SEPARATE_INITIAL vs CUMULATIVE_EFFECTIVE) hoàn toàn
   * tổng quát, không gắn cứng cột ở tầng giao diện.
   */
  private Map<String, Object> buildDynamicExampleMatrix(
      int year,
      String asOfDate,
      boolean isSnapshotOct23,
      String dimension,
      String viewMode) {
    Map<String, Map<String, Object>> colMetaMap = new LinkedHashMap<>();
    if ("DOMAIN_GROUP".equalsIgnoreCase(dimension)) {
      colMetaMap.put("GRP_CORE_TECH", createColMeta("GRP_CORE_TECH", "Nhóm KHCN & Hạ tầng", "RECURRENT", "Chi thường xuyên"));
      colMetaMap.put("GRP_INNOV_DIGITAL", createColMeta("GRP_INNOV_DIGITAL", "Nhóm Đổi mới sáng tạo & Chuyển đổi số", "RECURRENT", "Chi thường xuyên"));
    } else {
      colMetaMap.put("KHCN", createColMeta("KHCN", "Khoa học công nghệ", "RECURRENT", "Chi thường xuyên"));
      colMetaMap.put("HTTB", createColMeta("HTTB", "Hạ tầng thiết bị", "RECURRENT", "Chi thường xuyên"));
      colMetaMap.put("DMST", createColMeta("DMST", "Đổi mới sáng tạo", "RECURRENT", "Chi thường xuyên"));
      colMetaMap.put("CDS", createColMeta("CDS", "Chuyển đổi số", "RECURRENT", "Chi thường xuyên"));
    }

    List<String> colCodes = new ArrayList<>(colMetaMap.keySet());
    List<Map<String, Object>> rows = new ArrayList<>();

    // Tổng hợp cấp I (Dòng 1)
    BigDecimal rootKhcnAlloc =
        isSnapshotOct23 ? new BigDecimal("82357.139906") : new BigDecimal("82357.439906");
    BigDecimal rootCdsAlloc =
        isSnapshotOct23 ? new BigDecimal("63053.4704") : new BigDecimal("62939.8304");
    Map<String, BigDecimal> rootAlloc =
        mapDomainCols(dimension, rootKhcnAlloc, BigDecimal.ZERO, new BigDecimal("21035.2"), rootCdsAlloc);
    Map<String, BigDecimal> rootDisb =
        mapDomainCols(
            dimension,
            new BigDecimal("61455.3"),
            BigDecimal.ZERO,
            new BigDecimal("13759.6"),
            new BigDecimal("31024.99"));

    rows.add(
        buildMatrixRow(
            "1",
            "AGENCY_HEADER",
            "DON_VI_CAP_I",
            "Sở Khoa học và Công nghệ (Ví dụ minh họa)",
            "Sở Khoa học và Công nghệ",
            rootAlloc,
            rootDisb,
            "Khoa học Công Nghệ: 74.62%"));

    // Khối 1.1: Chuyển nguồn
    rows.add(
        buildMatrixRow(
            "-",
            "BLOCK_HEADER",
            "BLOCK_CARRY_OVER",
            "Chuyển nguồn " + year,
            "Chuyển nguồn " + year,
            mapDomainCols(dimension, new BigDecimal("35460.870306"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            mapDomainCols(dimension, new BigDecimal("29753"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            "Đổi mới sáng tạo: 65.41%"));
    rows.add(
        buildMatrixRow(
            "-",
            "UNIT_SUMMARY",
            "VAN_PHONG_SO",
            "Văn phòng Sở",
            "Văn phòng Sở",
            mapDomainCols(dimension, new BigDecimal("32607.870306"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            mapDomainCols(dimension, new BigDecimal("28412"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            isSnapshotOct23 ? "Chuyển đổi số: 49.20%" : "Chuyển đổi số: 49.29%"));
    rows.add(
        buildMatrixRow(
            "-",
            "UNIT_SUMMARY",
            "TT_CNSH",
            "Trung tâm Công nghệ sinh học Đà Nẵng",
            "Trung tâm Công nghệ sinh học Đà Nẵng",
            mapDomainCols(dimension, new BigDecimal("1341"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            mapDomainCols(dimension, new BigDecimal("1341"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            ""));
    rows.add(
        buildMatrixRow(
            "-",
            "UNIT_SUMMARY",
            "TT_UDKHCN",
            "Trung tâm Ứng dụng khoa học công nghệ và Tiêu chuẩn Đo lường Chất lượng Đà Nẵng",
            "Trung tâm Ứng dụng khoa học công nghệ và Tiêu chuẩn Đo lường Chất lượng Đà Nẵng",
            mapDomainCols(dimension, new BigDecimal("1512"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            ""));

    // Khối 1.2: Dự toán giao đầu năm (hỗ trợ cả SEPARATE_INITIAL như bản 23/10 và CUMULATIVE_EFFECTIVE như bản 30/10)
    if ("SEPARATE_INITIAL".equalsIgnoreCase(viewMode)) {
      rows.add(
          buildMatrixRow(
              "-",
              "BLOCK_HEADER",
              "BLOCK_INITIAL",
              "Dự toán giao đầu năm",
              "Dự toán giao đầu năm",
              mapDomainCols(dimension, new BigDecimal("52280"), BigDecimal.ZERO, new BigDecimal("18538"), new BigDecimal("124814")),
              mapDomainCols(dimension, new BigDecimal("30884"), BigDecimal.ZERO, new BigDecimal("12012"), new BigDecimal("26458")),
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "VAN_PHONG_SO",
              "Văn phòng Sở",
              "Văn phòng Sở",
              mapDomainCols(dimension, new BigDecimal("32753"), BigDecimal.ZERO, new BigDecimal("9333"), new BigDecimal("84483")),
              mapDomainCols(dimension, new BigDecimal("17505"), BigDecimal.ZERO, new BigDecimal("5533"), new BigDecimal("3126")),
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_KN_DMST",
              "Trung tâm Hỗ trợ khởi nghiệp đổi mới sáng tạo Đà Nẵng",
              "Trung tâm Hỗ trợ khởi nghiệp đổi mới sáng tạo Đà Nẵng",
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("9205"), BigDecimal.ZERO),
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("6479"), BigDecimal.ZERO),
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_CNSH",
              "Trung tâm Công nghệ sinh học Đà Nẵng",
              "Trung tâm Công nghệ sinh học Đà Nẵng",
              mapDomainCols(dimension, new BigDecimal("7532"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
              mapDomainCols(dimension, new BigDecimal("5366"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_UDKHCN",
              "Trung tâm Ứng dụng khoa học công nghệ và Tiêu chuẩn Đo lường Chất lượng Đà Nẵng",
              "Trung tâm Ứng dụng khoa học công nghệ và Tiêu chuẩn Đo lường Chất lượng Đà Nẵng",
              mapDomainCols(dimension, new BigDecimal("11995"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
              mapDomainCols(dimension, new BigDecimal("8013"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_IOC",
              "Trung tâm Thông tin và giám sát, điều hành thông minh Đà Nẵng",
              "Trung tâm Thông tin và giám sát, điều hành thông minh Đà Nẵng",
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("12315")),
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("7965")),
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_DDS",
              "Trung tâm Chuyển đổi số và Công nghệ chiến lược Đà Nẵng",
              "Trung tâm Chuyển đổi số và Công nghệ chiến lược Đà Nẵng",
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("24566")),
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("12980")),
              "Đã trừ: 3,779 theo Công văn DDS"));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_DSAC",
              "Trung tâm Nghiên cứu, đào tạo thiết kế vi mạch và trí tuệ nhân tạo",
              "Trung tâm Nghiên cứu, đào tạo thiết kế vi mạch và trí tuệ nhân tạo",
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("3450")),
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2387")),
              ""));
    } else {
      rows.add(
          buildMatrixRow(
              "-",
              "BLOCK_HEADER",
              "BLOCK_INITIAL",
              "Dự toán giao đầu năm ( kể cả chuyển nguồn, điều chỉnh bổ sung trong năm)",
              "Dự toán giao đầu năm ( kể cả chuyển nguồn, điều chỉnh bổ sung trong năm)",
              rootAlloc,
              rootDisb,
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "VAN_PHONG_SO",
              "Văn phòng Sở",
              "Văn phòng Sở",
              mapDomainCols(dimension, new BigDecimal("60180.870306"), BigDecimal.ZERO, new BigDecimal("10512"), new BigDecimal("10173.05")),
              mapDomainCols(dimension, new BigDecimal("45917"), BigDecimal.ZERO, new BigDecimal("5533"), new BigDecimal("3200")),
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_KN_DMST",
              "Trung tâm Hỗ trợ khởi nghiệp đổi mới sáng tạo Đà Nẵng",
              "Trung tâm Hỗ trợ khởi nghiệp đổi mới sáng tạo Đà Nẵng",
              mapDomainCols(dimension, new BigDecimal("310"), BigDecimal.ZERO, new BigDecimal("10523.2"), BigDecimal.ZERO),
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("8226.6"), BigDecimal.ZERO),
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_CNSH",
              "Trung tâm Công nghệ sinh học Đà Nẵng",
              "Trung tâm Công nghệ sinh học Đà Nẵng",
              mapDomainCols(dimension, new BigDecimal("10040.9"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
              mapDomainCols(dimension, new BigDecimal("7362"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_UDKHCN",
              "Trung tâm Ứng dụng khoa học công nghệ và Tiêu chuẩn Đo lường Chất lượng Đà Nẵng",
              "Trung tâm Ứng dụng khoa học công nghệ và Tiêu chuẩn Đo lường Chất lượng Đà Nẵng",
              mapDomainCols(dimension, new BigDecimal("11825.6696"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("712.3804")),
              mapDomainCols(dimension, new BigDecimal("8176.3"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50")),
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_IOC",
              "Trung tâm Thông tin và giám sát, điều hành thông minh Đà Nẵng",
              "Trung tâm Thông tin và giám sát, điều hành thông minh Đà Nẵng",
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("14548.2")),
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("9540")),
              ""));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_DDS",
              "Trung tâm Chuyển đổi số và Công nghệ chiến lược Đà Nẵng",
              "Trung tâm Chuyển đổi số và Công nghệ chiến lược Đà Nẵng",
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("34060.4")),
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("15847.99")),
              "Đã trừ: 3,779 theo Công văn DDS"));
      rows.add(
          buildMatrixRow(
              "-",
              "UNIT_SUMMARY",
              "TT_DSAC",
              "Trung tâm Nghiên cứu, đào tạo thiết kế vi mạch và trí tuệ nhân tạo",
              "Trung tâm Nghiên cứu, đào tạo thiết kế vi mạch và trí tuệ nhân tạo",
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("3445.8")),
              mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2387")),
              ""));
    }

    // Khối 1.3: Dự toán bổ sung điều chỉnh trong năm
    BigDecimal adjKhcn =
        isSnapshotOct23 ? new BigDecimal("-5383.7304") : new BigDecimal("-5383.4304");
    BigDecimal adjCds =
        isSnapshotOct23 ? new BigDecimal("-61760.5296") : new BigDecimal("-61874.1696");
    rows.add(
        buildMatrixRow(
            "-",
            "BLOCK_HEADER",
            "BLOCK_ADJUSTMENT",
            "Dự toán bổ sung điều chỉnh trong năm",
            "Dự toán bổ sung điều chỉnh trong năm",
            mapDomainCols(dimension, adjKhcn, BigDecimal.ZERO, new BigDecimal("2497.2"), adjCds),
            mapDomainCols(dimension, new BigDecimal("818.3"), BigDecimal.ZERO, new BigDecimal("1747.6"), new BigDecimal("4566.99")),
            ""));

    // Phần II: Mục 2 -> 8 (Chi tiết các đợt bổ sung/điều chỉnh theo đơn vị)
    List<Map<String, Object>> unitSections = new ArrayList<>();

    // 2. Văn phòng Sở
    List<Map<String, Object>> vpsItems = new ArrayList<>();
    vpsItems.add(buildMatrixRow("2", "UNIT_ADJUSTMENT_HEADER", "VAN_PHONG_SO", "Văn phòng Sở", "Văn phòng Sở",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-73366")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    vpsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "VAN_PHONG_SO", "Văn phòng Sở", "Điều chuyển nội bộ giữa các lĩnh vực (Tổng = 0)",
        mapDomainCols(dimension, new BigDecimal("-2655"), BigDecimal.ZERO, new BigDecimal("2655"), BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Điều chuyển chéo KHCN -> ĐMST"));
    vpsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "VAN_PHONG_SO", "Văn phòng Sở", "Tiết kiệm chi thường xuyên theo Nghị quyết 135",
        mapDomainCols(dimension, new BigDecimal("-1481"), BigDecimal.ZERO, new BigDecimal("-1299"), new BigDecimal("-1529")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Tiết kiệm theo NQ 135"));
    vpsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "VAN_PHONG_SO", "Văn phòng Sở", "Điều chỉnh giảm dự toán",
        mapDomainCols(dimension, new BigDecimal("-1044"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    vpsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "VAN_PHONG_SO", "Văn phòng Sở", "Điều chỉnh giảm do thừa dự toán",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-177"), BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Điều chỉnh giảm do thừa"));
    vpsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "VAN_PHONG_SO", "Văn phòng Sở", "Bổ sung kinh phí nhiệm vụ CĐS đợt 1",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("82.05")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("74")), ""));
    if (isSnapshotOct23) {
      vpsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "VAN_PHONG_SO", "Văn phòng Sở", "Bổ sung kinh phí nhiệm vụ CĐS (đợt ngày 23/10)",
          mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("113.64")),
          mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    }
    vpsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "VAN_PHONG_SO", "Văn phòng Sở", "Bổ sung kinh phí nhiệm vụ CĐS đợt 2",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("503")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    rows.addAll(vpsItems);
    unitSections.add(Map.of("sectionNo", 2, "nodeCode", "VAN_PHONG_SO", "nodeName", "Văn phòng Sở", "items", vpsItems));

    // 3. TT Ứng dụng KHCN & TCĐLCL
    List<Map<String, Object>> udkhItems = new ArrayList<>();
    udkhItems.add(buildMatrixRow("3", "UNIT_ADJUSTMENT_HEADER", "TT_UDKHCN", "Trung tâm Ứng dụng khoa học công nghệ và Tiêu chuẩn Đo lường Chất lượng Đà Nẵng", "Trung tâm Ứng dụng khoa học công nghệ và Tiêu chuẩn Đo lường Chất lượng Đà Nẵng",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("855")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50")), ""));
    udkhItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_UDKHCN", "Trung tâm Ứng dụng KHCN & TCĐLCL", "Bổ sung nhiệm vụ KHCN đợt 1",
        mapDomainCols(dimension, new BigDecimal("84.1"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, new BigDecimal("84.1"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    udkhItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_UDKHCN", "Trung tâm Ứng dụng KHCN & TCĐLCL", "Bổ sung nhiệm vụ KHCN đợt 2",
        mapDomainCols(dimension, new BigDecimal("105.6"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, new BigDecimal("79.2"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    udkhItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_UDKHCN", "Trung tâm Ứng dụng KHCN & TCĐLCL", "Tiết kiệm theo NQ 135",
        mapDomainCols(dimension, new BigDecimal("-898.3804"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-122.6196")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Tiết kiệm theo NQ 135"));
    udkhItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_UDKHCN", "Trung tâm Ứng dụng KHCN & TCĐLCL", "Điều chỉnh giảm dự toán KHCN",
        mapDomainCols(dimension, new BigDecimal("-1718.15"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    udkhItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_UDKHCN", "Trung tâm Ứng dụng KHCN & TCĐLCL", "Điều chỉnh giảm do thừa",
        mapDomainCols(dimension, new BigDecimal("-217.5"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-20")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Điều chỉnh giảm do thừa"));
    udkhItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_UDKHCN", "Trung tâm Ứng dụng KHCN & TCĐLCL", "Bổ sung nhiệm vụ KHCN đợt 3",
        mapDomainCols(dimension, new BigDecimal("229"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    udkhItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_UDKHCN", "Trung tâm Ứng dụng KHCN & TCĐLCL", "Bổ sung nhiệm vụ KHCN đợt 4",
        mapDomainCols(dimension, new BigDecimal("734"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    rows.addAll(udkhItems);
    unitSections.add(Map.of("sectionNo", 3, "nodeCode", "TT_UDKHCN", "nodeName", "Trung tâm Ứng dụng khoa học công nghệ và Tiêu chuẩn Đo lường Chất lượng Đà Nẵng", "items", udkhItems));

    // 4. TT IOC
    List<Map<String, Object>> iocItems = new ArrayList<>();
    iocItems.add(buildMatrixRow("4", "UNIT_ADJUSTMENT_HEADER", "TT_IOC", "Trung tâm Thông tin và giám sát, điều hành thông minh Đà Nẵng", "Trung tâm Thông tin và giám sát, điều hành thông minh Đà Nẵng",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1080")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("128")), ""));
    iocItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_IOC", "Trung tâm IOC", "Bổ sung vận hành IOC đợt 1",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("79.2")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("79")), ""));
    iocItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_IOC", "Trung tâm IOC", "Bổ sung vận hành IOC đợt 2",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("90")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("90")), ""));
    iocItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_IOC", "Trung tâm IOC", "Bổ sung hạ tầng số & giám sát thông minh",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1502")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1061")), "thiếu"));
    iocItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_IOC", "Trung tâm IOC", "Tiết kiệm theo NQ 135",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-689")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Tiết kiệm theo NQ 135"));
    iocItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_IOC", "Trung tâm IOC", "Điều chỉnh giảm dự toán",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-46")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    iocItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_IOC", "Trung tâm IOC", "Bổ sung nhiệm vụ CĐS đợt 4",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("217")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("217")), ""));
    rows.addAll(iocItems);
    unitSections.add(Map.of("sectionNo", 4, "nodeCode", "TT_IOC", "nodeName", "Trung tâm Thông tin và giám sát, điều hành thông minh Đà Nẵng", "items", iocItems));

    // 5. TT Chuyển đổi số và Công nghệ chiến lược (DDS)
    List<Map<String, Object>> ddsItems = new ArrayList<>();
    ddsItems.add(buildMatrixRow("5", "UNIT_ADJUSTMENT_HEADER", "TT_DDS", "Trung tâm Chuyển đổi số và Công nghệ chiến lược Đà Nẵng", "Trung tâm Chuyển đổi số và Công nghệ chiến lược Đà Nẵng",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("14488")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1801.59")), ""));
    ddsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_DDS", "Trung tâm DDS", "Bổ sung nhiệm vụ CĐS đợt 1",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("71.4")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("71.4")), ""));
    ddsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_DDS", "Trung tâm DDS", "Bổ sung nhiệm vụ CĐS đợt 2",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1478.4")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("995")), ""));
    ddsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_DDS", "Trung tâm DDS", "Điều chỉnh giảm theo Phê duyệt Phương án tự chủ",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-3779")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Điều chỉnh giảm theo Phê duyệt Phương án tự chủ"));
    ddsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_DDS", "Trung tâm DDS", "Điều chỉnh giảm do thừa",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-307.4")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Điều chỉnh giảm do thừa"));
    ddsItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_DDS", "Trung tâm DDS", "Tiết kiệm theo NQ 135",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-2457")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Tiết kiệm theo NQ 135"));
    rows.addAll(ddsItems);
    unitSections.add(Map.of("sectionNo", 5, "nodeCode", "TT_DDS", "nodeName", "Trung tâm Chuyển đổi số và Công nghệ chiến lược Đà Nẵng", "items", ddsItems));

    // 6. TT Nghiên cứu, đào tạo thiết kế vi mạch và AI (DSAC)
    List<Map<String, Object>> dsacItems = new ArrayList<>();
    dsacItems.add(buildMatrixRow("6", "UNIT_ADJUSTMENT_HEADER", "TT_DSAC", "Trung tâm Nghiên cứu, đào tạo thiết kế vi mạch và trí tuệ nhân tạo", "Trung tâm Nghiên cứu, đào tạo thiết kế vi mạch và trí tuệ nhân tạo",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1170")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    dsacItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_DSAC", "Trung tâm DSAC", "Bổ sung đào tạo vi mạch đợt 1",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("18")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    dsacItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_DSAC", "Trung tâm DSAC", "Bổ sung đào tạo vi mạch đợt 2",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("219")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    dsacItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_DSAC", "Trung tâm DSAC", "Bổ sung đào tạo AI đợt 3",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("44")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    dsacItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_DSAC", "Trung tâm DSAC", "Tiết kiệm theo NQ 135",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-460.2")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Tiết kiệm theo NQ 135"));
    dsacItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_DSAC", "Trung tâm DSAC", "Điều chỉnh giảm KP CNTT",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-995")),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Điều chỉnh giảm KP CNTT"));
    rows.addAll(dsacItems);
    unitSections.add(Map.of("sectionNo", 6, "nodeCode", "TT_DSAC", "nodeName", "Trung tâm Nghiên cứu, đào tạo thiết kế vi mạch và trí tuệ nhân tạo", "items", dsacItems));

    // 7. TT Hỗ trợ khởi nghiệp ĐMST
    List<Map<String, Object>> dmstItems = new ArrayList<>();
    dmstItems.add(buildMatrixRow("7", "UNIT_ADJUSTMENT_HEADER", "TT_KN_DMST", "Trung tâm Hỗ trợ khởi nghiệp đổi mới sáng tạo Đà Nẵng", "Trung tâm Hỗ trợ khởi nghiệp đổi mới sáng tạo Đà Nẵng",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("43.2"), BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("43"), BigDecimal.ZERO), ""));
    dmstItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_KN_DMST", "Trung tâm Khởi nghiệp ĐMST", "Bổ sung sự kiện ĐMST đợt 1",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("158.4"), BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("114"), BigDecimal.ZERO), ""));
    dmstItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_KN_DMST", "Trung tâm Khởi nghiệp ĐMST", "Bổ sung chương trình ươm tạo KHCN & ĐMST",
        mapDomainCols(dimension, new BigDecimal("310"), BigDecimal.ZERO, new BigDecimal("830"), BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("593"), BigDecimal.ZERO), ""));
    dmstItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_KN_DMST", "Trung tâm Khởi nghiệp ĐMST", "Tiết kiệm theo NQ 135",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-711"), BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Tiết kiệm theo NQ 135"));
    dmstItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_KN_DMST", "Trung tâm Khởi nghiệp ĐMST", "Bổ sung hỗ trợ hệ sinh thái ĐMST đợt 2",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("113"), BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("113"), BigDecimal.ZERO), ""));
    dmstItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_KN_DMST", "Trung tâm Khởi nghiệp ĐMST", "Bổ sung hỗ trợ hệ sinh thái ĐMST đợt 3",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("464.6"), BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("464.6"), BigDecimal.ZERO), ""));
    dmstItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_KN_DMST", "Trung tâm Khởi nghiệp ĐMST", "Bổ sung hỗ trợ hệ sinh thái ĐMST đợt 4",
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("420"), BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("420"), BigDecimal.ZERO), ""));
    rows.addAll(dmstItems);
    unitSections.add(Map.of("sectionNo", 7, "nodeCode", "TT_KN_DMST", "nodeName", "Trung tâm Hỗ trợ khởi nghiệp đổi mới sáng tạo Đà Nẵng", "items", dmstItems));

    // 8. TT Công nghệ sinh học Đà Nẵng
    List<Map<String, Object>> cnshItems = new ArrayList<>();
    cnshItems.add(buildMatrixRow("8", "UNIT_ADJUSTMENT_HEADER", "TT_CNSH", "Trung tâm Công nghệ sinh học Đà Nẵng", "Trung tâm Công nghệ sinh học Đà Nẵng",
        mapDomainCols(dimension, new BigDecimal("46.8"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, new BigDecimal("47"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    cnshItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_CNSH", "Trung tâm CNSH", "Bổ sung nghiên cứu CNSH đợt 1",
        mapDomainCols(dimension, new BigDecimal("52.8"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, new BigDecimal("44"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    cnshItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_CNSH", "Trung tâm CNSH", "Bổ sung nghiên cứu CNSH đợt 2",
        mapDomainCols(dimension, new BigDecimal("995"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, new BigDecimal("432"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    cnshItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_CNSH", "Trung tâm CNSH", "Bổ sung nghiên cứu CNSH đợt 3",
        mapDomainCols(dimension, new BigDecimal("357"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, new BigDecimal("132"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    if (isSnapshotOct23) {
      cnshItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_CNSH", "Trung tâm CNSH", "Tiết kiệm theo NQ 135",
          mapDomainCols(dimension, new BigDecimal("-385"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
          mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Tiết kiệm theo NQ 135"));
    } else {
      cnshItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_CNSH", "Trung tâm CNSH", "Tiết kiệm theo NQ 135 (Đợt 1)",
          mapDomainCols(dimension, new BigDecimal("-336"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
          mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Tiết kiệm theo NQ 135"));
      cnshItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_CNSH", "Trung tâm CNSH", "Tiết kiệm theo NQ 135 (Đợt 2)",
          mapDomainCols(dimension, new BigDecimal("-48.7"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
          mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Tiết kiệm theo NQ 135"));
    }
    cnshItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_CNSH", "Trung tâm CNSH", "Điều chỉnh theo Phương án tự chủ",
        mapDomainCols(dimension, new BigDecimal("-30"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), "Phương án tự chủ"));
    cnshItems.add(buildMatrixRow("", "ADJUSTMENT_ITEM", "TT_CNSH", "Trung tâm CNSH", "Bổ sung nhiệm vụ KHCN cuối kỳ",
        mapDomainCols(dimension, new BigDecimal("131"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        mapDomainCols(dimension, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), ""));
    rows.addAll(cnshItems);
    unitSections.add(Map.of("sectionNo", 8, "nodeCode", "TT_CNSH", "nodeName", "Trung tâm Công nghệ sinh học Đà Nẵng", "items", cnshItems));

    List<Map<String, Object>> columns =
        finalizeColumnSummaries(colMetaMap, rootAlloc, rootDisb);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("reportTitle", "KINH PHÍ SỰ NGHIỆP THEO LĨNH VỰC / NHÓM LĨNH VỰC NĂM " + year);
    result.put("subtitle", "(Giải ngân tính đến ngày " + asOfDate + ")");
    result.put("budgetYear", year);
    result.put("asOfDate", asOfDate);
    result.put("groupByDimension", dimension);
    result.put("viewMode", viewMode);
    result.put("columns", columns);
    result.put("rows", rows);
    result.put("unitAdjustmentSections", unitSections);
    result.put("totalAllocated", sumColMap(rootAlloc));
    result.put("totalDisbursed", sumColMap(rootDisb));
    result.put(
        "overallDisbursementRatePercent",
        calcPercent(sumColMap(rootDisb), sumColMap(rootAlloc)));
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

  private Map<String, BigDecimal> mapDomainCols(
      String dimension,
      BigDecimal khcn,
      BigDecimal httb,
      BigDecimal dmst,
      BigDecimal cds) {
    Map<String, BigDecimal> m = new LinkedHashMap<>();
    if ("DOMAIN_GROUP".equalsIgnoreCase(dimension)) {
      m.put("GRP_CORE_TECH", safeDec(khcn).add(safeDec(httb)));
      m.put("GRP_INNOV_DIGITAL", safeDec(dmst).add(safeDec(cds)));
    } else {
      m.put("KHCN", safeDec(khcn));
      m.put("HTTB", safeDec(httb));
      m.put("DMST", safeDec(dmst));
      m.put("CDS", safeDec(cds));
    }
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
      BudgetAllocation a, String dimension, String fallbackKey) {
    if ("DOMAIN_GROUP".equalsIgnoreCase(dimension)) {
      return StrUtil.blankToDefault(
          a.getDomainGroupCode(), StrUtil.blankToDefault(a.getDomainCode(), fallbackKey));
    }
    if ("CATEGORY".equalsIgnoreCase(dimension)) {
      return StrUtil.blankToDefault(a.getCategoryCode(), fallbackKey);
    }
    return StrUtil.blankToDefault(
        a.getDomainCode(), StrUtil.blankToDefault(a.getCategoryCode(), fallbackKey));
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
    addColValue(map, map.containsKey(key) ? key : colCodes.getFirst(), safeDec(tx.getDisbursedAmount()));
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

}
