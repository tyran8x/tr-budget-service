package vn.tr.budget.data.validator;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import vn.tr.budget.dao.service.BudgetUnitConfigService;
import vn.tr.budget.data.dto.BudgetUnitConfigData;
import vn.tr.common.core.utils.MessageUtils;
import vn.tr.common.web.validator.BaseValidator;

/**
 * Validator cho BudgetUnitConfig.
 *
 * @author tyran8x
 */
@Component
@RequiredArgsConstructor
public class BudgetUnitConfigValidator extends BaseValidator {

  private final BudgetUnitConfigService service;

  @Override
  public boolean supports(Class<?> clazz) {
    return BudgetUnitConfigData.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(Object target, Errors errors) {
    if (target == null) {
      rejectTargetNull(
          errors,
          "BudgetUnitConfigData",
          MessageUtils.messageOrDefault(
              "error.target.null", "Dữ liệu cấu hình đơn vị tự chủ không được để trống", "BudgetUnitConfigData"));
      return;
    }

    BudgetUnitConfigData data = (BudgetUnitConfigData) target;

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

    if (data.getSelfApproveLimitAmount() != null
        && data.getSelfApproveLimitAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "selfApproveLimitAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }

    if (data.getRecurrentSelfGuaranteeRate() != null
        && (data.getRecurrentSelfGuaranteeRate().compareTo(BigDecimal.ZERO) < 0
            || data.getRecurrentSelfGuaranteeRate().compareTo(new BigDecimal("100")) > 0)) {
      errors.rejectValue(
          "recurrentSelfGuaranteeRate",
          "budget.error.rate.invalid",
          MessageUtils.messageOrDefault(
              "budget.error.rate.invalid",
              "Tỷ lệ tự bảo đảm chi thường xuyên phải nằm trong khoảng từ 0% đến 100%"));
    }
  }

  private boolean isDuplicateCode(Long id, String code) {
    if (id != null) {
      return service.existsByIdNotAndCodeIgnoreCase(id, code);
    }
    return service.existsByCodeIgnoreCase(code);
  }
}
