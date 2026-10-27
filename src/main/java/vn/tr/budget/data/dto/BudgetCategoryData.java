package vn.tr.budget.data.dto;


import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vn.tr.common.web.data.dto.BaseCommonData;

/**
 * DTO dữ liệu cho BudgetCategory.
 *
 * @author tyran8x
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BudgetCategoryData extends BaseCommonData {

  private Long parentId;

  private String parentCode;

  private String parentName;

  private String categoryGroup;

  private String chapterCode;

  private String clauseCode;

  private String itemCode;

  private String autonomyType;

  private Boolean allowCarryOver;

  private Integer slaDisbursementWarningDays;
}
