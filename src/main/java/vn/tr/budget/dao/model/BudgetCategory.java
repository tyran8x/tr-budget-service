package vn.tr.budget.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
@SQLDelete(
    sql = "UPDATE budget_category SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetCategory")
public class BudgetCategory extends BaseCommonEntity {

  @Id
  @Column(name = "id", unique = true, nullable = false)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

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

  @Column(name = "clause_code", length = 50)
  private String clauseCode;

  @Column(name = "item_code", length = 50)
  private String itemCode;

  @Column(name = "autonomy_type", length = 50)
  private String autonomyType;

  @Column(name = "allow_carry_over")
  private Boolean allowCarryOver;

  @Column(name = "sla_disbursement_warning_days")
  private Integer slaDisbursementWarningDays;

}
