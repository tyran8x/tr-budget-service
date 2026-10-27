package vn.tr.budget.dao.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.data.criteria.BudgetAllocationSearchCriteria;
import vn.tr.common.jpa.helper.CriteriaBuilderHelper;

/**
 * JPA Specification cho BudgetAllocation.
 *
 * @author tyran8x
 */
public final class BudgetAllocationSpecifications {

  private BudgetAllocationSpecifications() {}

  public static Specification<BudgetAllocation> quickSearch(final BudgetAllocationSearchCriteria criteria) {
    return (root, query, cb) -> {
      if (criteria == null) {
        return null;
      }
      Predicate textSearchCondition =
          CriteriaBuilderHelper.createOrUnaccentedLike(
              cb, root, criteria.getSearch(), "name", "code", "nodeCode", "nodeName", "estimateItemName", "categoryName", "decisionNo");
      Predicate statusCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "status", criteria.getStatus());
      Predicate idsCondition = CriteriaBuilderHelper.createIn(cb, root, "id", criteria.getIds());
      Predicate codesCondition =
          CriteriaBuilderHelper.createIn(cb, root, "code", criteria.getCodes());
      Predicate budgetYearCondition = CriteriaBuilderHelper.createEquals(cb, root, "budgetYear", criteria.getBudgetYear());
      Predicate periodTypeCondition = CriteriaBuilderHelper.createEquals(cb, root, "periodType", criteria.getPeriodType());
      Predicate budgetQuarterCondition = CriteriaBuilderHelper.createEquals(cb, root, "budgetQuarter", criteria.getBudgetQuarter());
      Predicate nodeIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "nodeId", criteria.getNodeId());
      Predicate nodeCodeCondition = CriteriaBuilderHelper.createEquals(cb, root, "nodeCode", criteria.getNodeCode());
      Predicate estimateItemIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "estimateItemId", criteria.getEstimateItemId());
      Predicate itemTypeCondition = CriteriaBuilderHelper.createEquals(cb, root, "itemType", criteria.getItemType());
      Predicate categoryCodeCondition = CriteriaBuilderHelper.createEquals(cb, root, "categoryCode", criteria.getCategoryCode());
      Predicate workflowStatusCondition = CriteriaBuilderHelper.createEquals(cb, root, "workflowStatus", criteria.getWorkflowStatus());
      return CriteriaBuilderHelper.and(
          cb,
          textSearchCondition,
          statusCondition,
          idsCondition,
          codesCondition,
          budgetYearCondition,
          periodTypeCondition,
          budgetQuarterCondition,
          nodeIdCondition,
          nodeCodeCondition,
          estimateItemIdCondition,
          itemTypeCondition,
          categoryCodeCondition,
          workflowStatusCondition);
    };
  }
}
