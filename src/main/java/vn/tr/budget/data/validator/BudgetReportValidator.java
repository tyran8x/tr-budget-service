package vn.tr.budget.data.validator;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import vn.tr.budget.dao.service.BudgetReportService;
import vn.tr.budget.data.dto.BudgetReportData;
import vn.tr.common.core.utils.MessageUtils;
import vn.tr.common.web.validator.BaseValidator;

/**
 * Validator cho BudgetReport.
 *
 * @author tyran8x
 */
@Component
@RequiredArgsConstructor
public class BudgetReportValidator extends BaseValidator {

  private final BudgetReportService service;

  @Override
  public boolean supports(Class<?> clazz) {
    return BudgetReportData.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(Object target, Errors errors) {
    if (target == null) {
      rejectTargetNull(errors, "BudgetReportData", MessageUtils.message("error.target.null", "BudgetReportData"));
      return;
    }

    BudgetReportData data = (BudgetReportData) target;

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
