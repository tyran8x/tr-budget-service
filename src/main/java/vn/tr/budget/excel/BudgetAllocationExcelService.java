package vn.tr.budget.excel;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.service.BudgetAllocationService;
import vn.tr.budget.data.criteria.BudgetAllocationSearchCriteria;
import vn.tr.budget.data.dto.BudgetAllocationData;
import vn.tr.budget.data.dto.BudgetAllocationImportData;
import vn.tr.budget.data.mapper.BudgetAllocationMapper;
import vn.tr.budget.data.validator.BudgetAllocationValidator;
import vn.tr.common.excel.core.GlobalExcelService;

/**
 * Service Excel cho BudgetAllocation.
 *
 * @author tyran8x
 */
@Service
@Slf4j
public class BudgetAllocationExcelService
    extends GlobalExcelService<BudgetAllocation, BudgetAllocationImportData, BudgetAllocationData, BudgetAllocationSearchCriteria> {

  public BudgetAllocationExcelService(
      BudgetAllocationService service, BudgetAllocationMapper mapper, BudgetAllocationValidator validator) {
    super(
        "BudgetAllocation",
        BudgetAllocationImportData.class,
        service,
        mapper,
        validator,
        BudgetAllocationSearchCriteria::new);
  }

  @Override
  protected String getSheetName() {
    return "Phân bổ Dự toán Kinh phí";
  }

  @Override
  protected String getTemplateSheetName() {
    return "Template Import Phân bổ Dự toán Kinh phí";
  }

  @Override
  public byte[] generateTemplateWithSample() {
    List<BudgetAllocationImportData> sampleData =
        List.of(
            BudgetAllocationImportData.builder()
                .stt("1")
                .code("PB-2026-KHTC-01")
                .name("Phân bổ Dự toán Chi thường xuyên & Chuyển đổi số năm 2026 - Phòng KHTC")
                .description("Dữ liệu mẫu Phân bổ Dự toán Kinh phí")
                .build());
    return super.generateTemplateWithSample(sampleData);
  }
}
