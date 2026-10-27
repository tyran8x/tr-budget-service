package vn.tr.budget.data.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
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

  private Integer budgetYear;

  private String reportPeriod;

  private Integer periodNumber;

  private String reportScope;

  private Long nodeId;

  private String nodeCode;

  private String nodeName;

  private LocalDate reportDate;

  private BigDecimal totalRevenueEstimate;

  private BigDecimal totalRevenueActual;

  private BigDecimal totalRevenueCumulative;

  private BigDecimal totalExpenditureInitial;

  private BigDecimal totalExpenditureEffective;

  private BigDecimal totalExpenditureActual;

  private BigDecimal totalExpenditureCumulative;

  private BigDecimal totalCarryOverAmount;

  private BigDecimal totalSurplusOrSaving;

  private BigDecimal disbursementRatePercent;

  private String detailLinesJson;

  private Long submittedByPersonId;

  private String submittedByPersonName;

  private Long reviewedByPersonId;

  private String reviewedByPersonName;

  private String workflowStatus;
}
