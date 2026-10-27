package vn.tr.budget.data.dto;

import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vn.tr.common.web.data.dto.BaseCommonData;

/**
 * DTO dữ liệu cho BudgetUnitConfig.
 *
 * @author tyran8x
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BudgetUnitConfigData extends BaseCommonData {

  private Long nodeId;

  private String nodeCode;

  private String nodeName;

  private String unitType;

  private String treasuryAccountNo;

  private String treasuryBranchName;

  private String taxCode;

  private Boolean canSelfAllocate;

  private Boolean canSelfTransfer;

  private Boolean canCarryOver;

  private BigDecimal selfApproveLimitAmount;

  private String allowedCategoryCodesJson;

  private Long chiefAccountantPersonId;

  private String chiefAccountantName;

  private Long approverPersonId;

  private String approverPersonName;
}
