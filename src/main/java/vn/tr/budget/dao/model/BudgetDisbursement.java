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
@SQLDelete(
    sql = "UPDATE budget_disbursement SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetDisbursement")
public class BudgetDisbursement extends BaseCommonEntity {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

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

  @Column(name = "budget_year")
  private Integer budgetYear;

  @Column(name = "budget_quarter")
  private Integer budgetQuarter;

  @Column(name = "budget_month")
  private Integer budgetMonth;

  @Column(name = "disbursement_type", length = 50)
  private String disbursementType;

  @Column(name = "amount", precision = 19, scale = 2)
  private BigDecimal amount;

  @Column(name = "tax_amount", precision = 19, scale = 2)
  private BigDecimal taxAmount;

  @Column(name = "net_payment_amount", precision = 19, scale = 2)
  private BigDecimal netPaymentAmount;

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

  @Column(name = "payment_tx_code", length = 100)
  private String paymentTxCode;

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

  @Column(name = "approval_status", length = 50)
  private String approvalStatus;

}
