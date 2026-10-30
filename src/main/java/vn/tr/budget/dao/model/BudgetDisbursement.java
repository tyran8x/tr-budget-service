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
 * Entity đại diện cho Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán (BudgetDisbursement).
 *
 * <p>Mapping: Table {@code budget_disbursement}.
 *
 * @author tyran8x
 */
@Entity
@Table(name = "budget_disbursement")
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
        "UPDATE budget_disbursement SET deleted_at = CURRENT_TIMESTAMP, version = (version + 1) WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetDisbursement")
public class BudgetDisbursement extends BaseCommonEntity implements AppIsolated {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_code", length = 50, nullable = false)
  @Builder.Default
  private String appCode = "ADMIN";

  @Column(name = "parent_disbursement_id")
  private Long parentDisbursementId;

  @Column(name = "allocation_id")
  private Long allocationId;

  @Column(name = "allocation_code", length = 100)
  private String allocationCode;

  @Column(name = "allocation_name", length = 500)
  private String allocationName;

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

  @Column(name = "category_id")
  private Long categoryId;

  @Column(name = "category_code", length = 100)
  private String categoryCode;

  @Column(name = "category_name", length = 250)
  private String categoryName;

  @Column(name = "sub_item_code", length = 50)
  private String subItemCode;

  @Column(name = "budget_year")
  private Integer budgetYear;

  @Column(name = "budget_quarter")
  private Integer budgetQuarter;

  @Column(name = "budget_month")
  private Integer budgetMonth;

  @Column(name = "disbursement_type", length = 50)
  private String disbursementType;

  @Column(name = "requested_amount", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal requestedAmount = BigDecimal.ZERO;

  @Column(name = "approved_amount", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal approvedAmount = BigDecimal.ZERO;

  @Column(name = "amount", precision = 19, scale = 2)
  private BigDecimal amount;

  @Column(name = "settled_amount", precision = 19, scale = 2)
  @Builder.Default
  private BigDecimal settledAmount = BigDecimal.ZERO;

  @Column(name = "tax_amount", precision = 19, scale = 2)
  private BigDecimal taxAmount;

  @Column(name = "net_payment_amount", precision = 19, scale = 2)
  private BigDecimal netPaymentAmount;

  @Column(name = "beneficiary_enterprise_id")
  private Long beneficiaryEnterpriseId;

  @Column(name = "beneficiary_name", length = 250)
  private String beneficiaryName;

  @Column(name = "beneficiary_bank_account", length = 100)
  private String beneficiaryBankAccount;

  @Column(name = "beneficiary_bank_name", length = 250)
  private String beneficiaryBankName;

  @Column(name = "beneficiary_tax_code", length = 50)
  private String beneficiaryTaxCode;

  @Column(name = "contract_no", length = 150)
  private String contractNo;

  @Column(name = "contract_date")
  private LocalDate contractDate;

  @Column(name = "invoice_no", length = 100)
  private String invoiceNo;

  @Column(name = "invoice_date")
  private LocalDate invoiceDate;

  @Column(name = "voucher_no", length = 150)
  private String voucherNo;

  @Column(name = "voucher_date")
  private LocalDate voucherDate;

  @Column(name = "treasury_ref_code", length = 150)
  private String treasuryRefCode;

  @Column(name = "linked_plan_id")
  private Long linkedPlanId;

  @Column(name = "linked_plan_code", length = 100)
  private String linkedPlanCode;

  @Column(name = "linked_plan_item_id")
  private Long linkedPlanItemId;

  @Column(name = "linked_plan_item_name", length = 500)
  private String linkedPlanItemName;

  @Column(name = "linked_task_project_id")
  private Long linkedTaskProjectId;

  @Column(name = "linked_work_item_id")
  private Long linkedWorkItemId;

  @Column(name = "pay_transaction_id")
  private Long payTransactionId;

  @Column(name = "pay_e_receipt_id")
  private Long payEReceiptId;

  @Column(name = "payment_tx_code", length = 100)
  private String paymentTxCode;

  @Column(name = "payment_status", length = 50)
  private String paymentStatus;

  @Column(name = "sla_due_date")
  private LocalDate slaDueDate;

  @Column(name = "requested_by_person_id")
  private Long requestedByPersonId;

  @Column(name = "requested_by_person_name", length = 250)
  private String requestedByPersonName;

  @Column(name = "approved_by_person_id")
  private Long approvedByPersonId;

  @Column(name = "approved_by_person_name", length = 250)
  private String approvedByPersonName;

  @Column(name = "approved_date")
  private LocalDateTime approvedDate;

  @Column(name = "settled_date")
  private LocalDateTime settledDate;

  @Column(name = "reject_reason", length = 1000)
  private String rejectReason;

  @Column(name = "approval_status", length = 50)
  private String approvalStatus;

  @Column(name = "wf_instance_id")
  private Long wfInstanceId;

  @Column(name = "wf_process_code", length = 100)
  private String wfProcessCode;

  @Column(name = "decision_document_id")
  private Long decisionDocumentId;

  @Column(name = "attachment_file_ids_json", columnDefinition = "TEXT")
  private String attachmentFileIdsJson;

  @Column(name = "location_id")
  private Long locationId;

  @Column(name = "currency_code", length = 10)
  @Builder.Default
  private String currencyCode = "VND";

}
