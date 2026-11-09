package vn.tr.budget.data.validator;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import vn.tr.budget.dao.service.BudgetAllocationService;
import vn.tr.budget.data.dto.BudgetAllocationData;
import vn.tr.common.core.exception.base.BaseException;
import vn.tr.common.core.utils.MessageUtils;
import vn.tr.common.feign.identity.TemporalTargetScopeHelper;
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

  @Autowired(required = false)
  private TemporalTargetScopeHelper temporalScopeHelper =
      new TemporalTargetScopeHelper();
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private vn.tr.common.feign.identity.UnifiedIdentityResolver unifiedIdentityResolver;

  @jakarta.annotation.PostConstruct
  void initTemporalScopeHelper() {
      if (temporalScopeHelper != null && temporalScopeHelper.getIdentityResolver() == null && unifiedIdentityResolver != null) {
          temporalScopeHelper.setIdentityResolver(unifiedIdentityResolver);
      }
  }

  @Override
  public boolean supports(Class<?> clazz) {
    return BudgetAllocationData.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(Object target, Errors errors) {
    if (target == null) {
      rejectTargetNull(
          errors,
          "BudgetAllocationData",
          MessageUtils.messageOrDefault(
              "error.target.null", "Dữ liệu phân bổ dự toán không được để trống", "BudgetAllocationData"));
      return;
    }

    BudgetAllocationData data = (BudgetAllocationData) target;

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

    if (data.getInitialAmount() != null
        && data.getInitialAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "initialAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }

    if (data.getProposedAmount() != null
        && data.getProposedAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "proposedAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }

    if (data.getSupplementAmount() != null
        && data.getSupplementAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "supplementAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }

    if (data.getCarryOverInAmount() != null
        && data.getCarryOverInAmount().compareTo(BigDecimal.ZERO) < 0) {
      errors.rejectValue(
          "carryOverInAmount",
          "budget.error.amount.negative",
          MessageUtils.messageOrDefault(
              "budget.error.amount.negative",
              "Số tiền dự toán / kinh phí không được nhỏ hơn 0"));
    }

    if (data.getDecisionDate() != null
        && data.getBudgetYear() != null
        && Math.abs(data.getDecisionDate().getYear() - data.getBudgetYear()) > 1) {
      errors.rejectValue(
          "decisionDate",
          "budget.error.decision.date.mismatch",
          MessageUtils.messageOrDefault(
              "budget.error.decision.date.mismatch",
              "Ngày quyết định giao dự toán không phù hợp với năm ngân sách "
                  + data.getBudgetYear(),
              data.getBudgetYear()));
    }

    if (temporalScopeHelper != null
        && (data.getAgencyCode() != null
            || data.getTemporalRegime() != null
            || data.getNodeId() != null
            || data.getNodeCode() != null
            || data.getNodeName() != null
            || data.getSubmittedByPersonId() != null
            || data.getSubmittedByPersonName() != null)) {
      try {
        var resolved =
            temporalScopeHelper.validateScopeSelection(
                data.getAgencyCode(),
                data.getTemporalRegime(),
                data.getDecisionDate(),
                data.getDecisionDate(),
                data.getBudgetYear(),
                data.getNodeId(),
                data.getNodeCode(),
                data.getNodeName(),
                data.getSubmittedByPersonId(),
                null,
                data.getSubmittedByPersonName(),
                data.getSubmittedByUserId(),
                null);
        data.setAgencyCode((String) resolved.get("agencyCode"));
        if (resolved.get("agencyName") != null) {
          data.setAgencyName((String) resolved.get("agencyName"));
        }
        data.setTemporalRegime((String) resolved.get("temporalRegime"));
        if (resolved.get("resolvedNodeId") != null && data.getNodeId() == null) {
          data.setNodeId((Long) resolved.get("resolvedNodeId"));
        }
        if (resolved.get("resolvedNodeCode") != null
            && (data.getNodeCode() == null || data.getNodeCode().isBlank())) {
          data.setNodeCode((String) resolved.get("resolvedNodeCode"));
        }
        if (resolved.get("resolvedNodeName") != null
            && (data.getNodeName() == null || data.getNodeName().isBlank())) {
          data.setNodeName((String) resolved.get("resolvedNodeName"));
        }
        if (resolved.get("resolvedPersonId") != null && data.getSubmittedByPersonId() == null) {
          data.setSubmittedByPersonId((Long) resolved.get("resolvedPersonId"));
        }
        if (resolved.get("resolvedPersonName") != null
            && (data.getSubmittedByPersonName() == null
                || data.getSubmittedByPersonName().isBlank())) {
          data.setSubmittedByPersonName((String) resolved.get("resolvedPersonName"));
        }
        if (resolved.get("resolvedUserId") != null && data.getSubmittedByUserId() == null) {
          data.setSubmittedByUserId((Long) resolved.get("resolvedUserId"));
        }
        if (data.getApprovedByPersonId() != null || data.getApprovedByUserId() != null) {
          var approverResolved =
              temporalScopeHelper.validateScopeSelection(
                  data.getAgencyCode(),
                  data.getTemporalRegime(),
                  data.getDecisionDate(),
                  data.getDecisionDate(),
                  data.getBudgetYear(),
                  null,
                  null,
                  null,
                  data.getApprovedByPersonId(),
                  null,
                  data.getApprovedByPersonName(),
                  data.getApprovedByUserId(),
                  null);
          if (approverResolved.get("resolvedPersonId") != null
              && data.getApprovedByPersonId() == null) {
            data.setApprovedByPersonId((Long) approverResolved.get("resolvedPersonId"));
          }
          if (approverResolved.get("resolvedPersonName") != null
              && (data.getApprovedByPersonName() == null
                  || data.getApprovedByPersonName().isBlank())) {
            data.setApprovedByPersonName((String) approverResolved.get("resolvedPersonName"));
          }
          if (approverResolved.get("resolvedUserId") != null
              && data.getApprovedByUserId() == null) {
            data.setApprovedByUserId((Long) approverResolved.get("resolvedUserId"));
          }
        }
        data.setLineageTransitionType((String) resolved.get("transitionType"));
        data.setSuccessorNodeCode((String) resolved.get("successorNodeCode"));
        data.setSuccessorNodeName((String) resolved.get("successorNodeName"));
      } catch (BaseException ex) {
        errors.rejectValue("nodeName", "budget.error.org.scope.mismatch", ex.getMessage());
      }
    }
  }

  private boolean isDuplicateCode(Long id, String code) {
    if (id != null) {
      return service.existsByIdNotAndCodeIgnoreCase(id, code);
    }
    return service.existsByCodeIgnoreCase(code);
  }
}
