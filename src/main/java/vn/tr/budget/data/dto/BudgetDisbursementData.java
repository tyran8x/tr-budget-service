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
 * DTO dữ liệu cho BudgetDisbursement.
 *
 * @author tyran8x
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BudgetDisbursementData extends BaseCommonData {

  private String appCode;

  private Long parentDisbursementId;

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

  private String subItemCode;

  private Integer budgetYear;

  private Integer budgetQuarter;

  private Integer budgetMonth;

  private String disbursementType;

  private BigDecimal requestedAmount;

  private BigDecimal approvedAmount;

  private BigDecimal amount;

  private BigDecimal settledAmount;

  private BigDecimal taxAmount;

  private BigDecimal netPaymentAmount;

  private Long beneficiaryEnterpriseId;

  private String beneficiaryName;

  private String beneficiaryBankAccount;

  private String beneficiaryBankName;

  private String beneficiaryTaxCode;

  private String contractNo;

  private LocalDate contractDate;

  private String invoiceNo;

  private LocalDate invoiceDate;

  private String voucherNo;

  private LocalDate voucherDate;

  private String treasuryRefCode;

  private Long linkedPlanId;

  private String linkedPlanCode;

  private Long linkedPlanItemId;

  private String linkedPlanItemName;

  private Long linkedTaskProjectId;

  private Long linkedWorkItemId;

  private Long payTransactionId;

  private Long payEReceiptId;

  private String paymentTxCode;

  private String paymentStatus;

  private LocalDate slaDueDate;

  private Long requestedByPersonId;

  private String requestedByPersonName;

  private Long approvedByPersonId;

  private String approvedByPersonName;

  private LocalDateTime approvedDate;

  private LocalDateTime settledDate;

  private String rejectReason;

  private String approvalStatus;

  private Long wfInstanceId;

  private String wfProcessCode;

  private Long decisionDocumentId;

  private String attachmentFileIdsJson;

  private Long locationId;

  private String currencyCode;
}
