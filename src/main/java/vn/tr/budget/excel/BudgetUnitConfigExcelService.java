package vn.tr.budget.excel;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetUnitConfigService;
import vn.tr.budget.data.criteria.BudgetUnitConfigSearchCriteria;
import vn.tr.budget.data.dto.BudgetUnitConfigData;
import vn.tr.budget.data.dto.BudgetUnitConfigImportData;
import vn.tr.budget.data.mapper.BudgetUnitConfigMapper;
import vn.tr.budget.data.validator.BudgetUnitConfigValidator;
import vn.tr.common.excel.core.GlobalExcelService;

/**
 * Service Excel cho BudgetUnitConfig.
 *
 * @author tyran8x
 */
@Service
@Slf4j
public class BudgetUnitConfigExcelService
    extends GlobalExcelService<BudgetUnitConfig, BudgetUnitConfigImportData, BudgetUnitConfigData, BudgetUnitConfigSearchCriteria> {

  public BudgetUnitConfigExcelService(
      BudgetUnitConfigService service, BudgetUnitConfigMapper mapper, BudgetUnitConfigValidator validator) {
    super(
        "BudgetUnitConfig",
        BudgetUnitConfigImportData.class,
        service,
        mapper,
        validator,
        BudgetUnitConfigSearchCriteria::new);
  }

  @Override
  protected String getSheetName() {
    return "Cấu hình Đơn vị Ngân sách";
  }

  @Override
  protected String getTemplateSheetName() {
    return "Template Import Cấu hình Đơn vị Ngân sách";
  }

  @Override
  public byte[] generateTemplateWithSample() {
    List<BudgetUnitConfigImportData> sampleData =
        List.of(
            BudgetUnitConfigImportData.builder()
                .stt("1")
                .code("BUC-KHTC-01")
                .name("Cấu hình Tự chủ Kinh phí Phòng Kế hoạch - Tài chính")
                .description("Dữ liệu mẫu Cấu hình Đơn vị Ngân sách")
                .build());
    return super.generateTemplateWithSample(sampleData);
  }
}
