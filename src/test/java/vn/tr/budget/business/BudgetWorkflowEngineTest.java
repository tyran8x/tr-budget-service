package vn.tr.budget.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetCategoryRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.dao.service.BudgetUnitConfigRepo;

@ExtendWith(MockitoExtension.class)
class BudgetWorkflowEngineTest {

  @Mock private BudgetAllocationRepo budgetAllocationRepo;
  @Mock private BudgetTransactionRepo budgetTransactionRepo;
  @Mock private BudgetDisbursementRepo budgetDisbursementRepo;
  @Mock private BudgetUnitConfigRepo budgetUnitConfigRepo;
  @Mock private BudgetCategoryRepo budgetCategoryRepo;
  @Mock private BudgetEstimateItemRepo budgetEstimateItemRepo;

  @InjectMocks private BudgetWorkflowEngineHelper engineHelper;

  @Test
  @DisplayName("Kiểm tra tự chủ tài chính Đơn vị: Trong hạn mức cho phép UNIT_SELF, vượt hạn mức yêu cầu DIRECTORATE")
  void shouldCheckUnitAutonomyCorrectly() {
    BudgetUnitConfig cfg =
        BudgetUnitConfig.builder()
            .id(10L)
            .nodeId(101L)
            .nodeCode("PHONG-KHTC")
            .canSelfAllocate(true)
            .canSelfTransfer(true)
            .canCarryOver(true)
            .selfApproveLimitAmount(new BigDecimal("200000000"))
            .build();
    BudgetCategory cat =
        BudgetCategory.builder().code("LK-340-341").allowCarryOver(true).build();

    when(budgetUnitConfigRepo.findFirstByNodeIdOrderByIdDesc(101L)).thenReturn(Optional.of(cfg));
    when(budgetCategoryRepo.findAllByCodeIgnoreCaseIncludingDeletedSorted("LK-340-341"))
        .thenReturn(List.of(cat));

    Map<String, Object> withinLimit =
        engineHelper.checkUnitAutonomy(101L, "LK-340-341", new BigDecimal("150000000"));
    assertThat(withinLimit.get("canSelfApprove")).isEqualTo(true);
    assertThat(withinLimit.get("recommendedApprovalScope")).isEqualTo("UNIT_SELF");

    Map<String, Object> overLimit =
        engineHelper.checkUnitAutonomy(101L, "LK-340-341", new BigDecimal("250000000"));
    assertThat(overLimit.get("canSelfApprove")).isEqualTo(false);
    assertThat(overLimit.get("recommendedApprovalScope")).isEqualTo("DIRECTORATE");
  }

  @Test
  @DisplayName("Tính toán cân đối Sổ cái Dự toán: Đầu năm + Bổ sung + Chuyển nguồn đến - Chuyển nguồn đi - Giải ngân")
  void shouldRecalculateAllocationLedgerAccurately() {
    BudgetAllocation alloc =
        BudgetAllocation.builder()
            .id(1L)
            .code("PB-2026-01")
            .initialAmount(new BigDecimal("1000000000"))
            .build();

    List<BudgetTransaction> txs =
        List.of(
            BudgetTransaction.builder()
                .txType("INITIAL_ALLOCATION")
                .amount(new BigDecimal("1000000000"))
                .approvalStatus("APPROVED")
                .build(),
            BudgetTransaction.builder()
                .txType("SUPPLEMENT")
                .amount(new BigDecimal("300000000"))
                .approvalStatus("APPROVED")
                .build(),
            BudgetTransaction.builder()
                .txType("CARRY_OVER_IN")
                .amount(new BigDecimal("200000000"))
                .approvalStatus("APPROVED")
                .build(),
            BudgetTransaction.builder()
                .txType("CARRY_OVER_OUT")
                .amount(new BigDecimal("100000000"))
                .approvalStatus("APPROVED")
                .build());

    List<BudgetDisbursement> disbs =
        List.of(
            BudgetDisbursement.builder()
                .disbursementType("COMMITMENT")
                .amount(new BigDecimal("150000000"))
                .approvalStatus("APPROVED_DISBURSED")
                .build(),
            BudgetDisbursement.builder()
                .disbursementType("DISBURSEMENT")
                .amount(new BigDecimal("700000000"))
                .approvalStatus("APPROVED_DISBURSED")
                .build());

    when(budgetAllocationRepo.findById(1L)).thenReturn(Optional.of(alloc));
    when(budgetTransactionRepo.findByAllocationIdOrderByIdAsc(1L)).thenReturn(txs);
    when(budgetDisbursementRepo.findByAllocationIdOrderByIdAsc(1L)).thenReturn(disbs);
    when(budgetAllocationRepo.save(any(BudgetAllocation.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    BudgetAllocation updated = engineHelper.recalculateAllocationMetrics(1L);

    assertThat(updated.getEffectiveAmount()).isEqualByComparingTo("1400000000");
    assertThat(updated.getCommittedAmount()).isEqualByComparingTo("150000000");
    assertThat(updated.getDisbursedAmount()).isEqualByComparingTo("700000000");
    assertThat(updated.getAvailableAmount()).isEqualByComparingTo("550000000");
    assertThat(updated.getDisbursementRatePercent()).isEqualByComparingTo("50.00");
  }
}
