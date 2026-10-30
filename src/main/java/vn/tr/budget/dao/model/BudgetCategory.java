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
 * Entity đại diện cho Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí (BudgetCategory).
 *
 * <p>Mapping: Table {@code budget_category}.
 *
 * @author tyran8x
 */
@Entity
@Table(name = "budget_category")
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
        "UPDATE budget_category SET deleted_at = CURRENT_TIMESTAMP, version = (version + 1) WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetCategory")
public class BudgetCategory extends BaseCommonEntity implements AppIsolated {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_code", length = 50, nullable = false)
  @Builder.Default
  private String appCode = "ADMIN";

  @Column(name = "parent_id")
  private Long parentId;

  @Column(name = "parent_code", length = 100)
  private String parentCode;

  @Column(name = "parent_name", length = 250)
  private String parentName;

  @Column(name = "category_group", length = 50)
  private String categoryGroup;

  @Column(name = "chapter_code", length = 50)
  private String chapterCode;

  @Column(name = "type_code", length = 50)
  private String typeCode;

  @Column(name = "clause_code", length = 50)
  private String clauseCode;

  @Column(name = "group_code", length = 50)
  private String groupCode;

  @Column(name = "sub_group_code", length = 50)
  private String subGroupCode;

  @Column(name = "item_code", length = 50)
  private String itemCode;

  @Column(name = "sub_item_code", length = 50)
  private String subItemCode;

  @Column(name = "autonomy_type", length = 50)
  private String autonomyType;

  @Column(name = "is_allow_carry_over")
  @Builder.Default
  private Boolean isAllowCarryOver = Boolean.TRUE;

  @Column(name = "annual_limit_amount", precision = 19, scale = 2)
  private BigDecimal annualLimitAmount;

  @Column(name = "level_depth")
  @Builder.Default
  private Integer levelDepth = 1;

  @Column(name = "is_leaf")
  @Builder.Default
  private Boolean isLeaf = Boolean.TRUE;

  @Column(name = "sla_disbursement_warning_days")
  private Integer slaDisbursementWarningDays;

  public Boolean getAllowCarryOver() {
    return this.isAllowCarryOver;
  }

  public void setAllowCarryOver(Boolean allowCarryOver) {
    this.isAllowCarryOver = allowCarryOver;
  }
}
