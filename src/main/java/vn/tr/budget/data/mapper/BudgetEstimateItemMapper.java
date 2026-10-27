package vn.tr.budget.data.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.data.dto.BudgetEstimateItemData;
import vn.tr.budget.data.dto.BudgetEstimateItemImportData;
import vn.tr.common.excel.core.GlobalExcelMapper;

/**
 * Mapper chuyển đổi dữ liệu cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BudgetEstimateItemMapper
    extends GlobalExcelMapper<BudgetEstimateItem, BudgetEstimateItemData, BudgetEstimateItemImportData> {}
