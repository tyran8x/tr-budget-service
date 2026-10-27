package vn.tr.budget.data.validator;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import vn.tr.budget.dao.service.BudgetEstimateItemService;
import vn.tr.budget.data.dto.BudgetEstimateItemData;
import vn.tr.common.core.utils.MessageUtils;
import vn.tr.common.web.validator.BaseValidator;

/**
 * Validator cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
@Component
@RequiredArgsConstructor
public class BudgetEstimateItemValidator extends BaseValidator {

  private final BudgetEstimateItemService service;

  @Override
  public boolean supports(Class<?> clazz) {
    return BudgetEstimateItemData.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(Object target, Errors errors) {
    if (target == null) {
      rejectTargetNull(errors, "BudgetEstimateItemData", MessageUtils.message("error.target.null", "BudgetEstimateItemData"));
      return;
    }

    BudgetEstimateItemData data = (BudgetEstimateItemData) target;

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
