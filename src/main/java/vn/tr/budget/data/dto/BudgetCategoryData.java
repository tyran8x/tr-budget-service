package vn.tr.budget.data.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.math.BigDecimal;
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

  private String appCode;

  private Long parentId;

  private String parentCode;

  private String parentName;

  private String categoryGroup;

  private Integer budgetYear;

  private String domainCode;

  private String domainGroupCode;

  private String domainGroupName;

  private String previousYearDomainCode;

  private Long nodeId;

  private String nodeCode;

  private String nodeName;

  private String chapterCode;

  private String typeCode;

  private String clauseCode;

  private String groupCode;

  private String subGroupCode;

  private String itemCode;

  private String subItemCode;

  private String autonomyType;

  @JsonAlias("allowCarryOver")
  private Boolean isAllowCarryOver;

  @JsonAlias("annualLimit")
  private BigDecimal annualLimitAmount;

  private Integer levelDepth;

  private Boolean isLeaf;

  private Integer slaDisbursementWarningDays;

  public Boolean getAllowCarryOver() {
    return this.isAllowCarryOver;
  }

  public void setAllowCarryOver(Boolean allowCarryOver) {
    this.isAllowCarryOver = allowCarryOver;
  }
}
