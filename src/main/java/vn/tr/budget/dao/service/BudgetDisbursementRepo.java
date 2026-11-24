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
      "SELECT a FROM BudgetDisbursement a WHERE a.code = :code ORDER BY a.deletedAt ASC NULLS FIRST, a.updatedAt DESC")
  List<BudgetDisbursement> findAllByCodeIgnoreCaseIncludingDeletedSorted(@Param("code") String code);

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE BudgetDisbursement a SET a.deletedAt = CURRENT_TIMESTAMP, a.version = (a.version + 1) WHERE a.id IN :ids")
  void softDeleteByIds(@Param("ids") Collection<Long> ids);

  @Query(value = "SELECT * FROM budget_disbursement WHERE id = :id LIMIT 1", nativeQuery = true)
  Optional<BudgetDisbursement> findByIdIncludingDeleted(@Param("id") Long id);

  @Query(
      value =
          "SELECT * FROM budget_disbursement WHERE id = :id AND (app_code = :appCode OR :appCode IS NULL) LIMIT 1",
      nativeQuery = true)
  Optional<BudgetDisbursement> findByIdIncludingDeletedByAppCode(
      @Param("id") Long id, @Param("appCode") String appCode);

  Optional<BudgetDisbursement> findFirstByPaymentTxCodeIgnoreCase(String paymentTxCode);

  Optional<BudgetDisbursement> findFirstByPayTransactionId(Long payTransactionId);

  List<BudgetDisbursement> findByAllocationIdOrderByIdAsc(Long allocationId);

  List<BudgetDisbursement> findByBudgetYearOrderByIdDesc(Integer budgetYear);

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
