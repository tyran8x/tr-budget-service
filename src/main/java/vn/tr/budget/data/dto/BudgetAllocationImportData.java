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
 * DTO Import/Export Excel cho BudgetAllocation.
 *
 * @author tyran8x
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@HeadStyle(fillPatternType = FillPatternTypeEnum.SOLID_FOREGROUND, fillForegroundColor = 22)
@HeadFontStyle(fontHeightInPoints = 11, bold = BooleanEnum.TRUE)
public class BudgetAllocationImportData {

  @ExcelProperty(value = "STT", index = 0)
  @ColumnWidth(8)
  private String stt;

  @ExcelProperty(value = "Mã phân bổ", index = 1)
  @ExcelRequired
  @ExcelDataType(value = ExcelDataType.DataType.STRING, uniqueKey = true)
  @ColumnWidth(24)
  @NotBlank(message = "Mã không được để trống")
  @Size(max = 100, message = "Mã không được vượt quá 100 ký tự")
  private String code;

  @ExcelProperty(value = "Tên / Nội dung phân bổ", index = 2)
  @ExcelRequired
  @ColumnWidth(45)
  @NotBlank(message = "Tên không được để trống")
  @Size(max = 500, message = "Tên không được vượt quá 500 ký tự")
  private String name;

  @ExcelProperty(value = "Năm ngân sách", index = 3)
  @ColumnWidth(16)
  private Integer budgetYear;

  @ExcelProperty(value = "Kỳ phân bổ", index = 4)
  @ColumnWidth(16)
  private String periodType;

  @ExcelProperty(value = "Mã đơn vị", index = 5)
  @ColumnWidth(20)
  private String nodeCode;

  @ExcelProperty(value = "Tên đơn vị", index = 6)
  @ColumnWidth(35)
  private String nodeName;

  @ExcelProperty(value = "Mã loại khoản", index = 7)
  @ColumnWidth(18)
  private String categoryCode;

  @ExcelProperty(value = "Mã lĩnh vực", index = 8)
  @ColumnWidth(18)
  private String domainCode;

  @ExcelProperty(value = "Nguồn kinh phí", index = 9)
  @ColumnWidth(20)
  private String fundingSourceCode;

  @ExcelProperty(value = "Số đề xuất (VNĐ)", index = 10)
  @ColumnWidth(22)
  private BigDecimal proposedAmount;

  @ExcelProperty(value = "Dự toán đầu năm (VNĐ)", index = 11)
  @ColumnWidth(22)
  private BigDecimal initialAmount;

  @ExcelProperty(value = "Dự toán hiệu lực (VNĐ)", index = 12)
  @ColumnWidth(22)
  private BigDecimal effectiveAmount;

  @ExcelProperty(value = "Đã giải ngân (VNĐ)", index = 13)
  @ColumnWidth(22)
  private BigDecimal disbursedAmount;

  @ExcelProperty(value = "Trạng thái phê duyệt", index = 14)
  @ColumnWidth(20)
  private String approvalStatus;

  @ExcelProperty(value = "Mô tả", index = 15)
  @ColumnWidth(45)
  private String description;
}
