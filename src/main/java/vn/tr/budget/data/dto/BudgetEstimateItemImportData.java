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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.tr.common.excel.annotation.ExcelDataType;
import vn.tr.common.excel.annotation.ExcelRequired;

/**
 * DTO Import/Export Excel cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@HeadStyle(fillPatternType = FillPatternTypeEnum.SOLID_FOREGROUND, fillForegroundColor = 22)
@HeadFontStyle(fontHeightInPoints = 11, bold = BooleanEnum.TRUE)
public class BudgetEstimateItemImportData {

  @ExcelProperty(value = "STT", index = 0)
  @ColumnWidth(8)
  private String stt;

  @ExcelProperty(value = "Mã chỉ tiêu", index = 1)
  @ExcelRequired
  @ExcelDataType(value = ExcelDataType.DataType.STRING, uniqueKey = true)
  @ColumnWidth(24)
  @NotBlank(message = "Mã không được để trống")
  @Size(max = 100, message = "Mã không được vượt quá 100 ký tự")
  private String code;

  @ExcelProperty(value = "Tên chỉ tiêu dự toán", index = 2)
  @ExcelRequired
  @ColumnWidth(45)
  @NotBlank(message = "Tên không được để trống")
  @Size(max = 500, message = "Tên không được vượt quá 500 ký tự")
  private String name;

  @ExcelProperty(value = "Năm ngân sách", index = 3)
  @ColumnWidth(16)
  private Integer budgetYear;

  @ExcelProperty(value = "Loại chỉ tiêu (REVENUE/EXPENDITURE)", index = 4)
  @ColumnWidth(24)
  private String itemType;

  @ExcelProperty(value = "Mã chỉ tiêu cha", index = 5)
  @ColumnWidth(20)
  private String parentCode;

  @ExcelProperty(value = "Cấp độ cây", index = 6)
  @ColumnWidth(14)
  private Integer levelDepth;

  @ExcelProperty(value = "Đơn vị tính", index = 7)
  @ColumnWidth(16)
  private String unitOfMeasure;

  @ExcelProperty(value = "Định mức chuẩn (VNĐ)", index = 8)
  @ColumnWidth(22)
  private BigDecimal normStandardAmount;

  @ExcelProperty(value = "Mô tả", index = 9)
  @ColumnWidth(45)
  private String description;
}
