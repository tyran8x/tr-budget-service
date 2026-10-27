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
 * Entity đại diện cho Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ (BudgetReport).
 *
 * <p>Mapping: Table {@code budget_report}.
 *
 * @author tyran8x
 */
@Entity
@Table(name = "budget_report")
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@SQLDelete(
    sql = "UPDATE budget_report SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetReport")
public class BudgetReport extends BaseCommonEntity {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "budget_year")
  private Integer budgetYear;

  @Column(name = "report_period", length = 50)
  private String reportPeriod;

  @Column(name = "period_number")
  private Integer periodNumber;

  @Column(name = "report_scope", length = 50)
  private String reportScope;

  @Column(name = "node_id")
  private Long nodeId;

  @Column(name = "node_code", length = 100)
  private String nodeCode;

  @Column(name = "node_name", length = 500)
  private String nodeName;

  @Column(name = "report_date")
  private LocalDate reportDate;

  @Column(name = "total_revenue_estimate", precision = 19, scale = 2)
  private BigDecimal totalRevenueEstimate;

  @Column(name = "total_revenue_actual", precision = 19, scale = 2)
  private BigDecimal totalRevenueActual;

  @Column(name = "total_revenue_cumulative", precision = 19, scale = 2)
  private BigDecimal totalRevenueCumulative;

  @Column(name = "total_expenditure_initial", precision = 19, scale = 2)
  private BigDecimal totalExpenditureInitial;

  @Column(name = "total_expenditure_effective", precision = 19, scale = 2)
  private BigDecimal totalExpenditureEffective;

  @Column(name = "total_expenditure_actual", precision = 19, scale = 2)
  private BigDecimal totalExpenditureActual;

  @Column(name = "total_expenditure_cumulative", precision = 19, scale = 2)
  private BigDecimal totalExpenditureCumulative;

  @Column(name = "total_carry_over_amount", precision = 19, scale = 2)
  private BigDecimal totalCarryOverAmount;

  @Column(name = "total_surplus_or_saving", precision = 19, scale = 2)
  private BigDecimal totalSurplusOrSaving;

  @Column(name = "disbursement_rate_percent", precision = 5, scale = 2)
  private BigDecimal disbursementRatePercent;

  @Column(name = "detail_lines_json", length = 4000)
  private String detailLinesJson;

  @Column(name = "submitted_by_person_id")
  private Long submittedByPersonId;

  @Column(name = "submitted_by_person_name", length = 250)
  private String submittedByPersonName;

  @Column(name = "reviewed_by_person_id")
  private Long reviewedByPersonId;

  @Column(name = "reviewed_by_person_name", length = 250)
  private String reviewedByPersonName;

  @Column(name = "workflow_status", length = 50)
  private String workflowStatus;

}
