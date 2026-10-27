package vn.tr.budget.excel;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.dao.service.BudgetCategoryService;
import vn.tr.budget.data.criteria.BudgetCategorySearchCriteria;
import vn.tr.budget.data.dto.BudgetCategoryData;
import vn.tr.budget.data.dto.BudgetCategoryImportData;
import vn.tr.budget.data.mapper.BudgetCategoryMapper;
import vn.tr.budget.data.validator.BudgetCategoryValidator;
import vn.tr.common.excel.core.GlobalExcelService;

/**
 * Service Excel cho BudgetCategory.
 *
 * @author tyran8x
 */
@Service
@Slf4j
public class BudgetCategoryExcelService
    extends GlobalExcelService<BudgetCategory, BudgetCategoryImportData, BudgetCategoryData, BudgetCategorySearchCriteria> {

  public BudgetCategoryExcelService(
      BudgetCategoryService service, BudgetCategoryMapper mapper, BudgetCategoryValidator validator) {
    super(
        "BudgetCategory",
        BudgetCategoryImportData.class,
        service,
        mapper,
        validator,
        BudgetCategorySearchCriteria::new);
  }

  @Override
  protected String getSheetName() {
    return "Danh mục Loại Khoản";
  }

  @Override
  protected String getTemplateSheetName() {
    return "Template Import Danh mục Loại Khoản";
  }

  @Override
  public byte[] generateTemplateWithSample() {
    List<BudgetCategoryImportData> sampleData =
        List.of(
            BudgetCategoryImportData.builder()
                .stt("1")
                .code("LK-280-281")
                .name("Khoản 281 - Hoạt động quản lý nhà nước và sự nghiệp kinh tế")
                .description("Dữ liệu mẫu Danh mục Loại Khoản")
                .build());
    return super.generateTemplateWithSample(sampleData);
  }
}
