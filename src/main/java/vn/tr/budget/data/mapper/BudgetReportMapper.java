package vn.tr.budget.data.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import vn.tr.budget.dao.model.BudgetReport;
import vn.tr.budget.data.dto.BudgetReportData;
import vn.tr.budget.data.dto.BudgetReportImportData;
import vn.tr.common.excel.core.GlobalExcelMapper;

/**
 * Mapper chuyển đổi dữ liệu cho BudgetReport.
 *
 * @author tyran8x
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BudgetReportMapper
    extends GlobalExcelMapper<BudgetReport, BudgetReportData, BudgetReportImportData> {}
