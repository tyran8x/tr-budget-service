package vn.tr.budget.data.dto;

import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vn.tr.common.web.data.dto.BaseCommonData;

/**
 * DTO dữ liệu cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BudgetEstimateItemData extends BaseCommonData {

  private Long parentId;

  private String parentCode;

  private String parentName;

  private String itemType;

  private String numberingStyle;

  private Integer levelDepth;

  private Boolean isHeader;

  private String formula;

  private Long categoryId;

  private String categoryCode;

  private String categoryName;

  private String unitOfMeasure;

  private BigDecimal normStandardAmount;

  private Boolean requireVoucher;
}
