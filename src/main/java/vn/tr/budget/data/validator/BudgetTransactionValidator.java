package vn.tr.budget.data.validator;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import vn.tr.budget.dao.service.BudgetTransactionService;
import vn.tr.budget.data.dto.BudgetTransactionData;
import vn.tr.common.core.utils.MessageUtils;
import vn.tr.common.web.validator.BaseValidator;

/**
 * Validator cho BudgetTransaction.
 *
 * @author tyran8x
 */
@Component
@RequiredArgsConstructor
public class BudgetTransactionValidator extends BaseValidator {

  private final BudgetTransactionService service;

  @Override
  public boolean supports(Class<?> clazz) {
    return BudgetTransactionData.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(Object target, Errors errors) {
    if (target == null) {
      rejectTargetNull(
          errors,
          "BudgetTransactionData",
          MessageUtils.messageOrDefault(
              "error.target.null", "Dữ liệu bút toán sổ cái không được để trống", "BudgetTransactionData"));
      return;
    }

    BudgetTransactionData data = (BudgetTransactionData) target;

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
  }

  private boolean isDuplicateCode(Long id, String code) {
    if (id != null) {
      return service.existsByIdNotAndCodeIgnoreCase(id, code);
    }
    return service.existsByCodeIgnoreCase(code);
  }
}
