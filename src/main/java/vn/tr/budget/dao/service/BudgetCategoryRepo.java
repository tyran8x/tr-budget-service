package vn.tr.budget.dao.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.javers.spring.annotation.JaversSpringDataAuditable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vn.tr.budget.dao.model.BudgetCategory;

/**
 * Repository cho BudgetCategory.
 *
 * @author tyran8x
 */
@Repository
@JaversSpringDataAuditable
public interface BudgetCategoryRepo
    extends JpaRepository<BudgetCategory, Long>, JpaSpecificationExecutor<BudgetCategory> {

  boolean existsByIdNotAndCodeIgnoreCase(Long id, String code);

  boolean existsByIdNotAndNameIgnoreCase(Long id, String name);

  boolean existsByCodeIgnoreCase(String code);

  boolean existsByNameIgnoreCase(String name);

  @Query(
      value =
          "SELECT * FROM budget_category WHERE LOWER(code) = LOWER(:code) AND app_code = :appCode "
              + "ORDER BY deleted_at ASC NULLS FIRST, updated_at DESC",
      nativeQuery = true)
  List<BudgetCategory> findAllByCodeIgnoreCaseIncludingDeletedSorted(
      @Param("code") String code, @Param("appCode") String appCode);

  default List<BudgetCategory> findAllByCodeIgnoreCaseIncludingDeletedSorted(String code) {
    return findAllByCodeIgnoreCaseIncludingDeletedSorted(
        code, BudgetQueryHelper.resolveCurrentAppCode());
  }

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE BudgetCategory a SET a.deletedAt = CURRENT_TIMESTAMP, a.version = (a.version + 1) "
          + "WHERE a.id IN :ids AND a.appCode = :appCode")
  void softDeleteByIds(@Param("ids") Collection<Long> ids, @Param("appCode") String appCode);

  @Query(
      value =
          "SELECT * FROM budget_category WHERE id = :id AND app_code = :appCode LIMIT 1",
      nativeQuery = true)
  Optional<BudgetCategory> findByIdIncludingDeletedByAppCode(
      @Param("id") Long id, @Param("appCode") String appCode);

  default Optional<BudgetCategory> findByIdIncludingDeleted(Long id) {
    return findByIdIncludingDeletedByAppCode(id, BudgetQueryHelper.resolveCurrentAppCode());
  }

  List<BudgetCategory> findByParentIdOrderByIdAsc(Long parentId);

  List<BudgetCategory> findByBudgetYearAndCategoryGroupInOrderBySortOrderAscIdAsc(
      Integer budgetYear, Collection<String> categoryGroups);

  List<BudgetCategory> findByCategoryGroupInOrderByBudgetYearAscSortOrderAscIdAsc(
      Collection<String> categoryGroups);

  List<BudgetCategory> findByAppCodeAndCodeIn(String appCode, Collection<String> codes);
}
