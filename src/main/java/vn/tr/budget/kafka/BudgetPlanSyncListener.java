package vn.tr.budget.kafka;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import vn.tr.budget.business.helper.BudgetIntegrationOrchestrator;
import vn.tr.common.feign.planning.data.PlanBudgetSyncRequestData;

/**
 * Kafka Listener xử lý sự kiện Outbox đồng bộ số liệu Dự toán sang {@code tr-planning-service}
 * ({@code budget-plan-sync}) theo mô hình Transactional Outbox ({@code sys_outbox_event}).
 *
 * @author tyran8x
 */
@Component
@ConditionalOnProperty(
    prefix = "tr.kafka.plan-sync-listener",
    name = "enabled",
    havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class BudgetPlanSyncListener {

  private final BudgetIntegrationOrchestrator integrationOrchestrator;
  private final ObjectMapper objectMapper;

  @KafkaListener(
      topics = "${tr.kafka.topics.budget-plan-sync:budget-plan-sync}",
      groupId = "${spring.application.name:tr-budget-service}-plan-sync")
  public void onPlanSyncEvent(String rawPayload, Acknowledgment acknowledgment) {
    if (StrUtil.isBlank(rawPayload)) {
      ackSafely(acknowledgment);
      return;
    }
    try {
      PlanBudgetSyncRequestData req =
          objectMapper.readValue(rawPayload, PlanBudgetSyncRequestData.class);
      String key =
          StrUtil.blankToDefault(
              req.getPlanCode(),
              req.getAllocationId() != null ? String.valueOf(req.getAllocationId()) : req.getAllocationCode());
      integrationOrchestrator.dispatchPlanSyncFeign(req, key);
      ackSafely(acknowledgment);
    } catch (Exception e) {
      log.error(
          "[BudgetPlanSyncListener] Lỗi chuyển tiếp sự kiện Outbox sang Planning: {}",
          e.getMessage(),
          e);
      throw new IllegalStateException("Không thể xử lý sự kiện đồng bộ Planning từ Outbox", e);
    }
  }

  private void ackSafely(Acknowledgment acknowledgment) {
    if (acknowledgment != null) {
      acknowledgment.acknowledge();
    }
  }
}
