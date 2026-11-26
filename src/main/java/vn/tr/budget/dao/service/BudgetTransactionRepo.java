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
      value =
          "SELECT * FROM budget_transaction WHERE LOWER(code) = LOWER(:code) AND app_code = :appCode "
              + "ORDER BY deleted_at ASC NULLS FIRST, updated_at DESC",
      nativeQuery = true)
  List<BudgetTransaction> findAllByCodeIgnoreCaseIncludingDeletedSorted(
      @Param("code") String code, @Param("appCode") String appCode);

  default List<BudgetTransaction> findAllByCodeIgnoreCaseIncludingDeletedSorted(String code) {
    return findAllByCodeIgnoreCaseIncludingDeletedSorted(
        code, BudgetQueryHelper.resolveCurrentAppCode());
  }

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE BudgetTransaction a SET a.deletedAt = CURRENT_TIMESTAMP, a.version = (a.version + 1) "
          + "WHERE a.id IN :ids AND a.appCode = :appCode")
  void softDeleteByIds(@Param("ids") Collection<Long> ids, @Param("appCode") String appCode);

  @Query(
      value =
          "SELECT * FROM budget_transaction WHERE id = :id AND app_code = :appCode LIMIT 1",
      nativeQuery = true)
  Optional<BudgetTransaction> findByIdIncludingDeletedByAppCode(
      @Param("id") Long id, @Param("appCode") String appCode);

  default Optional<BudgetTransaction> findByIdIncludingDeleted(Long id) {
    return findByIdIncludingDeletedByAppCode(id, BudgetQueryHelper.resolveCurrentAppCode());
  }

  List<BudgetTransaction> findByCounterpartTxId(Long counterpartTxId);

  List<BudgetTransaction> findByAllocationIdOrderByIdAsc(Long allocationId);

  List<BudgetTransaction> findByBudgetYearOrderByIdDesc(Integer budgetYear);

  List<BudgetTransaction> findByBudgetYearAndNodeIdOrderByIdDesc(Integer budgetYear, Long nodeId);

  @Query(
      "SELECT COUNT(t) FROM BudgetTransaction t "
          + "WHERE (:budgetYear IS NULL OR t.budgetYear = :budgetYear) "
          + "AND (:unitCode IS NULL OR UPPER(COALESCE(t.nodeCode, '')) = UPPER(:unitCode) "
          + "     OR UPPER(COALESCE(t.domainCode, '')) = UPPER(:unitCode) "
          + "     OR CAST(t.nodeId AS string) = :unitCode) "
          + "AND (:appCode IS NULL OR t.appCode = :appCode)")
  long countInternalAnalytics(
      @Param("budgetYear") Integer budgetYear,
      @Param("unitCode") String unitCode,
      @Param("appCode") String appCode);
}
