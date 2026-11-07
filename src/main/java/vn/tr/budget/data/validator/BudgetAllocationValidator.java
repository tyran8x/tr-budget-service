package vn.tr.budget.data.validator;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import vn.tr.budget.dao.service.BudgetAllocationService;
import vn.tr.budget.data.dto.BudgetAllocationData;
import vn.tr.common.core.utils.MessageUtils;
import vn.tr.common.web.validator.BaseValidator;

/**
 * Validator cho BudgetAllocation.
 *
 * @author tyran8x
 */
@Component
@RequiredArgsConstructor
public class BudgetAllocationValidator extends BaseValidator {

  private final BudgetAllocationService service;

  @Override
  public boolean supports(Class<?> clazz) {
    return BudgetAllocationData.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(Object target, Errors errors) {
    if (target == null) {
      rejectTargetNull(
          errors,
          "BudgetAllocationData",
          MessageUtils.messageOrDefault(
              "error.target.null", "Dữ liệu phân bổ dự toán không được để trống", "BudgetAllocationData"));
      return;
    }

    BudgetAllocationData data = (BudgetAllocationData) target;

    if (data.getCode() != null && !data.getCode().isBlank()) {
      validateDuplicateCode(
          errors,
          "code",
          data.getCode(),
          isDuplicateCode(data.getId(), data.getCode()),
          MessageUtils.messageOrDefault(
              "error.code.duplicate",
              "Mã định danh '" + data.getCode() + "' đã tồn tại trong hệ thống",
              data.getCode()));
    }

    int maxYear = LocalDate.now().getYear() + 5;
    if (data.getBudgetYear() != null
        && (data.getBudgetYear() < 2000 || data.getBudgetYear() > maxYear)) {
      errors.rejectValue(
          "budgetYear",
          "budget.error.year.invalid",
          MessageUtils.messageOrDefault(
              "budget.error.year.invalid",
              "Năm ngân sách phải nằm trong khoảng từ 2000 đến " + maxYear,
              maxYear));
    }

    if (data.getInitialAmount() != null
        && data.getInitialAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "initialAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }

    if (data.getProposedAmount() != null
        && data.getProposedAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "proposedAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }

    if (data.getSupplementAmount() != null
        && data.getSupplementAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "supplementAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }

    if (data.getCarryOverInAmount() != null
        && data.getCarryOverInAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "carryOverInAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }

    if (data.getDecisionDate() != null
        && data.getBudgetYear() != null
        && Math.abs(data.getDecisionDate().getYear() - data.getBudgetYear()) > 1) {
      errors.rejectValue(
          "decisionDate",
          "budget.error.decision.date.mismatch",
          MessageUtils.messageOrDefault(
              "budget.error.decision.date.mismatch",
              "Ngày quyết định giao dự toán không phù hợp với năm ngân sách "
                  + data.getBudgetYear(),
              data.getBudgetYear()));
    }
  }

  private boolean isDuplicateCode(Long id, String code) {
    if (id != null) {
      return service.existsByIdNotAndCodeIgnoreCase(id, code);
    }
    return service.existsByCodeIgnoreCase(code);
  }
}
