package vn.tr.budget.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.exception.SameTokenInvalidException;
import cn.dev33.satoken.same.SaSameUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.budget.business.helper.BudgetSecurityGuardHelper;
import vn.tr.budget.business.helper.BudgetWorkflowEngineHelper;
import vn.tr.budget.controller.BudgetInternalController;
import vn.tr.budget.controller.BudgetReportController;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetReport;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetAllocationService;
import vn.tr.budget.dao.service.BudgetAllocationSpecifications;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetDisbursementService;
import vn.tr.budget.dao.service.BudgetQueryHelper;
import vn.tr.budget.dao.service.BudgetReportService;
import vn.tr.budget.dao.service.BudgetTransactionService;
import vn.tr.budget.dao.service.BudgetUnitConfigService;
import vn.tr.budget.data.dto.BudgetAllocationData;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.dto.BudgetReportData;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.budget.data.mapper.BudgetAllocationMapper;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.data.mapper.BudgetReportMapper;
import vn.tr.budget.data.mapper.BudgetTransactionMapper;
import vn.tr.budget.data.mapper.BudgetUnitConfigMapper;
import vn.tr.budget.data.validator.BudgetAllocationValidator;
import vn.tr.budget.data.validator.BudgetDisbursementValidator;
import vn.tr.budget.data.validator.BudgetReportValidator;
import vn.tr.budget.data.validator.BudgetTransactionValidator;
import vn.tr.budget.data.validator.BudgetUnitConfigValidator;
import vn.tr.common.core.context.HeaderContext;
import vn.tr.common.core.domain.model.LoginUser;
import vn.tr.common.core.exception.ServiceException;
import vn.tr.common.core.exception.base.PermissionDeniedException;
import vn.tr.common.feign.budget.data.BudgetCommitmentReserveData;
import vn.tr.common.feign.budget.data.BudgetLedgerTransactionData;
import vn.tr.common.feign.budget.data.BudgetPaymentSettleData;
import vn.tr.common.feign.budget.data.BudgetPlanSyncRequestData;
import vn.tr.common.feign.budget.data.BudgetPlanSyncResultData;
import vn.tr.common.feign.dto.InternalAnalyticsMetricData;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.satoken.utils.LoginHelper;
import vn.tr.common.security.abac.OrgSubtreeAuthorizationHelper;
import vn.tr.common.web.utils.BusinessLogicHelper;

/**
 * Kiểm thử chuyên sâu các hạng mục Bảo mật P0/P1 đã khắc phục: S2S Same-Token (TD-C01), Chống giả
 * mạo Actor & SoD 4 mắt (TD-C03/TD-C04), ABAC Org-Subtree chặn IDOR xuyên nodeId (TD-M03) và
 * Multi-Tenant Fail-Closed (TD-C10).
 *
 * @author tyran8x
 */
@ExtendWith(MockitoExtension.class)
class BudgetSecurityAndSodHardeningTest {

  @Mock private BudgetAllocationRepo budgetAllocationRepo;
  @Mock private BudgetDisbursementRepo budgetDisbursementRepo;
  @Mock private BudgetWorkflowEngineHelper workflowEngineHelper;
  @Mock private GenericUpsertHelper genericUpsertHelper;
  @Mock private AuditRollbackHelper auditRollbackHelper;
  @Mock private BusinessLogicHelper businessLogicHelper;

  @AfterEach
  void cleanContext() {
    HeaderContext.clear();
  }

  @Test
  @DisplayName("TD-C01: Gỡ hoàn toàn @SaIgnore và bắt buộc SaSameUtil.checkCurrentRequestToken() trên cả 7/7 endpoint S2S")
  void shouldEnforceSaSameUtilAndNoSaIgnoreOnAllSevenInternalEndpoints() {
    assertThat(BudgetInternalController.class.isAnnotationPresent(SaIgnore.class)).isFalse();
    for (Method m : BudgetInternalController.class.getDeclaredMethods()) {
      assertThat(m.isAnnotationPresent(SaIgnore.class))
          .as("Method %s must not have @SaIgnore", m.getName())
          .isFalse();
    }

    BudgetAllocationBusiness allocBiz = mock(BudgetAllocationBusiness.class);
    BudgetDisbursementBusiness disbBiz = mock(BudgetDisbursementBusiness.class);
    BudgetTransactionBusiness txBiz = mock(BudgetTransactionBusiness.class);
    BudgetDashboardBusiness dashBiz = mock(BudgetDashboardBusiness.class);

    when(allocBiz.syncFromPlan(any())).thenReturn(BudgetPlanSyncResultData.builder().build());
    when(allocBiz.getBudgetSnapshotByPlanCode("KH-1"))
        .thenReturn(BudgetPlanSyncResultData.builder().linkedPlanCode("KH-1").build());
    when(txBiz.recordFromPlan(any())).thenReturn(Map.of("recorded", true));
    when(disbBiz.reserveCommitment(any())).thenReturn(Map.of("committed", true));
    when(disbBiz.settleFromPaymentWebhook(eq(1L), any())).thenReturn(Map.of("settled", true));
    when(dashBiz.getOverview(2026, 101L, null)).thenReturn(Map.of("budgetYear", 2026));
    when(dashBiz.getInternalAnalyticsMetrics("VP", 2026, "YEAR"))
        .thenReturn(InternalAnalyticsMetricData.builder().totalRecords(1L).build());

    BudgetInternalController controller =
        new BudgetInternalController(allocBiz, disbBiz, txBiz, dashBiz, new ObjectMapper());

    try (MockedStatic<SaSameUtil> sameMock = mockStatic(SaSameUtil.class)) {
      controller.syncAllocationFromPlan(BudgetPlanSyncRequestData.builder().planCode("KH-1").build());
      controller.getBudgetSnapshotByPlanCode("KH-1");
      controller.recordPlanLedgerTransaction(BudgetLedgerTransactionData.builder().planCode("KH-1").build());
      controller.reserveCommitment(BudgetCommitmentReserveData.builder().planItemCode("NV-1").build());
      controller.settleDisbursementFromPaymentWebhook(
          1L,
          BudgetPaymentSettleData.builder()
              .transactionCode("PAY-1")
              .paymentStatus("PAID")
              .paidAmount(BigDecimal.TEN)
              .build());
      controller.getDashboardOverview(2026, 101L);
      controller.getInternalAnalyticsMetrics("VP", 2026, "YEAR");
      sameMock.verify(SaSameUtil::checkCurrentRequestToken, times(7));
    }

    // Khi Same-Token không hợp lệ -> chặn ngay lập tức, không gọi vào Business
    BudgetAllocationBusiness blockedAllocBiz = mock(BudgetAllocationBusiness.class);
    BudgetInternalController blockedController =
        new BudgetInternalController(blockedAllocBiz, disbBiz, txBiz, dashBiz, new ObjectMapper());
    try (MockedStatic<SaSameUtil> sameMock = mockStatic(SaSameUtil.class)) {
      sameMock
          .when(SaSameUtil::checkCurrentRequestToken)
          .thenThrow(new SameTokenInvalidException("Invalid Same-Token"));
      assertThatThrownBy(() -> blockedController.getBudgetSnapshotByPlanCode("KH-99"))
          .isInstanceOf(SameTokenInvalidException.class);
      verify(blockedAllocBiz, never()).getBudgetSnapshotByPlanCode(any());
    }
  }

  @Test
  @DisplayName("TD-C04: Chống giả mạo danh tính từ Request Body — chỉ tin cậy phiên Sa-Token")
  void shouldIgnoreSpoofedActorFieldsInRequestBody() {
    BudgetIntegrationOrchestrator orchestrator =
        new BudgetIntegrationOrchestrator(null, null, null, budgetDisbursementRepo);
    LoginUser realUser = new LoginUser();
    realUser.setUserId("101");
    realUser.setUsername("ketoan_that");
    realUser.setFullName("Nguyễn Văn Thật");

    try (MockedStatic<LoginHelper> loginMock = mockStatic(LoginHelper.class)) {
      loginMock.when(LoginHelper::getLoginUser).thenReturn(realUser);
      loginMock.when(LoginHelper::getUserId).thenReturn(101L);
      loginMock.when(LoginHelper::getUsername).thenReturn("ketoan_that");

      Map<String, Object> spoofedBody =
          Map.of(
              "actorId", 999L,
              "approvedByPersonId", 888L,
              "actorName", "Giám đốc Giả Mạo",
              "approvedByName", "Giám đốc Giả Mạo");

      assertThat(orchestrator.resolveCurrentPersonId(spoofedBody, "actorId", "approvedByPersonId"))
          .isEqualTo(101L);
      assertThat(orchestrator.resolveCurrentActorName(spoofedBody, "actorName", "approvedByName"))
          .isEqualTo("Nguyễn Văn Thật");
    }
  }

  @Test
  @DisplayName("TD-C03: Cưỡng chế SoD 4 mắt (creator != reviewer != approver) trên cả 4 thực thể workflow & tách quyền BudgetReportReconcile")
  void shouldEnforceFourEyesSodAcrossAllFourWorkflowEntities() throws Exception {
    // 1. Kiểm tra tách quyền riêng BudgetReportReconcile trên BudgetReportController
    Method reconcileMethod =
        BudgetReportController.class.getMethod("confirmTreasuryReconciliation", Long.class, Map.class);
    SaCheckPermission perm = reconcileMethod.getAnnotation(SaCheckPermission.class);
    assertThat(perm).isNotNull();
    assertThat(perm.value()).containsExactly("BudgetReportReconcile");

    AtomicLong currentUserId = new AtomicLong(101L);
    AtomicReference<String> currentUsername = new AtomicReference<>("creator_101");
    AtomicReference<String> currentFullName = new AtomicReference<>("Người Lập 101");

    try (MockedStatic<LoginHelper> loginMock = mockStatic(LoginHelper.class);
        MockedStatic<StpUtil> stpMock = mockStatic(StpUtil.class)) {
      loginMock.when(LoginHelper::getUserId).thenAnswer(inv -> currentUserId.get());
      loginMock.when(LoginHelper::getUsername).thenAnswer(inv -> currentUsername.get());
      loginMock
          .when(LoginHelper::getLoginUser)
          .thenAnswer(
              inv -> {
                LoginUser u = new LoginUser();
                u.setUserId(String.valueOf(currentUserId.get()));
                u.setUsername(currentUsername.get());
                u.setFullName(currentFullName.get());
                return u;
              });
      lenient().when(LoginHelper.isSuperAdmin()).thenReturn(false);
      stpMock.when(StpUtil::isLogin).thenReturn(true);
      stpMock.when(StpUtil::getRoleList).thenReturn(List.of("ROLE_BUDGET_DIRECTORATE"));
      stpMock.when(StpUtil::getPermissionList).thenReturn(List.of());

      // 2. BudgetAllocation: người trình (101) tự duyệt -> 403 SoD; người khác (202) -> thành công
      BudgetAllocationService allocService = mock(BudgetAllocationService.class);
      BudgetAllocationMapper allocMapper = mock(BudgetAllocationMapper.class);
      BudgetAllocation alloc =
          BudgetAllocation.builder()
              .id(1L)
              .code("PB-SOD-01")
              .nodeId(10L)
              .workflowStatus("PENDING_APPROVAL")
              .submittedByPersonId(101L)
              .initialAmount(new BigDecimal("100000000"))
              .build();
      alloc.setCreatedBy("creator_101");
      when(allocService.findById(1L)).thenReturn(Optional.of(alloc));
      when(allocService.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(workflowEngineHelper.recalculateAllocationMetrics(1L)).thenReturn(alloc);
      when(allocMapper.toData(any(BudgetAllocation.class)))
          .thenAnswer(
              inv -> {
                BudgetAllocation a = inv.getArgument(0);
                return BudgetAllocationData.builder()
                    .id(a.getId())
                    .workflowStatus(a.getWorkflowStatus())
                    .approvedByPersonId(a.getApprovedByPersonId())
                    .build();
              });

      BudgetAllocationBusiness allocBiz =
          new BudgetAllocationBusiness(
              allocService,
              allocMapper,
              mock(BudgetAllocationValidator.class),
              genericUpsertHelper,
              auditRollbackHelper,
              businessLogicHelper,
              workflowEngineHelper,
              budgetAllocationRepo);

      assertThatThrownBy(() -> allocBiz.approveAllocation(1L, Map.of()))
          .isInstanceOf(PermissionDeniedException.class)
          .hasMessageContaining("4 mắt");

      currentUserId.set(202L);
      currentUsername.set("approver_202");
      currentFullName.set("Lãnh đạo 202");
      BudgetAllocationData approvedAlloc = allocBiz.approveAllocation(1L, Map.of());
      assertThat(approvedAlloc.getWorkflowStatus()).isEqualTo("APPROVED");
      assertThat(approvedAlloc.getApprovedByPersonId()).isEqualTo(202L);

      // 3. BudgetDisbursement: người lập (101) tự duyệt -> 403 SoD; người khác (202) -> thành công
      BudgetDisbursementService disbService = mock(BudgetDisbursementService.class);
      BudgetDisbursementMapper disbMapper = mock(BudgetDisbursementMapper.class);
      BudgetDisbursement disb =
          BudgetDisbursement.builder()
              .id(2L)
              .code("GN-SOD-01")
              .allocationId(1L)
              .nodeId(10L)
              .amount(new BigDecimal("50000000"))
              .disbursementType("DISBURSEMENT")
              .approvalStatus("PENDING_APPROVAL")
              .requestedByPersonId(101L)
              .build();
      disb.setCreatedBy("creator_101");
      when(disbService.findById(2L)).thenReturn(Optional.of(disb));
      when(disbService.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(workflowEngineHelper.lockAllocation(1L))
          .thenReturn(
              Optional.of(
                  BudgetAllocation.builder()
                      .id(1L)
                      .availableAmount(new BigDecimal("100000000"))
                      .build()));
      when(disbMapper.toData(any(BudgetDisbursement.class)))
          .thenAnswer(
              inv -> {
                BudgetDisbursement d = inv.getArgument(0);
                return BudgetDisbursementData.builder()
                    .id(d.getId())
                    .approvalStatus(d.getApprovalStatus())
                    .approvedByPersonId(d.getApprovedByPersonId())
                    .build();
              });

      BudgetDisbursementBusiness disbBiz =
          new BudgetDisbursementBusiness(
              disbService,
              disbMapper,
              mock(BudgetDisbursementValidator.class),
              genericUpsertHelper,
              auditRollbackHelper,
              businessLogicHelper,
              workflowEngineHelper,
              budgetAllocationRepo);

      currentUserId.set(101L);
      currentUsername.set("creator_101");
      assertThatThrownBy(() -> disbBiz.approveDisbursement(2L, Map.of()))
          .isInstanceOf(PermissionDeniedException.class)
          .hasMessageContaining("4 mắt");

      currentUserId.set(202L);
      currentUsername.set("approver_202");
      BudgetDisbursementData approvedDisb = disbBiz.approveDisbursement(2L, Map.of());
      assertThat(approvedDisb.getApprovalStatus()).isEqualTo("APPROVED_DISBURSED");

      // 4. BudgetTransaction: người lập (101) tự duyệt -> 403 SoD; người khác (202) -> thành công
      BudgetTransactionService txService = mock(BudgetTransactionService.class);
      BudgetTransactionMapper txMapper = mock(BudgetTransactionMapper.class);
      BudgetTransaction tx =
          BudgetTransaction.builder()
              .id(3L)
              .code("TX-SOD-01")
              .allocationId(1L)
              .nodeId(10L)
              .txType("SUPPLEMENT")
              .amount(new BigDecimal("20000000"))
              .approvalStatus("PENDING_APPROVAL")
              .requestedByPersonId(101L)
              .build();
      tx.setCreatedBy("creator_101");
      when(txService.findById(3L)).thenReturn(Optional.of(tx));
      when(txService.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(txMapper.toData(any(BudgetTransaction.class)))
          .thenAnswer(
              inv -> {
                BudgetTransaction t = inv.getArgument(0);
                return BudgetTransactionData.builder()
                    .id(t.getId())
                    .approvalStatus(t.getApprovalStatus())
                    .build();
              });

      BudgetTransactionBusiness txBiz =
          new BudgetTransactionBusiness(
              txService,
              txMapper,
              mock(BudgetTransactionValidator.class),
              genericUpsertHelper,
              auditRollbackHelper,
              businessLogicHelper,
              workflowEngineHelper,
              budgetAllocationRepo);

      currentUserId.set(101L);
      currentUsername.set("creator_101");
      assertThatThrownBy(() -> txBiz.approveTransaction(3L, Map.of()))
          .isInstanceOf(PermissionDeniedException.class)
          .hasMessageContaining("4 mắt");

      currentUserId.set(202L);
      currentUsername.set("approver_202");
      BudgetTransactionData approvedTx = txBiz.approveTransaction(3L, Map.of());
      assertThat(approvedTx.getApprovalStatus()).isEqualTo("APPROVED");

      // 5. BudgetReport: SoD 3 vai trò (creator 101 != reviewer 202 != approver 303), không ghi đè reviewedBy
      BudgetReportService rptService = mock(BudgetReportService.class);
      BudgetReportMapper rptMapper = mock(BudgetReportMapper.class);
      BudgetReport rpt =
          BudgetReport.builder()
              .id(4L)
              .code("BC-SOD-01")
              .budgetYear(2026)
              .periodNumber(1)
              .nodeId(10L)
              .workflowStatus("PENDING_APPROVAL")
              .submittedByPersonId(101L)
              .build();
      rpt.setCreatedBy("creator_101");
      when(rptService.findById(4L)).thenReturn(Optional.of(rpt));
      when(rptService.save(any())).thenAnswer(inv -> inv.getArgument(0));
      lenient().when(budgetAllocationRepo.findByBudgetYearOrderByIdAsc(2026)).thenReturn(List.of());
      when(rptMapper.toData(any(BudgetReport.class)))
          .thenAnswer(
              inv -> {
                BudgetReport r = inv.getArgument(0);
                return BudgetReportData.builder()
                    .id(r.getId())
                    .workflowStatus(r.getWorkflowStatus())
                    .reviewedByPersonId(r.getReviewedByPersonId())
                    .approvedByPersonId(r.getApprovedByPersonId())
                    .build();
              });

      BudgetReportBusiness rptBiz =
          new BudgetReportBusiness(
              rptService,
              rptMapper,
              mock(BudgetReportValidator.class),
              genericUpsertHelper,
              auditRollbackHelper,
              businessLogicHelper,
              budgetAllocationRepo);

      // Creator (101) không được tự xác nhận đối chiếu KBNN
      currentUserId.set(101L);
      currentUsername.set("creator_101");
      assertThatThrownBy(() -> rptBiz.confirmTreasuryReconciliation(4L, Map.of()))
          .isInstanceOf(PermissionDeniedException.class)
          .hasMessageContaining("4 mắt");

      // Reviewer (202) xác nhận đối chiếu KBNN thành công
      currentUserId.set(202L);
      currentUsername.set("reviewer_202");
      currentFullName.set("Kế toán thẩm định 202");
      rptBiz.confirmTreasuryReconciliation(4L, Map.of("treasuryConfirmationCode", "KBNN-OK-01"));
      assertThat(rpt.getReviewedByPersonId()).isEqualTo(202L);

      // Reviewer (202) KHÔNG được tự phê duyệt cuối cùng
      assertThatThrownBy(() -> rptBiz.approveReport(4L, Map.of()))
          .isInstanceOf(PermissionDeniedException.class)
          .hasMessageContaining("người thẩm định");

      // Approver (303) phê duyệt thành công và giữ nguyên reviewedByPersonId = 202
      currentUserId.set(303L);
      currentUsername.set("approver_303");
      currentFullName.set("Giám đốc 303");
      BudgetReportData approvedRpt = rptBiz.approveReport(4L, Map.of());
      assertThat(approvedRpt.getWorkflowStatus()).isEqualTo("APPROVED");
      assertThat(approvedRpt.getReviewedByPersonId()).isEqualTo(202L);
      assertThat(approvedRpt.getApprovedByPersonId()).isEqualTo(303L);
    }
  }

  @Test
  @DisplayName("TD-M03 & TD-C10: Chặn IDOR xuyên nodeId bằng ABAC OrgSubtree, fail-closed Specifications & resolveCurrentAppCode()")
  void shouldEnforceAbacOrgSubtreeAndFailClosedQueryHelpers() {
    OrgSubtreeAuthorizationHelper abac = mock(OrgSubtreeAuthorizationHelper.class);
    doThrow(new PermissionDeniedException("Ngoài phạm vi cây tổ chức"))
        .when(abac)
        .assertCurrentCallerWithinSubtree(eq(999L), isNull(), any(), isNull());

    BudgetSecurityGuardHelper guard = new BudgetSecurityGuardHelper();
    guard.setOrgSubtreeAuthorizationHelper(abac);

    // 1. Single-record IDOR trên BudgetAllocation & BudgetUnitConfig bị chặn ngay 403
    BudgetAllocationService allocService = mock(BudgetAllocationService.class);
    when(allocService.findById(50L))
        .thenReturn(
            Optional.of(
                BudgetAllocation.builder().id(50L).nodeId(999L).nodeCode("OTHER_UNIT").build()));
    BudgetAllocationBusiness allocBiz =
        new BudgetAllocationBusiness(
            allocService,
            mock(BudgetAllocationMapper.class),
            mock(BudgetAllocationValidator.class),
            genericUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper,
            workflowEngineHelper,
            budgetAllocationRepo);
    allocBiz.setSecurityGuard(guard);

    assertThatThrownBy(() -> allocBiz.findById(50L))
        .isInstanceOf(PermissionDeniedException.class)
        .hasMessageContaining("Ngoài phạm vi");

    BudgetUnitConfigService cfgService = mock(BudgetUnitConfigService.class);
    when(cfgService.findById(60L))
        .thenReturn(
            Optional.of(
                BudgetUnitConfig.builder().id(60L).nodeId(999L).nodeCode("OTHER_UNIT").build()));
    BudgetUnitConfigBusiness cfgBiz =
        new BudgetUnitConfigBusiness(
            cfgService,
            mock(BudgetUnitConfigMapper.class),
            mock(BudgetUnitConfigValidator.class),
            genericUpsertHelper,
            auditRollbackHelper,
            businessLogicHelper,
            workflowEngineHelper);
    cfgBiz.setSecurityGuardHelper(guard);

    assertThatThrownBy(() -> cfgBiz.findById(60L))
        .isInstanceOf(PermissionDeniedException.class);

    // 2. Specifications fail-closed khi criteria == null và DENY_ALL OrgSubtreeScope
    @SuppressWarnings("unchecked")
    Root<BudgetAllocation> root = mock(Root.class);
    CriteriaQuery<?> query = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);
    Predicate denyPredicate = mock(Predicate.class);
    when(cb.disjunction()).thenReturn(denyPredicate);

    Predicate nullResult = BudgetAllocationSpecifications.quickSearch(null).toPredicate(root, query, cb);
    assertThat(nullResult).isSameAs(denyPredicate);

    OrgSubtreeAuthorizationHelper.OrgSubtreeScope denyScope =
        new OrgSubtreeAuthorizationHelper.OrgSubtreeScope(
            true, null, null, null, Set.of(), Set.of(), Set.of());
    Predicate scopeResult =
        BudgetQueryHelper.buildOrgSubtreePredicate(cb, root, denyScope, "nodeId", "nodeCode", false);
    assertThat(scopeResult).isSameAs(denyPredicate);

    // 3. BudgetQueryHelper.resolveCurrentAppCode() fail-closed khi thiếu X-App-Code & Sa-Token
    HeaderContext.clear();
    try (MockedStatic<LoginHelper> loginMock = mockStatic(LoginHelper.class)) {
      loginMock.when(LoginHelper::getAppCode).thenReturn(null);
      assertThatThrownBy(BudgetQueryHelper::resolveCurrentAppCode)
          .isInstanceOf(ServiceException.class)
          .hasMessageContaining("X-App-Code");
    }
  }
}
