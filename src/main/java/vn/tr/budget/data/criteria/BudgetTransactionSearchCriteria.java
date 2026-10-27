package vn.tr.budget.data.criteria;

import java.util.Collection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.web.data.criteria.BaseSearchCriteria;

/**
 * Tiêu chí tìm kiếm cho BudgetTransaction.
 *
 * @author tyran8x
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class BudgetTransactionSearchCriteria extends BaseSearchCriteria {

  private Collection<String> codes;

  private String name;

  private String code;

  private LifecycleStatus status;

  private Long allocationId;

  private Long nodeId;

  private String txType;

  private Integer budgetYear;

  private String fundingSourceCode;

  private String approvalStatus;
}
