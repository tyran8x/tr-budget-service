package vn.tr.budget.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.javers.core.metamodel.annotation.TypeName;
import vn.tr.common.jpa.entity.BaseCommonEntity;

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
@SQLDelete(
    sql = "UPDATE budget_allocation SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetAllocation")
public class BudgetAllocation extends BaseCommonEntity {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

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

  @Column(name = "linked_plan_id")
  private Long linkedPlanId;

  @Column(name = "linked_plan_code", length = 100)
  private String linkedPlanCode;

  @Column(name = "linked_plan_name", length = 500)
  private String linkedPlanName;

  @Column(name = "approval_scope", length = 50)
  private String approvalScope;

  @Column(name = "workflow_status", length = 50)
  private String workflowStatus;

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

  @Column(name = "disbursement_rate_percent", precision = 5, scale = 2)
  private BigDecimal disbursementRatePercent;

}
