package vn.tr.budget.data.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vn.tr.common.web.data.dto.BaseCommonData;

/**
 * DTO dữ liệu cho BudgetAllocation.
 *
 * @author tyran8x
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BudgetAllocationData extends BaseCommonData {

  private Integer budgetYear;

  private String periodType;

  private Integer budgetQuarter;

  private Integer budgetMonth;

  private Long nodeId;

  private String nodeCode;

  private String nodeName;

  private Long estimateItemId;

  private String estimateItemCode;

  private String estimateItemName;

  private String itemType;

  private Long categoryId;

  private String categoryCode;

  private String categoryName;

  private String fundingSourceCode;

  private String fundingSourceName;

  private String decisionNo;

  private LocalDate decisionDate;

  private String decisionIssuer;

  private Long linkedPlanId;

  private String linkedPlanCode;

  private String linkedPlanName;

  private String approvalScope;

  private String workflowStatus;

  private Integer currentVersion;

  private BigDecimal proposedAmount;

  private BigDecimal initialAmount;

  private BigDecimal supplementAmount;

  private BigDecimal carryOverInAmount;

  private BigDecimal carryOverOutAmount;

  private BigDecimal transferNetAmount;

  private BigDecimal reductionAmount;

  private BigDecimal effectiveAmount;

  private BigDecimal committedAmount;

  private BigDecimal advancedAmount;

  private BigDecimal disbursedAmount;

  private BigDecimal settledAmount;

  private BigDecimal availableAmount;

  private BigDecimal disbursementRatePercent;
}
