package vn.tr.budget.data.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload đồng bộ toàn bộ Cây Dự toán Thu - Chi từ Trình thiết kế trực quan (Tree Builder).
 *
 * @author tyran8x
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetEstimateTreeSaveRequest {

  private Integer budgetYear;

  /** Nếu có giá trị (REVENUE/EXPENDITURE), phạm vi prune chỉ áp dụng trong nhóm đó. */
  private String itemType;

  /** True: Xóa mềm các node thuộc cùng năm/nhóm không còn trong cây gửi lên. */
  @Builder.Default
  private Boolean pruneRemoved = Boolean.TRUE;

  @Builder.Default
  private List<TreeNodePayload> nodes = new ArrayList<>();

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TreeNodePayload {

    private Long id;

    private String code;

    private String name;

    private String description;

    private Long parentId;

    private String parentCode;

    private String itemType;

    private String numberingStyle;

    private Boolean isHeader;

    private String formula;

    private Long categoryId;

    private String categoryCode;

    private String categoryName;

    private String subItemCode;

    private String fundingSourceCode;

    private String autonomyType;

    private String unitOfMeasure;

    @JsonAlias("normAmount")
    private BigDecimal normStandardAmount;

    private BigDecimal maxCeilingAmount;

    private String legalBasisRef;

    @JsonAlias({"isRequireVoucher", "isMandatoryEvidence"})
    private Boolean requireVoucher;

    private Integer sortOrder;

    @Builder.Default
    private List<TreeNodePayload> children = new ArrayList<>();
  }
}
