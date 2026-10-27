package vn.tr.budget.data.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.data.dto.BudgetUnitConfigData;
import vn.tr.budget.data.dto.BudgetUnitConfigImportData;
import vn.tr.common.excel.core.GlobalExcelMapper;

/**
 * Mapper chuyển đổi dữ liệu cho BudgetUnitConfig.
 *
 * @author tyran8x
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BudgetUnitConfigMapper
    extends GlobalExcelMapper<BudgetUnitConfig, BudgetUnitConfigData, BudgetUnitConfigImportData> {}
