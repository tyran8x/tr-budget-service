package vn.tr.budget.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetCategoryRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetDisbursementService;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.dao.service.BudgetTransactionService;
import vn.tr.budget.dao.service.BudgetUnitConfigRepo;
import vn.tr.budget.dao.service.BudgetUnitConfigService;
import vn.tr.budget.data.criteria.BudgetDisbursementSearchCriteria;
import vn.tr.budget.data.criteria.BudgetUnitConfigSearchCriteria;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.data.mapper.BudgetTransactionMapper;
import vn.tr.budget.data.mapper.BudgetUnitConfigMapper;
import vn.tr.budget.data.validator.BudgetDisbursementValidator;
import vn.tr.budget.data.validator.BudgetTransactionValidator;
import vn.tr.budget.data.validator.BudgetUnitConfigValidator;
import vn.tr.budget.kafka.BudgetPaymentEventListener;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.domain.data.RefData;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.core.event.payment.PaymentCompletedEvent;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.json.utils.JsonUtils;
import vn.tr.common.web.utils.BusinessLogicHelper;

@ExtendWith(MockitoExtension.class)
class BudgetAuditRemediationTest {

  @Mock private BudgetAllocationRepo budgetAllocationRepo;
  @Mock private BudgetTransactionRepo budgetTransactionRepo;
  @Mock private BudgetDisbursementRepo budgetDisbursementRepo;
  @Mock private BudgetUnitConfigRepo budgetUnitConfigRepo;
  @Mock private BudgetCategoryRepo budgetCategoryRepo;
  @Mock private BudgetEstimateItemRepo budgetEstimateItemRepo;

  @Test
  @DisplayName("Kafka BudgetPaymentEventListener xử lý payment-events idempotent và cập nhật giải ngân SETTLED")
  void shouldConsumePaymentEventIdempotentlyAndSettleDisbursement() {
    BudgetDisbursementService disbService = mock(BudgetDisbursementService.class);
    BudgetDisbursementMapper disbMapper = mock(BudgetDisbursementMapper.class);
    BudgetDisbursementValidator disbValidator = mock(BudgetDisbursementValidator.class);
    BudgetWorkflowEngineHelper engineHelper = mock(BudgetWorkflowEngineHelper.class);

    BudgetDisbursement pendingDisb =
        BudgetDisbursement.builder()
            .id(77L)
            .code("GN-2026-077")
            .allocationId(10L)
            .amount(new BigDecimal("250000000"))
            .disbursementType("DISBURSEMENT")
            .approvalStatus("APPROVED_DISBURSED")
            .build();

    when(disbService.findById(77L)).thenReturn(Optional.of(pendingDisb));
    when(disbService.save(any(BudgetDisbursement.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(engineHelper.recalculateAllocationMetrics(10L))
        .thenReturn(BudgetAllocation.builder().id(10L).code("PB-10").build());

    BudgetDisbursementBusiness disbBusiness =
        new BudgetDisbursementBusiness(
            disbService,
            disbMapper,
            disbValidator,
            mock(GenericUpsertHelper.class),
            mock(AuditRollbackHelper.class),
            mock(BusinessLogicHelper.class),
            engineHelper,
            budgetAllocationRepo);
    disbBusiness.setBudgetDisbursementRepo(budgetDisbursementRepo);

    BudgetPaymentEventListener listener = new BudgetPaymentEventListener(disbBusiness);

    PaymentCompletedEvent event =
        PaymentCompletedEvent.builder()
            .transactionCode("SEPAY-77")
            .appCode("budget")
            .sourceModule("BUDGET_DISBURSEMENT")
            .referenceId("77")
            .referenceCode("GN-2026-077")
            .amount(new BigDecimal("250000000"))
            .paidAmount(new BigDecimal("250000000"))
            .bankReferenceNo("FT-998877")
            .receiptNo("UNC-77")
            .paidAt(LocalDateTime.now())
            .build();

    String json = JsonUtils.toJsonString(event);
    listener.handlePaymentCompleted(json);

    assertThat(pendingDisb.getApprovalStatus()).isEqualTo("SETTLED");
    assertThat(pendingDisb.getPaymentTxCode()).isEqualTo("SEPAY-77");
    assertThat(pendingDisb.getTreasuryRefCode()).isEqualTo("FT-998877");
    assertThat(pendingDisb.getSettledAmount()).isEqualByComparingTo("250000000");

    // Gửi lặp lần 2 -> idempotent, không recalculate thêm
    listener.handlePaymentCompleted(json);
    verify(engineHelper).recalculateAllocationMetrics(10L);
  }

  @Test
  @DisplayName("Xóa toàn bộ giải ngân và biến động -> chỉ số committed, disbursed, supplement, transferNet reset về 0")
  void shouldResetAllocationMetricsToZeroWhenLedgersCleared() {
    BudgetWorkflowEngineHelper engineHelper =
        new BudgetWorkflowEngineHelper(
            budgetAllocationRepo,
            budgetTransactionRepo,
            budgetDisbursementRepo,
            budgetUnitConfigRepo,
            budgetCategoryRepo,
            budgetEstimateItemRepo);

    BudgetAllocation dirtyAlloc =
        BudgetAllocation.builder()
            .id(1L)
            .code("PB-RESET-01")
            .initialAmount(new BigDecimal("1000000000"))
            .supplementAmount(new BigDecimal("300000000"))
            .transferNetAmount(new BigDecimal("100000000"))
            .reductionAmount(new BigDecimal("50000000"))
            .committedAmount(new BigDecimal("200000000"))
            .disbursedAmount(new BigDecimal("400000000"))
            .settledAmount(new BigDecimal("300000000"))
            .effectiveAmount(new BigDecimal("1350000000"))
            .availableAmount(new BigDecimal("750000000"))
            .build();

    when(budgetAllocationRepo.findById(1L)).thenReturn(Optional.of(dirtyAlloc));
    when(budgetTransactionRepo.findByAllocationIdOrderByIdAsc(1L)).thenReturn(List.of());
    when(budgetDisbursementRepo.findByAllocationIdOrderByIdAsc(1L)).thenReturn(List.of());
    when(budgetAllocationRepo.save(any(BudgetAllocation.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    BudgetAllocation recalculated = engineHelper.recalculateAllocationMetrics(1L);

    assertThat(recalculated.getSupplementAmount()).isEqualByComparingTo("0");
    assertThat(recalculated.getTransferNetAmount()).isEqualByComparingTo("0");
    assertThat(recalculated.getReductionAmount()).isEqualByComparingTo("0");
    assertThat(recalculated.getCommittedAmount()).isEqualByComparingTo("0");
    assertThat(recalculated.getDisbursedAmount()).isEqualByComparingTo("0");
    assertThat(recalculated.getSettledAmount()).isEqualByComparingTo("0");
    assertThat(recalculated.getEffectiveAmount()).isEqualByComparingTo("1000000000");
    assertThat(recalculated.getAvailableAmount()).isEqualByComparingTo("1000000000");
  }

  @Test
  @DisplayName("INTERNAL_TRANSFER bút toán kép ghi DEBIT/CREDIT và đồng bộ counterpartTxId khi từ chối")
  void shouldEnforceDebitCreditDoubleEntryAndSyncCounterpartOnReject() {
    BudgetWorkflowEngineHelper engineHelper =
        new BudgetWorkflowEngineHelper(
            budgetAllocationRepo,
            budgetTransactionRepo,
            budgetDisbursementRepo,
            budgetUnitConfigRepo,
            budgetCategoryRepo,
            budgetEstimateItemRepo);

    BudgetAllocation src =
        BudgetAllocation.builder()
            .id(10L)
            .code("PB-SRC")
            .budgetYear(2026)
            .initialAmount(new BigDecimal("1000000000"))
            .effectiveAmount(new BigDecimal("1000000000"))
            .availableAmount(new BigDecimal("1000000000"))
            .build();
    BudgetAllocation tgt =
        BudgetAllocation.builder()
            .id(20L)
            .code("PB-TGT")
            .budgetYear(2026)
            .initialAmount(new BigDecimal("500000000"))
            .effectiveAmount(new BigDecimal("500000000"))
            .availableAmount(new BigDecimal("500000000"))
            .build();

    BudgetTransaction srcTx =
        BudgetTransaction.builder()
            .id(100L)
            .code("TX-IT-01")
            .name("Điều chuyển nguồn sang đơn vị 20")
            .allocationId(10L)
            .counterpartAllocationId(20L)
            .txType("INTERNAL_TRANSFER")
            .amount(new BigDecimal("200000000"))
            .approvalStatus("APPROVED")
            .build();

    BudgetTransaction capturedRecv =
        BudgetTransaction.builder()
            .id(200L)
            .code("TX-IT-01-RECV")
            .allocationId(20L)
            .counterpartAllocationId(10L)
            .counterpartTxId(100L)
            .txType("TRANSFER_IN")
            .entryDirection("CREDIT")
            .amount(new BigDecimal("200000000"))
            .approvalStatus("APPROVED")
            .build();

    when(budgetAllocationRepo.findById(10L)).thenReturn(Optional.of(src));
    when(budgetAllocationRepo.findById(20L)).thenReturn(Optional.of(tgt));
    when(budgetTransactionRepo.findByAllocationIdOrderByIdAsc(10L)).thenReturn(List.of(srcTx));
    when(budgetTransactionRepo.findByAllocationIdOrderByIdAsc(20L))
        .thenReturn(List.of(capturedRecv));
    when(budgetDisbursementRepo.findByAllocationIdOrderByIdAsc(any())).thenReturn(List.of());
    when(budgetAllocationRepo.save(any(BudgetAllocation.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(budgetTransactionRepo.save(any(BudgetTransaction.class)))
        .thenAnswer(
            inv -> {
              BudgetTransaction arg = inv.getArgument(0);
              if (arg.getId() == null) {
                arg.setId(200L);
              }
              return arg;
            });

    engineHelper.executeDoubleEntryLedger(srcTx);

    assertThat(srcTx.getEntryDirection()).isEqualTo("DEBIT");
    assertThat(srcTx.getAmount()).isEqualByComparingTo("-200000000");
    assertThat(srcTx.getCounterpartTxId()).isEqualTo(200L);
    assertThat(srcTx.getBalanceAfterAmount()).isEqualByComparingTo("800000000");

    // Từ chối giao dịch nguồn phải tự động từ chối giao dịch đối ứng 200L
    BudgetTransactionService txService = mock(BudgetTransactionService.class);
    BudgetTransactionMapper txMapper = mock(BudgetTransactionMapper.class);
    BudgetWorkflowEngineHelper mockedEngine = mock(BudgetWorkflowEngineHelper.class);
    when(txService.findById(100L)).thenReturn(Optional.of(srcTx));
    when(txService.findById(200L)).thenReturn(Optional.of(capturedRecv));
    when(txService.save(any(BudgetTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    when(txMapper.toData(any(BudgetTransaction.class)))
        .thenReturn(BudgetTransactionData.builder().id(100L).approvalStatus("REJECTED").build());

    BudgetTransactionBusiness txBusiness =
        new BudgetTransactionBusiness(
            txService,
            txMapper,
            mock(BudgetTransactionValidator.class),
            mock(GenericUpsertHelper.class),
            mock(AuditRollbackHelper.class),
            mock(BusinessLogicHelper.class),
            mockedEngine,
            budgetAllocationRepo);
    txBusiness.setBudgetTransactionRepo(budgetTransactionRepo);

    txBusiness.rejectTransaction(100L, Map.of("reason", "Hủy điều chuyển"));

    assertThat(srcTx.getApprovalStatus()).isEqualTo("REJECTED");
    assertThat(capturedRecv.getApprovalStatus()).isEqualTo("REJECTED");
    verify(mockedEngine).recalculateAllocationMetrics(eq(10L));
    verify(mockedEngine).recalculateAllocationMetrics(eq(20L));
  }

  @Test
  @DisplayName("GET /options trả về RefData<Long> và không rò rỉ tài khoản ngân hàng / mã số thuế")
  void shouldReturnRefDataOptionsWithoutSensitiveBankingFields() {
    BudgetUnitConfigService cfgService = mock(BudgetUnitConfigService.class);
    BudgetUnitConfig cfg =
        BudgetUnitConfig.builder()
            .id(1L)
            .code("UNIT-101")
            .name("Văn phòng Sở")
            .nodeId(101L)
            .nodeCode("VP")
            .unitType("GROUP_2")
            .taxCode("0400123456")
            .bankAccountNo("118000999888")
            .treasuryAccountNo("3712.1.1054321")
            .status(LifecycleStatus.ACTIVE)
            .build();
    Page<BudgetUnitConfig> cfgPage = new PageImpl<>(List.of(cfg));
    when(cfgService.findAll(any(BudgetUnitConfigSearchCriteria.class), any(Pageable.class)))
        .thenReturn(cfgPage);

    BudgetUnitConfigBusiness cfgBusiness =
        new BudgetUnitConfigBusiness(
            cfgService,
            mock(BudgetUnitConfigMapper.class),
            mock(BudgetUnitConfigValidator.class),
            mock(GenericUpsertHelper.class),
            mock(AuditRollbackHelper.class),
            mock(BusinessLogicHelper.class),
            mock(BudgetWorkflowEngineHelper.class));

    BudgetUnitConfigSearchCriteria cfgCriteria = new BudgetUnitConfigSearchCriteria();
    cfgCriteria.setSize(999);
    PagedData<RefData<Long>> cfgOptions = cfgBusiness.getOptions(cfgCriteria);
    assertThat(cfgCriteria.getSize()).isEqualTo(200);
    assertThat(cfgOptions.getContent()).hasSize(1);
    RefData<Long> opt = cfgOptions.getContent().getFirst();
    assertThat(opt.getKey()).isEqualTo(1L);
    assertThat(opt.getCode()).isEqualTo("UNIT-101");
    assertThat(opt.getStatus()).isEqualTo("ACTIVE");
    assertThat(opt.getExtra()).doesNotContainKeys("taxCode", "bankAccountNo", "treasuryAccountNo");

    BudgetDisbursementService disbService = mock(BudgetDisbursementService.class);
    BudgetDisbursement disb =
        BudgetDisbursement.builder()
            .id(5L)
            .code("GN-05")
            .name("Thanh toán đợt 1")
            .beneficiaryBankAccount("9988776655")
            .beneficiaryTaxCode("0400999888")
            .status(LifecycleStatus.ACTIVE)
            .build();
    when(disbService.findAll(any(BudgetDisbursementSearchCriteria.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(disb)));

    BudgetDisbursementBusiness disbBusiness =
        new BudgetDisbursementBusiness(
            disbService,
            mock(BudgetDisbursementMapper.class),
            mock(BudgetDisbursementValidator.class),
            mock(GenericUpsertHelper.class),
            mock(AuditRollbackHelper.class),
            mock(BusinessLogicHelper.class),
            mock(BudgetWorkflowEngineHelper.class),
            budgetAllocationRepo);

    PagedData<RefData<Long>> disbOptions =
        disbBusiness.getOptions(new BudgetDisbursementSearchCriteria());
    assertThat(disbOptions.getContent()).hasSize(1);
    assertThat(disbOptions.getContent().getFirst().getExtra()).isEmpty();
  }
}
