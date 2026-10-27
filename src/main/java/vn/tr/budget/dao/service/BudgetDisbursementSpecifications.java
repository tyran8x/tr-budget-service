package vn.tr.budget.dao.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.data.criteria.BudgetDisbursementSearchCriteria;
import vn.tr.common.jpa.helper.CriteriaBuilderHelper;

/**
 * JPA Specification cho BudgetDisbursement.
 *
 * @author tyran8x
 */
public final class BudgetDisbursementSpecifications {

  private BudgetDisbursementSpecifications() {}

  public static Specification<BudgetDisbursement> quickSearch(final BudgetDisbursementSearchCriteria criteria) {
    return (root, query, cb) -> {
      if (criteria == null) {
        return null;
      }
      Predicate textSearchCondition =
          CriteriaBuilderHelper.createOrUnaccentedLike(
              cb, root, criteria.getSearch(), "name", "code", "allocationCode", "nodeName", "beneficiaryName", "voucherNo", "contractNo");
      Predicate statusCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "status", criteria.getStatus());
      Predicate idsCondition = CriteriaBuilderHelper.createIn(cb, root, "id", criteria.getIds());
      Predicate codesCondition =
          CriteriaBuilderHelper.createIn(cb, root, "code", criteria.getCodes());
      Predicate allocationIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "allocationId", criteria.getAllocationId());
      Predicate nodeIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "nodeId", criteria.getNodeId());
      Predicate budgetYearCondition = CriteriaBuilderHelper.createEquals(cb, root, "budgetYear", criteria.getBudgetYear());
      Predicate disbursementTypeCondition = CriteriaBuilderHelper.createEquals(cb, root, "disbursementType", criteria.getDisbursementType());
      Predicate approvalStatusCondition = CriteriaBuilderHelper.createEquals(cb, root, "approvalStatus", criteria.getApprovalStatus());
      Predicate linkedPlanIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "linkedPlanId", criteria.getLinkedPlanId());
      return CriteriaBuilderHelper.and(
          cb,
          textSearchCondition,
          statusCondition,
          idsCondition,
          codesCondition,
          allocationIdCondition,
          nodeIdCondition,
          budgetYearCondition,
          disbursementTypeCondition,
          approvalStatusCondition,
          linkedPlanIdCondition);
    };
  }
}
