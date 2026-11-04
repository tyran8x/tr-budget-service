package vn.tr.budget.data.dto;

import cn.idev.excel.annotation.ExcelProperty;
import cn.idev.excel.annotation.write.style.ColumnWidth;
import cn.idev.excel.annotation.write.style.HeadFontStyle;
import cn.idev.excel.annotation.write.style.HeadStyle;
import cn.idev.excel.enums.BooleanEnum;
import cn.idev.excel.enums.poi.FillPatternTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.tr.common.excel.annotation.ExcelDataType;
import vn.tr.common.excel.annotation.ExcelRequired;

/**
 * DTO Import/Export Excel cho BudgetCategory.
 *
 * @author tyran8x
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@HeadStyle(fillPatternType = FillPatternTypeEnum.SOLID_FOREGROUND, fillForegroundColor = 22)
@HeadFontStyle(fontHeightInPoints = 11, bold = BooleanEnum.TRUE)
public class BudgetCategoryImportData {

  @ExcelProperty(value = "STT", index = 0)
  @ColumnWidth(8)
  private String stt;

  @ExcelProperty(value = "Mã", index = 1)
  @ExcelRequired
  @ExcelDataType(value = ExcelDataType.DataType.STRING, uniqueKey = true)
  @ColumnWidth(24)
  @NotBlank(message = "Mã không được để trống")
  @Size(max = 100, message = "Mã không được vượt quá 100 ký tự")
  private String code;

  @ExcelProperty(value = "Tên / Nội dung", index = 2)
  @ExcelRequired
  @ColumnWidth(45)
  @NotBlank(message = "Tên không được để trống")
  @Size(max = 500, message = "Tên không được vượt quá 500 ký tự")
  private String name;

  @ExcelProperty(value = "Phân nhóm mục lục", index = 3)
  @ColumnWidth(22)
  private String categoryGroup;

  @ExcelProperty(value = "Mã chương", index = 4)
  @ColumnWidth(14)
  private String chapterCode;

  @ExcelProperty(value = "Mã loại", index = 5)
  @ColumnWidth(14)
  private String typeCode;

  @ExcelProperty(value = "Mã khoản", index = 6)
  @ColumnWidth(14)
  private String clauseCode;

  @ExcelProperty(value = "Mã tiểu mục", index = 7)
  @ColumnWidth(16)
  private String subItemCode;

  @ExcelProperty(value = "Mã lĩnh vực", index = 8)
  @ColumnWidth(18)
  private String domainCode;

  @ExcelProperty(value = "Nguồn kinh phí", index = 9)
  @ColumnWidth(20)
  private String fundingSource;

  @ExcelProperty(value = "Mô tả", index = 10)
  @ColumnWidth(45)
  private String description;
}
