package vn.tr.budget.dao.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.data.criteria.BudgetTransactionSearchCriteria;
import vn.tr.common.jpa.helper.CriteriaBuilderHelper;

/**
 * JPA Specification cho BudgetTransaction.
 *
 * @author tyran8x
 */
public final class BudgetTransactionSpecifications {

  private BudgetTransactionSpecifications() {}

  public static Specification<BudgetTransaction> quickSearch(final BudgetTransactionSearchCriteria criteria) {
    return (root, query, cb) -> {
      if (criteria == null) {
        return null;
      }
      Predicate textSearchCondition =
          CriteriaBuilderHelper.createOrUnaccentedLike(
              cb, root, criteria.getSearch(), "name", "code", "allocationCode", "nodeName", "txType", "decisionNo", "reason");
      Predicate statusCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "status", criteria.getStatus());
      Predicate idsCondition = CriteriaBuilderHelper.createIn(cb, root, "id", criteria.getIds());
      Predicate codesCondition =
          CriteriaBuilderHelper.createIn(cb, root, "code", criteria.getCodes());
      Predicate allocationIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "allocationId", criteria.getAllocationId());
      Predicate nodeIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "nodeId", criteria.getNodeId());
      Predicate txTypeCondition = CriteriaBuilderHelper.createEquals(cb, root, "txType", criteria.getTxType());
      Predicate budgetYearCondition = CriteriaBuilderHelper.createEquals(cb, root, "budgetYear", criteria.getBudgetYear());
      Predicate fundingSourceCodeCondition = CriteriaBuilderHelper.createEquals(cb, root, "fundingSourceCode", criteria.getFundingSourceCode());
      Predicate approvalStatusCondition = CriteriaBuilderHelper.createEquals(cb, root, "approvalStatus", criteria.getApprovalStatus());
      return CriteriaBuilderHelper.and(
          cb,
          textSearchCondition,
          statusCondition,
          idsCondition,
          codesCondition,
          allocationIdCondition,
          nodeIdCondition,
          txTypeCondition,
          budgetYearCondition,
          fundingSourceCodeCondition,
          approvalStatusCondition);
    };
  }
}
