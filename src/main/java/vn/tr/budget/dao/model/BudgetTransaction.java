package vn.tr.budget.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.javers.core.metamodel.annotation.TypeName;
import vn.tr.common.jpa.entity.AppIsolated;
import vn.tr.common.jpa.entity.BaseCommonEntity;
import vn.tr.common.jpa.listener.AppIsolatedListener;

/**
 * Entity đại diện cho Sổ cái Biến động Nguồn Kinh phí Bút toán kép (BudgetTransaction).
 *
 * <p>Mapping: Table {@code budget_transaction}.
 *
 * @author tyran8x
 */
@Entity
@Table(name = "budget_transaction")
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@EntityListeners(AppIsolatedListener.class)
@Filter(name = "appCodeFilter", condition = "app_code = :appCode")
@SQLDelete(
    sql =
        "UPDATE budget_transaction SET deleted_at = CURRENT_TIMESTAMP, version = (version + 1) WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetTransaction")
public class BudgetTransaction extends BaseCommonEntity implements AppIsolated {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_code", length = 50, nullable = false)
  @Builder.Default
  private String appCode = "ADMIN";

  @Column(name = "allocation_id")
  private Long allocationId;

  @Column(name = "allocation_code", length = 100)
  private String allocationCode;

  @Column(name = "allocation_name", length = 500)
  private String allocationName;

  @Column(name = "node_id")
  private Long nodeId;

  @Column(name = "node_code", length = 100)
  private String nodeCode;

  @Column(name = "node_name", length = 500)
  private String nodeName;

  @Column(name = "estimate_item_id")
  private Long estimateItemId;

  @Column(name = "estimate_item_code", length = 100)
  private String estimateItemCode;

  @Column(name = "estimate_item_name", length = 500)
  private String estimateItemName;

  @Column(name = "category_id")
  private Long categoryId;

  @Column(name = "category_code", length = 100)
  private String categoryCode;

  @Column(name = "category_name", length = 250)
  private String categoryName;

  @Column(name = "domain_code", length = 100)
  private String domainCode;

  @Column(name = "domain_name", length = 250)
  private String domainName;

  @Column(name = "domain_group_code", length = 100)
  private String domainGroupCode;

  @Column(name = "domain_group_name", length = 250)
  private String domainGroupName;

  @Column(name = "dynamic_breakdown_json", columnDefinition = "TEXT")
  private String dynamicBreakdownJson;

  @Column(name = "funding_source_code", length = 100)
  private String fundingSourceCode;

  @Column(name = "funding_source_name", length = 250)
  private String fundingSourceName;

  @Column(name = "tx_type", length = 50)
  private String txType;

  @Column(name = "budget_year")
  private Integer budgetYear;

  @Column(name = "target_budget_year")
  private Integer targetBudgetYear;

  @Column(name = "amount", precision = 19, scale = 2)
  private BigDecimal amount;

  @Column(name = "disbursed_amount", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal disbursedAmount = BigDecimal.ZERO;

  @Column(name = "balance_before_amount", precision = 19, scale = 2)
  private BigDecimal balanceBeforeAmount;

  @Column(name = "balance_after_amount", precision = 19, scale = 2)
  private BigDecimal balanceAfterAmount;

  @Column(name = "counterpart_allocation_id")
  private Long counterpartAllocationId;

  @Column(name = "counterpart_allocation_code", length = 100)
  private String counterpartAllocationCode;

  @Column(name = "counterpart_allocation_name", length = 500)
  private String counterpartAllocationName;

  @Column(name = "counterpart_node_id")
  private Long counterpartNodeId;

  @Column(name = "counterpart_node_code", length = 100)
  private String counterpartNodeCode;

  @Column(name = "counterpart_node_name", length = 500)
  private String counterpartNodeName;

  @Column(name = "counterpart_estimate_item_id")
  private Long counterpartEstimateItemId;

  @Column(name = "plan_id")
  private Long planId;

  @Column(name = "plan_code", length = 100)
  private String planCode;

  @Column(name = "plan_name", length = 500)
  private String planName;

  @Column(name = "decision_no", length = 150)
  private String decisionNo;

  @Column(name = "decision_date")
  private LocalDate decisionDate;

  @Column(name = "decision_document_id")
  private Long decisionDocumentId;

  @Column(name = "tx_date")
  private LocalDate txDate;

  @Column(name = "reason", length = 1000)
  private String reason;

  @Column(name = "previous_version")
  private Integer previousVersion;

  @Column(name = "new_version")
  private Integer newVersion;

  @Column(name = "requested_by_person_id")
  private Long requestedByPersonId;

  @Column(name = "requested_by_person_name", length = 250)
  private String requestedByPersonName;

  @Column(name = "approved_by_person_id")
  private Long approvedByPersonId;

  @Column(name = "approved_by_person_name", length = 250)
  private String approvedByPersonName;

  @Column(name = "approved_date")
  private LocalDateTime approvedDate;

  @Column(name = "reject_reason", length = 1000)
  private String rejectReason;

  @Column(name = "approval_status", length = 50)
  private String approvalStatus;

  @Column(name = "wf_instance_id")
  private Long wfInstanceId;

  @Column(name = "wf_process_code", length = 100)
  private String wfProcessCode;

  @Column(name = "attachment_file_ids_json", columnDefinition = "TEXT")
  private String attachmentFileIdsJson;

  @Column(name = "currency_code", length = 10)
  @Builder.Default
  private String currencyCode = "VND";

}
