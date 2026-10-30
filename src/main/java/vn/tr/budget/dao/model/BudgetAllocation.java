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
 * Entity đại diện cho Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ (BudgetAllocation).
 *
 * <p>Mapping: Table {@code budget_allocation}.
 *
 * @author tyran8x
 */
@Entity
@Table(name = "budget_allocation")
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
        "UPDATE budget_allocation SET deleted_at = CURRENT_TIMESTAMP, version = (version + 1) WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetAllocation")
public class BudgetAllocation extends BaseCommonEntity implements AppIsolated {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_code", length = 50, nullable = false)
  @Builder.Default
  private String appCode = "ADMIN";

  @Column(name = "parent_allocation_id")
  private Long parentAllocationId;

  @Column(name = "budget_year")
  private Integer budgetYear;

  @Column(name = "period_type", length = 50)
  private String periodType;

  @Column(name = "budget_quarter")
  private Integer budgetQuarter;

  @Column(name = "budget_month")
  private Integer budgetMonth;

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

  @Column(name = "item_type", length = 50)
  private String itemType;

  @Column(name = "category_id")
  private Long categoryId;

  @Column(name = "category_code", length = 100)
  private String categoryCode;

  @Column(name = "category_name", length = 250)
  private String categoryName;

  @Column(name = "sub_item_code", length = 50)
  private String subItemCode;

  @Column(name = "domain_id")
  private Long domainId;

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

  @Column(name = "decision_no", length = 150)
  private String decisionNo;

  @Column(name = "decision_date")
  private LocalDate decisionDate;

  @Column(name = "decision_issuer", length = 250)
  private String decisionIssuer;

  @Column(name = "decision_document_id")
  private Long decisionDocumentId;

  @Column(name = "linked_plan_id")
  private Long linkedPlanId;

  @Column(name = "linked_plan_code", length = 100)
  private String linkedPlanCode;

  @Column(name = "linked_plan_name", length = 500)
  private String linkedPlanName;

  @Column(name = "linked_plan_item_id")
  private Long linkedPlanItemId;

  @Column(name = "approval_scope", length = 50)
  private String approvalScope;

  @Column(name = "workflow_status", length = 50)
  private String workflowStatus;

  @Column(name = "wf_instance_id")
  private Long wfInstanceId;

  @Column(name = "wf_process_code", length = 100)
  private String wfProcessCode;

  @Column(name = "current_version")
  private Integer currentVersion;

  @Column(name = "proposed_amount", precision = 19, scale = 2)
  private BigDecimal proposedAmount;

  @Column(name = "initial_amount", precision = 19, scale = 2)
  private BigDecimal initialAmount;

  @Column(name = "supplement_amount", precision = 19, scale = 2)
  private BigDecimal supplementAmount;

  @Column(name = "carry_over_in_amount", precision = 19, scale = 2)
  private BigDecimal carryOverInAmount;

  @Column(name = "carry_over_out_amount", precision = 19, scale = 2)
  private BigDecimal carryOverOutAmount;

  @Column(name = "transfer_net_amount", precision = 19, scale = 2)
  private BigDecimal transferNetAmount;

  @Column(name = "reduction_amount", precision = 19, scale = 2)
  private BigDecimal reductionAmount;

  @Column(name = "effective_amount", precision = 19, scale = 2)
  private BigDecimal effectiveAmount;

  @Column(name = "committed_amount", precision = 19, scale = 2)
  private BigDecimal committedAmount;

  @Column(name = "advanced_amount", precision = 19, scale = 2)
  private BigDecimal advancedAmount;

  @Column(name = "disbursed_amount", precision = 19, scale = 2)
  private BigDecimal disbursedAmount;

  @Column(name = "settled_amount", precision = 19, scale = 2)
  private BigDecimal settledAmount;

  @Column(name = "available_amount", precision = 19, scale = 2)
  private BigDecimal availableAmount;

  @Column(name = "expired_cancelled_amount", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal expiredCancelledAmount = BigDecimal.ZERO;

  @Column(name = "estimate_year_plus_1", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal estimateYearPlus1 = BigDecimal.ZERO;

  @Column(name = "estimate_year_plus_2", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal estimateYearPlus2 = BigDecimal.ZERO;

  @Column(name = "disbursement_rate_percent", precision = 5, scale = 2)
  private BigDecimal disbursementRatePercent;

  @Column(name = "submitted_by_person_id")
  private Long submittedByPersonId;

  @Column(name = "submitted_by_person_name", length = 250)
  private String submittedByPersonName;

  @Column(name = "submitted_date")
  private LocalDateTime submittedDate;

  @Column(name = "approved_by_person_id")
  private Long approvedByPersonId;

  @Column(name = "approved_by_person_name", length = 250)
  private String approvedByPersonName;

  @Column(name = "approved_date")
  private LocalDateTime approvedDate;

  @Column(name = "review_comment", length = 1000)
  private String reviewComment;

  @Column(name = "attachment_file_ids_json", columnDefinition = "TEXT")
  private String attachmentFileIdsJson;

  @Column(name = "location_id")
  private Long locationId;

  @Column(name = "currency_code", length = 10)
  @Builder.Default
  private String currencyCode = "VND";

}
