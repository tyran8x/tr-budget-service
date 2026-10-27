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
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.service.BudgetAllocationRepo;

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
            .submittedByPersonId(12L)
            .submittedByPersonName("Đinh Quang Huy")
            .reviewedByPersonId(12L)
            .reviewedByPersonName("Đinh Quang Huy")
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
    rpt.setReviewedByPersonName("Đinh Quang Huy");
    return budgetReportMapper.toData(budgetReportService.save(rpt));
  }

}
