package vn.tr.budget.dao.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.data.criteria.BudgetAllocationSearchCriteria;
import vn.tr.common.jpa.helper.CriteriaBuilderHelper;
import vn.tr.common.security.abac.OrgSubtreeAuthorizationHelper;
import vn.tr.common.web.utils.LookupOptionsHelper;

/**
 * JPA Specification cho BudgetAllocation (hỗ trợ ABAC Org-Subtree & Fail-Closed khi criteria null).
 *
 * @author tyran8x
 */
public final class BudgetAllocationSpecifications {

  private BudgetAllocationSpecifications() {}

  public static Specification<BudgetAllocation> quickSearch(
      final BudgetAllocationSearchCriteria criteria) {
    return quickSearch(criteria, null);
  }

  public static Specification<BudgetAllocation> quickSearch(
      final BudgetAllocationSearchCriteria criteria,
      final OrgSubtreeAuthorizationHelper.OrgSubtreeScope scope) {
    return (root, query, cb) -> {
      if (criteria == null) {
        return cb.disjunction();
      }
      Predicate textSearchCondition =
          CriteriaBuilderHelper.createOrUnaccentedLike(
              cb,
              root,
              criteria.getSearch(),
              "name",
              "code",
              "nodeCode",
              "nodeName",
              "estimateItemName",
              "categoryName",
              "decisionNo");
      Predicate statusCondition =
          LookupOptionsHelper.hasExplicitIds(criteria)
              ? null
              : CriteriaBuilderHelper.createEquals(cb, root, "status", criteria.getStatus());
      Predicate idsCondition = CriteriaBuilderHelper.createIn(cb, root, "id", criteria.getIds());
      Predicate codesCondition =
          CriteriaBuilderHelper.createIn(cb, root, "code", criteria.getCodes());
      Predicate budgetYearCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "budgetYear", criteria.getBudgetYear());
      Predicate periodTypeCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "periodType", criteria.getPeriodType());
      Predicate budgetQuarterCondition =
          CriteriaBuilderHelper.createEquals(
              cb, root, "budgetQuarter", criteria.getBudgetQuarter());
      Predicate nodeIdCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "nodeId", criteria.getNodeId());
      Predicate nodeCodeCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "nodeCode", criteria.getNodeCode());
      Predicate estimateItemIdCondition =
          CriteriaBuilderHelper.createEquals(
              cb, root, "estimateItemId", criteria.getEstimateItemId());
      Predicate itemTypeCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "itemType", criteria.getItemType());
      Predicate categoryCodeCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "categoryCode", criteria.getCategoryCode());
      Predicate workflowStatusCondition =
          CriteriaBuilderHelper.createEquals(
              cb, root, "workflowStatus", criteria.getWorkflowStatus());
      Predicate orgScopeCondition =
          BudgetQueryHelper.buildOrgSubtreePredicate(cb, root, scope, "nodeId", "nodeCode", false);
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
          workflowStatusCondition,
          orgScopeCondition);
    };
  }
}
