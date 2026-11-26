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
import vn.tr.budget.dao.model.BudgetDisbursement;

/**
 * Repository cho BudgetDisbursement.
 *
 * @author tyran8x
 */
@Repository
@JaversSpringDataAuditable
public interface BudgetDisbursementRepo
    extends JpaRepository<BudgetDisbursement, Long>, JpaSpecificationExecutor<BudgetDisbursement> {

  boolean existsByIdNotAndCodeIgnoreCase(Long id, String code);

  boolean existsByIdNotAndNameIgnoreCase(Long id, String name);

  boolean existsByCodeIgnoreCase(String code);

  boolean existsByNameIgnoreCase(String name);

  @Query(
      value =
          "SELECT * FROM budget_disbursement WHERE LOWER(code) = LOWER(:code) AND app_code = :appCode "
              + "ORDER BY deleted_at ASC NULLS FIRST, updated_at DESC",
      nativeQuery = true)
  List<BudgetDisbursement> findAllByCodeIgnoreCaseIncludingDeletedSorted(
      @Param("code") String code, @Param("appCode") String appCode);

  default List<BudgetDisbursement> findAllByCodeIgnoreCaseIncludingDeletedSorted(String code) {
    return findAllByCodeIgnoreCaseIncludingDeletedSorted(
        code, BudgetQueryHelper.resolveCurrentAppCode());
  }

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE BudgetDisbursement a SET a.deletedAt = CURRENT_TIMESTAMP, a.version = (a.version + 1) "
          + "WHERE a.id IN :ids AND a.appCode = :appCode")
  void softDeleteByIds(@Param("ids") Collection<Long> ids, @Param("appCode") String appCode);

  @Query(
      value =
          "SELECT * FROM budget_disbursement WHERE id = :id AND app_code = :appCode LIMIT 1",
      nativeQuery = true)
  Optional<BudgetDisbursement> findByIdIncludingDeletedByAppCode(
      @Param("id") Long id, @Param("appCode") String appCode);

  default Optional<BudgetDisbursement> findByIdIncludingDeleted(Long id) {
    return findByIdIncludingDeletedByAppCode(id, BudgetQueryHelper.resolveCurrentAppCode());
  }

  Optional<BudgetDisbursement> findFirstByPaymentTxCodeIgnoreCase(String paymentTxCode);

  Optional<BudgetDisbursement> findFirstByPayTransactionId(Long payTransactionId);

  List<BudgetDisbursement> findByAllocationIdOrderByIdAsc(Long allocationId);

  List<BudgetDisbursement> findByBudgetYearOrderByIdDesc(Integer budgetYear);

  List<BudgetDisbursement> findByBudgetYearAndNodeIdOrderByIdDesc(Integer budgetYear, Long nodeId);

  @Query(
      value =
          "SELECT DISTINCT app_code FROM budget_disbursement "
              + "WHERE deleted_at IS NULL AND app_code IS NOT NULL",
      nativeQuery = true)
  List<String> findDistinctActiveAppCodes();

  @Query(
      value =
          "SELECT * FROM budget_disbursement "
              + "WHERE deleted_at IS NULL AND app_code = :appCode "
              + "AND sla_due_date IS NOT NULL "
              + "AND COALESCE(is_overdue_warning_sent, FALSE) = FALSE "
              + "AND UPPER(COALESCE(approval_status, '')) NOT IN ('SETTLED', 'REJECTED', 'CANCELLED', 'DRAFT') "
              + "ORDER BY sla_due_date ASC",
      nativeQuery = true)
  List<BudgetDisbursement> findPendingSlaDisbursementsByAppCode(@Param("appCode") String appCode);

  @Query(
      "SELECT COUNT(d), "
          + "COALESCE(SUM(CASE WHEN (d.voucherNo IS NOT NULL AND TRIM(d.voucherNo) <> '') "
          + "  OR (d.treasuryRefCode IS NOT NULL AND TRIM(d.treasuryRefCode) <> '') THEN 1 ELSE 0 END), 0) "
          + "FROM BudgetDisbursement d "
          + "WHERE (:budgetYear IS NULL OR d.budgetYear = :budgetYear) "
          + "AND (:unitCode IS NULL OR UPPER(COALESCE(d.nodeCode, '')) = UPPER(:unitCode) "
          + "     OR CAST(d.nodeId AS string) = :unitCode) "
          + "AND (:appCode IS NULL OR d.appCode = :appCode)")
  List<Object[]> summarizeInternalAnalytics(
      @Param("budgetYear") Integer budgetYear,
      @Param("unitCode") String unitCode,
      @Param("appCode") String appCode);
}
