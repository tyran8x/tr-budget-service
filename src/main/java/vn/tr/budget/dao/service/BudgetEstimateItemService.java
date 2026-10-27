package vn.tr.budget.dao.service;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.data.criteria.BudgetEstimateItemSearchCriteria;
import vn.tr.common.core.service.GlobalCatalogService;

/**
 * Giao diện Service quản lý BudgetEstimateItem.
 *
 * @author tyran8x
 */
public interface BudgetEstimateItemService extends GlobalCatalogService<BudgetEstimateItem, BudgetEstimateItemSearchCriteria> {

  JpaRepository<BudgetEstimateItem, Long> getRepository();

  BudgetEstimateItemRepo getRepo();
}
