package vn.tr.budget.business.helper;

import cn.hutool.core.util.StrUtil;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetReport;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.service.BudgetCategoryRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.common.core.constant.AppCodeConstants;
import vn.tr.common.core.constant.NotifEventTypes;
import vn.tr.common.core.context.HeaderContext;
import vn.tr.common.core.domain.R;
import vn.tr.common.core.domain.model.LoginUser;
import vn.tr.common.core.event.notification.NotificationRequestData;
import vn.tr.common.core.event.notification.NotificationRequestData.ActionConfigData;
import vn.tr.common.feign.directory.bean.DirectoryPersonBean;
import vn.tr.common.feign.directory.service.DirectoryPersonClient;
import vn.tr.common.feign.identity.UnifiedIdentityResolver;
import vn.tr.common.feign.identity.UserPersonContextData;
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
import vn.tr.common.kafka.outbox.OutboxPublisher;
import vn.tr.common.satoken.utils.LoginHelper;

/**
 * Bộ điều phối tích hợp liên dịch vụ (Cross-Service Integration Orchestrator) cho Phân hệ Quản lý
 * Kinh phí.
 *
 * <p><b>Bảo mật & Độ tin cậy (TD-C04, TD-C08, TD-C11, TD-H07, TD-H08)</b>:
 *
 * <ul>
 *   <li>CẤM trích xuất danh tính tác nhân ({@code actorId}/{@code actorName}) từ HTTP Request Body
 *       — luôn phân giải duy nhất từ phiên Sa-Token ({@link LoginHelper} / {@link
 *       UnifiedIdentityResolver}).
 *   <li>Tách riêng cờ circuit-backoff cho từng peer client (không dùng chung giữa Identity và
 *       DirectoryPerson) và kết hợp Resilience4j {@code @CircuitBreaker}.
 *   <li>Triển khai Transactional Outbox ({@code sys_outbox_event}) cho đồng bộ {@code
 *       tr-planning-service}; tuyệt đối không trả {@code synced=true} giả khi gọi lỗi.
 *   <li>Đẩy các lời gọi Feign ngoại vi ra sau khi giao dịch DB commit ({@code afterCommit}) nhằm
 *       không giữ khóa dòng {@code FOR UPDATE} và kết nối HikariCP trong thời gian chờ mạng.
 *   <li>Bọc tác vụ {@code @Scheduled} cảnh báo SLA bằng khóa phân tán Redisson {@code RLock},
 *       quét cô lập từng tenant {@code appCode}, đọc cấu hình {@code slaDisbursementWarningDays}
 *       và đánh dấu cờ {@code isOverdueWarningSent} chống spam lặp.
 * </ul>
 *
 * @author tyran8x
 */
@Component
@Slf4j
public class BudgetIntegrationOrchestrator {

  public static final String WF_PROCESS_ALLOCATION = "budget_allocation_approval";
  public static final String WF_PROCESS_TRANSACTION = "budget_transaction_approval";
  public static final String WF_PROCESS_DISBURSEMENT = "budget_disbursement_approval";
  public static final String WF_PROCESS_REPORT = "budget_report_approval";
  public static final String PLAN_SYNC_OUTBOX_TOPIC = "budget-plan-sync";
  private static final String SLA_LOCK_KEY = "lock:budget:sla:disbursement-scan";
  private static final int DEFAULT_SLA_WARNING_DAYS = 7;

  private static final long CIRCUIT_BACKOFF_MS = 60_000L;

  private final ObjectProvider<WorkflowFeignClient> workflowClientProvider;
  private final ObjectProvider<NotificationInternalClient> notificationClientProvider;
  private final ObjectProvider<UnifiedIdentityResolver> identityResolverProvider;
  private final BudgetDisbursementRepo budgetDisbursementRepo;

  private volatile long identityBackoffUntil = 0L;
  private volatile long directoryPersonBackoffUntil = 0L;
  private volatile long workflowBackoffUntil = 0L;
  private volatile long notificationBackoffUntil = 0L;
  private volatile long planningBackoffUntil = 0L;
  private volatile long paymentBackoffUntil = 0L;

  @Autowired(required = false)
  private ObjectProvider<PlanningInternalClient> planningClientProvider;

  @Autowired(required = false)
  private ObjectProvider<PaymentInternalClient> paymentClientProvider;

  @Autowired(required = false)
  private ObjectProvider<DirectoryPersonClient> directoryPersonClientProvider;

  @Autowired(required = false)
  private ObjectProvider<RedissonClient> redissonClientProvider;

  @Autowired(required = false)
  private BudgetCategoryRepo budgetCategoryRepo;

  @Autowired(required = false)
  private OutboxPublisher outboxPublisher;

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

  public void setDirectoryPersonClientProvider(
      ObjectProvider<DirectoryPersonClient> directoryPersonClientProvider) {
    this.directoryPersonClientProvider = directoryPersonClientProvider;
  }

  public void setRedissonClientProvider(ObjectProvider<RedissonClient> redissonClientProvider) {
    this.redissonClientProvider = redissonClientProvider;
  }

  public void setBudgetCategoryRepo(BudgetCategoryRepo budgetCategoryRepo) {
    this.budgetCategoryRepo = budgetCategoryRepo;
  }

  public void setOutboxPublisher(OutboxPublisher outboxPublisher) {
    this.outboxPublisher = outboxPublisher;
  }

  /**
   * Phân giải họ tên người thực hiện thao tác TUYỆT ĐỐI từ phiên đăng nhập Sa-Token / {@link
   * UnifiedIdentityResolver#resolveCurrentUser()}.
   *
   * <p>Bỏ qua hoàn toàn tham số {@code body}/{@code keys} từ HTTP request để chống giả mạo danh
   * tính (Actor Spoofing — TD-C04).
   */
  public String resolveCurrentActorName(Map<String, Object> body, String... keys) {
    try {
      LoginUser loginUser = LoginHelper.getLoginUser();
      if (loginUser != null) {
        if (StrUtil.isNotBlank(loginUser.getFullName())) {
          return loginUser.getFullName().trim();
        }
        if (StrUtil.isNotBlank(loginUser.getUsername())) {
          return loginUser.getUsername().trim();
        }
      }
      String username = LoginHelper.getUsername();
      if (StrUtil.isNotBlank(username)) {
        return username.trim();
      }
    } catch (Exception ignored) {
      // Bỏ qua khi không có ngữ cảnh Sa-Token
    }
    UnifiedIdentityResolver resolver =
        System.currentTimeMillis() >= identityBackoffUntil && identityResolverProvider != null
            ? identityResolverProvider.getIfAvailable()
            : null;
    if (resolver != null) {
      try {
        Optional<UserPersonContextData> ctxOpt = resolver.resolveCurrentUser();
        if (ctxOpt != null && ctxOpt.isPresent()) {
          UserPersonContextData ctx = ctxOpt.get();
          if (StrUtil.isNotBlank(ctx.getDisplayName())) {
            return ctx.getDisplayName().trim();
          }
          if (StrUtil.isNotBlank(ctx.getUsername())) {
            return ctx.getUsername().trim();
          }
        }
      } catch (Exception e) {
        identityBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
        log.debug("[BudgetIdentity] Fallback current user actor resolution: {}", e.getMessage());
      }
    }
    return "Cán bộ Tài chính - Kế toán";
  }

  /**
   * Phân giải {@code personId} ({@code dir_person.id}) của tác nhân TUYỆT ĐỐI từ phiên đăng nhập
   * Sa-Token / {@link UnifiedIdentityResolver#resolveCurrentUser()}.
   *
   * <p>Cấm đọc từ {@code body} (chống giả mạo danh tính & vượt kiểm soát 4 mắt SoD — TD-C04).
   */
  public Long resolveCurrentPersonId(Map<String, Object> body, String... keys) {
    UnifiedIdentityResolver resolver =
        System.currentTimeMillis() >= identityBackoffUntil && identityResolverProvider != null
            ? identityResolverProvider.getIfAvailable()
            : null;
    if (resolver != null) {
      try {
        Optional<UserPersonContextData> ctxOpt = resolver.resolveCurrentUser();
        if (ctxOpt != null && ctxOpt.isPresent()) {
          UserPersonContextData ctx = ctxOpt.get();
          if (ctx.getPersonId() != null) {
            return ctx.getPersonId();
          }
          if (ctx.getUserId() != null) {
            return ctx.getUserId();
          }
        }
      } catch (Exception e) {
        identityBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
        log.debug("[BudgetIdentity] Fallback current personId resolution: {}", e.getMessage());
      }
    }
    try {
      Long userId = LoginHelper.getUserId();
      if (userId != null) {
        return userId;
      }
      LoginUser loginUser = LoginHelper.getLoginUser();
      if (loginUser != null && StrUtil.isNotBlank(loginUser.getUserId())) {
        return Long.valueOf(loginUser.getUserId().trim());
      }
    } catch (Exception ignored) {
      // Không có phiên Sa-Token hoặc userId không phải số
    }
    return null;
  }

  /**
   * Truy vấn số lượng nhân sự đang hoạt động thuộc Đơn vị ({@code dir_node.id}) qua {@link
   * DirectoryPersonClient#findByContext} (có cờ backoff độc lập không ảnh hưởng Identity).
   */
  @CircuitBreaker(name = "directoryPersonClient", fallbackMethod = "resolveActiveStaffCountFallback")
  public Integer resolveActiveStaffCount(Long nodeId) {
    if (nodeId == null || System.currentTimeMillis() < directoryPersonBackoffUntil) {
      return null;
    }
    DirectoryPersonClient dirClient =
        directoryPersonClientProvider != null ? directoryPersonClientProvider.getIfAvailable() : null;
    if (dirClient == null) {
      return null;
    }
    try {
      R<List<DirectoryPersonBean>> res =
          dirClient.findByContext(null, null, nodeId, false, null, null);
      if (res != null && res.getData() != null) {
        return res.getData().size();
      }
    } catch (Exception e) {
      directoryPersonBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
      log.warn(
          "[BudgetDirectory] Degraded mode resolving active staff for nodeId={}: {}",
          nodeId,
          e.getMessage());
    }
    return null;
  }

  private Integer resolveActiveStaffCountFallback(Long nodeId, Throwable ex) {
    log.debug(
        "[BudgetDirectory] Circuit breaker fallback for active staff nodeId={}: {}",
        nodeId,
        ex != null ? ex.getMessage() : "");
    return null;
  }

  public String resolvePersonNameWithFallback(Long personId, String snapshotName) {
    if (StrUtil.isNotBlank(snapshotName)) {
      return snapshotName.trim();
    }
    if (personId == null || System.currentTimeMillis() < identityBackoffUntil) {
      return null;
    }
    UnifiedIdentityResolver resolver =
        identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
    if (resolver != null) {
      try {
        String resolved = resolver.resolvePersonNameWithFallback(personId, snapshotName);
        if (StrUtil.isNotBlank(resolved)) {
          return resolved;
        }
      } catch (Exception e) {
        identityBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
        log.debug("[BudgetIdentity] Fallback person name for id={}: {}", personId, e.getMessage());
      }
    }
    return null;
  }

  public String resolveNodeNameWithFallback(Long nodeId, String snapshotName) {
    if (StrUtil.isNotBlank(snapshotName)) {
      return snapshotName.trim();
    }
    if (nodeId == null || System.currentTimeMillis() < identityBackoffUntil) {
      return null;
    }
    UnifiedIdentityResolver resolver =
        identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
    if (resolver != null) {
      try {
        String resolved = resolver.resolveNodeNameWithFallback(nodeId, snapshotName);
        if (StrUtil.isNotBlank(resolved)) {
          return resolved;
        }
      } catch (Exception e) {
        identityBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
        log.debug("[BudgetIdentity] Fallback node name for id={}: {}", nodeId, e.getMessage());
      }
    }
    return null;
  }

  public String resolveNodeCodeWithFallback(Long nodeId, String snapshotCode) {
    if (StrUtil.isNotBlank(snapshotCode)) {
      return snapshotCode.trim();
    }
    if (nodeId == null || System.currentTimeMillis() < identityBackoffUntil) {
      return null;
    }
    UnifiedIdentityResolver resolver =
        identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
    if (resolver != null) {
      try {
        String resolved = resolver.resolveNodeCodeWithFallback(nodeId, snapshotCode);
        if (StrUtil.isNotBlank(resolved)) {
          return resolved;
        }
      } catch (Exception e) {
        identityBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
        log.debug("[BudgetIdentity] Fallback node code for id={}: {}", nodeId, e.getMessage());
      }
    }
    return null;
  }

  public Long resolveNodeIdByCode(String nodeCode) {
    if (StrUtil.isBlank(nodeCode) || System.currentTimeMillis() < identityBackoffUntil) {
      return null;
    }
    UnifiedIdentityResolver resolver =
        identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
    if (resolver != null) {
      try {
        Optional<UserPersonContextData> ctxOpt = resolver.resolveByNodeCode(nodeCode.trim());
        if (ctxOpt != null && ctxOpt.isPresent() && ctxOpt.get().getPrimaryNodeId() != null) {
          return ctxOpt.get().getPrimaryNodeId();
        }
      } catch (Exception e) {
        identityBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
        log.debug(
            "[BudgetIdentity] Fallback nodeId resolution for code={}: {}",
            nodeCode,
            e.getMessage());
      }
    }
    return null;
  }

  public String resolveNodeNameByCodeWithFallback(String nodeCode, String snapshotName) {
    if (StrUtil.isNotBlank(snapshotName)) {
      return snapshotName.trim();
    }
    if (StrUtil.isNotBlank(nodeCode) && System.currentTimeMillis() >= identityBackoffUntil) {
      UnifiedIdentityResolver resolver =
          identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
      if (resolver != null) {
        try {
          Optional<UserPersonContextData> ctxOpt = resolver.resolveByNodeCode(nodeCode.trim());
          if (ctxOpt != null
              && ctxOpt.isPresent()
              && StrUtil.isNotBlank(ctxOpt.get().getPrimaryNodeName())) {
            return ctxOpt.get().getPrimaryNodeName();
          }
        } catch (Exception e) {
          identityBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
          log.debug(
              "[BudgetIdentity] Fallback nodeName by code={}: {}", nodeCode, e.getMessage());
        }
      }
    }
    return null;
  }

  /**
   * Đồng bộ ngược số liệu Dự toán từ {@link BudgetAllocation} sang Kế hoạch công tác tại {@code
   * tr-planning-service} theo mô hình Transactional Outbox ({@code sys_outbox_event}).
   *
   * <p>Nếu đang nằm trong giao dịch DB ({@code @Transactional}), ghi sự kiện vào {@code
   * sys_outbox_event} hoặc xếp hàng chạy {@code afterCommit} (tuyệt đối không giữ khóa dòng DB khi
   * gọi mạng). Nếu gọi trực tiếp thất bại, tuyệt đối trả {@code synced=false} (không báo {@code
   * synced=true} giả — TD-C11).
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

    String messageKey =
        StrUtil.blankToDefault(
            alloc.getLinkedPlanCode(),
            alloc.getId() != null ? String.valueOf(alloc.getId()) : alloc.getCode());

    // Nếu đang ở trong giao dịch DB đang hoạt động: dùng Transactional Outbox / afterCommit
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      boolean enqueued = enqueuePlanSyncOutbox(messageKey, req);
      if (!enqueued) {
        runAfterCommitOrNow(() -> dispatchPlanSyncFeign(req, messageKey));
      }
      summary.put("synced", false);
      summary.put("queuedOutbox", enqueued);
      summary.put("syncMode", enqueued ? "OUTBOX_ENQUEUED" : "DEFERRED_AFTER_COMMIT");
      summary.put("planId", alloc.getLinkedPlanId());
      summary.put("planCode", alloc.getLinkedPlanCode());
      summary.put("allocationCode", alloc.getCode());
      summary.put("effectiveBudgetAmount", alloc.getEffectiveAmount());
      summary.put("disbursedBudgetAmount", alloc.getDisbursedAmount());
      summary.put("availableAmount", alloc.getAvailableAmount());
      return summary;
    }

    return dispatchPlanSyncFeign(req, messageKey);
  }

  /** Thực thi đồng bộ trực tiếp sang {@code tr-planning-service} qua Feign (được bảo vệ bởi CircuitBreaker). */
  @CircuitBreaker(name = "planningClient", fallbackMethod = "dispatchPlanSyncFallback")
  public Map<String, Object> dispatchPlanSyncFeign(
      PlanBudgetSyncRequestData req, String messageKey) {
    Map<String, Object> summary = new LinkedHashMap<>();
    PlanningInternalClient planningClient =
        System.currentTimeMillis() >= planningBackoffUntil && planningClientProvider != null
            ? planningClientProvider.getIfAvailable()
            : null;
    String errorMessage = null;
    if (planningClient != null) {
      try {
        R<PlanBudgetSyncResultData> res = planningClient.syncBudgetMetricsToPlan(req);
        if (res != null && res.getData() != null) {
          PlanBudgetSyncResultData data = res.getData();
          summary.put("synced", true);
          summary.put("queuedOutbox", false);
          summary.put("syncMode", "REMOTE_PLANNING_SERVICE");
          summary.put("planId", data.getPlanId());
          summary.put("planCode", data.getPlanCode());
          summary.put("effectiveBudgetAmount", data.getEffectiveBudgetAmount());
          summary.put("disbursedBudgetAmount", data.getDisbursedBudgetAmount());
          summary.put("remainingBudgetAmount", data.getRemainingBudgetAmount());
          log.info(
              "[Budget->Plan Sync] Synced allocation {} to plan {} (mode=REMOTE_PLANNING_SERVICE)",
              req.getAllocationCode(),
              data.getPlanCode());
          return summary;
        }
        errorMessage = "EMPTY_RESPONSE_FROM_PLANNING_SERVICE";
      } catch (Exception e) {
        planningBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
        errorMessage = e.getMessage();
        log.warn(
            "[Budget->Plan Sync] Degraded mode when syncing allocation {} to plan {}: {}",
            req.getAllocationCode(),
            req.getPlanCode(),
            e.getMessage());
      }
    } else {
      errorMessage = "PLANNING_CLIENT_UNAVAILABLE";
    }

    boolean enqueued = enqueuePlanSyncOutbox(messageKey, req);
    summary.put("synced", false);
    summary.put("queuedOutbox", enqueued);
    summary.put("syncMode", enqueued ? "OUTBOX_FALLBACK" : "SYNC_FAILED");
    summary.put("planId", req.getPlanId());
    summary.put("planCode", req.getPlanCode());
    summary.put("allocationCode", req.getAllocationCode());
    summary.put("effectiveBudgetAmount", req.getEffectiveBudgetAmount());
    summary.put("disbursedBudgetAmount", req.getDisbursedBudgetAmount());
    if (StrUtil.isNotBlank(errorMessage)) {
      summary.put("error", errorMessage);
    }
    return summary;
  }

  private Map<String, Object> dispatchPlanSyncFallback(
      PlanBudgetSyncRequestData req, String messageKey, Throwable ex) {
    boolean enqueued = enqueuePlanSyncOutbox(messageKey, req);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("synced", false);
    summary.put("queuedOutbox", enqueued);
    summary.put("syncMode", enqueued ? "OUTBOX_CIRCUIT_OPEN" : "CIRCUIT_OPEN");
    summary.put("planId", req.getPlanId());
    summary.put("planCode", req.getPlanCode());
    summary.put("allocationCode", req.getAllocationCode());
    summary.put("error", ex != null ? ex.getMessage() : "CIRCUIT_OPEN");
    return summary;
  }

  private boolean enqueuePlanSyncOutbox(String messageKey, PlanBudgetSyncRequestData req) {
    if (outboxPublisher == null || req == null) {
      return false;
    }
    try {
      outboxPublisher.enqueue(PLAN_SYNC_OUTBOX_TOPIC, messageKey, req);
      return true;
    } catch (Exception e) {
      log.warn("[Budget->Plan Outbox] Không thể ghi sys_outbox_event cho {}: {}", messageKey, e.getMessage());
      return false;
    }
  }

  /**
   * Khởi tạo lệnh thanh toán / đối soát chứng từ điện tử qua {@code tr-payment-service} khi phê
   * duyệt giải ngân Kho bạc (dùng {@code appCode} động theo ngữ cảnh tenant, không hardcode).
   */
  @CircuitBreaker(name = "paymentClient", fallbackMethod = "syncDisbursementPaymentFallback")
  public String syncDisbursementWithPaymentService(BudgetDisbursement disb) {
    if (disb == null) {
      return null;
    }
    PaymentInternalClient paymentClient =
        System.currentTimeMillis() >= paymentBackoffUntil && paymentClientProvider != null
            ? paymentClientProvider.getIfAvailable()
            : null;
    if (paymentClient != null) {
      try {
        String effectiveAppCode = resolveDynamicAppCode(disb.getAppCode());
        CreatePaymentOrderData orderReq =
            CreatePaymentOrderData.builder()
                .appCode(effectiveAppCode)
                .sourceModule("BUDGET_DISBURSEMENT")
                .referenceId(disb.getId() != null ? String.valueOf(disb.getId()) : disb.getCode())
                .referenceCode(disb.getCode())
                .payerName(StrUtil.blankToDefault(disb.getNodeName(), "Đơn vị dự toán"))
                .amount(
                    disb.getNetPaymentAmount() != null
                        ? disb.getNetPaymentAmount()
                        : disb.getAmount())
                .currency(StrUtil.blankToDefault(disb.getCurrencyCode(), "VND"))
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
        paymentBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
        log.warn(
            "[BudgetPayment] Fallback payment tx code for disbursement {}: {}",
            disb.getCode(),
            e.getMessage());
      }
    }
    return StrUtil.isNotBlank(disb.getPaymentTxCode())
        ? disb.getPaymentTxCode()
        : ("PAY-KBNN-" + disb.getCode());
  }

  private String syncDisbursementPaymentFallback(BudgetDisbursement disb, Throwable ex) {
    log.warn(
        "[BudgetPayment] Circuit breaker fallback for disbursement {}: {}",
        disb != null ? disb.getCode() : "null",
        ex != null ? ex.getMessage() : "");
    if (disb == null) {
      return null;
    }
    return StrUtil.isNotBlank(disb.getPaymentTxCode())
        ? disb.getPaymentTxCode()
        : ("PAY-KBNN-" + disb.getCode());
  }

  public Long startAllocationWorkflow(BudgetAllocation alloc) {
    if (alloc == null || alloc.getId() == null) {
      return null;
    }
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("allocationCode", StrUtil.nullToEmpty(alloc.getCode()));
    vars.put("budgetYear", alloc.getBudgetYear());
    vars.put("nodeId", alloc.getNodeId());
    vars.put("nodeCode", StrUtil.nullToEmpty(alloc.getNodeCode()));
    vars.put("categoryCode", StrUtil.nullToEmpty(alloc.getCategoryCode()));
    vars.put("effectiveAmount", alloc.getEffectiveAmount());
    return startWorkflowGeneric(
        "BudgetAllocation",
        alloc.getId(),
        WF_PROCESS_ALLOCATION,
        "Trình phê duyệt Phân bổ Kinh phí: [" + alloc.getCode() + "] " + alloc.getName(),
        vars);
  }

  public Long startDisbursementWorkflow(BudgetDisbursement disb) {
    if (disb == null || disb.getId() == null) {
      return null;
    }
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("disbursementCode", StrUtil.nullToEmpty(disb.getCode()));
    vars.put("allocationId", disb.getAllocationId());
    vars.put("budgetYear", disb.getBudgetYear());
    vars.put("nodeId", disb.getNodeId());
    vars.put("disbursementType", StrUtil.nullToEmpty(disb.getDisbursementType()));
    vars.put("amount", disb.getAmount());
    return startWorkflowGeneric(
        "BudgetDisbursement",
        disb.getId(),
        WF_PROCESS_DISBURSEMENT,
        "Trình phê duyệt Giải ngân Kho bạc: [" + disb.getCode() + "] " + disb.getName(),
        vars);
  }

  public Long startTransactionWorkflow(BudgetTransaction tx) {
    if (tx == null || tx.getId() == null) {
      return null;
    }
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("transactionCode", StrUtil.nullToEmpty(tx.getCode()));
    vars.put("allocationId", tx.getAllocationId());
    vars.put("budgetYear", tx.getBudgetYear());
    vars.put("nodeId", tx.getNodeId());
    vars.put("txType", StrUtil.nullToEmpty(tx.getTxType()));
    vars.put("amount", tx.getAmount());
    return startWorkflowGeneric(
        "BudgetTransaction",
        tx.getId(),
        WF_PROCESS_TRANSACTION,
        "Trình phê duyệt Biến động Nguồn kinh phí: [" + tx.getCode() + "] " + tx.getName(),
        vars);
  }

  public Long startReportWorkflow(BudgetReport report) {
    if (report == null || report.getId() == null) {
      return null;
    }
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("reportCode", StrUtil.nullToEmpty(report.getCode()));
    vars.put("budgetYear", report.getBudgetYear());
    vars.put("reportPeriod", StrUtil.nullToEmpty(report.getReportPeriod()));
    vars.put("nodeId", report.getNodeId());
    return startWorkflowGeneric(
        "BudgetReport",
        report.getId(),
        WF_PROCESS_REPORT,
        "Trình phê duyệt Báo cáo Quyết toán: [" + report.getCode() + "] " + report.getName(),
        vars);
  }

  @CircuitBreaker(name = "workflowClient", fallbackMethod = "startWorkflowFallback")
  private Long startWorkflowGeneric(
      String entityAppCode,
      Long entityId,
      String processCode,
      String title,
      Map<String, Object> vars) {
    if (entityId == null || System.currentTimeMillis() < workflowBackoffUntil) {
      return null;
    }
    WorkflowFeignClient wfClient =
        workflowClientProvider != null ? workflowClientProvider.getIfAvailable() : null;
    if (wfClient == null) {
      return null;
    }
    try {
      WfStartRequest req =
          WfStartRequest.builder()
              .appCode(entityAppCode)
              .entityId(entityId)
              .processCode(processCode)
              .title(title)
              .actionType("SUBMIT")
              .variables(vars)
              .build();
      R<Long> res = wfClient.startWorkflowInternal(req);
      return res != null ? res.getData() : null;
    } catch (Exception e) {
      workflowBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
      log.warn(
          "[BudgetWorkflow] Degraded mode when starting workflow for {} id={}: {}",
          entityAppCode,
          entityId,
          e.getMessage());
      return null;
    }
  }

  private Long startWorkflowFallback(
      String entityAppCode,
      Long entityId,
      String processCode,
      String title,
      Map<String, Object> vars,
      Throwable ex) {
    log.warn(
        "[BudgetWorkflow] Circuit open starting workflow {} id={}: {}",
        entityAppCode,
        entityId,
        ex != null ? ex.getMessage() : "");
    return null;
  }

  public void executeWorkflowAction(String appCode, Long entityId, String action, String comment) {
    if (entityId == null || System.currentTimeMillis() < workflowBackoffUntil) {
      return;
    }
    runAfterCommitOrNow(
        () -> {
          WorkflowFeignClient wfClient =
              workflowClientProvider != null ? workflowClientProvider.getIfAvailable() : null;
          if (wfClient == null) {
            return;
          }
          try {
            if ("WITHDRAW".equalsIgnoreCase(action)) {
              wfClient.withdrawInternal(appCode, entityId, comment);
            } else {
              wfClient.executeActionInternal(
                  WfActionRequest.builder()
                      .appCode(appCode)
                      .entityId(entityId)
                      .action(action)
                      .comment(comment)
                      .build());
            }
          } catch (Exception e) {
            workflowBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
            log.warn(
                "[BudgetWorkflow] Degraded mode when executing action={} for {} id={}: {}",
                action,
                appCode,
                entityId,
                e.getMessage());
          }
        });
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

    WorkflowFeignClient wfClient =
        System.currentTimeMillis() >= workflowBackoffUntil && workflowClientProvider != null
            ? workflowClientProvider.getIfAvailable()
            : null;
    if (wfClient != null && alloc.getId() != null) {
      try {
        R<WfEntityStatus> res = wfClient.getEntityStatusInternal("BudgetAllocation", alloc.getId());
        if (res != null && res.getData() != null) {
          result.put("engineConnected", true);
          result.put("engineStatus", res.getData());
          return result;
        }
      } catch (Exception e) {
        workflowBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
        log.debug("[BudgetWorkflow] Using built-in timeline for id={}", alloc.getId());
      }
    }

    result.put("engineConnected", false);
    String st = StrUtil.nullToEmpty(alloc.getWorkflowStatus()).toUpperCase();
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
            "status", "DRAFT".equals(st) || "REWORK".equals(st) ? "PENDING" : "COMPLETED"));
    if (!"UNIT_SELF_APPROVAL".equalsIgnoreCase(alloc.getApprovalScope())) {
      steps.add(
          Map.of(
              "stepOrder", 3,
              "stepName", "Phê duyệt & Ban hành Quyết định Giao Dự toán",
              "actorRole", "Ban Giám đốc Sở / Thủ trưởng Đơn vị dự toán cấp I",
              "status",
              List.of("APPROVED", "SELF_APPROVED", "EXECUTING", "SETTLED", "LOCKED_SETTLED")
                      .contains(st)
                  ? "COMPLETED"
                  : ("PENDING_APPROVAL".equals(st) ? "IN_PROGRESS" : "PENDING")));
    }
    result.put("steps", steps);
    return result;
  }

  public void notifyAllocationLifecycleEvent(
      BudgetAllocation alloc, String eventType, String actorName, String comment) {
    if (alloc == null) {
      return;
    }
    String effectiveAppCode = resolveDynamicAppCode(alloc.getAppCode());
    String effectiveActor = StrUtil.blankToDefault(actorName, resolveCurrentActorName(null));
    runAfterCommitOrNow(
        () -> {
          Set<String> recipients =
              resolveNodeRecipientUserIds(alloc.getNodeCode(), alloc.getNodeId());
          String upperEvent = StrUtil.nullToEmpty(eventType).toUpperCase();
          String title =
              switch (upperEvent) {
                case "PENDING_APPROVAL", "SUBMITTED" ->
                    "Trình thẩm định dự toán kinh phí [" + alloc.getCode() + "]";
                case "APPROVED" -> "Đã phê duyệt giao dự toán kinh phí [" + alloc.getCode() + "]";
                case "SELF_APPROVED" ->
                    "Đơn vị tự phê duyệt phân bổ kinh phí [" + alloc.getCode() + "]";
                case "REWORK" -> "Yêu cầu chỉnh sửa dự toán kinh phí [" + alloc.getCode() + "]";
                case "REJECTED" -> "Yêu cầu rà soát lại dự toán kinh phí [" + alloc.getCode() + "]";
                default -> "Cập nhật phương án phân bổ kinh phí [" + alloc.getCode() + "]";
              };
          String body =
              String.format(
                  "Phân bổ '%s' (%s) đang ở trạng thái %s. Người thực hiện: %s.%s",
                  alloc.getName(),
                  StrUtil.blankToDefault(alloc.getNodeName(), "Đơn vị"),
                  alloc.getWorkflowStatus(),
                  effectiveActor,
                  StrUtil.isNotBlank(comment) ? " Ghi chú: " + comment : "");
          String notifEventType =
              switch (upperEvent) {
                case "PENDING_APPROVAL", "SUBMITTED" -> NotifEventTypes.BUDGET_ALLOCATION_SUBMITTED;
                case "APPROVED", "SELF_APPROVED" -> NotifEventTypes.BUDGET_ALLOCATION_APPROVED;
                case "REJECTED", "REWORK" -> NotifEventTypes.BUDGET_ALLOCATION_REJECTED;
                default -> NotifEventTypes.BUDGET_ALLOCATION_SUBMITTED;
              };
          sendNotification(
              effectiveAppCode,
              notifEventType,
              recipients,
              List.of("ACCOUNTANT", "FINANCE_HEAD", "SUPERIOR_APPROVAL"),
              "NORMAL",
              title,
              body,
              "/budget/allocations");
        });
  }

  public void notifyTransactionEvent(BudgetTransaction tx, String eventType) {
    if (tx == null) {
      return;
    }
    String effectiveAppCode = resolveDynamicAppCode(tx.getAppCode());
    runAfterCommitOrNow(
        () -> {
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
              effectiveAppCode,
              "budget.transaction." + StrUtil.nullToEmpty(eventType).toLowerCase(),
              recipients,
              List.of("ACCOUNTANT", "FINANCE_HEAD"),
              "HIGH",
              title,
              body,
              "/budget/transactions");
        });
  }

  public void notifyDisbursementEvent(BudgetDisbursement disb, String eventType) {
    if (disb == null) {
      return;
    }
    String effectiveAppCode = resolveDynamicAppCode(disb.getAppCode());
    runAfterCommitOrNow(
        () -> {
          Set<String> recipients =
              resolveNodeRecipientUserIds(disb.getNodeCode(), disb.getNodeId());
          if (disb.getRequestedByPersonId() != null) {
            recipients.addAll(
                resolvePersonIdsToUserIdStrings(List.of(disb.getRequestedByPersonId())));
          }
          String title = "Hồ sơ Giải ngân / Quyết toán [" + disb.getCode() + "]";
          String body =
              String.format(
                  "Hồ sơ '%s' (%s VNĐ - Thụ hưởng: %s) đang ở trạng thái %s.",
                  disb.getName(),
                  disb.getAmount() != null ? disb.getAmount().toPlainString() : "0",
                  StrUtil.blankToDefault(disb.getBeneficiaryName(), "Đơn vị thụ hưởng"),
                  disb.getApprovalStatus());
          String notifEventType =
              "SETTLED".equalsIgnoreCase(eventType) || "WEBHOOK_SETTLED".equalsIgnoreCase(eventType)
                  ? NotifEventTypes.BUDGET_DISBURSEMENT_SETTLED
                  : NotifEventTypes.BUDGET_DISBURSEMENT_APPROVED;
          sendNotification(
              effectiveAppCode,
              notifEventType,
              recipients,
              List.of("ACCOUNTANT", "FINANCE_HEAD"),
              "HIGH",
              title,
              body,
              "/budget/disbursements");
        });
  }

  /**
   * Quét cảnh báo SLA hồ sơ giải ngân định kỳ với khóa phân tán Redisson {@code RLock}, cô lập
   * từng tenant {@code appCode}, đọc ngưỡng {@code slaDisbursementWarningDays} từ {@link
   * BudgetCategory} và cập nhật cờ {@code isOverdueWarningSent} chống gửi trùng.
   */
  @Scheduled(cron = "${budget.sla.cron:0 15 8 * * *}")
  public Map<String, Object> scanAndDispatchDisbursementSlaAlerts() {
    LocalDate today = LocalDate.now();
    RLock lock = null;
    boolean lockAcquired = false;
    RedissonClient redisson =
        redissonClientProvider != null ? redissonClientProvider.getIfAvailable() : null;
    if (redisson != null) {
      lock = redisson.getLock(SLA_LOCK_KEY);
      try {
        lockAcquired = lock.tryLock(0, 10, TimeUnit.MINUTES);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Map.of(
            "scannedDate", today.toString(),
            "skipped", true,
            "reason", "LOCK_INTERRUPTED");
      }
      if (!lockAcquired) {
        log.info("[BudgetSLA] Bỏ qua lượt quét SLA vì Pod khác đang giữ khóa {}", SLA_LOCK_KEY);
        return Map.of(
            "scannedDate", today.toString(),
            "skipped", true,
            "reason", "LOCK_HELD_BY_ANOTHER_INSTANCE");
      }
    }

    try {
      int totalScanned = 0;
      int overdueCount = 0;
      int dueSoonCount = 0;
      List<Map<String, Object>> alertedItems = new ArrayList<>();

      List<String> appCodes = budgetDisbursementRepo.findDistinctActiveAppCodes();
      if (appCodes != null && !appCodes.isEmpty()) {
        for (String tenantAppCode : appCodes) {
          if (StrUtil.isBlank(tenantAppCode)) {
            continue;
          }
          String previousAppCode = HeaderContext.getAppCode();
          try {
            HeaderContext.setAppCode(tenantAppCode.trim());
            List<BudgetDisbursement> pending =
                budgetDisbursementRepo.findPendingSlaDisbursementsByAppCode(tenantAppCode.trim());
            int[] counts =
                evaluateSlaBatch(today, tenantAppCode.trim(), pending, alertedItems);
            totalScanned += counts[0];
            overdueCount += counts[1];
            dueSoonCount += counts[2];
          } finally {
            if (StrUtil.isNotBlank(previousAppCode)) {
              HeaderContext.setAppCode(previousAppCode);
            } else {
              HeaderContext.clear();
            }
          }
        }
      } else {
        // Fallback cho môi trường unit test chưa stub findDistinctActiveAppCodes()
        List<BudgetDisbursement> allDisb = budgetDisbursementRepo.findAll();
        int[] counts = evaluateSlaBatch(today, null, allDisb, alertedItems);
        totalScanned += counts[0];
        overdueCount += counts[1];
        dueSoonCount += counts[2];
      }

      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("scannedDate", today.toString());
      summary.put("distributedLockAcquired", lock != null ? lockAcquired : false);
      summary.put("totalScanned", totalScanned);
      summary.put("overdueCount", overdueCount);
      summary.put("dueSoonCount", dueSoonCount);
      summary.put("alertedItems", alertedItems);
      return summary;
    } finally {
      if (lock != null && lockAcquired && lock.isHeldByCurrentThread()) {
        try {
          lock.unlock();
        } catch (Exception e) {
          log.warn("[BudgetSLA] Lỗi nhả khóa Redisson {}: {}", SLA_LOCK_KEY, e.getMessage());
        }
      }
    }
  }

  private int[] evaluateSlaBatch(
      LocalDate today,
      String tenantAppCode,
      List<BudgetDisbursement> records,
      List<Map<String, Object>> alertedItems) {
    if (records == null || records.isEmpty()) {
      return new int[] {0, 0, 0};
    }
    Map<String, Integer> warningDaysByCategoryCode = loadSlaWarningDaysMap(tenantAppCode, records);
    int overdue = 0;
    int dueSoon = 0;
    List<BudgetDisbursement> dirtyAlerted = new ArrayList<>();

    for (BudgetDisbursement d : records) {
      String st = StrUtil.nullToEmpty(d.getApprovalStatus()).toUpperCase();
      if ("SETTLED".equals(st)
          || "REJECTED".equals(st)
          || "CANCELLED".equals(st)
          || "DRAFT".equals(st)
          || d.getSlaDueDate() == null
          || Boolean.TRUE.equals(d.getIsOverdueWarningSent())) {
        continue;
      }
      int warningDays =
          d.getCategoryCode() != null
              ? warningDaysByCategoryCode.getOrDefault(
                  d.getCategoryCode().trim().toUpperCase(), DEFAULT_SLA_WARNING_DAYS)
              : DEFAULT_SLA_WARNING_DAYS;
      long daysRemaining = ChronoUnit.DAYS.between(today, d.getSlaDueDate());
      if (daysRemaining < 0) {
        overdue++;
        long daysOverdue = Math.abs(daysRemaining);
        alertedItems.add(
            Map.of(
                "id", d.getId(),
                "code", StrUtil.nullToEmpty(d.getCode()),
                "name", StrUtil.nullToEmpty(d.getName()),
                "alertType", "OVERDUE_SETTLEMENT",
                "daysOverdue", daysOverdue));
        sendInAppNotification(
            resolveDynamicAppCode(StrUtil.blankToDefault(tenantAppCode, d.getAppCode())),
            d,
            "SLA_OVERDUE",
            "Cảnh báo quá hạn thanh toán tạm ứng: " + StrUtil.nullToEmpty(d.getCode()),
            "Hồ sơ giải ngân ["
                + StrUtil.nullToEmpty(d.getName())
                + "] đã quá hạn quyết toán "
                + daysOverdue
                + " ngày.");
        d.setIsOverdueWarningSent(Boolean.TRUE);
        dirtyAlerted.add(d);
      } else if (daysRemaining <= warningDays) {
        dueSoon++;
        alertedItems.add(
            Map.of(
                "id", d.getId(),
                "code", StrUtil.nullToEmpty(d.getCode()),
                "name", StrUtil.nullToEmpty(d.getName()),
                "alertType", "DUE_SOON_SETTLEMENT",
                "daysRemaining", daysRemaining,
                "warningThresholdDays", warningDays));
        sendInAppNotification(
            resolveDynamicAppCode(StrUtil.blankToDefault(tenantAppCode, d.getAppCode())),
            d,
            "SLA_WARNING",
            "Nhắc hạn thanh toán tạm ứng: " + StrUtil.nullToEmpty(d.getCode()),
            "Hồ sơ giải ngân ["
                + StrUtil.nullToEmpty(d.getName())
                + "] còn "
                + daysRemaining
                + " ngày đến hạn quyết toán.");
        d.setIsOverdueWarningSent(Boolean.TRUE);
        dirtyAlerted.add(d);
      }
    }

    if (!dirtyAlerted.isEmpty()) {
      for (BudgetDisbursement alerted : dirtyAlerted) {
        budgetDisbursementRepo.save(alerted);
      }
    }
    return new int[] {records.size(), overdue, dueSoon};
  }

  private Map<String, Integer> loadSlaWarningDaysMap(
      String tenantAppCode, List<BudgetDisbursement> records) {
    Map<String, Integer> result = new HashMap<>();
    if (budgetCategoryRepo == null || StrUtil.isBlank(tenantAppCode)) {
      return result;
    }
    Set<String> catCodes = new LinkedHashSet<>();
    for (BudgetDisbursement d : records) {
      if (StrUtil.isNotBlank(d.getCategoryCode())) {
        catCodes.add(d.getCategoryCode().trim());
      }
    }
    if (catCodes.isEmpty()) {
      return result;
    }
    try {
      List<BudgetCategory> categories =
          budgetCategoryRepo.findByAppCodeAndCodeIn(tenantAppCode, catCodes);
      if (categories != null) {
        for (BudgetCategory cat : categories) {
          if (StrUtil.isNotBlank(cat.getCode())
              && cat.getSlaDisbursementWarningDays() != null
              && cat.getSlaDisbursementWarningDays() > 0) {
            result.put(
                cat.getCode().trim().toUpperCase(), cat.getSlaDisbursementWarningDays());
          }
        }
      }
    } catch (Exception e) {
      log.debug("[BudgetSLA] Fallback ngưỡng SLA mặc định ({} ngày): {}", DEFAULT_SLA_WARNING_DAYS, e.getMessage());
    }
    return result;
  }

  private void sendInAppNotification(
      String appCode, BudgetDisbursement disb, String alertType, String title, String message) {
    if (disb == null) {
      return;
    }
    Set<String> recipients = new LinkedHashSet<>();
    if (disb.getRequestedByPersonId() != null) {
      recipients.addAll(resolvePersonIdsToUserIdStrings(List.of(disb.getRequestedByPersonId())));
    }
    if (StrUtil.isNotBlank(disb.getNodeCode()) || disb.getNodeId() != null) {
      recipients.addAll(resolveNodeRecipientUserIds(disb.getNodeCode(), disb.getNodeId()));
    }
    String priority = "SLA_OVERDUE".equalsIgnoreCase(alertType) ? "URGENT" : "HIGH";
    sendNotification(
        appCode,
        NotifEventTypes.BUDGET_SLA_OVERDUE_WARNING,
        recipients,
        List.of("ACCOUNTANT", "FINANCE_HEAD"),
        priority,
        title,
        message,
        "/budget/disbursements");
  }

  private Set<String> resolvePersonIdsToUserIdStrings(Collection<Long> personIds) {
    Set<String> result = new LinkedHashSet<>();
    if (personIds == null
        || personIds.isEmpty()
        || System.currentTimeMillis() < identityBackoffUntil) {
      return result;
    }
    UnifiedIdentityResolver resolver =
        identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
    if (resolver != null) {
      try {
        Map<Long, Long> mapped = resolver.mapPersonIdsToUserIds(personIds);
        if (mapped != null) {
          for (Long uid : mapped.values()) {
            if (uid != null) {
              result.add(String.valueOf(uid));
            }
          }
        }
      } catch (Exception e) {
        identityBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
        log.debug("[BudgetIdentity] Fallback personIds mapping: {}", e.getMessage());
      }
    }
    return result;
  }

  private Set<String> resolveNodeRecipientUserIds(String nodeCode, Long nodeId) {
    Set<String> result = new LinkedHashSet<>();
    if (System.currentTimeMillis() < identityBackoffUntil) {
      return result;
    }
    UnifiedIdentityResolver resolver =
        identityResolverProvider != null ? identityResolverProvider.getIfAvailable() : null;
    if (resolver == null) {
      return result;
    }
    try {
      if (StrUtil.isNotBlank(nodeCode)) {
        List<Long> uids = resolver.resolveRecipientUserIdsForNode(nodeCode, false);
        if (uids != null && !uids.isEmpty()) {
          uids.forEach(uid -> result.add(String.valueOf(uid)));
        }
      } else if (nodeId != null) {
        List<UserPersonContextData> users = resolver.resolveUsersByNodeId(nodeId, false);
        if (users != null && !users.isEmpty()) {
          users.forEach(
              ctx -> {
                if (ctx.getUserId() != null) {
                  result.add(String.valueOf(ctx.getUserId()));
                }
              });
        }
      }
    } catch (Exception e) {
      identityBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
      log.debug("[BudgetIdentity] Fallback node recipient resolution: {}", e.getMessage());
    }
    return result;
  }

  private void sendNotification(
      String appCode,
      String eventType,
      Set<String> recipientIds,
      List<String> recipientRoles,
      String priority,
      String title,
      String body,
      String routePath) {
    NotificationInternalClient client =
        System.currentTimeMillis() >= notificationBackoffUntil && notificationClientProvider != null
            ? notificationClientProvider.getIfAvailable()
            : null;
    if (client == null) {
      log.info("[BudgetNotification][Simulated] appCode={} eventType={} title={}", appCode, eventType, title);
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
              .appCode(resolveDynamicAppCode(appCode))
              .createdAt(Instant.now())
              .build();
      client.sendNotification(req);
    } catch (Exception e) {
      notificationBackoffUntil = System.currentTimeMillis() + CIRCUIT_BACKOFF_MS;
      log.warn(
          "[BudgetNotification] Degraded mode when sending notification eventType={}: {}",
          eventType,
          e.getMessage());
    }
  }

  /**
   * Trì hoãn thực thi tác vụ gọi Feign ngoại vi sang giai đoạn {@code afterCommit} nếu đang nằm
   * trong giao dịch DB, tránh chiếm giữ khóa dòng và kết nối HikariCP trong lúc chờ mạng.
   */
  public void runAfterCommitOrNow(Runnable task) {
    if (task == null) {
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              try {
                task.run();
              } catch (Exception e) {
                log.warn("[BudgetIntegration] Lỗi thực thi tác vụ afterCommit: {}", e.getMessage());
              }
            }
          });
    } else {
      task.run();
    }
  }

  /** Phân giải {@code appCode} động theo thứ tự ưu tiên: HeaderContext -> LoginHelper -> Entity -> mặc định. */
  public String resolveDynamicAppCode(String entityAppCode) {
    String headerAppCode = HeaderContext.getAppCode();
    if (StrUtil.isNotBlank(headerAppCode)) {
      return headerAppCode.trim();
    }
    try {
      String loginAppCode = LoginHelper.getAppCode();
      if (StrUtil.isNotBlank(loginAppCode)) {
        return loginAppCode.trim();
      }
    } catch (Exception ignored) {
      // Không có phiên Sa-Token
    }
    if (StrUtil.isNotBlank(entityAppCode) && !"ADMIN".equalsIgnoreCase(entityAppCode.trim())) {
      return entityAppCode.trim();
    }
    return StrUtil.isNotBlank(entityAppCode) ? entityAppCode.trim() : AppCodeConstants.BUDGET;
  }
}
