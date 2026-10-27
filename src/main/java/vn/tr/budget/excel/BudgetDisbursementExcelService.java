package vn.tr.budget.excel;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.service.BudgetDisbursementService;
import vn.tr.budget.data.criteria.BudgetDisbursementSearchCriteria;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.budget.data.dto.BudgetDisbursementImportData;
import vn.tr.budget.data.mapper.BudgetDisbursementMapper;
import vn.tr.budget.data.validator.BudgetDisbursementValidator;
import vn.tr.common.excel.core.GlobalExcelService;

/**
 * Service Excel cho BudgetDisbursement.
 *
 * @author tyran8x
 */
@Service
@Slf4j
public class BudgetDisbursementExcelService
    extends GlobalExcelService<BudgetDisbursement, BudgetDisbursementImportData, BudgetDisbursementData, BudgetDisbursementSearchCriteria> {

  public BudgetDisbursementExcelService(
      BudgetDisbursementService service, BudgetDisbursementMapper mapper, BudgetDisbursementValidator validator) {
    super(
        "BudgetDisbursement",
        BudgetDisbursementImportData.class,
        service,
        mapper,
        validator,
        BudgetDisbursementSearchCriteria::new);
  }

  @Override
  protected String getSheetName() {
    return "Hồ sơ Giải ngân Quyết toán";
  }

  @Override
  protected String getTemplateSheetName() {
    return "Template Import Hồ sơ Giải ngân Quyết toán";
  }

  @Override
  public byte[] generateTemplateWithSample() {
    List<BudgetDisbursementImportData> sampleData =
        List.of(
            BudgetDisbursementImportData.builder()
                .stt("1")
                .code("GN-2026-001")
                .name("Giải ngân đợt 1 Hợp đồng thuê dịch vụ hạ tầng số & truyền dẫn")
                .description("Dữ liệu mẫu Hồ sơ Giải ngân Quyết toán")
                .build());
    return super.generateTemplateWithSample(sampleData);
  }
}
