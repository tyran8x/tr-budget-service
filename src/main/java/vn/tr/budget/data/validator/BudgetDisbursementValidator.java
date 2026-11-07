package vn.tr.budget.data.validator;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import vn.tr.budget.dao.service.BudgetDisbursementService;
import vn.tr.budget.data.dto.BudgetDisbursementData;
import vn.tr.common.core.utils.MessageUtils;
import vn.tr.common.web.validator.BaseValidator;

/**
 * Validator cho BudgetDisbursement.
 *
 * @author tyran8x
 */
@Component
@RequiredArgsConstructor
public class BudgetDisbursementValidator extends BaseValidator {

  private final BudgetDisbursementService service;

  @Override
  public boolean supports(Class<?> clazz) {
    return BudgetDisbursementData.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(Object target, Errors errors) {
    if (target == null) {
      rejectTargetNull(
          errors,
          "BudgetDisbursementData",
          MessageUtils.messageOrDefault(
              "error.target.null", "Dữ liệu đề nghị giải ngân không được để trống", "BudgetDisbursementData"));
      return;
    }

    BudgetDisbursementData data = (BudgetDisbursementData) target;

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

    if (data.getAmount() != null && data.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
      errors.rejectValue(
          "amount",
          "budget.error.amount.nonpositive",
          MessageUtils.messageOrDefault(
              "budget.error.amount.nonpositive",
              "Số tiền đề nghị thanh toán / giao dịch phải lớn hơn 0"));
    }

    if (data.getRequestedAmount() != null
        && data.getRequestedAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "requestedAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }

    if (data.getTaxAmount() != null && data.getTaxAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "taxAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }

    BigDecimal baseAmount =
        data.getAmount() != null ? data.getAmount() : data.getRequestedAmount();
    if (data.getTaxAmount() != null
        && baseAmount != null
        && baseAmount.compareTo(BigDecimal.ZERO) > 0
        && data.getTaxAmount().compareTo(baseAmount) > 0) {
      errors.rejectValue(
          "taxAmount",
          "budget.error.tax.exceeds.amount",
          MessageUtils.messageOrDefault(
              "budget.error.tax.exceeds.amount",
              "Thuế GTGT khấu trừ không được vượt quá tổng số tiền thanh toán"));
    }
  }

  private boolean isDuplicateCode(Long id, String code) {
    if (id != null) {
      return service.existsByIdNotAndCodeIgnoreCase(id, code);
    }
    return service.existsByCodeIgnoreCase(code);
  }
}
