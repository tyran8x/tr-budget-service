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
 * Entity đại diện cho Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp (BudgetEstimateItem).
 *
 * <p>Mapping: Table {@code budget_estimate_item}.
 *
 * @author tyran8x
 */
@Entity
@Table(name = "budget_estimate_item")
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
        "UPDATE budget_estimate_item SET deleted_at = CURRENT_TIMESTAMP, version = (version + 1) WHERE id = ? AND version = ?")
@SQLRestriction(value = "deleted_at IS NULL")
@TypeName("BudgetEstimateItem")
public class BudgetEstimateItem extends BaseCommonEntity implements AppIsolated {

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

  @Column(name = "parent_name", length = 500)
  private String parentName;

  @Column(name = "item_type", length = 50)
  private String itemType;

  @Column(name = "numbering_style", length = 50)
  private String numberingStyle;

  @Column(name = "level_depth")
  private Integer levelDepth;

  @Column(name = "is_header")
  private Boolean isHeader;

  @Column(name = "formula", length = 250)
  private String formula;

  @Column(name = "category_id")
  private Long categoryId;

  @Column(name = "category_code", length = 100)
  private String categoryCode;

  @Column(name = "category_name", length = 250)
  private String categoryName;

  @Column(name = "sub_item_code", length = 50)
  private String subItemCode;

  @Column(name = "funding_source_code", length = 50)
  private String fundingSourceCode;

  @Column(name = "autonomy_type", length = 50)
  private String autonomyType;

  @Column(name = "unit_of_measure", length = 50)
  private String unitOfMeasure;

  @Column(name = "norm_standard_amount", precision = 19, scale = 2)
  private BigDecimal normStandardAmount;

  @Column(name = "is_require_voucher")
  @Builder.Default
  private Boolean isRequireVoucher = Boolean.TRUE;

  public Boolean getRequireVoucher() {
    return this.isRequireVoucher;
  }

  public void setRequireVoucher(Boolean requireVoucher) {
    this.isRequireVoucher = requireVoucher;
  }
}
