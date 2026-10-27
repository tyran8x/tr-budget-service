package vn.tr.budget.dao.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.data.criteria.BudgetUnitConfigSearchCriteria;
import vn.tr.common.jpa.helper.CriteriaBuilderHelper;

/**
 * JPA Specification cho BudgetUnitConfig.
 *
 * @author tyran8x
 */
public final class BudgetUnitConfigSpecifications {

  private BudgetUnitConfigSpecifications() {}

  public static Specification<BudgetUnitConfig> quickSearch(final BudgetUnitConfigSearchCriteria criteria) {
    return (root, query, cb) -> {
      if (criteria == null) {
        return null;
      }
      Predicate textSearchCondition =
          CriteriaBuilderHelper.createOrUnaccentedLike(
              cb, root, criteria.getSearch(), "name", "code", "nodeCode", "nodeName", "treasuryAccountNo", "approverPersonName");
      Predicate statusCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "status", criteria.getStatus());
      Predicate idsCondition = CriteriaBuilderHelper.createIn(cb, root, "id", criteria.getIds());
      Predicate codesCondition =
          CriteriaBuilderHelper.createIn(cb, root, "code", criteria.getCodes());
      Predicate nodeIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "nodeId", criteria.getNodeId());
      Predicate nodeCodeCondition = CriteriaBuilderHelper.createEquals(cb, root, "nodeCode", criteria.getNodeCode());
      Predicate unitTypeCondition = CriteriaBuilderHelper.createEquals(cb, root, "unitType", criteria.getUnitType());
      Predicate canSelfAllocateCondition = CriteriaBuilderHelper.createEquals(cb, root, "canSelfAllocate", criteria.getCanSelfAllocate());
      Predicate canSelfTransferCondition = CriteriaBuilderHelper.createEquals(cb, root, "canSelfTransfer", criteria.getCanSelfTransfer());
      return CriteriaBuilderHelper.and(
          cb,
          textSearchCondition,
          statusCondition,
          idsCondition,
          codesCondition,
          nodeIdCondition,
          nodeCodeCondition,
          unitTypeCondition,
          canSelfAllocateCondition,
          canSelfTransferCondition);
    };
  }
}
