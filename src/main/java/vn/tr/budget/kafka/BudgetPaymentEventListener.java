package vn.tr.budget.kafka;

import cn.hutool.core.util.StrUtil;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.tr.budget.business.BudgetDisbursementBusiness;
import vn.tr.common.core.constant.AppCodeConstants;
import vn.tr.common.core.context.HeaderContext;
import vn.tr.common.core.event.payment.PaymentCompletedEvent;
import vn.tr.common.json.utils.JsonUtils;

/**
 * Kafka Listener tiếp nhận sự kiện thanh toán hoàn tất ({@code payment-events}) từ {@code
 * tr-payment-service} để tự động đối soát và quyết toán hồ sơ giải ngân (Idempotent).
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

  @KafkaListener(
      topics = "${tr.kafka.topics.payment-events:payment-events}",
      groupId = "${spring.application.name:tr-budget-service}-payment-group")
  public void handlePaymentCompleted(String payload) {
    if (StrUtil.isBlank(payload)) {
      return;
    }
    PaymentCompletedEvent event;
    try {
      event = JsonUtils.parseObject(payload, PaymentCompletedEvent.class);
    } catch (Exception e) {
      log.warn("[BudgetKafka] Bỏ qua payload payment-events không hợp lệ: {}", e.getMessage());
      return;
    }
    if (event == null) {
      return;
    }
    HeaderContext.setAppCode(
        StrUtil.blankToDefault(event.getAppCode(), AppCodeConstants.BUDGET));
    try {
      Map<String, Object> result = budgetDisbursementBusiness.settleFromPaymentEvent(event);
      if (Boolean.TRUE.equals(result.get("settled"))) {
        log.info(
            "[BudgetKafka] Đã đối soát quyết toán giải ngân id={} txCode={} (idempotent={})",
            result.get("disbursementId"),
            event.getTransactionCode(),
            result.get("idempotent"));
      }
    } finally {
      HeaderContext.clear();
    }
  }
}
