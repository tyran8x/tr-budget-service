package vn.tr.budget.data.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.budget.data.dto.BudgetTransactionImportData;
import vn.tr.common.excel.core.GlobalExcelMapper;

/**
 * Mapper chuyển đổi dữ liệu cho BudgetTransaction.
 *
 * @author tyran8x
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BudgetTransactionMapper
    extends GlobalExcelMapper<BudgetTransaction, BudgetTransactionData, BudgetTransactionImportData> {}
