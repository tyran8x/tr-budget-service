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
import vn.tr.budget.dao.model.BudgetEstimateItem;

/**
 * Repository cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
@Repository
@JaversSpringDataAuditable
public interface BudgetEstimateItemRepo
    extends JpaRepository<BudgetEstimateItem, Long>, JpaSpecificationExecutor<BudgetEstimateItem> {

  boolean existsByIdNotAndCodeIgnoreCase(Long id, String code);

  boolean existsByIdNotAndNameIgnoreCase(Long id, String name);

  boolean existsByCodeIgnoreCase(String code);

  boolean existsByNameIgnoreCase(String name);

  @Query(
      value =
          "SELECT * FROM budget_estimate_item WHERE LOWER(code) = LOWER(:code) AND app_code = :appCode "
              + "ORDER BY deleted_at ASC NULLS FIRST, updated_at DESC",
      nativeQuery = true)
  List<BudgetEstimateItem> findAllByCodeIgnoreCaseIncludingDeletedSorted(
      @Param("code") String code, @Param("appCode") String appCode);

  default List<BudgetEstimateItem> findAllByCodeIgnoreCaseIncludingDeletedSorted(String code) {
    return findAllByCodeIgnoreCaseIncludingDeletedSorted(
        code, BudgetQueryHelper.resolveCurrentAppCode());
  }

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE BudgetEstimateItem a SET a.deletedAt = CURRENT_TIMESTAMP, a.version = (a.version + 1) "
          + "WHERE a.id IN :ids AND a.appCode = :appCode")
  void softDeleteByIds(@Param("ids") Collection<Long> ids, @Param("appCode") String appCode);

  @Query(
      value =
          "SELECT * FROM budget_estimate_item WHERE id = :id AND app_code = :appCode LIMIT 1",
      nativeQuery = true)
  Optional<BudgetEstimateItem> findByIdIncludingDeletedByAppCode(
      @Param("id") Long id, @Param("appCode") String appCode);

  default Optional<BudgetEstimateItem> findByIdIncludingDeleted(Long id) {
    return findByIdIncludingDeletedByAppCode(id, BudgetQueryHelper.resolveCurrentAppCode());
  }

  List<BudgetEstimateItem> findByParentIdOrderByIdAsc(Long parentId);

  List<BudgetEstimateItem> findByItemTypeOrderByIdAsc(String itemType);

  @Query(
      "SELECT COUNT(e) > 0 FROM BudgetEstimateItem e "
          + "WHERE UPPER(e.code) = UPPER(:code) "
          + "AND COALESCE(e.budgetYear, 0) = COALESCE(:budgetYear, 0) "
          + "AND (:excludeId IS NULL OR e.id <> :excludeId)")
  boolean existsDuplicateCodeInYear(
      @Param("code") String code,
      @Param("budgetYear") Integer budgetYear,
      @Param("excludeId") Long excludeId);

  @Query(
      value =
          "SELECT * FROM budget_estimate_item "
              + "WHERE LOWER(code) = LOWER(:code) "
              + "AND COALESCE(budget_year, 0) = COALESCE(:budgetYear, 0) "
              + "AND app_code = :appCode "
              + "ORDER BY deleted_at ASC NULLS FIRST, updated_at DESC",
      nativeQuery = true)
  List<BudgetEstimateItem> findByCodeInYearSorted(
      @Param("code") String code,
      @Param("budgetYear") Integer budgetYear,
      @Param("appCode") String appCode);

  default List<BudgetEstimateItem> findByCodeInYearSorted(String code, Integer budgetYear) {
    return findByCodeInYearSorted(code, budgetYear, BudgetQueryHelper.resolveCurrentAppCode());
  }

  @Query(
      "SELECT e FROM BudgetEstimateItem e "
          + "WHERE (:budgetYear IS NULL OR e.budgetYear IS NULL OR e.budgetYear = :budgetYear) "
          + "AND (:appCode IS NULL OR e.appCode = :appCode) "
          + "ORDER BY e.sortOrder ASC NULLS LAST, e.code ASC, e.id ASC")
  List<BudgetEstimateItem> findByYearAndAppCodeOrdered(
      @Param("budgetYear") Integer budgetYear, @Param("appCode") String appCode);
}
