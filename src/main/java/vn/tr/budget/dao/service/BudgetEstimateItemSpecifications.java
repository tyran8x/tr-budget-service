package vn.tr.budget.dao.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.data.criteria.BudgetEstimateItemSearchCriteria;
import vn.tr.common.jpa.helper.CriteriaBuilderHelper;

/**
 * JPA Specification cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
public final class BudgetEstimateItemSpecifications {

  private BudgetEstimateItemSpecifications() {}

  public static Specification<BudgetEstimateItem> quickSearch(final BudgetEstimateItemSearchCriteria criteria) {
    return (root, query, cb) -> {
      if (criteria == null) {
        return null;
      }
      Predicate textSearchCondition =
          CriteriaBuilderHelper.createOrUnaccentedLike(
              cb, root, criteria.getSearch(), "name", "code", "itemType", "categoryCode", "categoryName", "numberingStyle");
      Predicate statusCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "status", criteria.getStatus());
      Predicate idsCondition = CriteriaBuilderHelper.createIn(cb, root, "id", criteria.getIds());
      Predicate codesCondition =
          CriteriaBuilderHelper.createIn(cb, root, "code", criteria.getCodes());
      Predicate parentIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "parentId", criteria.getParentId());
      Predicate itemTypeCondition = CriteriaBuilderHelper.createEquals(cb, root, "itemType", criteria.getItemType());
      Predicate categoryIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "categoryId", criteria.getCategoryId());
      Predicate categoryCodeCondition = CriteriaBuilderHelper.createEquals(cb, root, "categoryCode", criteria.getCategoryCode());
      Predicate isHeaderCondition = CriteriaBuilderHelper.createEquals(cb, root, "isHeader", criteria.getIsHeader());
      return CriteriaBuilderHelper.and(
          cb,
          textSearchCondition,
          statusCondition,
          idsCondition,
          codesCondition,
          parentIdCondition,
          itemTypeCondition,
          categoryIdCondition,
          categoryCodeCondition,
          isHeaderCondition);
    };
  }
}
