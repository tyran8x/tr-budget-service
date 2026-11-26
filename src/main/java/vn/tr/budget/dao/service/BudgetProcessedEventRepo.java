package vn.tr.budget.dao.service;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vn.tr.budget.dao.model.BudgetProcessedEvent;

/**
 * Repository quản lý nhật ký chống xử lý lặp (Idempotency) cho Webhook & Kafka Consumer.
 *
 * @author tyran8x
 */
@Repository
public interface BudgetProcessedEventRepo extends JpaRepository<BudgetProcessedEvent, Long> {

  boolean existsByEventKey(String eventKey);

  Optional<BudgetProcessedEvent> findByEventKey(String eventKey);
}
