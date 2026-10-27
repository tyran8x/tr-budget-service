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
import vn.tr.budget.dao.model.BudgetTransaction;

/**
 * Repository cho BudgetTransaction.
 *
 * @author tyran8x
 */
@Repository
@JaversSpringDataAuditable
public interface BudgetTransactionRepo
    extends JpaRepository<BudgetTransaction, Long>, JpaSpecificationExecutor<BudgetTransaction> {

  boolean existsByIdNotAndCodeIgnoreCase(Long id, String code);

  boolean existsByIdNotAndNameIgnoreCase(Long id, String name);

  boolean existsByCodeIgnoreCase(String code);

  boolean existsByNameIgnoreCase(String name);

  @Query(
      "SELECT a FROM BudgetTransaction a WHERE a.code = :code ORDER BY a.deletedAt ASC NULLS FIRST, a.updatedAt DESC")
  List<BudgetTransaction> findAllByCodeIgnoreCaseIncludingDeletedSorted(@Param("code") String code);

  @Modifying(clearAutomatically = true)
  @Query("UPDATE BudgetTransaction a SET a.deletedAt = CURRENT_TIMESTAMP WHERE a.id IN :ids")
  void softDeleteByIds(@Param("ids") Collection<Long> ids);

  @Query(value = "SELECT * FROM budget_transaction WHERE id = :id LIMIT 1", nativeQuery = true)
  Optional<BudgetTransaction> findByIdIncludingDeleted(@Param("id") Long id);

  List<BudgetTransaction> findByAllocationIdOrderByIdAsc(Long allocationId);

  List<BudgetTransaction> findByBudgetYearOrderByIdDesc(Integer budgetYear);
}
