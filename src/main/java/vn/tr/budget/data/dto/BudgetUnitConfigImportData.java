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
 * DTO Import/Export Excel cho BudgetUnitConfig.
 *
 * @author tyran8x
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@HeadStyle(fillPatternType = FillPatternTypeEnum.SOLID_FOREGROUND, fillForegroundColor = 22)
@HeadFontStyle(fontHeightInPoints = 11, bold = BooleanEnum.TRUE)
public class BudgetUnitConfigImportData {

  @ExcelProperty(value = "STT", index = 0)
  @ColumnWidth(8)
  private String stt;

  @ExcelProperty(value = "Mã cấu hình", index = 1)
  @ExcelRequired
  @ExcelDataType(value = ExcelDataType.DataType.STRING, uniqueKey = true)
  @ColumnWidth(24)
  @NotBlank(message = "Mã không được để trống")
  @Size(max = 100, message = "Mã không được vượt quá 100 ký tự")
  private String code;

  @ExcelProperty(value = "Tên đơn vị dự toán", index = 2)
  @ExcelRequired
  @ColumnWidth(45)
  @NotBlank(message = "Tên không được để trống")
  @Size(max = 500, message = "Tên không được vượt quá 500 ký tự")
  private String name;

  @ExcelProperty(value = "Mã đơn vị (Node)", index = 3)
  @ColumnWidth(20)
  private String nodeCode;

  @ExcelProperty(value = "Loại đơn vị / Nhóm tự chủ", index = 4)
  @ColumnWidth(24)
  private String unitType;

  @ExcelProperty(value = "Tài khoản Kho bạc (KBNN)", index = 5)
  @ColumnWidth(24)
  private String treasuryAccountNo;

  @ExcelProperty(value = "Tên Kho bạc giao dịch", index = 6)
  @ColumnWidth(30)
  private String treasuryAgencyName;

  @ExcelProperty(value = "Hạn mức tự phê duyệt (VNĐ)", index = 7)
  @ColumnWidth(24)
  private BigDecimal selfApproveLimitAmount;

  @ExcelProperty(value = "Mô tả", index = 8)
  @ColumnWidth(45)
  private String description;
}
