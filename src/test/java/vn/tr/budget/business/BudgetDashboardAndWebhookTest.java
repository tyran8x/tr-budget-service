package vn.tr.budget.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetDisbursementService;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.data.validator.BudgetDisbursementValidator;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.web.utils.BusinessLogicHelper;

/**
 * Kiểm thử đơn vị cho các tính năng nâng cấp Dashboard (Burn-rate Forecast, MTEF 3 năm, Scorecard
 * động), Đối soát Webhook Thanh toán tự động (UPG-01) và Biểu mẫu đối chiếu KBNN (UPG-03).
 */
@ExtendWith(MockitoExtension.class)
class BudgetDashboardAndWebhookTest {

  @Mock private BudgetAllocationRepo budgetAllocationRepo;
  @Mock private BudgetTransactionRepo budgetTransactionRepo;
  @Mock private BudgetDisbursementRepo budgetDisbursementRepo;
  @Mock private BudgetEstimateItemRepo budgetEstimateItemRepo;
  @Mock private BudgetDisbursementService budgetDisbursementService;
  @Mock private BudgetDisbursementMapper budgetDisbursementMapper;
  @Mock private BudgetDisbursementValidator budgetDisbursementValidator;
  @Mock private GenericUpsertHelper genericUpsertHelper;
  @Mock private AuditRollbackHelper auditRollbackHelper;
  @Mock private BusinessLogicHelper businessLogicHelper;
  @Mock private BudgetWorkflowEngineHelper workflowEngineHelper;

  @Test
  @DisplayName(
      "UPG-02 & UPG-04: Dự báo tốc độ tiêu dùng Burn-rate, gợi ý điều chuyển nội bộ và Khung MTEF 3 năm")
  void shouldComputeDynamicScorecardBurnRateAndMtef() {
    BudgetAllocation unitSurplus =
        BudgetAllocation.builder()
            .id(1L)
            .code("PB-2026-VP")
            .nodeId(101L)
            .nodeCode("VAN_PHONG_SO")
            .nodeName("Văn phòng Sở")
            .budgetYear(2026)
            .itemType("EXPENDITURE")
            .initialAmount(new BigDecimal("1000000000"))
            .effectiveAmount(new BigDecimal("1000000000"))
            .committedAmount(new BigDecimal("50000000"))
            .disbursedAmount(new BigDecimal("200000000"))
            .settledAmount(new BigDecimal("200000000"))
            .availableAmount(new BigDecimal("750000000"))
            .build();

    BudgetAllocation unitHighBurn =
        BudgetAllocation.builder()
            .id(2L)
            .code("PB-2026-TT")
            .nodeId(102L)
            .nodeCode("THANH_TRA_SO")
            .nodeName("Thanh tra Sở")
            .budgetYear(2026)
            .itemType("EXPENDITURE")
            .initialAmount(new BigDecimal("500000000"))
            .effectiveAmount(new BigDecimal("500000000"))
            .committedAmount(new BigDecimal("50000000"))
            .disbursedAmount(new BigDecimal("420000000"))
            .settledAmount(new BigDecimal("400000000"))
            .availableAmount(new BigDecimal("30000000"))
            .build();

    when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2026))
        .thenReturn(List.of(unitSurplus, unitHighBurn));
    when(budgetTransactionRepo.findByBudgetYearOrderByIdDesc(2026)).thenReturn(List.of());
    when(budgetDisbursementRepo.findByBudgetYearOrderByIdDesc(2026))
        .thenReturn(
            List.of(
                BudgetDisbursement.builder()
                    .id(10L)
                    .nodeId(101L)
                    .voucherNo("UNC-2026-01")
                    .treasuryRefCode("KBNN-001")
                    .build()));

    BudgetDashboardBusiness dashboardBusiness =
        new BudgetDashboardBusiness(
            budgetAllocationRepo,
            budgetTransactionRepo,
            budgetDisbursementRepo,
            budgetEstimateItemRepo);

    // 1. Kiểm tra Scorecard tính động
    List<Map<String, Object>> scorecards = dashboardBusiness.getDisbursementScorecard(2026);
    assertEquals(2, scorecards.size());
    assertEquals(new BigDecimal("20.0"), scorecards.get(0).get("estimateAccuracyScore"));
    assertEquals(new BigDecimal("15.0"), scorecards.get(0).get("voucherComplianceScore"));

    // 2. Kiểm tra Burn-rate Forecast & Gợi ý điều chuyển nội bộ (UPG-02)
    Map<String, Object> burnForecast = dashboardBusiness.getBurnRateForecast(2026);
    assertNotNull(burnForecast.get("unitForecasts"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> suggestions =
        (List<Map<String, Object>>) burnForecast.get("reallocationSuggestions");
    assertFalse(suggestions.isEmpty());
    assertEquals("INTERNAL_TRANSFER", suggestions.get(0).get("txType"));
    assertEquals("VAN_PHONG_SO", suggestions.get(0).get("sourceUnitCode"));
    assertEquals("THANH_TRA_SO", suggestions.get(0).get("targetUnitCode"));

    // 3. Kiểm tra Khung MTEF 3 năm (UPG-04)
    Map<String, Object> mtef = dashboardBusiness.getMtefSummary(2026);
    assertEquals("2026-2028", mtef.get("periodRange"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rollingYears = (List<Map<String, Object>>) mtef.get("rollingYears");
    assertEquals(3, rollingYears.size());
  }

  @Test
  @DisplayName(
      "UPG-01: Tự động đối soát và quyết toán hồ sơ giải ngân từ Webhook Thanh toán (settleFromPaymentWebhook)")
  void shouldAutoSettleDisbursementFromPaymentWebhook() {
    BudgetDisbursement existing =
        BudgetDisbursement.builder()
            .id(55L)
            .code("GN-2026-055")
            .name("Thanh toán hợp đồng phần mềm")
            .allocationId(1L)
            .amount(new BigDecimal("150000000"))
            .disbursementType("DISBURSEMENT")
            .approvalStatus("APPROVED_DISBURSED")
            .build();

    when(budgetDisbursementService.findById(55L)).thenReturn(Optional.of(existing));
    when(budgetDisbursementService.save(any(BudgetDisbursement.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(budgetDisbursementMapper.toData(any(BudgetDisbursement.class)))
        .thenReturn(
            BudgetDisbursementData.builder()
                .id(55L)
                .code("GN-2026-055")
                .approvalStatus("SETTLED")
                .build());

    BudgetDisbursementBusiness business =
        new BudgetDisbursementBusiness(
            budgetDisbursementService,
            budgetDisbursementMapper,
            budgetDisbursementValidator,
            genericUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper,
            workflowEngineHelper,
            budgetAllocationRepo);

    Map<String, Object> res =
        business.settleFromPaymentWebhook(
            55L,
            Map.of(
                "paymentTxCode", "SEPAY-20261028-9988",
                "treasuryRefCode", "KBNN-DN-7788",
                "voucherNo", "UNC-2026-055"));

    assertEquals(true, res.get("settled"));
    assertEquals("SEPAY-20261028-9988", res.get("paymentTxCode"));
    assertEquals("KBNN-DN-7788", res.get("treasuryRefCode"));
    assertEquals("SETTLED", res.get("approvalStatus"));
    verify(workflowEngineHelper, times(1)).recalculateAllocationMetrics(1L);
  }
}
