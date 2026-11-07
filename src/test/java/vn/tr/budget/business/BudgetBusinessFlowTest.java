package vn.tr.budget.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.validation.BeanPropertyBindingResult;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetAllocationService;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetDisbursementService;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.dao.service.BudgetTransactionService;
import vn.tr.budget.dao.service.BudgetUnitConfigService;
import vn.tr.budget.data.dto.BudgetAllocationData;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.budget.data.dto.BudgetUnitConfigData;
import vn.tr.budget.data.mapper.BudgetAllocationMapper;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.data.mapper.BudgetTransactionMapper;
import vn.tr.budget.data.validator.BudgetAllocationValidator;
import vn.tr.budget.data.validator.BudgetDisbursementValidator;
import vn.tr.budget.data.validator.BudgetTransactionValidator;
import vn.tr.budget.data.validator.BudgetUnitConfigValidator;
import vn.tr.common.core.exception.base.BaseException;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.web.utils.BusinessLogicHelper;

@ExtendWith(MockitoExtension.class)
class BudgetBusinessFlowTest {

  @Mock private BudgetDisbursementService budgetDisbursementService;
  @Mock private BudgetDisbursementMapper budgetDisbursementMapper;
  @Mock private BudgetDisbursementValidator budgetDisbursementValidator;
  @Mock private GenericUpsertHelper genericUpsertHelper;
  @Mock private AuditRollbackHelper auditRollbackHelper;
  @Mock private BusinessLogicHelper businessLogicHelper;
  @Mock private BudgetWorkflowEngineHelper workflowEngineHelper;
  @Mock private BudgetAllocationRepo budgetAllocationRepo;
  @Mock private BudgetTransactionRepo budgetTransactionRepo;
  @Mock private BudgetDisbursementRepo budgetDisbursementRepo;
  @Mock private BudgetEstimateItemRepo budgetEstimateItemRepo;

  @InjectMocks private BudgetDisbursementBusiness disbursementBusiness;
  @InjectMocks private BudgetDashboardBusiness dashboardBusiness;

  @Test
  @DisplayName("Chặn vượt dự toán (Over-Budget Gate): Không cho phép giải ngân vượt số dư khả dụng")
  void shouldRejectDisbursementExceedingAvailableBudget() {
    BudgetAllocation alloc =
        BudgetAllocation.builder()
            .id(1L)
            .code("PB-2026-KHTC-01")
            .availableAmount(new BigDecimal("100000000"))
            .build();

    when(workflowEngineHelper.isVoucherRequired(any())).thenReturn(false);
    when(budgetAllocationRepo.findById(1L)).thenReturn(Optional.of(alloc));

    BudgetDisbursementData req =
        BudgetDisbursementData.builder()
            .code("GN-OVER-01")
            .name("Đề nghị giải ngân vượt trần")
            .allocationId(1L)
            .disbursementType("DISBURSEMENT")
            .amount(new BigDecimal("150000000"))
            .build();

    assertThatThrownBy(() -> disbursementBusiness.create(req))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("vượt quá dự toán khả dụng");
  }

  @Test
  @DisplayName("Chặn thiếu chứng từ Kho bạc (Voucher Gate): Bắt buộc có voucherNo khi đầu mục yêu cầu chứng từ")
  void shouldRequireVoucherWhenEstimateItemDemandsIt() {
    when(workflowEngineHelper.isVoucherRequired(5L)).thenReturn(true);

    BudgetDisbursementData req =
        BudgetDisbursementData.builder()
            .code("GN-NOVOUCHER-01")
            .name("Giải ngân thiếu số chứng từ Kho bạc")
            .allocationId(1L)
            .estimateItemId(5L)
            .disbursementType("DISBURSEMENT")
            .amount(new BigDecimal("50000000"))
            .voucherNo("")
            .build();

    assertThatThrownBy(() -> disbursementBusiness.create(req))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("voucherNo");
  }

  @Test
  @DisplayName("Dashboard Kinh phí: Tổng hợp chính xác KPI Thu - Chi, Bảng điểm Kỷ luật Giải ngân & AI Advisor")
  void shouldComputeDashboardOverviewAndScorecard() {
    List<BudgetAllocation> allocs =
        List.of(
            BudgetAllocation.builder()
                .id(1L)
                .code("PB-EXP-01")
                .budgetYear(2026)
                .nodeId(101L)
                .nodeCode("PHONG-KHTC")
                .nodeName("Phòng Kế hoạch - Tài chính")
                .itemType("EXPENDITURE")
                .workflowStatus("APPROVED")
                .initialAmount(new BigDecimal("1000000000"))
                .supplementAmount(new BigDecimal("200000000"))
                .carryOverInAmount(new BigDecimal("100000000"))
                .effectiveAmount(new BigDecimal("1300000000"))
                .disbursedAmount(new BigDecimal("910000000"))
                .settledAmount(new BigDecimal("850000000"))
                .availableAmount(new BigDecimal("390000000"))
                .build(),
            BudgetAllocation.builder()
                .id(2L)
                .code("PB-REV-01")
                .budgetYear(2026)
                .nodeId(101L)
                .nodeCode("PHONG-KHTC")
                .nodeName("Phòng Kế hoạch - Tài chính")
                .itemType("REVENUE")
                .workflowStatus("APPROVED")
                .effectiveAmount(new BigDecimal("1500000000"))
                .disbursedAmount(new BigDecimal("1200000000"))
                .build());

    when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2026)).thenReturn(allocs);
    when(budgetTransactionRepo.findByBudgetYearOrderByIdDesc(2026)).thenReturn(List.of());
    when(budgetDisbursementRepo.findByBudgetYearOrderByIdDesc(2026)).thenReturn(List.of());

    Map<String, Object> overview = dashboardBusiness.getOverview(2026, null, null);
    assertThat((BigDecimal) overview.get("totalEffectiveBudget"))
        .isEqualByComparingTo("1300000000");
    assertThat((BigDecimal) overview.get("totalDisbursedBudget"))
        .isEqualByComparingTo("910000000");
    assertThat((BigDecimal) overview.get("disbursementRatePercent")).isEqualByComparingTo("70.00");

    List<Map<String, Object>> scorecards = dashboardBusiness.getDisbursementScorecard(2026);
    assertThat(scorecards).hasSize(1);
    assertThat(scorecards.getFirst().get("nodeCode")).isEqualTo("PHONG-KHTC");

    Map<String, Object> ai = dashboardBusiness.generateAiAdvisorInsights(2026);
    assertThat((List<?>) ai.get("recommendations")).hasSize(3);
  }

  @Test
  @DisplayName("Chống duyệt trùng giao dịch (Idempotency Gate): Giao dịch đã APPROVED không được ghi sổ kép lần 2")
  void shouldPreventDoubleApprovalOnTransaction() {
    BudgetTransactionService txService = mock(BudgetTransactionService.class);
    BudgetTransactionMapper txMapper = mock(BudgetTransactionMapper.class);
    BudgetTransactionValidator txValidator = mock(BudgetTransactionValidator.class);

    BudgetTransaction alreadyApproved =
        BudgetTransaction.builder()
            .id(88L)
            .code("BTX-2026-088")
            .txType("SUPPLEMENT")
            .amount(new BigDecimal("500000000"))
            .approvalStatus("APPROVED")
            .build();

    when(txService.findById(88L)).thenReturn(Optional.of(alreadyApproved));
    when(txMapper.toData(alreadyApproved))
        .thenReturn(
            BudgetTransactionData.builder()
                .id(88L)
                .code("BTX-2026-088")
                .approvalStatus("APPROVED")
                .build());

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

    BudgetTransactionData res = txBusiness.approveTransaction(88L, Map.of("approvedByName", "GĐ"));
    assertThat(res.getApprovalStatus()).isEqualTo("APPROVED");
    verify(workflowEngineHelper, never()).executeDoubleEntryLedger(any());
  }

  @Test
  @DisplayName("Cây Dự toán Thu - Chi đa cấp (3+ tầng): Lồng ghép đệ quy và cộng dồn chỉ tiêu từ nút lá lên nút gốc")
  void shouldBuildMultiLevelRevenueExpenditureTree() {
    List<BudgetEstimateItem> items =
        List.of(
            BudgetEstimateItem.builder()
                .id(1L)
                .code("EXP-ROOT")
                .name("Chi ngân sách địa phương")
                .itemType("EXPENDITURE")
                .levelDepth(1)
                .sortOrder(1)
                .build(),
            BudgetEstimateItem.builder()
                .id(2L)
                .code("EXP-TX")
                .name("Chi thường xuyên")
                .parentId(1L)
                .parentCode("EXP-ROOT")
                .itemType("EXPENDITURE")
                .levelDepth(2)
                .sortOrder(1)
                .build(),
            BudgetEstimateItem.builder()
                .id(3L)
                .code("EXP-TX-CNTT")
                .name("Chi ứng dụng CNTT & Chuyển đổi số")
                .parentId(2L)
                .parentCode("EXP-TX")
                .itemType("EXPENDITURE")
                .levelDepth(3)
                .sortOrder(1)
                .build());

    List<BudgetAllocation> allocs =
        List.of(
            BudgetAllocation.builder()
                .id(10L)
                .code("PB-CNTT-01")
                .budgetYear(2026)
                .estimateItemId(3L)
                .estimateItemCode("EXP-TX-CNTT")
                .itemType("EXPENDITURE")
                .effectiveAmount(new BigDecimal("400000000"))
                .disbursedAmount(new BigDecimal("300000000"))
                .settledAmount(new BigDecimal("250000000"))
                .build());

    when(budgetEstimateItemRepo.findAll()).thenReturn(items);
    when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2026)).thenReturn(allocs);

    List<Map<String, Object>> tree = dashboardBusiness.getRevenueExpenditureTree(2026);
    assertThat(tree).hasSize(3);
    Map<String, Object> root = tree.getFirst();
    assertThat((BigDecimal) root.get("effectiveAmount")).isEqualByComparingTo("400000000");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> level2 = (List<Map<String, Object>>) root.get("children");
    assertThat(level2).hasSize(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> level3 = (List<Map<String, Object>>) level2.getFirst().get("children");
    assertThat(level3).hasSize(1);
    assertThat(level3.getFirst().get("code")).isEqualTo("EXP-TX-CNTT");
  }

  @Test
  @DisplayName("Validator Phân bổ Dự toán (LP-001, LP-002, LP-005): Chặn mã trùng tiếng Việt, năm ngân sách ngoài biên, số tiền âm và lệch ngày quyết định")
  void shouldValidateAllocationBoundaryAndDuplicateCodeInVietnamese() {
    BudgetAllocationService allocService = mock(BudgetAllocationService.class);
    when(allocService.existsByCodeIgnoreCase("PB-DUP-01")).thenReturn(true);
    BudgetAllocationValidator validator = new BudgetAllocationValidator(allocService);

    BudgetAllocationData invalidData =
        BudgetAllocationData.builder()
            .code("PB-DUP-01")
            .budgetYear(1995)
            .initialAmount(new BigDecimal("-5000000"))
            .proposedAmount(new BigDecimal("-1000000"))
            .decisionDate(LocalDate.of(2026, 1, 15))
            .build();

    BeanPropertyBindingResult errors =
        new BeanPropertyBindingResult(invalidData, "budgetAllocationData");
    validator.validate(invalidData, errors);

    assertThat(errors.getFieldError("code")).isNotNull();
    assertThat(errors.getFieldError("code").getDefaultMessage())
        .contains("PB-DUP-01")
        .contains("đã tồn tại");
    assertThat(errors.getFieldError("budgetYear")).isNotNull();
    assertThat(errors.getFieldError("initialAmount")).isNotNull();
    assertThat(errors.getFieldError("proposedAmount")).isNotNull();
    assertThat(errors.getFieldError("decisionDate")).isNotNull();
  }

  @Test
  @DisplayName("Validator Giải ngân & Tự chủ Đơn vị (LP-001): Chặn số tiền giải ngân <= 0, thuế GTGT > tổng tiền và tỷ lệ tự bảo đảm ngoài 0..100%")
  void shouldValidateDisbursementAndUnitConfigNumericBoundaries() {
    BudgetDisbursementService disbService = mock(BudgetDisbursementService.class);
    BudgetDisbursementValidator disbValidator = new BudgetDisbursementValidator(disbService);

    BudgetDisbursementData badDisb =
        BudgetDisbursementData.builder()
            .code("GN-BAD-01")
            .budgetYear(2026)
            .amount(new BigDecimal("10000000"))
            .taxAmount(new BigDecimal("15000000"))
            .build();
    BeanPropertyBindingResult disbErrors =
        new BeanPropertyBindingResult(badDisb, "budgetDisbursementData");
    disbValidator.validate(badDisb, disbErrors);
    assertThat(disbErrors.getFieldError("taxAmount")).isNotNull();
    assertThat(disbErrors.getFieldError("taxAmount").getDefaultMessage())
        .contains("Thuế GTGT khấu trừ không được vượt quá");

    BudgetUnitConfigService unitService = mock(BudgetUnitConfigService.class);
    BudgetUnitConfigValidator unitValidator = new BudgetUnitConfigValidator(unitService);
    BudgetUnitConfigData badUnit =
        BudgetUnitConfigData.builder()
            .code("BUC-BAD-01")
            .selfApproveLimitAmount(new BigDecimal("-100"))
            .recurrentSelfGuaranteeRate(new BigDecimal("125"))
            .build();
    BeanPropertyBindingResult unitErrors =
        new BeanPropertyBindingResult(badUnit, "budgetUnitConfigData");
    unitValidator.validate(badUnit, unitErrors);
    assertThat(unitErrors.getFieldError("selfApproveLimitAmount")).isNotNull();
    assertThat(unitErrors.getFieldError("recurrentSelfGuaranteeRate")).isNotNull();
  }

  @Test
  @DisplayName("State Machine Invariant (LP-004): Tạo mới Phân bổ Dự toán luôn cưỡng bức trạng thái khởi tạo DRAFT")
  void shouldEnforceDraftWorkflowStatusOnCreateAllocation() {
    BudgetAllocationService allocService = mock(BudgetAllocationService.class);
    BudgetAllocationMapper allocMapper = mock(BudgetAllocationMapper.class);
    BudgetAllocationValidator allocValidator = new BudgetAllocationValidator(allocService);

    BudgetAllocationBusiness allocBusiness =
        new BudgetAllocationBusiness(
            allocService,
            allocMapper,
            allocValidator,
            genericUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper,
            workflowEngineHelper,
            budgetAllocationRepo);

    when(workflowEngineHelper.checkUnitAutonomy(any(), any(), any()))
        .thenReturn(Map.of("recommendedApprovalScope", "DIRECTORATE"));
    when(genericUpsertHelper.upsert(any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              BudgetAllocationData dto = inv.getArgument(0);
              return BudgetAllocation.builder()
                  .id(99L)
                  .code(dto.getCode())
                  .workflowStatus(dto.getWorkflowStatus())
                  .build();
            });
    when(workflowEngineHelper.recalculateAllocationMetrics(99L))
        .thenReturn(
            BudgetAllocation.builder()
                .id(99L)
                .code("PB-SM-01")
                .workflowStatus("DRAFT")
                .build());
    when(allocMapper.toData(any(BudgetAllocation.class)))
        .thenAnswer(
            inv -> {
              BudgetAllocation ent = inv.getArgument(0);
              return BudgetAllocationData.builder()
                  .id(ent.getId())
                  .code(ent.getCode())
                  .workflowStatus(ent.getWorkflowStatus())
                  .build();
            });

    BudgetAllocationData input =
        BudgetAllocationData.builder()
            .code("PB-SM-01")
            .name("Phân bổ thử nghiệm bypass trạng thái")
            .budgetYear(2026)
            .workflowStatus("APPROVED")
            .initialAmount(new BigDecimal("200000000"))
            .build();

    BudgetAllocationData created = allocBusiness.create(input);
    assertThat(input.getWorkflowStatus()).isEqualTo("DRAFT");
    assertThat(created.getWorkflowStatus()).isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("Chống N+1 Feign S2S Storm (BUG-BUD-07): Trả về ngay snapshotName/snapshotCode mà không gọi Feign khi trường snapshot đã tồn tại")
  void shouldShortCircuitIdentityFeignCallsWhenSnapshotFieldsExist() {
    @SuppressWarnings("unchecked")
    org.springframework.beans.factory.ObjectProvider<vn.tr.common.feign.identity.UnifiedIdentityResolver>
        identityProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
    vn.tr.budget.business.helper.BudgetIntegrationOrchestrator orchestrator =
        new vn.tr.budget.business.helper.BudgetIntegrationOrchestrator(
            null, null, identityProvider, budgetDisbursementRepo);

    assertThat(orchestrator.resolveNodeNameWithFallback(101L, " Phòng Kế hoạch - Tài chính "))
        .isEqualTo("Phòng Kế hoạch - Tài chính");
    assertThat(orchestrator.resolveNodeCodeWithFallback(101L, " PHONG-KHTC "))
        .isEqualTo("PHONG-KHTC");
    assertThat(orchestrator.resolvePersonNameWithFallback(12L, " Đinh Quang Huy "))
        .isEqualTo("Đinh Quang Huy");
    verify(identityProvider, never()).getIfAvailable();
  }
}
