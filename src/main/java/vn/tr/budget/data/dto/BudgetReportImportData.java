package vn.tr.budget.data.dto;

import cn.idev.excel.annotation.ExcelProperty;
import cn.idev.excel.annotation.write.style.ColumnWidth;
import cn.idev.excel.annotation.write.style.HeadFontStyle;
import cn.idev.excel.annotation.write.style.HeadStyle;
import cn.idev.excel.enums.BooleanEnum;
import cn.idev.excel.enums.poi.FillPatternTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.tr.common.excel.annotation.ExcelDataType;
import vn.tr.common.excel.annotation.ExcelRequired;

/**
 * DTO Import/Export Excel cho BudgetReport.
 *
 * @author tyran8x
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@HeadStyle(fillPatternType = FillPatternTypeEnum.SOLID_FOREGROUND, fillForegroundColor = 22)
@HeadFontStyle(fontHeightInPoints = 11, bold = BooleanEnum.TRUE)
public class BudgetReportImportData {

  @ExcelProperty(value = "STT", index = 0)
  @ColumnWidth(8)
  private String stt;

  @ExcelProperty(value = "Mã báo cáo", index = 1)
  @ExcelRequired
  @ExcelDataType(value = ExcelDataType.DataType.STRING, uniqueKey = true)
  @ColumnWidth(24)
  @NotBlank(message = "Mã không được để trống")
  @Size(max = 100, message = "Mã không được vượt quá 100 ký tự")
  private String code;

  @ExcelProperty(value = "Tên báo cáo", index = 2)
  @ExcelRequired
  @ColumnWidth(45)
  @NotBlank(message = "Tên không được để trống")
  @Size(max = 500, message = "Tên không được vượt quá 500 ký tự")
  private String name;

  @ExcelProperty(value = "Năm ngân sách", index = 3)
  @ColumnWidth(16)
  private Integer budgetYear;

  @ExcelProperty(value = "Kỳ báo cáo", index = 4)
  @ColumnWidth(16)
  private String reportPeriod;

  @ExcelProperty(value = "Phạm vi báo cáo", index = 5)
  @ColumnWidth(18)
  private String reportScope;

  @ExcelProperty(value = "Mã đơn vị", index = 6)
  @ColumnWidth(18)
  private String nodeCode;

  @ExcelProperty(value = "Tên đơn vị", index = 7)
  @ColumnWidth(32)
  private String nodeName;

  @ExcelProperty(value = "Ngày chốt số liệu", index = 8)
  @ColumnWidth(18)
  private LocalDate reportDate;

  @ExcelProperty(value = "Tổng chi dự toán (VNĐ)", index = 9)
  @ColumnWidth(22)
  private BigDecimal totalExpenditurePlan;

  @ExcelProperty(value = "Tổng chi thực hiện (VNĐ)", index = 10)
  @ColumnWidth(22)
  private BigDecimal totalExpenditureActual;

  @ExcelProperty(value = "Tỷ lệ giải ngân (%)", index = 11)
  @ColumnWidth(18)
  private BigDecimal disbursementRate;

  @ExcelProperty(value = "Trạng thái", index = 12)
  @ColumnWidth(20)
  private String approvalStatus;

  @ExcelProperty(value = "Mô tả", index = 13)
  @ColumnWidth(45)
  private String description;
}
