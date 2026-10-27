package vn.tr.budget.data.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.dto.BudgetDisbursementImportData;
import vn.tr.common.excel.core.GlobalExcelMapper;

/**
 * Mapper chuyển đổi dữ liệu cho BudgetDisbursement.
 *
 * @author tyran8x
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BudgetDisbursementMapper
    extends GlobalExcelMapper<BudgetDisbursement, BudgetDisbursementData, BudgetDisbursementImportData> {}
