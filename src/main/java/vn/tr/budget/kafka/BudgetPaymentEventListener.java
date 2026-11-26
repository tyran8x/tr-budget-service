package vn.tr.budget.kafka;

import cn.hutool.core.util.StrUtil;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import vn.tr.budget.business.BudgetDisbursementBusiness;
import vn.tr.common.core.context.HeaderContext;
import vn.tr.common.core.event.payment.PaymentCompletedEvent;
import vn.tr.common.json.utils.JsonUtils;

/**
 * Kafka Listener tiếp nhận sự kiện thanh toán hoàn tất ({@code payment-events}) từ {@code
 * tr-payment-service} để tự động đối soát và quyết toán hồ sơ giải ngân (hỗ trợ đa tenant động và
 * manual Acknowledgment).
 *
 * @author tyran8x
 */
@Component
@ConditionalOnProperty(
    prefix = "tr.kafka.payment-listener",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class BudgetPaymentEventListener {

  private final BudgetDisbursementBusiness budgetDisbursementBusiness;

  /** Overload giữ tương thích với test/caller không truyền Acknowledgment. */
  public void handlePaymentCompleted(String payload) {
    handlePaymentCompleted(payload, null);
  }

  @KafkaListener(
      topics = "${tr.kafka.topics.payment-events:payment-events}",
      groupId = "${spring.application.name:tr-budget-service}-payment-group")
  public void handlePaymentCompleted(String payload, Acknowledgment acknowledgment) {
    if (StrUtil.isBlank(payload)) {
      ackSafely(acknowledgment);
      return;
    }
    PaymentCompletedEvent event;
    try {
      event = JsonUtils.parseObject(payload, PaymentCompletedEvent.class);
    } catch (Exception e) {
      log.warn("[BudgetKafka] Bỏ qua payload payment-events không hợp lệ: {}", e.getMessage());
      ackSafely(acknowledgment);
      return;
    }
    if (event == null) {
      ackSafely(acknowledgment);
      return;
    }
    if (StrUtil.isBlank(event.getAppCode())) {
      log.warn(
          "[BudgetKafka] Bỏ qua sự kiện payment-events thiếu appCode (txCode={}) theo nguyên tắc fail-closed.",
          event.getTransactionCode());
      ackSafely(acknowledgment);
      return;
    }
    HeaderContext.setAppCode(event.getAppCode().trim());
    try {
      Map<String, Object> result = budgetDisbursementBusiness.settleFromPaymentEvent(event);
      if (Boolean.TRUE.equals(result.get("settled"))) {
        log.info(
            "[BudgetKafka] Đã đối soát quyết toán giải ngân tenant={} id={} txCode={} (idempotent={})",
            event.getAppCode(),
            result.get("disbursementId"),
            event.getTransactionCode(),
            result.get("idempotent"));
      }
      ackSafely(acknowledgment);
    } finally {
      HeaderContext.clear();
    }
  }

  private void ackSafely(Acknowledgment acknowledgment) {
    if (acknowledgment != null) {
      acknowledgment.acknowledge();
    }
  }
}
