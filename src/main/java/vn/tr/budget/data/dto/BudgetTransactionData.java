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
 * DTO dữ liệu cho BudgetTransaction.
 *
 * @author tyran8x
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BudgetTransactionData extends BaseCommonData {

  private String appCode;

  private Long allocationId;

  private String allocationCode;

  private String allocationName;

  private Long nodeId;

  private String nodeCode;

  private String nodeName;

  private Long estimateItemId;

  private String estimateItemCode;

  private String estimateItemName;

  private Long categoryId;

  private String categoryCode;

  private String categoryName;

  private String domainCode;

  private String domainName;

  private String domainGroupCode;

  private String domainGroupName;

  private String dynamicBreakdownJson;

  private String fundingSourceCode;

  private String fundingSourceName;

  private String txType;

  private Integer budgetYear;

  private Integer targetBudgetYear;

  private BigDecimal amount;

  private BigDecimal disbursedAmount;

  private BigDecimal balanceBeforeAmount;

  private BigDecimal balanceAfterAmount;

  private Long counterpartAllocationId;

  private String counterpartAllocationCode;

  private String counterpartAllocationName;

  private Long counterpartNodeId;

  private String counterpartNodeCode;

  private String counterpartNodeName;

  private Long counterpartEstimateItemId;

  private Long planId;

  private String planCode;

  private String planName;

  private String decisionNo;

  private LocalDate decisionDate;

  private Long decisionDocumentId;

  private LocalDate txDate;

  private String reason;

  private Integer previousVersion;

  private Integer newVersion;

  private Long requestedByPersonId;

  private String requestedByPersonName;

  private Long approvedByPersonId;

  private String approvedByPersonName;

  private LocalDateTime approvedDate;

  private String rejectReason;

  private String approvalStatus;

  private Long wfInstanceId;

  private String wfProcessCode;

  private String attachmentFileIdsJson;

  private String currencyCode;
}
