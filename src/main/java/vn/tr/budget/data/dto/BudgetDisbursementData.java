package vn.tr.budget.data.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vn.tr.common.web.data.dto.BaseCommonData;

/**
 * DTO dữ liệu cho BudgetDisbursement.
 *
 * @author tyran8x
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BudgetDisbursementData extends BaseCommonData {

  private Long allocationId;

  private String allocationCode;

  private String allocationName;

  private Long nodeId;

  private String nodeCode;

  private String nodeName;

  private Long estimateItemId;

  private String estimateItemCode;

  private String estimateItemName;

  private Integer budgetYear;

  private Integer budgetQuarter;

  private Integer budgetMonth;

  private String disbursementType;

  private BigDecimal amount;

  private BigDecimal taxAmount;

  private BigDecimal netPaymentAmount;

  private String beneficiaryName;

  private String beneficiaryBankAccount;

  private String beneficiaryBankName;

  private String beneficiaryTaxCode;

  private String contractNo;

  private LocalDate contractDate;

  private String voucherNo;

  private LocalDate voucherDate;

  private String treasuryRefCode;

  private Long linkedPlanId;

  private String linkedPlanCode;

  private Long linkedPlanItemId;

  private String linkedPlanItemName;

  private String paymentTxCode;

  private LocalDate slaDueDate;

  private Long requestedByPersonId;

  private String requestedByPersonName;

  private Long approvedByPersonId;

  private String approvedByPersonName;

  private String approvalStatus;
}
