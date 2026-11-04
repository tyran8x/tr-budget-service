package vn.tr.budget.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetDisbursementService;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.dao.service.BudgetTransactionService;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.data.mapper.BudgetTransactionMapper;
import vn.tr.budget.data.validator.BudgetDisbursementValidator;
import vn.tr.budget.data.validator.BudgetTransactionValidator;
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
}
