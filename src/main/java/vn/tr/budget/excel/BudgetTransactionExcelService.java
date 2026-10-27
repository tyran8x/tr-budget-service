package vn.tr.budget.excel;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.service.BudgetTransactionService;
import vn.tr.budget.data.criteria.BudgetTransactionSearchCriteria;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.budget.data.dto.BudgetTransactionImportData;
import vn.tr.budget.data.mapper.BudgetTransactionMapper;
import vn.tr.budget.data.validator.BudgetTransactionValidator;
import vn.tr.common.excel.core.GlobalExcelService;

/**
 * Service Excel cho BudgetTransaction.
 *
 * @author tyran8x
 */
@Service
@Slf4j
public class BudgetTransactionExcelService
    extends GlobalExcelService<BudgetTransaction, BudgetTransactionImportData, BudgetTransactionData, BudgetTransactionSearchCriteria> {

  public BudgetTransactionExcelService(
      BudgetTransactionService service, BudgetTransactionMapper mapper, BudgetTransactionValidator validator) {
    super(
        "BudgetTransaction",
        BudgetTransactionImportData.class,
        service,
        mapper,
        validator,
        BudgetTransactionSearchCriteria::new);
  }

  @Override
  protected String getSheetName() {
    return "Sổ cái Biến động Kinh phí";
  }

  @Override
  protected String getTemplateSheetName() {
    return "Template Import Sổ cái Biến động Kinh phí";
  }

  @Override
  public byte[] generateTemplateWithSample() {
    List<BudgetTransactionImportData> sampleData =
        List.of(
            BudgetTransactionImportData.builder()
                .stt("1")
                .code("BTX-2026-001")
                .name("Bổ sung nguồn kinh phí chuyển đổi số chuyên ngành Quý II/2026")
                .description("Dữ liệu mẫu Sổ cái Biến động Kinh phí")
                .build());
    return super.generateTemplateWithSample(sampleData);
  }
}
