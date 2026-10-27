package vn.tr.budget.data.validator;

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
      rejectTargetNull(errors, "BudgetCategoryData", MessageUtils.message("error.target.null", "BudgetCategoryData"));
      return;
    }

    BudgetCategoryData data = (BudgetCategoryData) target;

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
