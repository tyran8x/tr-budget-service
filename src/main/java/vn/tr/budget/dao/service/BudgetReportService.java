package vn.tr.budget.dao.service;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.tr.budget.dao.model.BudgetReport;
import vn.tr.budget.data.criteria.BudgetReportSearchCriteria;
import vn.tr.common.core.service.GlobalCatalogService;

/**
 * Giao diện Service quản lý BudgetReport.
 *
 * @author tyran8x
 */
public interface BudgetReportService extends GlobalCatalogService<BudgetReport, BudgetReportSearchCriteria> {

  JpaRepository<BudgetReport, Long> getRepository();

  BudgetReportRepo getRepo();
}
