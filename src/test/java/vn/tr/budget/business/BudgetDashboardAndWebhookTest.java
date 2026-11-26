package vn.tr.budget.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.same.SaSameUtil;
import cn.dev33.satoken.stp.StpUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.controller.BudgetInternalController;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetDisbursementService;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.dao.service.BudgetUnitConfigRepo;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.data.validator.BudgetDisbursementValidator;
import vn.tr.common.core.exception.base.BaseException;
import vn.tr.common.feign.budget.data.BudgetPaymentSettleData;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.satoken.utils.LoginHelper;
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
            .estimateYearPlus1(new BigDecimal("1100000000"))
            .estimateYearPlus2(new BigDecimal("1200000000"))
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
            .estimateYearPlus1(new BigDecimal("550000000"))
            .estimateYearPlus2(new BigDecimal("600000000"))
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

    // 3. Kiểm tra Khung MTEF 3 năm (UPG-04) & đồng bộ Contract FE
    Map<String, Object> mtef = dashboardBusiness.getMtefSummary(2026);
    assertEquals("2026-2028", mtef.get("periodRange"));
    assertEquals("2026 - 2028", mtef.get("frameworkPeriod"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rollingYears = (List<Map<String, Object>>) mtef.get("rollingYears");
    assertEquals(3, rollingYears.size());
    assertNotNull(rollingYears.get(0).get("baselineRecurrentCeiling"));
    assertNotNull(rollingYears.get(0).get("developmentInvestmentCeiling"));
    assertNotNull(rollingYears.get(0).get("totalMtefCeiling"));
    assertEquals(
        0,
        new BigDecimal("1650000000")
            .compareTo((BigDecimal) rollingYears.get(1).get("totalMtefCeiling")));
    assertEquals(
        0,
        new BigDecimal("1800000000")
            .compareTo((BigDecimal) rollingYears.get(2).get("totalMtefCeiling")));

    // 4. Kiểm tra Mô phỏng kịch bản ngân sách What-If (UPG-08)
    Map<String, Object> whatIf =
        dashboardBusiness.simulateWhatIfScenario(
            Map.of(
                "budgetYear", 2026,
                "recurrentSavingRatePercent", 10,
                "revenueChangeRatePercent", 5,
                "reallocateSavedToDevelopmentPercent", 70));
    assertEquals(new BigDecimal("150000000"), whatIf.get("savedRecurrentAmount"));
    assertEquals(new BigDecimal("105000000"), whatIf.get("reallocatedToPriorityAmount"));
    assertEquals(new BigDecimal("45000000"), whatIf.get("salaryReformReserveAmount"));
    assertTrue(
        ((BigDecimal) whatIf.get("simulatedYearEndDisbursementRate")).compareTo(BigDecimal.ZERO)
            > 0);
  }

  @Test
  @DisplayName(
      "UPG-01 & UPG-07: Tự động đối soát Webhook Thanh toán, kết xuất gói DVC KBNN C2-02a/NS và tính lại số dư khi xóa")
  void shouldAutoSettleDisbursementFromPaymentWebhook() {
    BudgetDisbursement existing =
        BudgetDisbursement.builder()
            .id(55L)
            .code("GN-2026-055")
            .name("Thanh toán hợp đồng phần mềm")
            .allocationId(1L)
            .nodeId(101L)
            .nodeCode("VAN_PHONG_SO")
            .nodeName("Văn phòng Sở")
            .budgetYear(2026)
            .amount(new BigDecimal("150000000"))
            .taxAmount(new BigDecimal("15000000"))
            .netPaymentAmount(new BigDecimal("135000000"))
            .disbursementType("DISBURSEMENT")
            .approvalStatus("APPROVED_DISBURSED")
            .beneficiaryName("Đơn vị Thụ hưởng Demo")
            .beneficiaryBankAccount("118000012345")
            .beneficiaryBankName("VietinBank - CN Đà Nẵng")
            .treasuryRefCode("KBNN-DN-7788")
            .voucherNo("UNC-2026-055")
            .build();

    BudgetDisbursement draftDisb =
        BudgetDisbursement.builder()
            .id(56L)
            .code("GN-2026-056")
            .allocationId(1L)
            .nodeId(101L)
            .amount(new BigDecimal("20000000"))
            .approvalStatus("DRAFT")
            .build();

    BudgetUnitConfigRepo unitConfigRepo = mock(BudgetUnitConfigRepo.class);
    when(unitConfigRepo.findFirstByNodeIdOrderByIdDesc(101L))
        .thenReturn(
            Optional.of(
                BudgetUnitConfig.builder()
                    .id(1L)
                    .nodeId(101L)
                    .nodeCode("VAN_PHONG_SO")
                    .treasuryAccountNo("3712.1.9990101.00000")
                    .treasuryBranchName("KBNN Đà Nẵng")
                    .taxCode("0000000101")
                    .build()));

    when(budgetDisbursementService.findById(55L)).thenReturn(Optional.of(existing));
    when(budgetDisbursementService.findById(56L)).thenReturn(Optional.of(draftDisb));
    when(budgetDisbursementService.save(any(BudgetDisbursement.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(budgetDisbursementMapper.toData(any(BudgetDisbursement.class)))
        .thenReturn(
            BudgetDisbursementData.builder()
                .id(55L)
                .code("GN-2026-055")
                .approvalStatus("SETTLED")
                .build());
    lenient()
        .when(workflowEngineHelper.safe(any()))
        .thenAnswer(inv -> inv.getArgument(0) != null ? inv.getArgument(0) : BigDecimal.ZERO);
    lenient()
        .when(workflowEngineHelper.scaleMoney(any()))
        .thenAnswer(
            inv -> {
              BigDecimal val = inv.getArgument(0);
              return (val != null ? val : BigDecimal.ZERO)
                  .setScale(2, java.math.RoundingMode.HALF_UP);
            });

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
    business.setBudgetUnitConfigRepo(unitConfigRepo);

    Map<String, Object> res =
        business.settleFromPaymentWebhook(
            55L,
            BudgetPaymentSettleData.builder()
                .transactionCode("SEPAY-20261028-9988")
                .paymentStatus("PAID")
                .paidAmount(new BigDecimal("135000000"))
                .build());

    assertEquals(true, res.get("settled"));
    assertEquals("SEPAY-20261028-9988", res.get("paymentTxCode"));
    assertEquals("KBNN-DN-7788", res.get("treasuryRefCode"));
    assertEquals("SETTLED", res.get("approvalStatus"));

    // Kiểm tra kết xuất gói DVC KBNN / TABMIS (UPG-07) dùng tài khoản cấu hình thật
    Map<String, Object> dvcPkg = business.exportKbnnDvcPackage(55L);
    assertEquals("READY_FOR_VGCA_TOKEN_SIGNING", dvcPkg.get("digitalSignatureStatus"));
    assertTrue(String.valueOf(dvcPkg.get("xmlPayloadPreview")).contains("DVC_KBNN_PACKAGE"));
    assertTrue(String.valueOf(dvcPkg.get("xmlPayloadPreview")).contains("3712.1.9990101.00000"));

    // Chứng từ đã SETTLED bị bất biến -> chặn xóa (TD-C06)
    assertThrows(BaseException.class, () -> business.delete(55L));

    // Xóa giải ngân ở trạng thái DRAFT tự động tính lại số dư dự toán
    business.delete(56L);
    verify(workflowEngineHelper, times(2)).recalculateAllocationMetrics(1L);
  }

  @Test
  @DisplayName(
      "Dynamic Domain/Group Matrix: Tự động phát hiện cột động theo Lĩnh vực / Nhóm lĩnh vực và chốt số liệu theo ngày asOfDate")
  void shouldGenerateDynamicDomainMatrixWithoutHardcodedColumns() {
    BudgetReportBusiness reportBusiness =
        new BudgetReportBusiness(
            null,
            null,
            null,
            genericUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper,
            budgetAllocationRepo);

    // 1. Khi DB trống -> trả về ma trận rỗng thực tế từ DB (không trả dữ liệu mẫu giả lập)
    when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2026)).thenReturn(List.of());

    Map<String, Object> emptyMatrix =
        reportBusiness.generateDynamicDomainMatrix(
            2026, "2026-10-23", "DOMAIN", "SEPARATE_INITIAL", null);
    assertEquals("SEPARATE_INITIAL", emptyMatrix.get("viewMode"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> emptyCols = (List<Map<String, Object>>) emptyMatrix.get("columns");
    assertEquals(1, emptyCols.size());
    assertEquals("GENERAL", emptyCols.get(0).get("code"));
    assertEquals(BigDecimal.ZERO, emptyMatrix.get("totalAllocated"));
    assertEquals(BigDecimal.ZERO, emptyMatrix.get("totalDisbursed"));

    // 2. Khi DB có dữ liệu của các Lĩnh vực tùy biến và giao dịch điều chỉnh theo mốc asOfDate -> tự động sinh cột động theo DB
    BudgetAllocation customUnit1 =
        BudgetAllocation.builder()
            .id(10L)
            .code("PB-SCT-01")
            .budgetYear(2026)
            .itemType("EXPENDITURE")
            .nodeCode("PHONG_QLCN")
            .nodeName("Phòng Quản lý Công nghiệp")
            .domainCode("XUC_TIEN_TM")
            .domainName("Xúc tiến Thương mại")
            .domainGroupCode("KINH_TE_NGANH")
            .domainGroupName("Sự nghiệp Kinh tế ngành")
            .initialAmount(new BigDecimal("5000"))
            .effectiveAmount(new BigDecimal("5000"))
            .disbursedAmount(new BigDecimal("3500"))
            .build();
    BudgetAllocation customUnit2 =
        BudgetAllocation.builder()
            .id(11L)
            .code("PB-SCT-02")
            .budgetYear(2026)
            .itemType("EXPENDITURE")
            .nodeCode("TT_KHUYEN_CONG")
            .nodeName("Trung tâm Khuyến công")
            .domainCode("KHUYEN_CONG")
            .domainName("Khuyến công & Tiết kiệm năng lượng")
            .domainGroupCode("KINH_TE_NGANH")
            .domainGroupName("Sự nghiệp Kinh tế ngành")
            .initialAmount(new BigDecimal("8000"))
            .carryOverInAmount(new BigDecimal("1200"))
            .effectiveAmount(new BigDecimal("9200"))
            .disbursedAmount(new BigDecimal("6000"))
            .build();

    when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2026))
        .thenReturn(List.of(customUnit1, customUnit2));
    reportBusiness.setBudgetTransactionRepo(budgetTransactionRepo);
    when(budgetTransactionRepo.findByBudgetYearOrderByIdDesc(2026))
        .thenReturn(
            List.of(
                BudgetTransaction.builder()
                    .id(501L)
                    .allocationId(10L)
                    .nodeCode("PHONG_QLCN")
                    .nodeName("Phòng Quản lý Công nghiệp")
                    .domainCode("XUC_TIEN_TM")
                    .budgetYear(2026)
                    .txType("SUPPLEMENT")
                    .txDate(LocalDate.of(2026, 10, 28))
                    .amount(new BigDecimal("800"))
                    .approvalStatus("APPROVED")
                    .build()));

    Map<String, Object> matrixOct23 =
        reportBusiness.generateDynamicDomainMatrix(
            2026, "2026-10-23", "DOMAIN", "SEPARATE_INITIAL", null);
    assertEquals("SEPARATE_INITIAL", matrixOct23.get("viewMode"));
    assertEquals(new BigDecimal("13000"), matrixOct23.get("totalInitial"));
    assertEquals(new BigDecimal("14200"), matrixOct23.get("totalAllocated"));
    assertEquals(BigDecimal.ZERO, matrixOct23.get("totalAdjustment"));

    Map<String, Object> customMatrix =
        reportBusiness.generateDynamicDomainMatrix(
            2026, "2026-10-30", "DOMAIN", "CUMULATIVE_EFFECTIVE", null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> customCols =
        (List<Map<String, Object>>) customMatrix.get("columns");
    assertEquals(2, customCols.size());
    assertEquals("XUC_TIEN_TM", customCols.get(0).get("code"));
    assertEquals("KHUYEN_CONG", customCols.get(1).get("code"));
    assertEquals(new BigDecimal("15000"), customMatrix.get("totalAllocated"));
    assertEquals(new BigDecimal("9500"), customMatrix.get("totalDisbursed"));
    assertEquals(new BigDecimal("800"), customMatrix.get("totalAdjustment"));
  }

  @Test
  @DisplayName(
      "S2S Internal Controller: Kiểm tra 5 endpoint nội bộ /internal/budget tương thích với BudgetInternalClient")
  void shouldExposeAllFiveInternalEndpointsOnBudgetInternalController() {
    BudgetAllocationBusiness allocBusiness = mock(BudgetAllocationBusiness.class);
    BudgetDisbursementBusiness disbBusiness = mock(BudgetDisbursementBusiness.class);
    BudgetDashboardBusiness dashBusiness = mock(BudgetDashboardBusiness.class);

    vn.tr.common.feign.budget.data.BudgetPlanSyncResultData syncResult =
        vn.tr.common.feign.budget.data.BudgetPlanSyncResultData.builder()
            .linkedPlanCode("KH-2026-01")
            .effectiveAmount(new BigDecimal("500"))
            .build();

    when(allocBusiness.syncFromPlan(any())).thenReturn(syncResult);
    when(allocBusiness.getBudgetSnapshotByPlanCode("KH-2026-01")).thenReturn(syncResult);
    when(disbBusiness.reserveCommitment(any()))
        .thenReturn(Map.of("committed", true, "disbursementCode", "CKC-01"));
    when(disbBusiness.settleFromPaymentWebhook(eq(55L), any()))
        .thenReturn(Map.of("settled", true, "id", 55L));
    when(dashBusiness.getOverview(2026, 101L, null))
        .thenReturn(Map.of("budgetYear", 2026, "nodeId", 101L));

    BudgetTransactionBusiness mockTxBusiness = mock(BudgetTransactionBusiness.class);
    when(mockTxBusiness.recordFromPlan(any()))
        .thenReturn(Map.of("recorded", true, "transactionCode", "PLNTX-KH-01"));

    com.fasterxml.jackson.databind.ObjectMapper objectMapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    objectMapper.findAndRegisterModules();
    BudgetInternalController internalController =
        new BudgetInternalController(
            allocBusiness, disbBusiness, mockTxBusiness, dashBusiness, objectMapper);

    try (MockedStatic<SaSameUtil> sameMock = mockStatic(SaSameUtil.class)) {
      assertEquals(
          "KH-2026-01",
          internalController
              .syncAllocationFromPlan(
                  vn.tr.common.feign.budget.data.BudgetPlanSyncRequestData.builder()
                      .planCode("KH-2026-01")
                      .build())
              .getData()
              .getLinkedPlanCode());
      assertEquals(
          "KH-2026-01",
          internalController
              .getBudgetSnapshotByPlanCode("KH-2026-01")
              .getData()
              .getLinkedPlanCode());
      assertEquals(
          true,
          internalController
              .reserveCommitment(
                  vn.tr.common.feign.budget.data.BudgetCommitmentReserveData.builder()
                      .planItemCode("NV-01")
                      .build())
              .getData()
              .get("committed"));
      assertEquals(
          true,
          internalController
              .settleDisbursementFromPaymentWebhook(
                  55L,
                  vn.tr.common.feign.budget.data.BudgetPaymentSettleData.builder()
                      .transactionCode("TX-01")
                      .paymentStatus("PAID")
                      .paidAmount(new BigDecimal("500"))
                      .build())
              .getData()
              .get("settled"));
      assertEquals(
          2026, internalController.getDashboardOverview(2026, 101L).getData().get("budgetYear"));
      assertEquals(
          true,
          internalController
              .recordPlanLedgerTransaction(
                  vn.tr.common.feign.budget.data.BudgetLedgerTransactionData.builder()
                      .planCode("KH-2026-01")
                      .build())
              .getData()
              .get("recorded"));
      sameMock.verify(SaSameUtil::checkCurrentRequestToken, times(6));
    }
  }

  @Test
  @DisplayName(
      "Year-Scoped Domain Config: Cấu hình Lĩnh vực & Nhóm lĩnh vực theo Năm ngân sách (2025 vs 2026) và Sao chép sang năm mới")
  void shouldManageAndCloneYearScopedDomainConfigs() {
    vn.tr.budget.dao.service.BudgetCategoryRepo categoryRepo =
        mock(vn.tr.budget.dao.service.BudgetCategoryRepo.class);
    vn.tr.budget.dao.service.BudgetCategoryService categoryService =
        mock(vn.tr.budget.dao.service.BudgetCategoryService.class);
    vn.tr.budget.data.mapper.BudgetCategoryMapper categoryMapper =
        mock(vn.tr.budget.data.mapper.BudgetCategoryMapper.class);
    vn.tr.budget.data.validator.BudgetCategoryValidator categoryValidator =
        mock(vn.tr.budget.data.validator.BudgetCategoryValidator.class);

    when(categoryService.getRepo()).thenReturn(categoryRepo);

    vn.tr.budget.dao.model.BudgetCategory dom2025 =
        vn.tr.budget.dao.model.BudgetCategory.builder()
            .id(101L)
            .code("DOM-2025-TCDLCL")
            .name("Tiêu chuẩn Đo lường Chất lượng")
            .categoryGroup("DOMAIN")
            .budgetYear(2025)
            .domainCode("TCDLCL")
            .domainGroupCode("GRP_STD_TECH")
            .domainGroupName("Nhóm KHCN & Tiêu chuẩn Đo lường")
            .sortOrder(2)
            .build();

    when(categoryRepo.findByBudgetYearAndCategoryGroupInOrderBySortOrderAscIdAsc(
            eq(2025), any()))
        .thenReturn(List.of(dom2025));
    when(categoryRepo.findByCategoryGroupInOrderByBudgetYearAscSortOrderAscIdAsc(any()))
        .thenReturn(List.of(dom2025));
    when(categoryMapper.toData(any(vn.tr.budget.dao.model.BudgetCategory.class)))
        .thenAnswer(
            inv -> {
              vn.tr.budget.dao.model.BudgetCategory c = inv.getArgument(0);
              return vn.tr.budget.data.dto.BudgetCategoryData.builder()
                  .id(c.getId())
                  .code(c.getCode())
                  .name(c.getName())
                  .categoryGroup(c.getCategoryGroup())
                  .budgetYear(c.getBudgetYear())
                  .domainCode(c.getDomainCode())
                  .domainGroupCode(c.getDomainGroupCode())
                  .domainGroupName(c.getDomainGroupName())
                  .previousYearDomainCode(c.getPreviousYearDomainCode())
                  .sortOrder(c.getSortOrder())
                  .build();
            });

    BudgetCategoryBusiness categoryBusiness =
        new BudgetCategoryBusiness(
            categoryService,
            categoryMapper,
            categoryValidator,
            genericUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper);

    Map<String, Object> cfg2025 = categoryBusiness.getYearDomainConfig(2025, null);
    assertEquals(2025, cfg2025.get("budgetYear"));
    @SuppressWarnings("unchecked")
    List<vn.tr.budget.data.dto.BudgetCategoryData> domains2025 =
        (List<vn.tr.budget.data.dto.BudgetCategoryData>) cfg2025.get("domains");
    assertEquals(1, domains2025.size());
    assertEquals("TCDLCL", domains2025.get(0).getDomainCode());

    // Sao chép cấu hình từ 2025 sang 2026
    when(categoryRepo.findByBudgetYearAndCategoryGroupInOrderBySortOrderAscIdAsc(
            eq(2026), any()))
        .thenReturn(List.of());
    Map<String, Object> cloneRes =
        categoryBusiness.cloneYearDomainConfig(
            Map.of("sourceYear", 2025, "targetYear", 2026, "overwrite", false));
    assertEquals(2025, cloneRes.get("sourceYear"));
    assertEquals(2026, cloneRes.get("targetYear"));
    assertEquals(1, cloneRes.get("clonedCount"));
  }

  @Test
  @DisplayName(
      "Auxiliary Budget Operations (NV-01..NV-05): Bổ sung nội dung, Chuyển nguồn liên năm, Điều chuyển chéo lĩnh vực (Tổng = 0), Tiết kiệm NQ 135 & Thu hồi hết niên độ")
  void shouldExecuteAuxiliaryBudgetOperationsEndToEnd() {
    vn.tr.budget.dao.service.BudgetTransactionService txService =
        mock(vn.tr.budget.dao.service.BudgetTransactionService.class);
    vn.tr.budget.data.mapper.BudgetTransactionMapper txMapper =
        mock(vn.tr.budget.data.mapper.BudgetTransactionMapper.class);
    vn.tr.budget.data.validator.BudgetTransactionValidator txValidator =
        mock(vn.tr.budget.data.validator.BudgetTransactionValidator.class);

    BudgetAllocation baseAlloc =
        BudgetAllocation.builder()
            .id(1L)
            .code("PB-2026-VP")
            .name("Dự toán Văn phòng Sở năm 2026")
            .budgetYear(2026)
            .nodeId(101L)
            .nodeCode("VAN_PHONG_SO")
            .nodeName("Văn phòng Sở")
            .itemType("EXPENDITURE")
            .domainCode("KHCN")
            .domainName("Khoa học công nghệ")
            .initialAmount(new BigDecimal("10000000000"))
            .effectiveAmount(new BigDecimal("10000000000"))
            .availableAmount(new BigDecimal("10000000000"))
            .build();

    BudgetAllocation baseAlloc2025 =
        BudgetAllocation.builder()
            .id(2L)
            .code("PB-2025-VP")
            .name("Dự toán Văn phòng Sở năm 2025")
            .budgetYear(2025)
            .nodeId(101L)
            .nodeCode("VAN_PHONG_SO")
            .nodeName("Văn phòng Sở")
            .itemType("EXPENDITURE")
            .domainCode("TCDLCL")
            .domainName("Tiêu chuẩn đo lường chất lượng")
            .initialAmount(new BigDecimal("5000000000"))
            .effectiveAmount(new BigDecimal("5000000000"))
            .availableAmount(new BigDecimal("5000000000"))
            .build();

    lenient().when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2025))
        .thenReturn(List.of(baseAlloc2025));
    lenient().when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2026))
        .thenReturn(List.of(baseAlloc));
    lenient().when(budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(101L, 2025))
        .thenReturn(List.of(baseAlloc2025));
    lenient().when(budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(101L, 2026))
        .thenReturn(List.of(baseAlloc));
    lenient().when(budgetAllocationRepo.findById(1L)).thenReturn(Optional.of(baseAlloc));
    lenient().when(budgetAllocationRepo.findById(2L)).thenReturn(Optional.of(baseAlloc2025));
    lenient().when(workflowEngineHelper.lockAllocation(1L)).thenReturn(Optional.of(baseAlloc));
    lenient().when(workflowEngineHelper.lockAllocation(2L)).thenReturn(Optional.of(baseAlloc2025));
    lenient()
        .when(workflowEngineHelper.computeCurrentEarmarkHeldAmount(1L))
        .thenReturn(new BigDecimal("5000000000"));
    lenient()
        .when(budgetAllocationRepo.save(any(BudgetAllocation.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient()
        .when(workflowEngineHelper.safe(any()))
        .thenAnswer(inv -> inv.getArgument(0) != null ? inv.getArgument(0) : BigDecimal.ZERO);

    when(genericUpsertHelper.upsert(any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              vn.tr.budget.data.dto.BudgetTransactionData d = inv.getArgument(0);
              return vn.tr.budget.dao.model.BudgetTransaction.builder()
                  .id(99L)
                  .code(d.getCode())
                  .name(d.getName())
                  .allocationId(d.getAllocationId())
                  .allocationCode(d.getAllocationCode())
                  .txType(d.getTxType())
                  .amount(d.getAmount())
                  .dynamicBreakdownJson(d.getDynamicBreakdownJson())
                  .approvalStatus(
                      d.getApprovalStatus() != null ? d.getApprovalStatus() : "PENDING_APPROVAL")
                  .build();
            });
    when(txMapper.toData(any(vn.tr.budget.dao.model.BudgetTransaction.class)))
        .thenAnswer(
            inv -> {
              vn.tr.budget.dao.model.BudgetTransaction e = inv.getArgument(0);
              return vn.tr.budget.data.dto.BudgetTransactionData.builder()
                  .id(e.getId())
                  .code(e.getCode())
                  .name(e.getName())
                  .txType(e.getTxType())
                  .amount(e.getAmount())
                  .dynamicBreakdownJson(e.getDynamicBreakdownJson())
                  .approvalStatus(e.getApprovalStatus())
                  .build();
            });

    BudgetTransactionBusiness txBusiness =
        new BudgetTransactionBusiness(
            txService,
            txMapper,
            txValidator,
            genericUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper,
            workflowEngineHelper,
            budgetAllocationRepo);

    BudgetIntegrationOrchestrator mockOrchestrator = mock(BudgetIntegrationOrchestrator.class);
    lenient().when(mockOrchestrator.resolveActiveStaffCount(101L)).thenReturn(35);
    txBusiness.setIntegrationOrchestrator(mockOrchestrator);

    // Kiểm tra chặn spoofing khi chưa đăng nhập dù truyền actorRole trong body
    assertThrows(
        BaseException.class,
        () ->
            txBusiness.supplementBudgetContent(
                Map.of(
                    "budgetYear", 2026,
                    "contentTitle", "Spoofed Director Action",
                    "amount", "1000000",
                    "actorRole", "STC_DIRECTOR")));

    AtomicBoolean isSuperAdmin = new AtomicBoolean(false);
    AtomicReference<List<String>> currentRoles =
        new AtomicReference<>(List.of("ROLE_BUDGET_DIRECTORATE"));

    try (MockedStatic<LoginHelper> loginMock = mockStatic(LoginHelper.class);
        MockedStatic<StpUtil> stpMock = mockStatic(StpUtil.class)) {
      loginMock.when(LoginHelper::isSuperAdmin).thenAnswer(inv -> isSuperAdmin.get());
      stpMock.when(StpUtil::isLogin).thenReturn(true);
      stpMock.when(StpUtil::getRoleList).thenAnswer(inv -> currentRoles.get());
      stpMock.when(StpUtil::getPermissionList).thenReturn(List.of());

      // 1. NV-01: Bổ sung nội dung nhiệm vụ chi
      Map<String, Object> suppRes =
          txBusiness.supplementBudgetContent(
              Map.of(
                  "budgetYear", 2026,
                  "nodeId", 101L,
                  "contentTitle", "Bổ sung kinh phí vận hành hạ tầng IOC",
                  "amount", "1502000000",
                  "domainCode", "CDS",
                  "decisionNo", "QĐ-2150/QĐ-UBND"));
      assertEquals(true, suppRes.get("supplemented"));
      assertEquals(new BigDecimal("1502000000"), suppRes.get("supplementedAmount"));

      // 2. NV-02: Chuyển nguồn liên năm (2025 -> 2026)
      Map<String, Object> carryRes =
          txBusiness.executeYearCarryOver(
              Map.of(
                  "fromYear", 2025,
                  "toYear", 2026,
                  "nodeId", 101L,
                  "amount", "1512000000",
                  "sourceDomainCode", "TCDLCL"));
      assertEquals(true, carryRes.get("carriedOver"));
      assertEquals(new BigDecimal("1512000000"), carryRes.get("carryOverAmount"));

      // 3. NV-03: Điều chuyển nội bộ chéo lĩnh vực (Tổng biến động = 0)
      Map<String, Object> crossRes =
          txBusiness.executeCrossDomainTransfer(
              Map.of(
                  "budgetYear", 2026,
                  "nodeId", 101L,
                  "fromDomainCode", "KHCN",
                  "toDomainCode", "DMST",
                  "amount", "2655000000"));
      assertEquals(true, crossRes.get("transferred"));
      assertEquals(BigDecimal.ZERO, crossRes.get("netUnitChange"));
      assertTrue(String.valueOf(crossRes.get("dynamicBreakdownJson")).contains("-2655000000"));

      // 4. NV-04: Tiết kiệm chi thường xuyên theo NQ 135
      Map<String, Object> saveRes =
          txBusiness.executeSavingAndReduction(
              Map.of(
                  "budgetYear", 2026,
                  "nodeId", 101L,
                  "reductionSubtype", "SAVING_NQ135",
                  "amount", "1481000000"));
      assertEquals(true, saveRes.get("reduced"));
      assertEquals(new BigDecimal("-1481000000"), saveRes.get("signedAdjustmentAmount"));

      // 5. NV-05: Thu hồi / Hủy dự toán hết niên độ (TREASURY_RECONCILER)
      currentRoles.set(List.of("TREASURY_RECONCILER"));
      Map<String, Object> cancelRes =
          txBusiness.cancelExpiredAllocation(
              Map.of("budgetYear", 2026, "nodeId", 101L, "amount", "500000000"));
      assertEquals(true, cancelRes.get("cancelled"));
      assertEquals(new BigDecimal("500000000"), cancelRes.get("cancelledAmount"));

      // 6. Ma trận Phân tách Vai trò & Quyền hạn (5 vai trò x 9 nghiệp vụ)
      Map<String, Object> roleMatrix = txBusiness.getOperationRoleMatrix();
      assertEquals(5, roleMatrix.get("totalRoles"));
      assertEquals(9, roleMatrix.get("totalOperations"));

      // Kiểm tra phân tách nhiệm vụ: Kế toán đơn vị (ROLE_BUDGET_UNIT_ACCOUNTANT) lập NV-01 -> PENDING_APPROVAL dù cố giả mạo actorRole
      currentRoles.set(List.of("ROLE_BUDGET_UNIT_ACCOUNTANT"));
      Map<String, Object> suppByAccountant =
          txBusiness.supplementBudgetContent(
              Map.of(
                  "budgetYear", 2026,
                  "nodeId", 101L,
                  "contentTitle", "Tờ trình bổ sung của Kế toán đơn vị",
                  "amount", "200000000",
                  "actorRole", "STC_DIRECTOR"));
      @SuppressWarnings("unchecked")
      Map<String, Object> accMeta = (Map<String, Object>) suppByAccountant.get("roleExecutionMeta");
      assertEquals("UNIT_ACCOUNTANT", accMeta.get("actorRole"));
      assertEquals("PENDING_APPROVAL", accMeta.get("effectiveApprovalStatus"));
      assertEquals("STC_DIRECTOR", accMeta.get("requiredApproverRole"));

      // Vai trò Kế toán đơn vị không được khởi tạo DX-01 -> bị chặn kể cả truyền actorRole trong body
      assertThrows(
          BaseException.class,
          () ->
              txBusiness.executeEarmarkHold(
                  Map.of(
                      "budgetYear", 2026,
                      "nodeId", 101L,
                      "holdAction", "HOLD",
                      "amount", "300000000",
                      "actorRole", "STC_DIRECTOR")));

      // 7. DX-01: Tạm giữ / Phong tỏa & Giải tỏa dự toán chờ duyệt chủ trương (ROLE_BUDGET_FINANCE_APPRAISER)
      currentRoles.set(List.of("ROLE_BUDGET_FINANCE_APPRAISER"));
      Map<String, Object> holdRes =
          txBusiness.executeEarmarkHold(
              Map.of(
                  "budgetYear", 2026,
                  "nodeId", 101L,
                  "holdAction", "HOLD",
                  "amount", "300000000"));
      assertEquals(true, holdRes.get("executed"));
      assertEquals("HOLD", holdRes.get("holdAction"));

      // 8. DX-02: Giải tỏa dự toán theo đợt (ROLE_BUDGET_DIRECTORATE)
      currentRoles.set(List.of("ROLE_BUDGET_DIRECTORATE"));
      Map<String, Object> trancheRes =
          txBusiness.executeTrancheRelease(
              Map.of(
                  "budgetYear", 2026,
                  "nodeId", 101L,
                  "trancheCode", "TRANCHE_Q3",
                  "releaseRatePercent", "25"));
      assertEquals(true, trancheRes.get("released"));
      assertEquals("TRANCHE_Q3", trancheRes.get("trancheCode"));

      // 9. DX-03: Điều chỉnh dự toán hàng loạt theo lương cơ sở & biên chế (từ Directory Service)
      Map<String, Object> payrollRes =
          txBusiness.executeBatchPayrollIndexation(
              Map.of(
                  "budgetYear", 2026,
                  "oldBaseSalary", "1800000",
                  "newBaseSalary", "2340000",
                  "staffChangeCount", 2));
      assertEquals(true, payrollRes.get("indexed"));
      assertEquals(new BigDecimal("30.00"), payrollRes.get("salaryIncreaseRatePercent"));

      // 10. DX-04: Trích lập 4 Quỹ tự chủ tài chính cuối năm theo NĐ 60/2021/NĐ-CP (ROLE_BUDGET_UNIT_HEAD)
      currentRoles.set(List.of("ROLE_BUDGET_UNIT_HEAD"));
      Map<String, Object> fundRes =
          txBusiness.executeAutonomyFundDistribution(
              Map.of(
                  "budgetYear", 2026,
                  "nodeId", 101L,
                  "autonomyGroup", "GROUP_2",
                  "surplusAmount", "1000000000"));
      assertEquals(true, fundRes.get("distributed"));
      assertEquals(0, new BigDecimal("250000000").compareTo((BigDecimal) fundRes.get("pthdsnAmount")));
      assertEquals(0, new BigDecimal("450000000").compareTo((BigDecimal) fundRes.get("bstnAmount")));
      assertEquals(0, new BigDecimal("200000000").compareTo((BigDecimal) fundRes.get("ktplAmount")));
      assertEquals(0, new BigDecimal("100000000").compareTo((BigDecimal) fundRes.get("dpodtnAmount")));
    }
  }
}
