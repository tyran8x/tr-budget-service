package vn.tr.budget.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.tr.budget.business.helper.BudgetSecurityGuardHelper;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetReport;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetAllocationService;
import vn.tr.budget.dao.service.BudgetCategoryRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetDisbursementService;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetReportService;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.dao.service.BudgetTransactionService;
import vn.tr.budget.dao.service.BudgetUnitConfigRepo;
import vn.tr.budget.data.dto.BudgetAllocationData;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.dto.BudgetReportData;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.budget.data.mapper.BudgetAllocationMapper;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.data.mapper.BudgetReportMapper;
import vn.tr.budget.data.mapper.BudgetTransactionMapper;
import vn.tr.budget.data.validator.BudgetAllocationValidator;
import vn.tr.budget.data.validator.BudgetDisbursementValidator;
import vn.tr.budget.data.validator.BudgetReportValidator;
import vn.tr.budget.data.validator.BudgetTransactionValidator;
import vn.tr.common.core.exception.base.BaseException;
import vn.tr.common.core.exception.base.InvalidDataException;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.satoken.utils.LoginHelper;
import vn.tr.common.web.utils.BusinessLogicHelper;

/**
 * Kiểm thử chuyên sâu khóa bi quan chống TOCTOU double-spend & AB-BA deadlock (TD-C05), bất biến
 * sổ cái & chống Mass Assignment (TD-C06), toán học NSNN / ADVANCE_SETTLEMENT / Enum parity /
 * NV-02..DX-04 / Báo cáo quyết toán & DVC KBNN (TD-C07, TD-H06, TD-M02).
 *
 * @author tyran8x
 */
@ExtendWith(MockitoExtension.class)
class BudgetLedgerConcurrencyAndMathTest {

  @Mock private BudgetAllocationRepo budgetAllocationRepo;
  @Mock private BudgetTransactionRepo budgetTransactionRepo;
  @Mock private BudgetDisbursementRepo budgetDisbursementRepo;
  @Mock private BudgetUnitConfigRepo budgetUnitConfigRepo;
  @Mock private BudgetCategoryRepo budgetCategoryRepo;
  @Mock private BudgetEstimateItemRepo budgetEstimateItemRepo;
  @Mock private GenericUpsertHelper genericUpsertHelper;
  @Mock private AuditRollbackHelper auditRollbackHelper;
  @Mock private BusinessLogicHelper businessLogicHelper;

  @Test
  @DisplayName("TD-C05: Khóa bi quan theo thứ tự ID tăng dần (10 -> 20) dù truyền (20, 10) — chống AB-BA deadlock")
  void shouldAcquirePessimisticLocksInDeterministicAscendingOrder() {
    BudgetWorkflowEngineHelper engine =
        new BudgetWorkflowEngineHelper(
            budgetAllocationRepo,
            budgetTransactionRepo,
            budgetDisbursementRepo,
            budgetUnitConfigRepo,
            budgetCategoryRepo,
            budgetEstimateItemRepo);

    BudgetAllocation a10 = BudgetAllocation.builder().id(10L).code("PB-10").build();
    BudgetAllocation a20 = BudgetAllocation.builder().id(20L).code("PB-20").build();
    when(budgetAllocationRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(a10));
    when(budgetAllocationRepo.findByIdForUpdate(20L)).thenReturn(Optional.of(a20));

    Map<Long, BudgetAllocation> lockedReversed = engine.lockAllocationsInDeterministicOrder(20L, 10L);
    assertThat(lockedReversed.keySet()).containsExactly(10L, 20L);

    InOrder order = inOrder(budgetAllocationRepo);
    order.verify(budgetAllocationRepo).findByIdForUpdate(10L);
    order.verify(budgetAllocationRepo).findByIdForUpdate(20L);
  }

  @Test
  @DisplayName("TD-C05: Chống TOCTOU double-spend — 2 luồng giải ngân 700M cùng lúc trên số dư 1.000M chỉ cho đúng 1 luồng đậu")
  void shouldPreventToctouDoubleSpendUnderConcurrentApproval() throws Exception {
    ReentrantLock dbRowLock = new ReentrantLock();
    BudgetAllocation sharedAlloc =
        BudgetAllocation.builder()
            .id(1L)
            .code("PB-RACE-01")
            .initialAmount(new BigDecimal("1000000000"))
            .effectiveAmount(new BigDecimal("1000000000"))
            .availableAmount(new BigDecimal("1000000000"))
            .build();

    BudgetDisbursement d1 =
        BudgetDisbursement.builder()
            .id(101L)
            .code("GN-RACE-1")
            .allocationId(1L)
            .amount(new BigDecimal("700000000"))
            .disbursementType("DISBURSEMENT")
            .approvalStatus("PENDING_APPROVAL")
            .requestedByPersonId(10L)
            .build();
    BudgetDisbursement d2 =
        BudgetDisbursement.builder()
            .id(102L)
            .code("GN-RACE-2")
            .allocationId(1L)
            .amount(new BigDecimal("700000000"))
            .disbursementType("DISBURSEMENT")
            .approvalStatus("PENDING_APPROVAL")
            .requestedByPersonId(10L)
            .build();

    BudgetDisbursementService disbService = mock(BudgetDisbursementService.class);
    BudgetDisbursementMapper disbMapper = mock(BudgetDisbursementMapper.class);
    BudgetWorkflowEngineHelper engineMock = mock(BudgetWorkflowEngineHelper.class);

    when(disbService.findById(101L)).thenReturn(Optional.of(d1));
    when(disbService.findById(102L)).thenReturn(Optional.of(d2));
    when(disbService.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(disbMapper.toData(any(BudgetDisbursement.class)))
        .thenAnswer(
            inv -> {
              BudgetDisbursement d = inv.getArgument(0);
              return BudgetDisbursementData.builder()
                  .id(d.getId())
                  .approvalStatus(d.getApprovalStatus())
                  .build();
            });
    when(engineMock.lockAllocation(1L))
        .thenAnswer(
            inv -> {
              dbRowLock.lock();
              return Optional.of(sharedAlloc);
            });
    when(engineMock.recalculateAllocationMetrics(1L))
        .thenAnswer(
            inv -> {
              sharedAlloc.setAvailableAmount(
                  sharedAlloc.getAvailableAmount().subtract(new BigDecimal("700000000")));
              return sharedAlloc;
            });

    BudgetDisbursementBusiness disbBiz =
        new BudgetDisbursementBusiness(
            disbService,
            disbMapper,
            mock(BudgetDisbursementValidator.class),
            genericUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper,
            engineMock,
            budgetAllocationRepo);

    CountDownLatch startGate = new CountDownLatch(1);
    Callable<Boolean> task1 =
        () -> {
          startGate.await();
          try {
            disbBiz.approveDisbursement(101L, Map.of());
            return true;
          } catch (BaseException ex) {
            return false;
          } finally {
            if (dbRowLock.isHeldByCurrentThread()) {
              dbRowLock.unlock();
            }
          }
        };
    Callable<Boolean> task2 =
        () -> {
          startGate.await();
          try {
            disbBiz.approveDisbursement(102L, Map.of());
            return true;
          } catch (BaseException ex) {
            return false;
          } finally {
            if (dbRowLock.isHeldByCurrentThread()) {
              dbRowLock.unlock();
            }
          }
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> f1 = pool.submit(task1);
      Future<Boolean> f2 = pool.submit(task2);
      startGate.countDown();
      boolean r1 = f1.get(5, TimeUnit.SECONDS);
      boolean r2 = f2.get(5, TimeUnit.SECONDS);
      assertThat(r1 ^ r2).as("Chỉ đúng 1 luồng được phép duyệt giải ngân").isTrue();
      assertThat(sharedAlloc.getAvailableAmount()).isEqualByComparingTo("300000000");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("TD-C06: Bất biến chứng từ đã duyệt (chặn sửa/xóa/rollback) và chống Mass Assignment cột tài chính")
  void shouldEnforceImmutabilityAndStripMassAssignment() {
    // 1. Mass Assignment sanitization giữ nguyên cột tài chính & phê duyệt
    BudgetSecurityGuardHelper guard = new BudgetSecurityGuardHelper();
    BudgetAllocation existing =
        BudgetAllocation.builder()
            .id(1L)
            .workflowStatus("DRAFT")
            .effectiveAmount(new BigDecimal("1000000000"))
            .availableAmount(new BigDecimal("400000000"))
            .approvedByPersonId(null)
            .build();
    BudgetAllocation tampered =
        BudgetAllocation.builder()
            .id(1L)
            .workflowStatus("APPROVED")
            .effectiveAmount(new BigDecimal("999999999999"))
            .availableAmount(new BigDecimal("999999999999"))
            .approvedByPersonId(999L)
            .build();
    guard.sanitizeAllocationForUpdate(tampered, existing);
    assertThat(tampered.getWorkflowStatus()).isEqualTo("DRAFT");
    assertThat(tampered.getEffectiveAmount()).isEqualByComparingTo("1000000000");
    assertThat(tampered.getAvailableAmount()).isEqualByComparingTo("400000000");
    assertThat(tampered.getApprovedByPersonId()).isNull();

    // 2. Chặn update/delete/rollback bản ghi APPROVED trên BudgetAllocation
    BudgetAllocationService allocService = mock(BudgetAllocationService.class);
    BudgetAllocation approvedAlloc =
        BudgetAllocation.builder().id(10L).code("PB-LOCK").workflowStatus("APPROVED").build();
    when(allocService.findById(10L)).thenReturn(Optional.of(approvedAlloc));
    BudgetAllocationBusiness allocBiz =
        new BudgetAllocationBusiness(
            allocService,
            mock(BudgetAllocationMapper.class),
            mock(BudgetAllocationValidator.class),
            genericUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper,
            mock(BudgetWorkflowEngineHelper.class),
            budgetAllocationRepo);

    assertThatThrownBy(
            () -> allocBiz.update(10L, BudgetAllocationData.builder().code("PB-LOCK").build()))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("đã chốt");
    assertThatThrownBy(() -> allocBiz.delete(10L))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("đã chốt");
    assertThatThrownBy(() -> allocBiz.rollback(10L, 1L))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("đã chốt");
  }

  @Test
  @DisplayName("TD-C07: Whitelist trạng thái duyệt, chống đếm trùng ADVANCE_SETTLEMENT, không .abs() SUPPLEMENT âm và EARMARK_RELEASE")
  void shouldComputeLedgerMathAccuratelyWithoutDoubleCounting() {
    BudgetWorkflowEngineHelper engine =
        new BudgetWorkflowEngineHelper(
            budgetAllocationRepo,
            budgetTransactionRepo,
            budgetDisbursementRepo,
            budgetUnitConfigRepo,
            budgetCategoryRepo,
            budgetEstimateItemRepo);

    BudgetAllocation alloc =
        BudgetAllocation.builder()
            .id(1L)
            .code("PB-MATH-01")
            .initialAmount(new BigDecimal("1000000000"))
            .build();

    List<BudgetTransaction> txs =
        List.of(
            // DRAFT phải bị bỏ qua (whitelist!)
            BudgetTransaction.builder()
                .txType("SUPPLEMENT")
                .amount(new BigDecimal("500000000"))
                .approvalStatus("DRAFT")
                .build(),
            // SUPPLEMENT âm (điều chỉnh giảm lương cơ sở) KHÔNG được .abs()
            BudgetTransaction.builder()
                .txType("BATCH_PAYROLL_INDEXATION")
                .amount(new BigDecimal("-50000000"))
                .approvalStatus("APPROVED")
                .build(),
            // WITHDRAWAL giảm hiệu lực
            BudgetTransaction.builder()
                .txType("WITHDRAWAL")
                .amount(new BigDecimal("100000000"))
                .approvalStatus("APPROVED")
                .build(),
            // EARMARK_HOLD 300M - EARMARK_RELEASE 100M = 200M đang tạm giữ
            BudgetTransaction.builder()
                .txType("EARMARK_HOLD")
                .amount(new BigDecimal("300000000"))
                .approvalStatus("APPROVED")
                .build(),
            BudgetTransaction.builder()
                .txType("EARMARK_RELEASE")
                .amount(new BigDecimal("100000000"))
                .approvalStatus("APPROVED")
                .build());

    // ADVANCE (100M) + ADVANCE_SETTLEMENT (100M) -> disbursedAmount = 100M (KHÔNG bị cộng đôi thành 200M!)
    List<BudgetDisbursement> disbs =
        List.of(
            BudgetDisbursement.builder()
                .disbursementType("ADVANCE")
                .amount(new BigDecimal("100000000"))
                .approvalStatus("APPROVED_DISBURSED")
                .build(),
            BudgetDisbursement.builder()
                .disbursementType("ADVANCE_SETTLEMENT")
                .amount(new BigDecimal("100000000"))
                .settledAmount(new BigDecimal("100000000"))
                .approvalStatus("SETTLED")
                .build(),
            // DRAFT phải bị bỏ qua
            BudgetDisbursement.builder()
                .disbursementType("DISBURSEMENT")
                .amount(new BigDecimal("999000000"))
                .approvalStatus("DRAFT")
                .build());

    when(budgetAllocationRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(alloc));
    when(budgetTransactionRepo.findByAllocationIdOrderByIdAsc(1L)).thenReturn(txs);
    when(budgetDisbursementRepo.findByAllocationIdOrderByIdAsc(1L)).thenReturn(disbs);
    when(budgetAllocationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    BudgetAllocation result = engine.recalculateAllocationMetrics(1L);

    // effective = 1000M - 50M (batch payroll âm) = 950M; expiredCancelled = 100M (WITHDRAWAL)
    assertThat(result.getEffectiveAmount()).isEqualByComparingTo("950000000.00");
    assertThat(result.getExpiredCancelledAmount()).isEqualByComparingTo("100000000.00");
    assertThat(result.getDisbursedAmount()).isEqualByComparingTo("100000000.00");
    assertThat(result.getAdvancedAmount()).isEqualByComparingTo("0.00");
    assertThat(result.getSettledAmount()).isEqualByComparingTo("100000000.00");
    assertThat(result.getCommittedAmount()).isEqualByComparingTo("200000000.00");
    // available = 950M - 200M (earmark held) - 100M (disbursed) - 100M (expiredCancelled) = 550M
    assertThat(result.getAvailableAmount()).isEqualByComparingTo("550000000.00");
    assertThat(engine.computeCurrentEarmarkHeldAmount(1L)).isEqualByComparingTo("200000000.00");
  }

  @Test
  @DisplayName("TD-C07, TD-H06, TD-M02: Chặn NV-02 tạo nguồn từ hư không, DX-03 không cap 3 đơn vị & nhận giảm lương, DX-04 tuân thủ NĐ 60, Báo cáo trừ carryOverOut & DVC KBNN không mutate trên GET")
  void shouldEnforceNv02Dx03Dx04ReportAndKbnnRules() {
    BudgetTransactionService txService = mock(BudgetTransactionService.class);
    BudgetTransactionMapper txMapper = mock(BudgetTransactionMapper.class);
    BudgetWorkflowEngineHelper engineMock = mock(BudgetWorkflowEngineHelper.class);
    lenient()
        .when(engineMock.safe(any()))
        .thenAnswer(inv -> inv.getArgument(0) != null ? inv.getArgument(0) : BigDecimal.ZERO);

    BudgetTransactionBusiness txBiz =
        new BudgetTransactionBusiness(
            txService,
            txMapper,
            mock(BudgetTransactionValidator.class),
            genericUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper,
            engineMock,
            budgetAllocationRepo);

    // Tạo 5 phân bổ cho 5 đơn vị (chứng minh DX-03 bỏ giới hạn cứng 3 đơn vị)
    List<BudgetAllocation> fiveAllocs = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      BudgetAllocation a =
          BudgetAllocation.builder()
              .id((long) i)
              .code("PB-2026-U" + i)
              .budgetYear(2026)
              .nodeId(100L + i)
              .nodeCode("UNIT_" + i)
              .nodeName("Đơn vị " + i)
              .itemType("EXPENDITURE")
              .initialAmount(new BigDecimal("1000000000"))
              .effectiveAmount(new BigDecimal("1000000000"))
              .availableAmount(new BigDecimal("1000000000"))
              .build();
      fiveAllocs.add(a);
      lenient().when(engineMock.lockAllocation((long) i)).thenReturn(Optional.of(a));
    }
    lenient().when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2026)).thenReturn(fiveAllocs);
    lenient().when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2025)).thenReturn(List.of());
    lenient()
        .when(budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(101L, 2026))
        .thenReturn(List.of(fiveAllocs.getFirst()));
    lenient()
        .when(budgetAllocationRepo.findByNodeIdAndBudgetYearOrderByIdAsc(101L, 2025))
        .thenReturn(List.of());
    lenient()
        .when(genericUpsertHelper.upsert(any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              BudgetTransactionData d = inv.getArgument(0);
              return BudgetTransaction.builder()
                  .id(500L)
                  .code(d.getCode())
                  .txType(d.getTxType())
                  .amount(d.getAmount())
                  .approvalStatus(d.getApprovalStatus())
                  .build();
            });
    lenient()
        .when(txMapper.toData(any(BudgetTransaction.class)))
        .thenAnswer(
            inv -> {
              BudgetTransaction t = inv.getArgument(0);
              return BudgetTransactionData.builder()
                  .id(t.getId())
                  .code(t.getCode())
                  .txType(t.getTxType())
                  .amount(t.getAmount())
                  .approvalStatus(t.getApprovalStatus())
                  .build();
            });

    AtomicReference<List<String>> roles = new AtomicReference<>(List.of("ROLE_BUDGET_DIRECTORATE"));
    try (MockedStatic<LoginHelper> loginMock = mockStatic(LoginHelper.class);
        MockedStatic<StpUtil> stpMock = mockStatic(StpUtil.class)) {
      loginMock.when(LoginHelper::isSuperAdmin).thenReturn(false);
      stpMock.when(StpUtil::isLogin).thenReturn(true);
      stpMock.when(StpUtil::getRoleList).thenAnswer(inv -> roles.get());
      stpMock.when(StpUtil::getPermissionList).thenReturn(List.of());

      // NV-02: Năm nguồn 2025 không có phân bổ -> PHẢI CHẶN (không tự bịa phân bổ APPROVED!)
      assertThatThrownBy(
              () ->
                  txBiz.executeYearCarryOver(
                      Map.of("fromYear", 2025, "toYear", 2026, "nodeId", 101L, "amount", "1000000")))
          .isInstanceOf(BaseException.class)
          .hasMessageContaining("Không tìm thấy phân bổ dự toán nguồn");

      // NV-02: toYear <= fromYear -> chặn
      assertThatThrownBy(
              () ->
                  txBiz.executeYearCarryOver(
                      Map.of("fromYear", 2026, "toYear", 2026, "nodeId", 101L, "amount", "1000000")))
          .isInstanceOf(BaseException.class);

      // DX-02: không có số dư tạm giữ (held == 0) -> chặn giải tỏa
      when(engineMock.computeCurrentEarmarkHeldAmount(1L)).thenReturn(BigDecimal.ZERO);
      assertThatThrownBy(
              () ->
                  txBiz.executeTrancheRelease(
                      Map.of("budgetYear", 2026, "nodeId", 101L, "releaseRatePercent", "25")))
          .isInstanceOf(BaseException.class)
          .hasMessageContaining("tạm giữ");

      // DX-03: xử lý đủ 5 đơn vị (không bị cap 3) & giữ nguyên dấu âm khi giảm lương/biên chế
      Map<String, Object> payrollRes =
          txBiz.executeBatchPayrollIndexation(
              Map.of(
                  "budgetYear", 2026,
                  "oldBaseSalary", "2340000",
                  "newBaseSalary", "1800000",
                  "baseStaffCount", 20,
                  "staffChangeCount", 0));
      assertThat(payrollRes.get("indexed")).isEqualTo(true);
      assertThat(payrollRes.get("affectedUnitCount")).isEqualTo(5);
      assertThat((BigDecimal) payrollRes.get("totalIndexedAmount")).isNegative();

      // DX-04: Tổng tỷ lệ > 100% -> InvalidDataException; GROUP_2 trích PTHDSN < 25% -> InvalidDataException
      roles.set(List.of("ROLE_BUDGET_UNIT_HEAD"));
      assertThatThrownBy(
              () ->
                  txBiz.executeAutonomyFundDistribution(
                      Map.of(
                          "budgetYear", 2026,
                          "nodeId", 101L,
                          "autonomyGroup", "GROUP_2",
                          "surplusAmount", "100000000",
                          "pthdsnRatePercent", "50",
                          "bstnRatePercent", "40",
                          "ktplRatePercent", "20")))
          .isInstanceOf(InvalidDataException.class)
          .hasMessageContaining("100%");

      assertThatThrownBy(
              () ->
                  txBiz.executeAutonomyFundDistribution(
                      Map.of(
                          "budgetYear", 2026,
                          "nodeId", 101L,
                          "autonomyGroup", "GROUP_2",
                          "surplusAmount", "100000000",
                          "pthdsnRatePercent", "10",
                          "bstnRatePercent", "40",
                          "ktplRatePercent", "20")))
          .isInstanceOf(InvalidDataException.class)
          .hasMessageContaining("25%");
    }

    // BudgetReportBusiness: trừ carryOverOut & dùng ADVANCE cho expAdv
    BudgetReportService rptService = mock(BudgetReportService.class);
    BudgetReportMapper rptMapper = mock(BudgetReportMapper.class);
    BudgetAllocation reportAlloc =
        BudgetAllocation.builder()
            .id(1L)
            .budgetYear(2026)
            .nodeId(101L)
            .itemType("EXPENDITURE")
            .initialAmount(new BigDecimal("1000"))
            .carryOverInAmount(new BigDecimal("300"))
            .carryOverOutAmount(new BigDecimal("100"))
            .effectiveAmount(new BigDecimal("1200"))
            .disbursedAmount(new BigDecimal("500"))
            .settledAmount(new BigDecimal("400"))
            .availableAmount(new BigDecimal("700"))
            .build();
    when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2026)).thenReturn(List.of(reportAlloc));
    when(budgetTransactionRepo.findByBudgetYearOrderByIdDesc(2026))
        .thenReturn(
            List.of(
                BudgetTransaction.builder()
                    .id(1L)
                    .nodeId(101L)
                    .txType("CARRY_OVER_IN")
                    .amount(new BigDecimal("300"))
                    .approvalStatus("APPROVED")
                    .build(),
                BudgetTransaction.builder()
                    .id(2L)
                    .nodeId(101L)
                    .txType("CARRY_OVER_OUT")
                    .amount(new BigDecimal("100"))
                    .approvalStatus("APPROVED")
                    .build()));
    when(budgetDisbursementRepo.findByBudgetYearOrderByIdDesc(2026))
        .thenReturn(
            List.of(
                BudgetDisbursement.builder()
                    .id(1L)
                    .nodeId(101L)
                    .disbursementType("ADVANCE")
                    .amount(new BigDecimal("150"))
                    .approvalStatus("APPROVED_DISBURSED")
                    .build(),
                BudgetDisbursement.builder()
                    .id(2L)
                    .nodeId(101L)
                    .disbursementType("COMMITMENT")
                    .amount(new BigDecimal("200"))
                    .approvalStatus("COMMITTED")
                    .build()));
    GenericUpsertHelper reportUpsertHelper = mock(GenericUpsertHelper.class);
    when(reportUpsertHelper.upsert(any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              BudgetReportData d = inv.getArgument(0);
              return BudgetReport.builder()
                  .id(700L)
                  .code(d.getCode())
                  .totalCarryOverAmount(d.getTotalCarryOverAmount())
                  .totalExpenditureAdvanced(d.getTotalExpenditureAdvanced())
                  .workflowStatus("DRAFT")
                  .build();
            });
    when(rptMapper.toData(any(BudgetReport.class)))
        .thenAnswer(
            inv -> {
              BudgetReport r = inv.getArgument(0);
              return BudgetReportData.builder()
                  .totalCarryOverAmount(r.getTotalCarryOverAmount())
                  .totalExpenditureAdvanced(r.getTotalExpenditureAdvanced())
                  .build();
            });

    BudgetReportBusiness rptBiz =
        new BudgetReportBusiness(
            rptService,
            rptMapper,
            mock(BudgetReportValidator.class),
            reportUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper,
            budgetAllocationRepo);
    rptBiz.setBudgetTransactionRepo(budgetTransactionRepo);
    rptBiz.setBudgetDisbursementRepo(budgetDisbursementRepo);

    BudgetReportData generated =
        rptBiz.generateConsolidatedReport(Map.of("budgetYear", 2026, "reportPeriod", "ANNUAL"));
    // carryOver net = 300 - 100 = 200; expAdv = 150 (chỉ tính ADVANCE, không lẫn COMMITMENT)
    assertThat(generated.getTotalCarryOverAmount()).isEqualByComparingTo("200.00");
    assertThat(generated.getTotalExpenditureAdvanced()).isEqualByComparingTo("150.00");

    // DVC KBNN: chặn nếu thiếu BudgetUnitConfig & GET preview không gọi save()
    BudgetDisbursementService disbService = mock(BudgetDisbursementService.class);
    BudgetDisbursement kbnnDisb =
        BudgetDisbursement.builder()
            .id(70L)
            .code("GN-KBNN-70")
            .nodeId(101L)
            .amount(new BigDecimal("100000"))
            .beneficiaryBankAccount("1180000999")
            .beneficiaryBankName("VietinBank")
            .build();
    when(disbService.findById(70L)).thenReturn(Optional.of(kbnnDisb));
    BudgetDisbursementBusiness disbBiz =
        new BudgetDisbursementBusiness(
            disbService,
            mock(BudgetDisbursementMapper.class),
            mock(BudgetDisbursementValidator.class),
            genericUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper,
            engineMock,
            budgetAllocationRepo);
    disbBiz.setBudgetUnitConfigRepo(budgetUnitConfigRepo);

    when(budgetUnitConfigRepo.findFirstByNodeIdOrderByIdDesc(101L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> disbBiz.exportKbnnDvcPackage(70L))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("BudgetUnitConfig");

    when(budgetUnitConfigRepo.findFirstByNodeIdOrderByIdDesc(101L))
        .thenReturn(
            Optional.of(
                BudgetUnitConfig.builder()
                    .nodeId(101L)
                    .treasuryAccountNo("3712.1.9990101.00000")
                    .treasuryBranchName("KBNN Đà Nẵng")
                    .build()));
    Map<String, Object> preview = disbBiz.previewKbnnDvcPackage(70L);
    assertThat(preview).containsKey("xmlPayloadPreview");
    verify(disbService, never()).save(any());
  }
}
