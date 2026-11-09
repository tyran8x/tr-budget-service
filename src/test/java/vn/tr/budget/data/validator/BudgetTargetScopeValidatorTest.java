package vn.tr.budget.data.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import vn.tr.budget.dao.service.BudgetAllocationService;
import vn.tr.budget.data.dto.BudgetAllocationData;

/**
 * Kiểm thử đơn vị tích hợp bộ phân giải dùng chung {@code TemporalTargetScopeHelper} vào {@link
 * BudgetAllocationValidator} của Phân hệ Quản lý Kinh phí.
 */
class BudgetTargetScopeValidatorTest {

  private final BudgetAllocationService allocationService = mock(BudgetAllocationService.class);
  private final BudgetAllocationValidator validator =
      new BudgetAllocationValidator(allocationService);

  @Test
  @DisplayName("TC-BUDGET-NODE-01: Tự động điền và chuẩn hóa RootNode, Node và Target lập dự toán hợp lệ")
  void shouldValidateAndEnrichValidBudgetAllocationScope() {
    BudgetAllocationData data =
        BudgetAllocationData.builder()
            .agencyCode("SXD")
            .temporalRegime("POST_MERGER")
            .budgetYear(2025)
            .decisionDate(LocalDate.of(2025, 8, 15))
            .nodeCode("SXD-HTGT")
            .submittedByPersonId(2004L)
            .initialAmount(new BigDecimal("500000000"))
            .build();

    BeanPropertyBindingResult errors = new BeanPropertyBindingResult(data, "budgetAllocationData");
    validator.validate(data, errors);

    assertThat(errors.hasErrors()).isFalse();
    assertThat(data.getNodeId()).isEqualTo(307L);
    assertThat(data.getNodeName()).isEqualTo("Phòng Quản lý Kết cấu hạ tầng giao thông");
    assertThat(data.getSubmittedByPersonName()).isEqualTo("Trần Quốc Tuấn");
    assertThat(data.getSubmittedByUserId()).isEqualTo(2004L);
    assertThat(data.getLineageTransitionType()).isEqualTo("TRANSFERRED_INTACT");
  }

  @Test
  @DisplayName("TC-BUDGET-NODE-02: Chặn giao dự toán cho Node hoặc Target khác Node gốc chủ quản")
  void shouldRejectCrossRootNodeOrTargetInBudgetAllocation() {
    BudgetAllocationData data =
        BudgetAllocationData.builder()
            .agencyCode("SCT")
            .temporalRegime("POST_MERGER")
            .budgetYear(2025)
            .decisionDate(LocalDate.of(2025, 8, 15))
            .nodeCode("SYT-NVY")
            .nodeName("Phòng Nghiệp vụ Y")
            .initialAmount(new BigDecimal("300000000"))
            .build();

    BeanPropertyBindingResult errors = new BeanPropertyBindingResult(data, "budgetAllocationData");
    validator.validate(data, errors);

    assertThat(errors.hasFieldErrors("nodeName")).isTrue();
    assertThat(errors.getFieldError("nodeName").getDefaultMessage())
        .contains("Phòng Nghiệp vụ Y")
        .contains("Sở Y tế");
  }

  @Test
  @DisplayName("TC-BUDGET-NODE-03: Kiểm thử dữ liệu thật từ tr_directory (dir_node 501 PHONG_KT_CDS, dir_person 102 ThS. Trần Thị Bích & 301 PGS.TS. Võ Quốc Hùng)")
  void shouldValidateRealDatabaseNodeAndTargetsInBudgetAllocation() {
    BudgetAllocationData realData =
        BudgetAllocationData.builder()
            .agencyCode("SO_KHCN_DN")
            .temporalRegime("POST_MERGER")
            .budgetYear(2026)
            .decisionDate(LocalDate.of(2026, 3, 15))
            .nodeId(501L)
            .nodeCode("PHONG_KT_CDS")
            .submittedByPersonId(102L)
            .approvedByPersonId(301L)
            .initialAmount(new BigDecimal("1250000000"))
            .build();

    BeanPropertyBindingResult errors =
        new BeanPropertyBindingResult(realData, "budgetAllocationData");
    validator.validate(realData, errors);

    assertThat(errors.hasErrors()).isFalse();
    assertThat(realData.getAgencyCode()).isEqualTo("SKHCN");
    assertThat(realData.getNodeId()).isEqualTo(501L);
    assertThat(realData.getNodeName()).isEqualTo("Phòng Kỹ thuật & Chuyển đổi số");
    assertThat(realData.getSubmittedByPersonName()).isEqualTo("ThS. Trần Thị Bích");
    assertThat(realData.getSubmittedByUserId()).isEqualTo(102L);
    assertThat(realData.getApprovedByPersonName()).isEqualTo("PGS.TS. Võ Quốc Hùng");
  }
}
