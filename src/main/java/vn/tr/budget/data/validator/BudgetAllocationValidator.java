package vn.tr.budget.data.validator;

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
      rejectTargetNull(errors, "BudgetAllocationData", MessageUtils.message("error.target.null", "BudgetAllocationData"));
      return;
    }

    BudgetAllocationData data = (BudgetAllocationData) target;

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
