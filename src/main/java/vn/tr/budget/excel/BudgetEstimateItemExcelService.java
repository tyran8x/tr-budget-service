package vn.tr.budget.excel;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.service.BudgetEstimateItemService;
import vn.tr.budget.data.criteria.BudgetEstimateItemSearchCriteria;
import vn.tr.budget.data.dto.BudgetEstimateItemData;
import vn.tr.budget.data.dto.BudgetEstimateItemImportData;
import vn.tr.budget.data.mapper.BudgetEstimateItemMapper;
import vn.tr.budget.data.validator.BudgetEstimateItemValidator;
import vn.tr.common.excel.core.GlobalExcelService;

/**
 * Service Excel cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
@Service
@Slf4j
public class BudgetEstimateItemExcelService
    extends GlobalExcelService<BudgetEstimateItem, BudgetEstimateItemImportData, BudgetEstimateItemData, BudgetEstimateItemSearchCriteria> {

  public BudgetEstimateItemExcelService(
      BudgetEstimateItemService service, BudgetEstimateItemMapper mapper, BudgetEstimateItemValidator validator) {
    super(
        "BudgetEstimateItem",
        BudgetEstimateItemImportData.class,
        service,
        mapper,
        validator,
        BudgetEstimateItemSearchCriteria::new);
  }

  @Override
  protected String getSheetName() {
    return "Cây Dự toán Thu Chi";
  }

  @Override
  protected String getTemplateSheetName() {
    return "Template Import Cây Dự toán Thu Chi";
  }

  @Override
  public byte[] generateTemplateWithSample() {
    List<BudgetEstimateItemImportData> sampleData =
        List.of(
            BudgetEstimateItemImportData.builder()
                .stt("1")
                .code("DT-CHI-01")
                .name("I. Chi thường xuyên giao tự chủ")
                .description("Dữ liệu mẫu Cây Dự toán Thu Chi")
                .build());
    return super.generateTemplateWithSample(sampleData);
  }
}
