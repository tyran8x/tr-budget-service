package vn.tr.budget.data.criteria;

import java.util.Collection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.web.data.criteria.BaseSearchCriteria;

/**
 * Tiêu chí tìm kiếm cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class BudgetEstimateItemSearchCriteria extends BaseSearchCriteria {

  private Collection<String> codes;

  private String name;

  private String code;

  private LifecycleStatus status;

  private Long parentId;

  private String itemType;

  private Long categoryId;

  private String categoryCode;

  private Boolean isHeader;
}
