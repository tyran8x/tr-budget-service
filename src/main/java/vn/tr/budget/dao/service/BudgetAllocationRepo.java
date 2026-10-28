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
import vn.tr.budget.dao.model.BudgetAllocation;

/**
 * Repository cho BudgetAllocation.
 *
 * @author tyran8x
 */
@Repository
@JaversSpringDataAuditable
public interface BudgetAllocationRepo
    extends JpaRepository<BudgetAllocation, Long>, JpaSpecificationExecutor<BudgetAllocation> {

  boolean existsByIdNotAndCodeIgnoreCase(Long id, String code);

  boolean existsByIdNotAndNameIgnoreCase(Long id, String name);

  boolean existsByCodeIgnoreCase(String code);

  boolean existsByNameIgnoreCase(String name);

  @Query(
      "SELECT a FROM BudgetAllocation a WHERE a.code = :code ORDER BY a.deletedAt ASC NULLS FIRST, a.updatedAt DESC")
  List<BudgetAllocation> findAllByCodeIgnoreCaseIncludingDeletedSorted(@Param("code") String code);

  @Modifying(clearAutomatically = true)
  @Query("UPDATE BudgetAllocation a SET a.deletedAt = CURRENT_TIMESTAMP WHERE a.id IN :ids")
  void softDeleteByIds(@Param("ids") Collection<Long> ids);

  @Query(value = "SELECT * FROM budget_allocation WHERE id = :id LIMIT 1", nativeQuery = true)
  Optional<BudgetAllocation> findByIdIncludingDeleted(@Param("id") Long id);

  List<BudgetAllocation> findByBudgetYearOrderByIdAsc(Integer budgetYear);

  List<BudgetAllocation> findByNodeIdAndBudgetYearOrderByIdAsc(Long nodeId, Integer budgetYear);

  List<BudgetAllocation> findByLinkedPlanCodeIgnoreCaseOrderByIdAsc(String linkedPlanCode);

  List<BudgetAllocation> findByLinkedPlanIdOrderByIdAsc(Long linkedPlanId);
}
