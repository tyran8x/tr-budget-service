package vn.tr.budget.dao.service;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.data.criteria.BudgetTransactionSearchCriteria;
import vn.tr.common.core.service.GlobalCatalogService;

/**
 * Giao diện Service quản lý BudgetTransaction.
 *
 * @author tyran8x
 */
public interface BudgetTransactionService extends GlobalCatalogService<BudgetTransaction, BudgetTransactionSearchCriteria> {

  JpaRepository<BudgetTransaction, Long> getRepository();

  BudgetTransactionRepo getRepo();
}
