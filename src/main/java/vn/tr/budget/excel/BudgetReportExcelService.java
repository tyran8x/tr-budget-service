package vn.tr.budget.excel;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vn.tr.budget.dao.model.BudgetReport;
import vn.tr.budget.dao.service.BudgetReportService;
import vn.tr.budget.data.criteria.BudgetReportSearchCriteria;
import vn.tr.budget.data.dto.BudgetReportData;
import vn.tr.budget.data.dto.BudgetReportImportData;
import vn.tr.budget.data.mapper.BudgetReportMapper;
import vn.tr.budget.data.validator.BudgetReportValidator;
import vn.tr.common.excel.core.GlobalExcelService;

/**
 * Service Excel cho BudgetReport.
 *
 * @author tyran8x
 */
@Service
@Slf4j
public class BudgetReportExcelService
    extends GlobalExcelService<BudgetReport, BudgetReportImportData, BudgetReportData, BudgetReportSearchCriteria> {

  public BudgetReportExcelService(
      BudgetReportService service, BudgetReportMapper mapper, BudgetReportValidator validator) {
    super(
        "BudgetReport",
        BudgetReportImportData.class,
        service,
        mapper,
        validator,
        BudgetReportSearchCriteria::new);
  }

  @Override
  protected String getSheetName() {
    return "Báo cáo Thu Chi Quyết toán";
  }

  @Override
  protected String getTemplateSheetName() {
    return "Template Import Báo cáo Thu Chi Quyết toán";
  }

  @Override
  public byte[] generateTemplateWithSample() {
    List<BudgetReportImportData> sampleData =
        List.of(
            BudgetReportImportData.builder()
                .stt("1")
                .code("BCKP-2026-Q1-SO")
                .name("Báo cáo Tổng hợp Tình hình Thực hiện Dự toán Thu - Chi Ngân sách Quý I/2026")
                .description("Dữ liệu mẫu Báo cáo Thu Chi Quyết toán")
                .build());
    return super.generateTemplateWithSample(sampleData);
  }
}
