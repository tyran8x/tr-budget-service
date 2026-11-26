package vn.tr.budget.kafka;

import cn.hutool.core.util.StrUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.common.core.constant.ReportingKafkaConstants;
import vn.tr.common.core.constant.ServiceNameConstants;
import vn.tr.common.core.context.HeaderContext;
import vn.tr.common.core.event.reporting.DomainFactMetricEvent;
import vn.tr.common.core.event.reporting.DomainFactMetricEvent.MetricItemData;
import vn.tr.common.kafka.outbox.OutboxPublisher;
import vn.tr.common.satoken.utils.LoginHelper;

/**
 * Publisher đẩy 1 chiều sự kiện {@link DomainFactMetricEvent} từ {@code tr-budget-service} lên
 * Kafka topic {@code topic.domain.fact.metrics} khi phê duyệt giải ngân hoặc nghiệm thu quyết toán,
 * phục vụ CQRS Fact Store trên {@code tr-reporting-service}.
 *
 * @author tyran8x
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BudgetFactMetricPublisher {

  public static final String PROVIDER_CODE = "BUDGET";

  private final ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplateProvider;

  @Autowired(required = false)
  private ObjectProvider<OutboxPublisher> outboxPublisherProvider;

  public void setOutboxPublisherProvider(ObjectProvider<OutboxPublisher> outboxPublisherProvider) {
    this.outboxPublisherProvider = outboxPublisherProvider;
  }

  /** Phát sự kiện chỉ số giải ngân/quyết toán ngân sách sau khi giao dịch DB commit. */
  public void publishDisbursementSnapshot(
      BudgetDisbursement disbursement, BudgetAllocation allocation, String triggerAction) {
    if (disbursement == null) {
      return;
    }
    String appCode =
        resolveEffectiveAppCode(
            StrUtil.isNotBlank(disbursement.getAppCode())
                ? disbursement.getAppCode()
                : (allocation != null ? allocation.getAppCode() : null));
    if (StrUtil.isBlank(appCode)) {
      return;
    }

    BigDecimal amount = safe(disbursement.getAmount());
    BigDecimal settledAmount = safe(disbursement.getSettledAmount());
    BigDecimal effectiveBudget = allocation != null ? safe(allocation.getEffectiveAmount()) : amount;
    BigDecimal totalDisbursed =
        allocation != null ? safe(allocation.getDisbursedAmount()) : amount;
    double disbursementRate =
        effectiveBudget.compareTo(BigDecimal.ZERO) > 0
            ? totalDisbursed
                .multiply(new BigDecimal("100"))
                .divide(effectiveBudget, 2, RoundingMode.HALF_UP)
                .doubleValue()
            : 0.0;

    String unitCode =
        StrUtil.isNotBlank(disbursement.getNodeCode())
            ? disbursement.getNodeCode().trim()
            : (allocation != null && StrUtil.isNotBlank(allocation.getNodeCode())
                ? allocation.getNodeCode().trim()
                : (disbursement.getNodeId() != null ? "NODE_" + disbursement.getNodeId() : null));
    int reportYear =
        disbursement.getBudgetYear() != null
            ? disbursement.getBudgetYear()
            : (allocation != null && allocation.getBudgetYear() != null
                ? allocation.getBudgetYear()
                : LocalDate.now().getYear());
    boolean isSettled = "SETTLED".equalsIgnoreCase(disbursement.getApprovalStatus());

    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("triggerAction", StrUtil.blankToDefault(triggerAction, "DISBURSEMENT_UPDATED"));
    metrics.put("disbursementId", disbursement.getId());
    metrics.put("disbursementCode", StrUtil.nullToEmpty(disbursement.getCode()));
    metrics.put("approvalStatus", StrUtil.nullToEmpty(disbursement.getApprovalStatus()));
    metrics.put("disbursementAmount", amount);
    metrics.put("settledAmount", settledAmount);
    metrics.put("totalEffectiveBudget", effectiveBudget);
    metrics.put("totalDisbursedBudget", totalDisbursed);
    metrics.put("disbursementRatePercent", disbursementRate);

    List<MetricItemData> metricItems = new ArrayList<>();
    metricItems.add(
        MetricItemData.builder()
            .metricCode("DISBURSED_AMOUNT")
            .metricName("Giá trị giải ngân")
            .metricValue(totalDisbursed)
            .unit("VNĐ")
            .unitCode(unitCode)
            .build());
    metricItems.add(
        MetricItemData.builder()
            .metricCode("DISBURSEMENT_RATE")
            .metricName("Tỷ lệ giải ngân")
            .metricValue(BigDecimal.valueOf(disbursementRate))
            .unit("%")
            .unitCode(unitCode)
            .build());
    if (isSettled) {
      metricItems.add(
          MetricItemData.builder()
              .metricCode("SETTLED_AMOUNT")
              .metricName("Giá trị quyết toán")
              .metricValue(settledAmount)
              .unit("VNĐ")
              .unitCode(unitCode)
              .build());
    }

    DomainFactMetricEvent event =
        DomainFactMetricEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .appCode(appCode)
            .sourceService(ServiceNameConstants.BUDGET_SERVICE)
            .providerCode(PROVIDER_CODE)
            .unitCode(unitCode)
            .reportYear(reportYear)
            .reportPeriod("YEAR")
            .totalRecords(1L)
            .completedRecords(
                isSettled || "APPROVED_DISBURSED".equalsIgnoreCase(disbursement.getApprovalStatus())
                    ? 1L
                    : 0L)
            .completionRate(disbursementRate)
            .avgScore(disbursementRate)
            .metrics(metrics)
            .metricItems(metricItems)
            .occurredAt(LocalDateTime.now())
            .build();
    publishAfterCommit(event);
  }

  public void publishAfterCommit(DomainFactMetricEvent event) {
    if (event == null || StrUtil.isBlank(event.getAppCode())) {
      return;
    }
    String key =
        event.getAppCode()
            + ":"
            + PROVIDER_CODE
            + ":"
            + StrUtil.blankToDefault(event.getUnitCode(), "ALL");
    OutboxPublisher outboxPublisher =
        outboxPublisherProvider != null ? outboxPublisherProvider.getIfAvailable() : null;
    if (outboxPublisher != null && TransactionSynchronizationManager.isActualTransactionActive()) {
      try {
        outboxPublisher.enqueue(ReportingKafkaConstants.TOPIC_DOMAIN_FACT_METRICS, key, event);
        return;
      } catch (Exception ex) {
        log.warn(
            "[BudgetFactMetricPublisher] Lỗi ghi sys_outbox_event, chuyển fallback afterCommit (eventId={}): {}",
            event.getEventId(),
            ex.getMessage());
      }
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              sendSafely(event, key);
            }
          });
    } else {
      sendSafely(event, key);
    }
  }

  private void sendSafely(DomainFactMetricEvent event, String key) {
    try {
      OutboxPublisher outboxPublisher =
          outboxPublisherProvider != null ? outboxPublisherProvider.getIfAvailable() : null;
      if (outboxPublisher != null) {
        outboxPublisher.publishWithOutboxFallback(
            ReportingKafkaConstants.TOPIC_DOMAIN_FACT_METRICS, key, event);
        return;
      }
      KafkaTemplate<String, Object> kafkaTemplate =
          kafkaTemplateProvider != null ? kafkaTemplateProvider.getIfAvailable() : null;
      if (kafkaTemplate == null) {
        log.debug(
            "[BudgetFactMetricPublisher] Bỏ qua phát Kafka do không có bean KafkaTemplate (eventId={})",
            event.getEventId());
        return;
      }
      kafkaTemplate.send(ReportingKafkaConstants.TOPIC_DOMAIN_FACT_METRICS, key, event);
    } catch (Exception ex) {
      log.warn(
          "[BudgetFactMetricPublisher] Không thể phát DomainFactMetricEvent (eventId={}): {}",
          event.getEventId(),
          ex.getMessage());
    }
  }

  private String resolveEffectiveAppCode(String entityAppCode) {
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
      // Không có phiên đăng nhập
    }
    return StrUtil.isNotBlank(entityAppCode) ? entityAppCode.trim() : null;
  }

  private static BigDecimal safe(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }
}
