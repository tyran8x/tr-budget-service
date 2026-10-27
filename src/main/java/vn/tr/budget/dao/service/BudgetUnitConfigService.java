package vn.tr.budget.dao.service;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.data.criteria.BudgetUnitConfigSearchCriteria;
import vn.tr.common.core.service.GlobalCatalogService;

/**
 * Giao diện Service quản lý BudgetUnitConfig.
 *
 * @author tyran8x
 */
public interface BudgetUnitConfigService extends GlobalCatalogService<BudgetUnitConfig, BudgetUnitConfigSearchCriteria> {

  JpaRepository<BudgetUnitConfig, Long> getRepository();

  BudgetUnitConfigRepo getRepo();
}
