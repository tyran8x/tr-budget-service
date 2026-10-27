package vn.tr.budget.dao.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.data.criteria.BudgetCategorySearchCriteria;
import vn.tr.common.jpa.helper.CriteriaBuilderHelper;

/**
 * JPA Specification cho BudgetCategory.
 *
 * @author tyran8x
 */
public final class BudgetCategorySpecifications {

  private BudgetCategorySpecifications() {}

  public static Specification<BudgetCategory> quickSearch(final BudgetCategorySearchCriteria criteria) {
    return (root, query, cb) -> {
      if (criteria == null) {
        return null;
      }
      Predicate textSearchCondition =
          CriteriaBuilderHelper.createOrUnaccentedLike(
              cb, root, criteria.getSearch(), "name", "code", "categoryGroup", "chapterCode", "clauseCode", "autonomyType");
      Predicate statusCondition =
          CriteriaBuilderHelper.createEquals(cb, root, "status", criteria.getStatus());
      Predicate idsCondition = CriteriaBuilderHelper.createIn(cb, root, "id", criteria.getIds());
      Predicate codesCondition =
          CriteriaBuilderHelper.createIn(cb, root, "code", criteria.getCodes());
      Predicate parentIdCondition = CriteriaBuilderHelper.createEquals(cb, root, "parentId", criteria.getParentId());
      Predicate categoryGroupCondition = CriteriaBuilderHelper.createEquals(cb, root, "categoryGroup", criteria.getCategoryGroup());
      Predicate chapterCodeCondition = CriteriaBuilderHelper.createEquals(cb, root, "chapterCode", criteria.getChapterCode());
      Predicate clauseCodeCondition = CriteriaBuilderHelper.createEquals(cb, root, "clauseCode", criteria.getClauseCode());
      Predicate autonomyTypeCondition = CriteriaBuilderHelper.createEquals(cb, root, "autonomyType", criteria.getAutonomyType());
      Predicate allowCarryOverCondition = CriteriaBuilderHelper.createEquals(cb, root, "allowCarryOver", criteria.getAllowCarryOver());
      return CriteriaBuilderHelper.and(
          cb,
          textSearchCondition,
          statusCondition,
          idsCondition,
          codesCondition,
          parentIdCondition,
          categoryGroupCondition,
          chapterCodeCondition,
          clauseCodeCondition,
          autonomyTypeCondition,
          allowCarryOverCondition);
    };
  }
}
