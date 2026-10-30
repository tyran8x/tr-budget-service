package vn.tr.budget.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
@EntityListeners(AppIsolatedListener.class)
@Filter(name = "appCodeFilter", condition = "app_code = :appCode")
@SQLDelete(
    sql =
        "UPDATE budget_unit_config SET deleted_at = CURRENT_TIMESTAMP, version = (version + 1) WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetUnitConfig")
public class BudgetUnitConfig extends BaseCommonEntity implements AppIsolated {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_code", length = 50, nullable = false)
  @Builder.Default
  private String appCode = "ADMIN";

  @Column(name = "node_id")
  private Long nodeId;

  @Column(name = "node_code", length = 100)
  private String nodeCode;

  @Column(name = "node_name", length = 500)
  private String nodeName;

  @Column(name = "parent_node_id")
  private Long parentNodeId;

  @Column(name = "unit_level")
  @Builder.Default
  private Integer unitLevel = 3;

  @Column(name = "unit_type", length = 50)
  private String unitType;

  @Column(name = "recurrent_self_guarantee_rate", precision = 5, scale = 2)
  @Builder.Default
  private BigDecimal recurrentSelfGuaranteeRate = BigDecimal.ZERO;

  @Column(name = "default_chapter_code", length = 20)
  private String defaultChapterCode;

  @Column(name = "treasury_relation_code", length = 20)
  private String treasuryRelationCode;

  @Column(name = "treasury_account_no", length = 100)
  private String treasuryAccountNo;

  @Column(name = "treasury_branch_name", length = 250)
  private String treasuryBranchName;

  @Column(name = "pay_bank_account_id")
  private Long payBankAccountId;

  @Column(name = "bank_account_no", length = 50)
  private String bankAccountNo;

  @Column(name = "bank_name", length = 255)
  private String bankName;

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

  @Column(name = "allowed_category_codes_json", columnDefinition = "TEXT")
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
