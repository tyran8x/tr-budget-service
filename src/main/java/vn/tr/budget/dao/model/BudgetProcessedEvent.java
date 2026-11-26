package vn.tr.budget.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bảng lưu dấu vết các sự kiện Webhook/Kafka đã xử lý thành công nhằm đảm bảo tính lũy đẳng
 * (Idempotency) chống xử lý lặp khi nhận trùng sự kiện thanh toán hoặc đồng bộ.
 *
 * <p>Mapping: Table {@code budget_processed_event}.
 *
 * @author tyran8x
 */
@Entity
@Table(name = "budget_processed_event")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BudgetProcessedEvent {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_key", length = 200, nullable = false, unique = true)
  private String eventKey;

  @Column(name = "event_type", length = 100, nullable = false)
  private String eventType;

  @Column(name = "app_code", length = 50, nullable = false)
  @Builder.Default
  private String appCode = "ADMIN";

  @Column(name = "reference_id")
  private Long referenceId;

  @Column(name = "payload_hash", length = 128)
  private String payloadHash;

  @Column(name = "processed_at", nullable = false)
  @Builder.Default
  private LocalDateTime processedAt = LocalDateTime.now();
}
