package vn.tr.budget.dao.service;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.data.criteria.BudgetDisbursementSearchCriteria;
import vn.tr.common.core.service.GlobalCatalogService;

/**
 * Giao diện Service quản lý BudgetDisbursement.
 *
 * @author tyran8x
 */
public interface BudgetDisbursementService extends GlobalCatalogService<BudgetDisbursement, BudgetDisbursementSearchCriteria> {

  JpaRepository<BudgetDisbursement, Long> getRepository();

  BudgetDisbursementRepo getRepo();
}
