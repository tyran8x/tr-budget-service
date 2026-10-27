package vn.tr.budget.data.validator;

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
      rejectTargetNull(errors, "BudgetTransactionData", MessageUtils.message("error.target.null", "BudgetTransactionData"));
      return;
    }

    BudgetTransactionData data = (BudgetTransactionData) target;

    if (data.getCode() != null && !data.getCode().isBlank()) {
      validateDuplicateCode(
          errors,
          "code",
          data.getCode(),
          isDuplicateCode(data.getId(), data.getCode()),
          MessageUtils.message("error.code.duplicate", data.getCode()));
    }
  }

  private boolean isDuplicateCode(Long id, String code) {
    if (id != null) {
      return service.existsByIdNotAndCodeIgnoreCase(id, code);
    }
    return service.existsByCodeIgnoreCase(code);
  }
}
