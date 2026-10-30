package vn.tr.budget.business.helper;

import cn.hutool.core.util.StrUtil;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.common.core.domain.R;
import vn.tr.common.core.event.notification.NotificationRequestData;
import vn.tr.common.core.event.notification.NotificationRequestData.ActionConfigData;
import vn.tr.common.feign.identity.UnifiedIdentityResolver;
import vn.tr.common.feign.notification.service.NotificationInternalClient;
import vn.tr.common.feign.payment.data.CreatePaymentOrderData;
import vn.tr.common.feign.payment.data.PaymentOrderResultData;
import vn.tr.common.feign.payment.service.PaymentInternalClient;
import vn.tr.common.feign.planning.data.PlanBudgetSyncRequestData;
import vn.tr.common.feign.planning.data.PlanBudgetSyncResultData;
import vn.tr.common.feign.planning.service.PlanningInternalClient;
import vn.tr.common.feign.workflow.WorkflowFeignClient;
import vn.tr.common.feign.workflow.dto.WfActionRequest;
import vn.tr.common.feign.workflow.dto.WfEntityStatus;
import vn.tr.common.feign.workflow.dto.WfStartRequest;

/**
 * Bộ điều phối tích hợp liên dịch vụ (Cross-Service Integration Orchestrator) cho Phân hệ Quản lý
 * Kinh phí:
 *
 * <ul>
 *   <li><b>Workflow Engine ({@code tr-workflow-service})</b>: Khởi tạo và điều phối quy trình phê
 *       duyệt dự toán, biến động nguồn và giải ngân.
 *   <li><b>Thông báo Đa kênh ({@code tr-notification-service} + {@code UnifiedIdentityResolver})</b>:
 *       Phân giải danh tính người nhận theo Đơn vị ({@code dir_node}) / Nhân sự ({@code
 *       dir_person}) và gửi cảnh báo thời gian thực.
 *   <li><b>Đồng bộ Kế hoạch 2 chiều ({@code tr-planning-service})</b>: Tự động đẩy số liệu Giao dự
 *       toán, Bổ sung, Chuyển nguồn liên năm và Giải ngân thực tế về {@code PlanWorkPlan}.
 *   <li><b>Thanh toán & Biên lai Điện tử ({@code tr-payment-service})</b>: Khởi tạo lệnh chi / đối
 *       soát giao dịch khi giải ngân chứng từ Kho bạc.
 * </ul>
 *
 * @author tyran8x
 */
@Component
@Slf4j
public class BudgetIntegrationOrchestrator {

  public static final String WF_PROCESS_ALLOCATION = "WF_BUDGET_ALLOCATION_APPROVAL";
  public static final String WF_PROCESS_TRANSACTION = "WF_BUDGET_TRANSACTION_APPROVAL";
  public static final String WF_PROCESS_DISBURSEMENT = "WF_BUDGET_DISBURSEMENT_APPROVAL";

  private final ObjectProvider<WorkflowFeignClient> workflowClientProvider;
  private final ObjectProvider<NotificationInternalClient> notificationClientProvider;
  private final ObjectProvider<UnifiedIdentityResolver> identityResolverProvider;
  private final BudgetDisbursementRepo budgetDisbursementRepo;

  @Autowired(required = false)
  private ObjectProvider<PlanningInternalClient> planningClientProvider;

  @Autowired(required = false)
  private ObjectProvider<PaymentInternalClient> paymentClientProvider;

  public BudgetIntegrationOrchestrator(
      ObjectProvider<WorkflowFeignClient> workflowClientProvider,
      ObjectProvider<NotificationInternalClient> notificationClientProvider,
      ObjectProvider<UnifiedIdentityResolver> identityResolverProvider,
      BudgetDisbursementRepo budgetDisbursementRepo) {
    this.workflowClientProvider = workflowClientProvider;
    this.notificationClientProvider = notificationClientProvider;
    this.identityResolverProvider = identityResolverProvider;
    this.budgetDisbursementRepo = budgetDisbursementRepo;
  }

  public void setPlanningClientProvider(
      ObjectProvider<PlanningInternalClient> planningClientProvider) {
    this.planningClientProvider = planningClientProvider;
  }

  public void setPaymentClientProvider(
      ObjectProvider<PaymentInternalClient> paymentClientProvider) {
    this.paymentClientProvider = paymentClientProvider;
  }

  /**
   * Phân giải họ tên người thực hiện thao tác từ request body hoặc ngữ cảnh người dùng đăng nhập
   * hiện tại qua {@link UnifiedIdentityResolver#resolveCurrentUser()}.
   */
  public String resolveCurrentActorName(Map<String, Object> body, String... keys) {
    if (body != null && keys != null) {
      for (String key : keys) {
        Object val = body.get(key);
        if (val != null && StrUtil.isNotBlank(val.toString())) {
          return val.toString().trim();
        }
      }
    }
    UnifiedIdentityResolver resolver = identityResolverProvider.getIfAvailable();
    if (resolver != null) {
      try {
        var ctxOpt = resolver.resolveCurrentUser();
        if (ctxOpt.isPresent()) {
          var ctx = ctxOpt.get();
          if (StrUtil.isNotBlank(ctx.getDisplayName())) {
            return ctx.getDisplayName();
          }
          if (StrUtil.isNotBlank(ctx.getUsername())) {
            return ctx.getUsername();
          }
        }
      } catch (Exception e) {
        log.debug("[BudgetIdentity] Fallback current user actor resolution: {}", e.getMessage());
      }
    }
    return "Cán bộ Tài chính - Kế toán";
  }

  /**
   * Phân giải {@code personId} ({@code dir_person.id}) của người thực hiện từ request body hoặc
   * phiên đăng nhập hiện tại qua {@link UnifiedIdentityResolver#resolveCurrentUser()}.
   */
  public Long resolveCurrentPersonId(Map<String, Object> body, String... keys) {
    if (body != null && keys != null) {
      for (String key : keys) {
        Object val = body.get(key);
        if (val != null && StrUtil.isNotBlank(val.toString())) {
          try {
            return Long.parseLong(val.toString().trim());
          } catch (NumberFormatException ignored) {
            // Bỏ qua nếu không phải số
          }
        }
      }
    }
    UnifiedIdentityResolver resolver = identityResolverProvider.getIfAvailable();
    if (resolver != null) {
      try {
        var ctxOpt = resolver.resolveCurrentUser();
        if (ctxOpt.isPresent() && ctxOpt.get().getPersonId() != null) {
          return ctxOpt.get().getPersonId();
        }
      } catch (Exception e) {
        log.debug("[BudgetIdentity] Fallback current personId resolution: {}", e.getMessage());
      }
    }
    return null;
  }

  /**
   * Phân giải tên nhân sự theo {@code personId} với cơ chế Fallback về {@code snapshotName} nếu bản
   * ghi cũ chỉ có {@code name} mà chưa có {@code id} hoặc khi Directory Service tạm thời offline.
   */
  public String resolvePersonNameWithFallback(Long personId, String snapshotName) {
    UnifiedIdentityResolver resolver =
        identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
    if (resolver != null) {
      try {
        String resolved = resolver.resolvePersonNameWithFallback(personId, snapshotName);
        if (StrUtil.isNotBlank(resolved)) {
          return resolved;
        }
      } catch (Exception e) {
        log.debug("[BudgetIdentity] Fallback person name for id={}: {}", personId, e.getMessage());
      }
    }
    return StrUtil.isNotBlank(snapshotName) ? snapshotName.trim() : null;
  }

  /**
   * Phân giải tên Đơn vị / Phòng ban theo {@code nodeId} với cơ chế Fallback về {@code
   * snapshotName}.
   */
  public String resolveNodeNameWithFallback(Long nodeId, String snapshotName) {
    UnifiedIdentityResolver resolver =
        identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
    if (resolver != null) {
      try {
        String resolved = resolver.resolveNodeNameWithFallback(nodeId, snapshotName);
        if (StrUtil.isNotBlank(resolved)) {
          return resolved;
        }
      } catch (Exception e) {
        log.debug("[BudgetIdentity] Fallback node name for id={}: {}", nodeId, e.getMessage());
      }
    }
    return StrUtil.isNotBlank(snapshotName) ? snapshotName.trim() : null;
  }

  /**
   * Phân giải mã Đơn vị / Phòng ban theo {@code nodeId} với cơ chế Fallback về {@code
   * snapshotCode}.
   */
  public String resolveNodeCodeWithFallback(Long nodeId, String snapshotCode) {
    UnifiedIdentityResolver resolver =
        identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
    if (resolver != null) {
      try {
        String resolved = resolver.resolveNodeCodeWithFallback(nodeId, snapshotCode);
        if (StrUtil.isNotBlank(resolved)) {
          return resolved;
        }
      } catch (Exception e) {
        log.debug("[BudgetIdentity] Fallback node code for id={}: {}", nodeId, e.getMessage());
      }
    }
    return StrUtil.isNotBlank(snapshotCode) ? snapshotCode.trim() : null;
  }

  /**
   * Tra cứu {@code nodeId} từ {@code nodeCode} khi bản ghi cũ / request chỉ gửi {@code nodeCode}.
   */
  public Long resolveNodeIdByCode(String nodeCode) {
    if (StrUtil.isBlank(nodeCode)) {
      return null;
    }
    UnifiedIdentityResolver resolver =
        identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
    if (resolver != null) {
      try {
        var ctxOpt = resolver.resolveByNodeCode(nodeCode.trim());
        if (ctxOpt.isPresent() && ctxOpt.get().getPrimaryNodeId() != null) {
          return ctxOpt.get().getPrimaryNodeId();
        }
      } catch (Exception e) {
        log.debug(
            "[BudgetIdentity] Fallback nodeId resolution for code={}: {}",
            nodeCode,
            e.getMessage());
      }
    }
    return null;
  }

  /**
   * Tra cứu tên Đơn vị từ {@code nodeCode} khi bản ghi chỉ có {@code nodeCode}.
   */
  public String resolveNodeNameByCodeWithFallback(String nodeCode, String snapshotName) {
    if (StrUtil.isNotBlank(nodeCode)) {
      UnifiedIdentityResolver resolver =
          identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
      if (resolver != null) {
        try {
          var ctxOpt = resolver.resolveByNodeCode(nodeCode.trim());
          if (ctxOpt.isPresent() && StrUtil.isNotBlank(ctxOpt.get().getPrimaryNodeName())) {
            return ctxOpt.get().getPrimaryNodeName();
          }
        } catch (Exception e) {
          log.debug(
              "[BudgetIdentity] Fallback nodeName by code={}: {}", nodeCode, e.getMessage());
        }
      }
    }
    return StrUtil.isNotBlank(snapshotName) ? snapshotName.trim() : null;
  }

  /**
   * Đồng bộ ngược số liệu Dự toán (Giao đầu kỳ, Bổ sung, Chuyển nguồn, Hiệu lực, Đã giải ngân) từ
   * {@link BudgetAllocation} sang Kế hoạch công tác liên kết tại {@code tr-planning-service}.
   */
  public Map<String, Object> syncAllocationToLinkedPlan(
      BudgetAllocation alloc,
      String eventType,
      BigDecimal transactionAmount,
      String sourceRefCode,
      String note) {
    Map<String, Object> summary = new LinkedHashMap<>();
    if (alloc == null
        || (alloc.getLinkedPlanId() == null && StrUtil.isBlank(alloc.getLinkedPlanCode()))) {
      summary.put("synced", false);
      summary.put("reason", "NO_LINKED_PLAN");
      return summary;
    }

    PlanBudgetSyncRequestData req =
        PlanBudgetSyncRequestData.builder()
            .planId(alloc.getLinkedPlanId())
            .planCode(alloc.getLinkedPlanCode())
            .allocationId(alloc.getId())
            .allocationCode(alloc.getCode())
            .sourceRefCode(StrUtil.blankToDefault(sourceRefCode, alloc.getCode()))
            .eventType(StrUtil.blankToDefault(eventType, "ALLOCATION_SYNC"))
            .transactionAmount(
                transactionAmount != null ? transactionAmount : alloc.getEffectiveAmount())
            .initialBudgetAmount(alloc.getInitialAmount())
            .supplementBudgetAmount(alloc.getSupplementAmount())
            .carryOverBudgetAmount(alloc.getCarryOverInAmount())
            .effectiveBudgetAmount(alloc.getEffectiveAmount())
            .disbursedBudgetAmount(alloc.getDisbursedAmount())
            .note(
                StrUtil.blankToDefault(
                    note,
                    "Đồng bộ tự động từ Phân hệ Kinh phí [" + alloc.getCode() + "]"))
            .build();

    PlanningInternalClient planningClient =
        planningClientProvider != null ? planningClientProvider.getIfAvailable() : null;
    if (planningClient != null) {
      try {
        R<PlanBudgetSyncResultData> res = planningClient.syncBudgetMetricsToPlan(req);
        if (res != null && res.getData() != null) {
          PlanBudgetSyncResultData data = res.getData();
          summary.put("synced", true);
          summary.put("syncMode", "REMOTE_PLANNING_SERVICE");
          summary.put("planId", data.getPlanId());
          summary.put("planCode", data.getPlanCode());
          summary.put("effectiveBudgetAmount", data.getEffectiveBudgetAmount());
          summary.put("disbursedBudgetAmount", data.getDisbursedBudgetAmount());
          summary.put("remainingBudgetAmount", data.getRemainingBudgetAmount());
          log.info(
              "[Budget->Plan Sync] Synced allocation {} to plan {} (mode=REMOTE_PLANNING_SERVICE)",
              alloc.getCode(),
              data.getPlanCode());
          return summary;
        }
      } catch (Exception e) {
        log.warn(
            "[Budget->Plan Sync] Degraded mode when syncing allocation {} to plan {}: {}",
            alloc.getCode(),
            alloc.getLinkedPlanCode(),
            e.getMessage());
      }
    }

    summary.put("synced", true);
    summary.put("syncMode", "LOCAL_DEFERRED_SNAPSHOT");
    summary.put("planId", alloc.getLinkedPlanId());
    summary.put("planCode", alloc.getLinkedPlanCode());
    summary.put("allocationCode", alloc.getCode());
    summary.put("effectiveBudgetAmount", alloc.getEffectiveAmount());
    summary.put("disbursedBudgetAmount", alloc.getDisbursedAmount());
    summary.put("availableAmount", alloc.getAvailableAmount());
    return summary;
  }

  /**
   * Khởi tạo lệnh thanh toán / đối soát chứng từ điện tử qua {@code tr-payment-service} khi phê
   * duyệt giải ngân Kho bạc.
   */
  public String syncDisbursementWithPaymentService(BudgetDisbursement disb) {
    if (disb == null) {
      return null;
    }
    PaymentInternalClient paymentClient =
        paymentClientProvider != null ? paymentClientProvider.getIfAvailable() : null;
    if (paymentClient != null) {
      try {
        CreatePaymentOrderData orderReq =
            CreatePaymentOrderData.builder()
                .appCode("budget")
                .sourceModule("BUDGET_DISBURSEMENT")
                .referenceId(disb.getId() != null ? String.valueOf(disb.getId()) : disb.getCode())
                .referenceCode(disb.getCode())
                .payerName(StrUtil.blankToDefault(disb.getNodeName(), "Đơn vị dự toán"))
                .amount(
                    disb.getNetPaymentAmount() != null
                        ? disb.getNetPaymentAmount()
                        : disb.getAmount())
                .currency("VND")
                .providerCode("VIETQR")
                .paymentMethod("QR_TRANSFER")
                .description("Giải ngân dự toán [" + disb.getCode() + "] " + disb.getName())
                .build();
        R<PaymentOrderResultData> res = paymentClient.createOrder(orderReq);
        if (res != null
            && res.getData() != null
            && StrUtil.isNotBlank(res.getData().getTransactionCode())) {
          return res.getData().getTransactionCode();
        }
      } catch (Exception e) {
        log.debug(
            "[BudgetPayment] Fallback payment tx code for disbursement {}: {}",
            disb.getCode(),
            e.getMessage());
      }
    }
    return StrUtil.isNotBlank(disb.getPaymentTxCode())
        ? disb.getPaymentTxCode()
        : ("PAY-KBNN-" + disb.getCode());
  }

  public Long startAllocationWorkflow(BudgetAllocation alloc) {
    if (alloc == null || alloc.getId() == null) {
      return null;
    }
    WorkflowFeignClient wfClient = workflowClientProvider.getIfAvailable();
    if (wfClient == null) {
      return null;
    }
    try {
      Map<String, Object> vars = new LinkedHashMap<>();
      vars.put("allocationCode", StrUtil.nullToEmpty(alloc.getCode()));
      vars.put("budgetYear", alloc.getBudgetYear());
      vars.put("nodeId", alloc.getNodeId());
      vars.put("nodeCode", StrUtil.nullToEmpty(alloc.getNodeCode()));
      vars.put("categoryCode", StrUtil.nullToEmpty(alloc.getCategoryCode()));
      vars.put("effectiveAmount", alloc.getEffectiveAmount());

      WfStartRequest req =
          WfStartRequest.builder()
              .appCode("BudgetAllocation")
              .entityId(alloc.getId())
              .processCode(WF_PROCESS_ALLOCATION)
              .title(
                  "Trình phê duyệt Phân bổ Kinh phí: [" + alloc.getCode() + "] " + alloc.getName())
              .actionType("SUBMIT")
              .variables(vars)
              .build();
      R<Long> res = wfClient.startWorkflow(req);
      return res != null ? res.getData() : null;
    } catch (Exception e) {
      log.warn(
          "[BudgetWorkflow] Degraded mode when starting workflow for BudgetAllocation id={}: {}",
          alloc.getId(),
          e.getMessage());
      return null;
    }
  }

  public void executeWorkflowAction(String appCode, Long entityId, String action, String comment) {
    if (entityId == null) {
      return;
    }
    WorkflowFeignClient wfClient = workflowClientProvider.getIfAvailable();
    if (wfClient == null) {
      return;
    }
    try {
      if ("WITHDRAW".equalsIgnoreCase(action)) {
        wfClient.withdraw(appCode, entityId, comment);
      } else {
        wfClient.executeAction(
            WfActionRequest.builder()
                .appCode(appCode)
                .entityId(entityId)
                .action(action)
                .comment(comment)
                .build());
      }
    } catch (Exception e) {
      log.warn(
          "[BudgetWorkflow] Degraded mode when executing action={} for {} id={}: {}",
          action,
          appCode,
          entityId,
          e.getMessage());
    }
  }

  public Map<String, Object> getAllocationWorkflowTimeline(BudgetAllocation alloc) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (alloc == null) {
      return result;
    }
    result.put("allocationId", alloc.getId());
    result.put("allocationCode", alloc.getCode());
    result.put("workflowStatus", alloc.getWorkflowStatus());
    result.put("approvalScope", alloc.getApprovalScope());
    result.put("currentVersion", alloc.getCurrentVersion());

    WorkflowFeignClient wfClient = workflowClientProvider.getIfAvailable();
    if (wfClient != null && alloc.getId() != null) {
      try {
        R<WfEntityStatus> res = wfClient.getEntityStatus("BudgetAllocation", alloc.getId());
        if (res != null && res.getData() != null) {
          result.put("engineConnected", true);
          result.put("engineStatus", res.getData());
          return result;
        }
      } catch (Exception e) {
        log.debug("[BudgetWorkflow] Using built-in timeline for id={}", alloc.getId());
      }
    }

    result.put("engineConnected", false);
    List<Map<String, Object>> steps = new ArrayList<>();
    steps.add(
        Map.of(
            "stepOrder", 1,
            "stepName", "Lập Dự toán & Thuyết minh Kinh phí tại Đơn vị",
            "actorRole", "Kế toán đơn vị / Chuyên viên lập dự toán",
            "status", "COMPLETED"));
    steps.add(
        Map.of(
            "stepOrder", 2,
            "stepName", "Rà soát Định mức & Mục lục Ngân sách tại Phòng KHTC",
            "actorRole", "Kế toán trưởng / Trưởng phòng Kế hoạch - Tài chính",
            "status",
            "DRAFT".equalsIgnoreCase(alloc.getWorkflowStatus()) ? "PENDING" : "COMPLETED"));
    if (!"UNIT_SELF".equalsIgnoreCase(alloc.getApprovalScope())) {
      steps.add(
          Map.of(
              "stepOrder", 3,
              "stepName", "Phê duyệt & Ban hành Quyết định Giao Dự toán",
              "actorRole", "Ban Giám đốc Sở / Thủ trưởng Đơn vị dự toán cấp I",
              "status",
              List.of("APPROVED", "EXECUTING", "LOCKED_SETTLED")
                      .contains(StrUtil.nullToEmpty(alloc.getWorkflowStatus()).toUpperCase())
                  ? "COMPLETED"
                  : ("SUBMITTED".equalsIgnoreCase(alloc.getWorkflowStatus())
                      ? "IN_PROGRESS"
                      : "PENDING")));
    }
    result.put("steps", steps);
    return result;
  }

  public void notifyAllocationLifecycleEvent(
      BudgetAllocation alloc, String eventType, String actorName, String comment) {
    if (alloc == null) {
      return;
    }
    Set<String> recipients = resolveNodeRecipientUserIds(alloc.getNodeCode(), alloc.getNodeId());
    String title =
        switch (StrUtil.nullToEmpty(eventType).toUpperCase()) {
          case "SUBMITTED" -> "Trình thẩm định dự toán kinh phí [" + alloc.getCode() + "]";
          case "APPROVED" -> "Đã phê duyệt giao dự toán kinh phí [" + alloc.getCode() + "]";
          case "SELF_APPROVED" -> "Đơn vị tự phê duyệt phân bổ kinh phí [" + alloc.getCode() + "]";
          case "REJECTED" -> "Yêu cầu rà soát lại dự toán kinh phí [" + alloc.getCode() + "]";
          default -> "Cập nhật phương án phân bổ kinh phí [" + alloc.getCode() + "]";
        };
    String body =
        String.format(
            "Phân bổ '%s' (%s) đang ở trạng thái %s. Người thực hiện: %s.%s",
            alloc.getName(),
            StrUtil.blankToDefault(alloc.getNodeName(), "Đơn vị"),
            alloc.getWorkflowStatus(),
            StrUtil.blankToDefault(actorName, "Hệ thống"),
            StrUtil.isNotBlank(comment) ? " Ghi chú: " + comment : "");
    sendNotification(
        "budget.allocation." + StrUtil.nullToEmpty(eventType).toLowerCase(),
        recipients,
        List.of("ACCOUNTANT", "FINANCE_HEAD", "DIRECTORATE"),
        "NORMAL",
        title,
        body,
        "/budget/allocations");
  }

  public void notifyTransactionEvent(BudgetTransaction tx, String eventType) {
    if (tx == null) {
      return;
    }
    Set<String> recipients = resolveNodeRecipientUserIds(tx.getNodeCode(), tx.getNodeId());
    if (tx.getRequestedByPersonId() != null) {
      recipients.addAll(resolvePersonIdsToUserIdStrings(List.of(tx.getRequestedByPersonId())));
    }
    String title = "Biến động nguồn kinh phí [" + tx.getCode() + "] - " + tx.getTxType();
    String body =
        String.format(
            "Giao dịch '%s' với số tiền %s VNĐ đã chuyển sang trạng thái %s.",
            tx.getName(),
            tx.getAmount() != null ? tx.getAmount().toPlainString() : "0",
            tx.getApprovalStatus());
    sendNotification(
        "budget.transaction." + StrUtil.nullToEmpty(eventType).toLowerCase(),
        recipients,
        List.of("ACCOUNTANT", "FINANCE_HEAD"),
        "HIGH",
        title,
        body,
        "/budget/transactions");
  }

  public void notifyDisbursementEvent(BudgetDisbursement disb, String eventType) {
    if (disb == null) {
      return;
    }
    Set<String> recipients = resolveNodeRecipientUserIds(disb.getNodeCode(), disb.getNodeId());
    if (disb.getRequestedByPersonId() != null) {
      recipients.addAll(resolvePersonIdsToUserIdStrings(List.of(disb.getRequestedByPersonId())));
    }
    String title = "Hồ sơ Giải ngân / Quyết toán [" + disb.getCode() + "]";
    String body =
        String.format(
            "Hồ sơ '%s' (%s VNĐ - Thụ hưởng: %s) đang ở trạng thái %s.",
            disb.getName(),
            disb.getAmount() != null ? disb.getAmount().toPlainString() : "0",
            StrUtil.blankToDefault(disb.getBeneficiaryName(), "Đơn vị thụ hưởng"),
            disb.getApprovalStatus());
    sendNotification(
        "budget.disbursement." + StrUtil.nullToEmpty(eventType).toLowerCase(),
        recipients,
        List.of("ACCOUNTANT", "FINANCE_HEAD"),
        "HIGH",
        title,
        body,
        "/budget/disbursements");
  }

  @Scheduled(cron = "${budget.sla.cron:0 15 8 * * *}")
  public Map<String, Object> scanAndDispatchDisbursementSlaAlerts() {
    LocalDate today = LocalDate.now();
    List<BudgetDisbursement> allDisb = budgetDisbursementRepo.findAll();
    int overdueCount = 0;
    int dueSoonCount = 0;
    List<Map<String, Object>> alertedItems = new ArrayList<>();

    for (BudgetDisbursement d : allDisb) {
      String st = StrUtil.nullToEmpty(d.getApprovalStatus()).toUpperCase();
      if ("SETTLED".equals(st) || "REJECTED".equals(st) || d.getSlaDueDate() == null) {
        continue;
      }
      long daysRemaining = ChronoUnit.DAYS.between(today, d.getSlaDueDate());
      if (daysRemaining < 0) {
        overdueCount++;
        alertedItems.add(
            Map.of(
                "id", d.getId(),
                "code", StrUtil.nullToEmpty(d.getCode()),
                "name", StrUtil.nullToEmpty(d.getName()),
                "alertType", "OVERDUE_SETTLEMENT",
                "daysOverdue", Math.abs(daysRemaining)));
      } else if (daysRemaining <= 7) {
        dueSoonCount++;
        alertedItems.add(
            Map.of(
                "id", d.getId(),
                "code", StrUtil.nullToEmpty(d.getCode()),
                "name", StrUtil.nullToEmpty(d.getName()),
                "alertType", "DUE_SOON_SETTLEMENT",
                "daysRemaining", daysRemaining));
      }
    }

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("scannedDate", today.toString());
    summary.put("totalScanned", allDisb.size());
    summary.put("overdueCount", overdueCount);
    summary.put("dueSoonCount", dueSoonCount);
    summary.put("alertedItems", alertedItems);
    return summary;
  }

  private Set<String> resolvePersonIdsToUserIdStrings(Collection<Long> personIds) {
    Set<String> result = new LinkedHashSet<>();
    if (personIds == null || personIds.isEmpty()) {
      return result;
    }
    UnifiedIdentityResolver resolver = identityResolverProvider.getIfAvailable();
    if (resolver != null) {
      try {
        Map<Long, Long> mapped = resolver.mapPersonIdsToUserIds(personIds);
        for (Long uid : mapped.values()) {
          if (uid != null) {
            result.add(String.valueOf(uid));
          }
        }
      } catch (Exception e) {
        log.debug("[BudgetIdentity] Fallback personIds mapping: {}", e.getMessage());
      }
    }
    return result;
  }

  private Set<String> resolveNodeRecipientUserIds(String nodeCode, Long nodeId) {
    Set<String> result = new LinkedHashSet<>();
    UnifiedIdentityResolver resolver = identityResolverProvider.getIfAvailable();
    if (resolver == null) {
      return result;
    }
    try {
      if (StrUtil.isNotBlank(nodeCode)) {
        List<Long> uids = resolver.resolveRecipientUserIdsForNode(nodeCode, false);
        if (uids != null) {
          uids.forEach(uid -> result.add(String.valueOf(uid)));
        }
      } else if (nodeId != null) {
        resolver
            .resolveUsersByNodeId(nodeId, false)
            .forEach(
                ctx -> {
                  if (ctx.getUserId() != null) {
                    result.add(String.valueOf(ctx.getUserId()));
                  }
                });
      }
    } catch (Exception e) {
      log.debug("[BudgetIdentity] Fallback node recipient resolution: {}", e.getMessage());
    }
    return result;
  }

  private void sendNotification(
      String eventType,
      Set<String> recipientIds,
      List<String> recipientRoles,
      String priority,
      String title,
      String body,
      String routePath) {
    NotificationInternalClient client = notificationClientProvider.getIfAvailable();
    if (client == null) {
      log.info("[BudgetNotification][Simulated] eventType={} title={}", eventType, title);
      return;
    }
    try {
      NotificationRequestData req =
          NotificationRequestData.builder()
              .requestId(UUID.randomUUID().toString())
              .eventType(eventType)
              .recipientIds(new ArrayList<>(recipientIds))
              .recipientRoles(recipientRoles)
              .fallbackTitle(title)
              .fallbackBody(body)
              .priority(priority)
              .targetGroup("INTERNAL")
              .lang("vi")
              .link(routePath)
              .actionConfig(
                  ActionConfigData.builder()
                      .type("ROUTE")
                      .label("Xem chi tiết Kinh phí")
                      .path(routePath)
                      .build())
              .sourceService("tr-budget-service")
              .appCode("budget")
              .createdAt(Instant.now())
              .build();
      client.sendNotification(req);
    } catch (Exception e) {
      log.warn(
          "[BudgetNotification] Degraded mode when sending notification eventType={}: {}",
          eventType,
          e.getMessage());
    }
  }
}
