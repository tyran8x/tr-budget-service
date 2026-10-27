package vn.tr.budget.business.helper;

import cn.hutool.core.util.StrUtil;
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
import vn.tr.common.feign.workflow.WorkflowFeignClient;
import vn.tr.common.feign.workflow.dto.WfActionRequest;
import vn.tr.common.feign.workflow.dto.WfEntityStatus;
import vn.tr.common.feign.workflow.dto.WfStartRequest;

/**
 * Bộ điều phối tích hợp liên dịch vụ (Cross-Service Integration Orchestrator) cho Phân hệ Quản lý Kinh phí.
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
              .title("Trình phê duyệt Phân bổ Kinh phí: [" + alloc.getCode() + "] " + alloc.getName())
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
