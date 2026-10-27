package vn.tr.budget.data.criteria;

import java.util.Collection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.web.data.criteria.BaseSearchCriteria;

/**
 * Tiêu chí tìm kiếm cho BudgetAllocation.
 *
 * @author tyran8x
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class BudgetAllocationSearchCriteria extends BaseSearchCriteria {

  private Collection<String> codes;

  private String name;

  private String code;

  private LifecycleStatus status;

  private Integer budgetYear;

  private String periodType;

  private Integer budgetQuarter;

  private Long nodeId;

  private String nodeCode;

  private Long estimateItemId;

  private String itemType;

  private String categoryCode;

  private String workflowStatus;
}
