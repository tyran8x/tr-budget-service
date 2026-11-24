package vn.tr.budget.dao.service;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.javers.spring.annotation.JaversSpringDataAuditable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
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
  @Query(
      "UPDATE BudgetAllocation a SET a.deletedAt = CURRENT_TIMESTAMP, a.version = (a.version + 1) WHERE a.id IN :ids")
  void softDeleteByIds(@Param("ids") Collection<Long> ids);

  @Query(value = "SELECT * FROM budget_allocation WHERE id = :id LIMIT 1", nativeQuery = true)
  Optional<BudgetAllocation> findByIdIncludingDeleted(@Param("id") Long id);

  @Query(
      value =
          "SELECT * FROM budget_allocation WHERE id = :id AND (app_code = :appCode OR :appCode IS NULL) LIMIT 1",
      nativeQuery = true)
  Optional<BudgetAllocation> findByIdIncludingDeletedByAppCode(
      @Param("id") Long id, @Param("appCode") String appCode);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM BudgetAllocation a WHERE a.id = :id")
  Optional<BudgetAllocation> findByIdForUpdate(@Param("id") Long id);

  List<BudgetAllocation> findByBudgetYearOrderByIdAsc(Integer budgetYear);

  List<BudgetAllocation> findByNodeIdAndBudgetYearOrderByIdAsc(Long nodeId, Integer budgetYear);

  List<BudgetAllocation> findByLinkedPlanCodeIgnoreCaseOrderByIdAsc(String linkedPlanCode);

  List<BudgetAllocation> findByLinkedPlanIdOrderByIdAsc(Long linkedPlanId);

  @Query(
      "SELECT COUNT(a), "
          + "COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.workflowStatus, '')) IN ('APPROVED', 'EXECUTING') THEN 1 ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.workflowStatus, '')) = 'SUBMITTED' THEN 1 ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.itemType, '')) = 'REVENUE' THEN a.effectiveAmount ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.itemType, '')) = 'REVENUE' THEN a.disbursedAmount ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.itemType, 'EXPENDITURE')) <> 'REVENUE' THEN a.initialAmount ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.itemType, 'EXPENDITURE')) <> 'REVENUE' THEN a.effectiveAmount ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.itemType, 'EXPENDITURE')) <> 'REVENUE' THEN a.committedAmount ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.itemType, 'EXPENDITURE')) <> 'REVENUE' THEN a.disbursedAmount ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.itemType, 'EXPENDITURE')) <> 'REVENUE' THEN a.settledAmount ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.itemType, 'EXPENDITURE')) <> 'REVENUE' THEN a.availableAmount ELSE 0 END), 0) "
          + "FROM BudgetAllocation a "
          + "WHERE (:budgetYear IS NULL OR a.budgetYear = :budgetYear) "
          + "AND (:periodType IS NULL OR UPPER(COALESCE(a.periodType, '')) = UPPER(:periodType)) "
          + "AND (:unitCode IS NULL OR UPPER(COALESCE(a.nodeCode, '')) = UPPER(:unitCode) "
          + "     OR UPPER(COALESCE(a.domainCode, '')) = UPPER(:unitCode) "
          + "     OR CAST(a.nodeId AS string) = :unitCode) "
          + "AND (:appCode IS NULL OR a.appCode = :appCode)")
  List<Object[]> summarizeInternalAnalytics(
      @Param("budgetYear") Integer budgetYear,
      @Param("periodType") String periodType,
      @Param("unitCode") String unitCode,
      @Param("appCode") String appCode);

  @Query(
      "SELECT COALESCE(a.nodeCode, CONCAT('NODE_', CAST(a.nodeId AS string))), "
          + "COALESCE(a.nodeName, a.nodeCode, 'GENERAL'), "
          + "COUNT(a), "
          + "COALESCE(SUM(a.initialAmount), 0), "
          + "COALESCE(SUM(a.effectiveAmount), 0), "
          + "COALESCE(SUM(a.disbursedAmount), 0), "
          + "COALESCE(SUM(a.settledAmount), 0), "
          + "COALESCE(SUM(a.availableAmount), 0) "
          + "FROM BudgetAllocation a "
          + "WHERE UPPER(COALESCE(a.itemType, 'EXPENDITURE')) <> 'REVENUE' "
          + "AND (:budgetYear IS NULL OR a.budgetYear = :budgetYear) "
          + "AND (:periodType IS NULL OR UPPER(COALESCE(a.periodType, '')) = UPPER(:periodType)) "
          + "AND (:unitCode IS NULL OR UPPER(COALESCE(a.nodeCode, '')) = UPPER(:unitCode) "
          + "     OR UPPER(COALESCE(a.domainCode, '')) = UPPER(:unitCode) "
          + "     OR CAST(a.nodeId AS string) = :unitCode) "
          + "AND (:appCode IS NULL OR a.appCode = :appCode) "
          + "GROUP BY a.nodeCode, a.nodeId, a.nodeName")
  List<Object[]> aggregateInternalAnalyticsByUnit(
      @Param("budgetYear") Integer budgetYear,
      @Param("periodType") String periodType,
      @Param("unitCode") String unitCode,
      @Param("appCode") String appCode);
}
