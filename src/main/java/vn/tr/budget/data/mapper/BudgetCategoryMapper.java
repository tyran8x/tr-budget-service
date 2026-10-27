package vn.tr.budget.data.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.data.dto.BudgetCategoryData;
import vn.tr.budget.data.dto.BudgetCategoryImportData;
import vn.tr.common.excel.core.GlobalExcelMapper;

/**
 * Mapper chuyển đổi dữ liệu cho BudgetCategory.
 *
 * @author tyran8x
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BudgetCategoryMapper
    extends GlobalExcelMapper<BudgetCategory, BudgetCategoryData, BudgetCategoryImportData> {}
