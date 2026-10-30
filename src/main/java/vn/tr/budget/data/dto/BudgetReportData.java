package vn.tr.budget.data.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vn.tr.common.web.data.dto.BaseCommonData;

/**
 * DTO dữ liệu cho BudgetReport.
 *
 * @author tyran8x
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BudgetReportData extends BaseCommonData {

  private String appCode;

  private Integer budgetYear;

  private String reportPeriod;

  private Integer periodNumber;

  private String periodLabel;

  private String reportScope;

  private Long nodeId;

  private String nodeCode;

  private String nodeName;

  private LocalDate reportDate;

  private BigDecimal totalRevenueEstimate;

  private BigDecimal totalRevenueActual;

  private BigDecimal totalRevenueCumulative;

  private BigDecimal totalExpenditureInitial;

  private BigDecimal totalExpenditureAdjustment;

  private BigDecimal totalExpenditureEffective;

  private BigDecimal totalExpenditureCommitted;

  private BigDecimal totalExpenditureAdvanced;

  private BigDecimal totalExpenditureActual;

  private BigDecimal totalExpenditureSettled;

  private BigDecimal totalExpenditureCumulative;

  private BigDecimal totalCarryOverAmount;

  private BigDecimal totalRemainingBalance;

  private BigDecimal totalExpiredCancelled;

  private BigDecimal totalSurplusOrSaving;

  private BigDecimal disbursementRatePercent;

  private String detailLinesJson;

  private Long preparedByPersonId;

  private String preparedByPersonName;

  private Long submittedByPersonId;

  private String submittedByPersonName;

  private LocalDateTime submittedDate;

  private Long reviewedByPersonId;

  private String reviewedByPersonName;

  private Long approvedByPersonId;

  private String approvedByPersonName;

  private LocalDateTime approvedDate;

  private String reviewComment;

  private String notes;

  private String workflowStatus;

  private Long linkedRptSubmissionId;

  private Long wfInstanceId;

  private String wfProcessCode;

  private Long decisionDocumentId;

  private Long docSignatureId;

  private String attachmentFileIdsJson;
}
