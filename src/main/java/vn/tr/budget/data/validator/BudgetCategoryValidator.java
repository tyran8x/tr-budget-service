package vn.tr.budget.data.validator;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import vn.tr.budget.dao.service.BudgetCategoryService;
import vn.tr.budget.data.dto.BudgetCategoryData;
import vn.tr.common.core.utils.MessageUtils;
import vn.tr.common.web.validator.BaseValidator;

/**
 * Validator cho BudgetCategory.
 *
 * @author tyran8x
 */
@Component
@RequiredArgsConstructor
public class BudgetCategoryValidator extends BaseValidator {

  private final BudgetCategoryService service;

  @Override
  public boolean supports(Class<?> clazz) {
    return BudgetCategoryData.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(Object target, Errors errors) {
    if (target == null) {
      rejectTargetNull(
          errors,
          "BudgetCategoryData",
          MessageUtils.messageOrDefault(
              "error.target.null", "Dữ liệu danh mục ngân sách không được để trống", "BudgetCategoryData"));
      return;
    }

    BudgetCategoryData data = (BudgetCategoryData) target;

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

    if (data.getAnnualLimitAmount() != null
        && data.getAnnualLimitAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "annualLimitAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }
  }

  private boolean isDuplicateCode(Long id, String code) {
    if (id != null) {
      return service.existsByIdNotAndCodeIgnoreCase(id, code);
    }
    return service.existsByCodeIgnoreCase(code);
  }
}
