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
@EntityListeners(AppIsolatedListener.class)
@Filter(name = "appCodeFilter", condition = "app_code = :appCode")
@SQLDelete(
    sql =
        "UPDATE budget_report SET deleted_at = CURRENT_TIMESTAMP, version = (version + 1) WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetReport")
public class BudgetReport extends BaseCommonEntity implements AppIsolated {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_code", length = 50, nullable = false)
  @Builder.Default
  private String appCode = "ADMIN";

  @Column(name = "budget_year")
  private Integer budgetYear;

  @Column(name = "report_period", length = 50)
  private String reportPeriod;

  @Column(name = "period_number")
  private Integer periodNumber;

  @Column(name = "period_label", length = 100)
  private String periodLabel;

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

  @Column(name = "total_expenditure_adjustment", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal totalExpenditureAdjustment = BigDecimal.ZERO;

  @Column(name = "total_expenditure_effective", precision = 19, scale = 2)
  private BigDecimal totalExpenditureEffective;

  @Column(name = "total_expenditure_committed", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal totalExpenditureCommitted = BigDecimal.ZERO;

  @Column(name = "total_expenditure_advanced", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal totalExpenditureAdvanced = BigDecimal.ZERO;

  @Column(name = "total_expenditure_actual", precision = 19, scale = 2)
  private BigDecimal totalExpenditureActual;

  @Column(name = "total_expenditure_settled", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal totalExpenditureSettled = BigDecimal.ZERO;

  @Column(name = "total_expenditure_cumulative", precision = 19, scale = 2)
  private BigDecimal totalExpenditureCumulative;

  @Column(name = "total_carry_over_amount", precision = 19, scale = 2)
  private BigDecimal totalCarryOverAmount;

  @Column(name = "total_remaining_balance", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal totalRemainingBalance = BigDecimal.ZERO;

  @Column(name = "total_expired_cancelled", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal totalExpiredCancelled = BigDecimal.ZERO;

  @Column(name = "total_surplus_or_saving", precision = 19, scale = 2)
  private BigDecimal totalSurplusOrSaving;

  @Column(name = "disbursement_rate_percent", precision = 5, scale = 2)
  private BigDecimal disbursementRatePercent;

  @Column(name = "detail_lines_json", columnDefinition = "TEXT")
  private String detailLinesJson;

  @Column(name = "prepared_by_person_id")
  private Long preparedByPersonId;

  @Column(name = "prepared_by_person_name", length = 250)
  private String preparedByPersonName;

  @Column(name = "submitted_by_person_id")
  private Long submittedByPersonId;

  @Column(name = "submitted_by_person_name", length = 250)
  private String submittedByPersonName;

  @Column(name = "submitted_date")
  private LocalDateTime submittedDate;

  @Column(name = "reviewed_by_person_id")
  private Long reviewedByPersonId;

  @Column(name = "reviewed_by_person_name", length = 250)
  private String reviewedByPersonName;

  @Column(name = "approved_by_person_id")
  private Long approvedByPersonId;

  @Column(name = "approved_by_person_name", length = 250)
  private String approvedByPersonName;

  @Column(name = "approved_date")
  private LocalDateTime approvedDate;

  @Column(name = "review_comment", length = 1000)
  private String reviewComment;

  @Column(name = "notes", columnDefinition = "TEXT")
  private String notes;

  @Column(name = "workflow_status", length = 50)
  private String workflowStatus;

  @Column(name = "linked_rpt_submission_id")
  private Long linkedRptSubmissionId;

  @Column(name = "wf_instance_id")
  private Long wfInstanceId;

  @Column(name = "wf_process_code", length = 100)
  private String wfProcessCode;

  @Column(name = "decision_document_id")
  private Long decisionDocumentId;

  @Column(name = "doc_signature_id")
  private Long docSignatureId;

  @Column(name = "attachment_file_ids_json", columnDefinition = "TEXT")
  private String attachmentFileIdsJson;

}
