package vn.tr.budget.dao.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import vn.tr.budget.dao.model.BudgetReport;
import vn.tr.budget.data.criteria.BudgetReportSearchCriteria;
import vn.tr.common.jpa.helper.CriteriaBuilderHelper;

/**
 * JPA Specification cho BudgetReport.
 *
 * @author tyran8x
 */
public final class BudgetReportSpecifications {

  private BudgetReportSpecifications() {}

  public static Specification<BudgetReport> quickSearch(final BudgetReportSearchCriteria criteria) {
    return (root, query, cb) -> {
      if (criteria == null) {
        return null;
      }
      Predicate textSearchCondition =
          CriteriaBuilderHelper.createOrUnaccentedLike(
              cb, root, criteria.getSearch(), "name", "code", "nodeCode", "nodeName", "reportPeriod", "reportScope");
      Predicate statusCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "status", criteria.getStatus());
      Predicate idsCondition = CriteriaBuilderHelper.createIn(cb, root, "id", criteria.getIds());
      Predicate codesCondition =
          CriteriaBuilderHelper.createIn(cb, root, "code", criteria.getCodes());
      Predicate budgetYearCondition = CriteriaBuilderHelper.createEquals(cb, root, "budgetYear", criteria.getBudgetYear());
      Predicate reportPeriodCondition = CriteriaBuilderHelper.createEquals(cb, root, "reportPeriod", criteria.getReportPeriod());
      Predicate periodNumberCondition = CriteriaBuilderHelper.createEquals(cb, root, "periodNumber", criteria.getPeriodNumber());
      Predicate reportScopeCondition = CriteriaBuilderHelper.createEquals(cb, root, "reportScope", criteria.getReportScope());
      Predicate nodeIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "nodeId", criteria.getNodeId());
      Predicate workflowStatusCondition = CriteriaBuilderHelper.createEquals(cb, root, "workflowStatus", criteria.getWorkflowStatus());
      return CriteriaBuilderHelper.and(
          cb,
          textSearchCondition,
          statusCondition,
          idsCondition,
          codesCondition,
          budgetYearCondition,
          reportPeriodCondition,
          periodNumberCondition,
          reportScopeCondition,
          nodeIdCondition,
          workflowStatusCondition);
    };
  }
}
