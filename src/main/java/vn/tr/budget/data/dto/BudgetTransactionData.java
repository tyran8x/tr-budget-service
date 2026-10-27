package vn.tr.budget.data.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
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

  private Long allocationId;

  private String allocationCode;

  private String allocationName;

  private Long nodeId;

  private String nodeCode;

  private String nodeName;

  private Long estimateItemId;

  private String estimateItemCode;

  private String estimateItemName;

  private String categoryCode;

  private String fundingSourceCode;

  private String fundingSourceName;

  private String txType;

  private Integer budgetYear;

  private Integer targetBudgetYear;

  private BigDecimal amount;

  private Long counterpartAllocationId;

  private String counterpartAllocationCode;

  private String counterpartAllocationName;

  private Long counterpartNodeId;

  private String counterpartNodeName;

  private String decisionNo;

  private LocalDate decisionDate;

  private LocalDate txDate;

  private String reason;

  private Integer previousVersion;

  private Integer newVersion;

  private Long requestedByPersonId;

  private String requestedByPersonName;

  private Long approvedByPersonId;

  private String approvedByPersonName;

  private String approvalStatus;
}
