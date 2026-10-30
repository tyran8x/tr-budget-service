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
            .nodeCode("VAN_PHONG_SO")
            .nodeName("Văn phòng Sở")
            .budgetYear(2026)
            .amount(new BigDecimal("150000000"))
            .taxAmount(new BigDecimal("15000000"))
            .netPaymentAmount(new BigDecimal("135000000"))
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
    when(workflowEngineHelper.safe(any()))
        .thenAnswer(
            inv -> inv.getArgument(0) != null ? inv.getArgument(0) : BigDecimal.ZERO);

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

    // Kiểm tra kết xuất gói DVC KBNN / TABMIS (UPG-07)
    Map<String, Object> dvcPkg = business.exportKbnnDvcPackage(55L);
    assertEquals("READY_FOR_VGCA_TOKEN_SIGNING", dvcPkg.get("digitalSignatureStatus"));
    assertTrue(String.valueOf(dvcPkg.get("xmlPayloadPreview")).contains("DVC_KBNN_PACKAGE"));

    // Kiểm tra xóa giải ngân tự động tính lại số dư dự toán
    business.delete(55L);
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

    // 1. Khi DB trống -> kiểm tra ví dụ minh họa tại 2 mốc ngày 2026-10-23 (SEPARATE_INITIAL) & 2026-10-30 (CUMULATIVE_EFFECTIVE)
    when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2026)).thenReturn(List.of());

    Map<String, Object> matrixOct23 =
        reportBusiness.generateDynamicDomainMatrix(
            2026, "2026-10-23", "DOMAIN", "SEPARATE_INITIAL", null);
    assertEquals("SEPARATE_INITIAL", matrixOct23.get("viewMode"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> colsOct23 = (List<Map<String, Object>>) matrixOct23.get("columns");
    assertEquals(4, colsOct23.size());
    assertEquals(new BigDecimal("166445.810306"), matrixOct23.get("totalAllocated"));

    Map<String, Object> matrixOct30 =
        reportBusiness.generateDynamicDomainMatrix(
            2026, "2026-10-30", "DOMAIN", "CUMULATIVE_EFFECTIVE", null);
    assertEquals("CUMULATIVE_EFFECTIVE", matrixOct30.get("viewMode"));
    assertEquals(new BigDecimal("166332.470306"), matrixOct30.get("totalAllocated"));
    assertEquals(new BigDecimal("106239.89"), matrixOct30.get("totalDisbursed"));

    // 2. Khi DB có dữ liệu của một Đơn vị khác với các Lĩnh vực tùy biến bất kỳ -> tự động sinh cột động theo DB
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

    Map<String, Object> customMatrix =
        reportBusiness.generateDynamicDomainMatrix(
            2026, "2026-10-30", "DOMAIN", "CUMULATIVE_EFFECTIVE", null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> customCols =
        (List<Map<String, Object>>) customMatrix.get("columns");
    assertEquals(2, customCols.size());
    assertEquals("XUC_TIEN_TM", customCols.get(0).get("code"));
    assertEquals("KHUYEN_CONG", customCols.get(1).get("code"));
    assertEquals(new BigDecimal("14200"), customMatrix.get("totalAllocated"));
    assertEquals(new BigDecimal("9500"), customMatrix.get("totalDisbursed"));
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
}
