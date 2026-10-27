package vn.tr.budget.dao.service;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.data.criteria.BudgetAllocationSearchCriteria;
import vn.tr.common.core.service.GlobalCatalogService;

/**
 * Giao diện Service quản lý BudgetAllocation.
 *
 * @author tyran8x
 */
public interface BudgetAllocationService extends GlobalCatalogService<BudgetAllocation, BudgetAllocationSearchCriteria> {

  JpaRepository<BudgetAllocation, Long> getRepository();

  BudgetAllocationRepo getRepo();
}
