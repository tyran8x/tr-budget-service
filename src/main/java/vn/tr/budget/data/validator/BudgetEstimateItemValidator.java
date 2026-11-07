package vn.tr.budget.data.validator;

import java.math.BigDecimal;
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
      rejectTargetNull(
          errors,
          "BudgetEstimateItemData",
          MessageUtils.messageOrDefault(
              "error.target.null", "Dữ liệu chỉ tiêu dự toán không được để trống", "BudgetEstimateItemData"));
      return;
    }

    BudgetEstimateItemData data = (BudgetEstimateItemData) target;

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

    if (data.getNormStandardAmount() != null
        && data.getNormStandardAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "normStandardAmount",
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
