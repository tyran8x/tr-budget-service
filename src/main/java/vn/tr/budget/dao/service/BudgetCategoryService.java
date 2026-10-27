package vn.tr.budget.dao.service;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.data.criteria.BudgetCategorySearchCriteria;
import vn.tr.common.core.service.GlobalCatalogService;

/**
 * Giao diện Service quản lý BudgetCategory.
 *
 * @author tyran8x
 */
public interface BudgetCategoryService extends GlobalCatalogService<BudgetCategory, BudgetCategorySearchCriteria> {

  JpaRepository<BudgetCategory, Long> getRepository();

  BudgetCategoryRepo getRepo();
}
