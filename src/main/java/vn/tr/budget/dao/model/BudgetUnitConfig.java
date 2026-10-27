package vn.tr.budget.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
 * Entity đại diện cho Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính (BudgetUnitConfig).
 *
 * <p>Mapping: Table {@code budget_unit_config}.
 *
 * @author tyran8x
 */
@Entity
@Table(name = "budget_unit_config")
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@SQLDelete(
    sql = "UPDATE budget_unit_config SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetUnitConfig")
public class BudgetUnitConfig extends BaseCommonEntity {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "node_id")
  private Long nodeId;

  @Column(name = "node_code", length = 100)
  private String nodeCode;

  @Column(name = "node_name", length = 500)
  private String nodeName;

  @Column(name = "unit_type", length = 50)
  private String unitType;

  @Column(name = "treasury_account_no", length = 100)
  private String treasuryAccountNo;

  @Column(name = "treasury_branch_name", length = 250)
  private String treasuryBranchName;

  @Column(name = "tax_code", length = 50)
  private String taxCode;

  @Column(name = "can_self_allocate")
  private Boolean canSelfAllocate;

  @Column(name = "can_self_transfer")
  private Boolean canSelfTransfer;

  @Column(name = "can_carry_over")
  private Boolean canCarryOver;

  @Column(name = "self_approve_limit_amount", precision = 19, scale = 2)
  private BigDecimal selfApproveLimitAmount;

  @Column(name = "allowed_category_codes_json", length = 1000)
  private String allowedCategoryCodesJson;

  @Column(name = "chief_accountant_person_id")
  private Long chiefAccountantPersonId;

  @Column(name = "chief_accountant_name", length = 250)
  private String chiefAccountantName;

  @Column(name = "approver_person_id")
  private Long approverPersonId;

  @Column(name = "approver_person_name", length = 250)
  private String approverPersonName;

}
