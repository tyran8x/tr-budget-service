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
      "SELECT a FROM BudgetCategory a WHERE a.code = :code ORDER BY a.deletedAt ASC NULLS FIRST, a.updatedAt DESC")
  List<BudgetCategory> findAllByCodeIgnoreCaseIncludingDeletedSorted(@Param("code") String code);

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE BudgetCategory a SET a.deletedAt = CURRENT_TIMESTAMP, a.version = (a.version + 1) WHERE a.id IN :ids")
  void softDeleteByIds(@Param("ids") Collection<Long> ids);

  @Query(value = "SELECT * FROM budget_category WHERE id = :id LIMIT 1", nativeQuery = true)
  Optional<BudgetCategory> findByIdIncludingDeleted(@Param("id") Long id);

  List<BudgetCategory> findByParentIdOrderByIdAsc(Long parentId);

  List<BudgetCategory> findByBudgetYearAndCategoryGroupInOrderBySortOrderAscIdAsc(
      Integer budgetYear, Collection<String> categoryGroups);

  List<BudgetCategory> findByCategoryGroupInOrderByBudgetYearAscSortOrderAscIdAsc(
      Collection<String> categoryGroups);
}
