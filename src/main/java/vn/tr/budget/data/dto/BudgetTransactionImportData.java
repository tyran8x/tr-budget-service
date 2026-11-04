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
 * DTO Import/Export Excel cho BudgetTransaction.
 *
 * @author tyran8x
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@HeadStyle(fillPatternType = FillPatternTypeEnum.SOLID_FOREGROUND, fillForegroundColor = 22)
@HeadFontStyle(fontHeightInPoints = 11, bold = BooleanEnum.TRUE)
public class BudgetTransactionImportData {

  @ExcelProperty(value = "STT", index = 0)
  @ColumnWidth(8)
  private String stt;

  @ExcelProperty(value = "Mã giao dịch", index = 1)
  @ExcelRequired
  @ExcelDataType(value = ExcelDataType.DataType.STRING, uniqueKey = true)
  @ColumnWidth(24)
  @NotBlank(message = "Mã không được để trống")
  @Size(max = 100, message = "Mã không được vượt quá 100 ký tự")
  private String code;

  @ExcelProperty(value = "Nội dung biến động", index = 2)
  @ExcelRequired
  @ColumnWidth(45)
  @NotBlank(message = "Tên không được để trống")
  @Size(max = 500, message = "Tên không được vượt quá 500 ký tự")
  private String name;

  @ExcelProperty(value = "Năm ngân sách", index = 3)
  @ColumnWidth(16)
  private Integer budgetYear;

  @ExcelProperty(value = "Loại giao dịch", index = 4)
  @ColumnWidth(22)
  private String txType;

  @ExcelProperty(value = "Ngày giao dịch", index = 5)
  @ColumnWidth(16)
  private LocalDate txDate;

  @ExcelProperty(value = "Mã đơn vị", index = 6)
  @ColumnWidth(18)
  private String nodeCode;

  @ExcelProperty(value = "Tên đơn vị", index = 7)
  @ColumnWidth(32)
  private String nodeName;

  @ExcelProperty(value = "Mã loại khoản", index = 8)
  @ColumnWidth(18)
  private String categoryCode;

  @ExcelProperty(value = "Số tiền biến động (VNĐ)", index = 9)
  @ColumnWidth(22)
  private BigDecimal amount;

  @ExcelProperty(value = "Số quyết định", index = 10)
  @ColumnWidth(20)
  private String decisionNo;

  @ExcelProperty(value = "Trạng thái", index = 11)
  @ColumnWidth(20)
  private String approvalStatus;

  @ExcelProperty(value = "Mô tả", index = 12)
  @ColumnWidth(45)
  private String description;
}
