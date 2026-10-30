package vn.tr.budget.data.criteria;

import java.util.Collection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.web.data.criteria.BaseSearchCriteria;

/**
 * Tiêu chí tìm kiếm cho BudgetCategory.
 *
 * @author tyran8x
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class BudgetCategorySearchCriteria extends BaseSearchCriteria {

  private Collection<String> codes;

  private String name;

  private String code;

  private LifecycleStatus status;

  private Long parentId;

  private String categoryGroup;

  private Integer budgetYear;

  private String domainCode;

  private String domainGroupCode;

  private Long nodeId;

  private String chapterCode;

  private String clauseCode;

  private String autonomyType;

  private Boolean allowCarryOver;
}
