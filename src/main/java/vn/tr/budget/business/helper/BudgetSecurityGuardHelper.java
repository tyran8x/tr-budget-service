package vn.tr.budget.business.helper;

import cn.hutool.core.util.StrUtil;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetReport;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.common.core.domain.model.LoginUser;
import vn.tr.common.core.exception.base.PermissionDeniedException;
import vn.tr.common.feign.identity.UnifiedIdentityResolver;
import vn.tr.common.feign.identity.UserPersonContextData;
import vn.tr.common.satoken.utils.LoginHelper;
import vn.tr.common.security.abac.OrgSubtreeAuthorizationHelper;

/**
 * Lớp bảo vệ an ninh dùng chung cho toàn bộ phân hệ Ngân sách:
 *
 * <ol>
 *   <li>Trích xuất danh tính tác nhân (Actor) TUYỆT ĐỐI từ phiên Sa-Token ({@link LoginHelper} /
 *       {@link UnifiedIdentityResolver}), cấm đọc từ HTTP Request Body (chống Actor Spoofing).
 *   <li>Cưỡng chế nguyên tắc kiểm soát 4 mắt (Segregation of Duties — SoD: {@code creator !=
 *       reviewer != approver}).
 *   <li>Cưỡng chế phân quyền phạm vi cây tổ chức (ABAC Org-Subtree via {@link
 *       OrgSubtreeAuthorizationHelper}) chặn lỗ hổng IDOR xuyên {@code nodeId}.
 *   <li>Khử Mass Assignment các trường tài chính tính toán và phê duyệt khi tạo/cập nhật.
 * </ol>
 *
 * @author tyran8x
 */
@Component
@Slf4j
public class BudgetSecurityGuardHelper {

  @Autowired(required = false)
  private OrgSubtreeAuthorizationHelper orgSubtreeAuthorizationHelper;

  @Autowired(required = false)
  private UnifiedIdentityResolver identityResolver;

  public void setOrgSubtreeAuthorizationHelper(
      OrgSubtreeAuthorizationHelper orgSubtreeAuthorizationHelper) {
    this.orgSubtreeAuthorizationHelper = orgSubtreeAuthorizationHelper;
  }

  public void setIdentityResolver(UnifiedIdentityResolver identityResolver) {
    this.identityResolver = identityResolver;
  }

  /**
   * Giải mã Person ID / User ID của tác nhân hiện tại chỉ từ phiên đăng nhập Sa-Token. Không bao
   * giờ đọc từ Request Body.
   */
  public Long resolveAuthenticatedPersonId() {
    if (identityResolver != null) {
      try {
        Optional<UserPersonContextData> resolvedOpt = identityResolver.resolveCurrentUser();
        if (resolvedOpt != null && resolvedOpt.isPresent()) {
          UserPersonContextData resolved = resolvedOpt.get();
          if (resolved.getPersonId() != null) {
            return resolved.getPersonId();
          }
          if (resolved.getUserId() != null) {
            return resolved.getUserId();
          }
        }
      } catch (Exception e) {
        log.debug("[BudgetSecurityGuard] Không thể phân giải UnifiedIdentity: {}", e.getMessage());
      }
    }
    try {
      Long userId = LoginHelper.getUserId();
      if (userId != null) {
        return userId;
      }
      LoginUser loginUser = LoginHelper.getLoginUser();
      if (loginUser != null && StrUtil.isNotBlank(loginUser.getUserId())) {
        return Long.valueOf(loginUser.getUserId().trim());
      }
    } catch (Exception ignored) {
      // Không có phiên Sa-Token hoặc userId không phải số
    }
    return null;
  }

  /** Giải mã User ID (CoreUser) của tác nhân hiện tại chỉ từ phiên Sa-Token. */
  public Long resolveAuthenticatedUserId() {
    try {
      return LoginHelper.getUserId();
    } catch (Exception ignored) {
      return null;
    }
  }

  /** Giải mã tên hiển thị tác nhân chỉ từ phiên Sa-Token (cấm đọc từ HTTP body). */
  public String resolveAuthenticatedActorName() {
    try {
      LoginUser loginUser = LoginHelper.getLoginUser();
      if (loginUser != null) {
        if (StrUtil.isNotBlank(loginUser.getFullName())) {
          return loginUser.getFullName().trim();
        }
        if (StrUtil.isNotBlank(loginUser.getUsername())) {
          return loginUser.getUsername().trim();
        }
      }
      String username = LoginHelper.getUsername();
      if (StrUtil.isNotBlank(username)) {
        return username.trim();
      }
    } catch (Exception ignored) {
      // Fallback sang UnifiedIdentityResolver
    }
    if (identityResolver != null) {
      try {
        Optional<UserPersonContextData> resolvedOpt = identityResolver.resolveCurrentUser();
        if (resolvedOpt != null && resolvedOpt.isPresent()) {
          UserPersonContextData resolved = resolvedOpt.get();
          if (StrUtil.isNotBlank(resolved.getDisplayName())) {
            return resolved.getDisplayName().trim();
          }
          if (StrUtil.isNotBlank(resolved.getUsername())) {
            return resolved.getUsername().trim();
          }
        }
      } catch (Exception ignored) {
        // Fallback sang system
      }
    }
    return "Cán bộ Tài chính - Kế toán";
  }

  /** Giải mã username đăng nhập hiện tại từ phiên Sa-Token. */
  public String resolveAuthenticatedUsername() {
    try {
      String username = LoginHelper.getUsername();
      return StrUtil.isNotBlank(username) ? username.trim() : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * Kiểm tra nguyên tắc kiểm soát 4 mắt (SoD) cho thao tác phê duyệt / tự phê duyệt / chốt đối
   * chiếu: {@code creator/submitter != approver} và {@code reviewer != approver}.
   */
  public void assertFourEyesApproval(
      String entityLabel,
      String entityCode,
      Long creatorOrSubmitterPersonId,
      String createdByUsername,
      Long reviewerPersonId) {
    Long actorPersonId = resolveAuthenticatedPersonId();
    Long actorUserId = resolveAuthenticatedUserId();
    String actorUsername = resolveAuthenticatedUsername();

    boolean sameByPersonId =
        creatorOrSubmitterPersonId != null
            && ((actorPersonId != null && actorPersonId.equals(creatorOrSubmitterPersonId))
                || (actorUserId != null && actorUserId.equals(creatorOrSubmitterPersonId)));
    boolean sameByUsername =
        StrUtil.isNotBlank(createdByUsername)
            && StrUtil.isNotBlank(actorUsername)
            && createdByUsername.trim().equalsIgnoreCase(actorUsername.trim());

    if (sameByPersonId || sameByUsername) {
      throw new PermissionDeniedException(
          "Vi phạm nguyên tắc kiểm soát 4 mắt (SoD): người phê duyệt không được trùng với người lập/trình "
              + entityLabel
              + " ["
              + entityCode
              + "].");
    }

    boolean sameAsReviewer =
        reviewerPersonId != null
            && ((actorPersonId != null && actorPersonId.equals(reviewerPersonId))
                || (actorUserId != null && actorUserId.equals(reviewerPersonId)));
    if (sameAsReviewer) {
      throw new PermissionDeniedException(
          "Vi phạm nguyên tắc kiểm soát 4 mắt (SoD): người phê duyệt cuối cùng không được trùng với người thẩm định "
              + entityLabel
              + " ["
              + entityCode
              + "].");
    }
  }

  /**
   * Kiểm tra nguyên tắc 4 mắt (SoD) cho thao tác thẩm định / đối chiếu Kho bạc: người thẩm định
   * không được trùng người lập hoặc người trình.
   */
  public void assertFourEyesReview(
      String entityLabel,
      String entityCode,
      Long creatorOrSubmitterPersonId,
      String createdByUsername) {
    Long actorPersonId = resolveAuthenticatedPersonId();
    Long actorUserId = resolveAuthenticatedUserId();
    String actorUsername = resolveAuthenticatedUsername();

    boolean sameByPersonId =
        creatorOrSubmitterPersonId != null
            && ((actorPersonId != null && actorPersonId.equals(creatorOrSubmitterPersonId))
                || (actorUserId != null && actorUserId.equals(creatorOrSubmitterPersonId)));
    boolean sameByUsername =
        StrUtil.isNotBlank(createdByUsername)
            && StrUtil.isNotBlank(actorUsername)
            && createdByUsername.trim().equalsIgnoreCase(actorUsername.trim());

    if (sameByPersonId || sameByUsername) {
      throw new PermissionDeniedException(
          "Vi phạm nguyên tắc kiểm soát 4 mắt (SoD): người thẩm định/đối chiếu không được trùng với người lập/trình "
              + entityLabel
              + " ["
              + entityCode
              + "].");
    }
  }

  /**
   * Cưỡng chế ABAC phạm vi cây đơn vị ({@link OrgSubtreeAuthorizationHelper}) cho các bản ghi gắn
   * {@code nodeId}/{@code nodeCode}.
   */
  public void assertNodeAccess(Long nodeId, String nodeCode) {
    if (orgSubtreeAuthorizationHelper == null) {
      return;
    }
    orgSubtreeAuthorizationHelper.assertCurrentCallerWithinSubtree(nodeId, null, nodeCode, null);
  }

  /**
   * Cưỡng chế ABAC khi bản ghi có gắn {@code nodeId} hoặc {@code nodeCode} (bỏ qua danh mục dùng
   * chung toàn hệ thống).
   */
  public void assertNodeAccessIfPresent(Long nodeId, String nodeCode) {
    if (nodeId == null && StrUtil.isBlank(nodeCode)) {
      return;
    }
    assertNodeAccess(nodeId, nodeCode);
  }

  /** Trả về phạm vi cây đơn vị của caller hiện tại cho tầng truy vấn danh sách/dashboard. */
  public OrgSubtreeAuthorizationHelper.OrgSubtreeScope resolveCurrentScope() {
    if (orgSubtreeAuthorizationHelper == null) {
      return null;
    }
    return orgSubtreeAuthorizationHelper.resolveCurrentCallerScope();
  }

  // ── Mass Assignment Sanitization (TD-C06) ─────────────────────────────────

  public void sanitizeAllocationForCreate(BudgetAllocation entity) {
    entity.setWorkflowStatus("DRAFT");
    entity.setWfInstanceId(null);
    entity.setSubmittedByPersonId(null);
    entity.setSubmittedByPersonName(null);
    entity.setSubmittedDate(null);
    entity.setApprovedByPersonId(null);
    entity.setApprovedByPersonName(null);
    entity.setApprovedDate(null);
    entity.setReviewComment(null);
  }

  public void sanitizeAllocationForUpdate(BudgetAllocation incoming, BudgetAllocation existing) {
    incoming.setWorkflowStatus(existing.getWorkflowStatus());
    incoming.setWfInstanceId(existing.getWfInstanceId());
    incoming.setCurrentVersion(existing.getCurrentVersion());
    incoming.setSupplementAmount(existing.getSupplementAmount());
    incoming.setCarryOverInAmount(existing.getCarryOverInAmount());
    incoming.setCarryOverOutAmount(existing.getCarryOverOutAmount());
    incoming.setTransferNetAmount(existing.getTransferNetAmount());
    incoming.setReductionAmount(existing.getReductionAmount());
    incoming.setEffectiveAmount(existing.getEffectiveAmount());
    incoming.setCommittedAmount(existing.getCommittedAmount());
    incoming.setAdvancedAmount(existing.getAdvancedAmount());
    incoming.setDisbursedAmount(existing.getDisbursedAmount());
    incoming.setSettledAmount(existing.getSettledAmount());
    incoming.setAvailableAmount(existing.getAvailableAmount());
    incoming.setExpiredCancelledAmount(existing.getExpiredCancelledAmount());
    incoming.setDisbursementRatePercent(existing.getDisbursementRatePercent());
    incoming.setSubmittedByPersonId(existing.getSubmittedByPersonId());
    incoming.setSubmittedByPersonName(existing.getSubmittedByPersonName());
    incoming.setSubmittedDate(existing.getSubmittedDate());
    incoming.setApprovedByPersonId(existing.getApprovedByPersonId());
    incoming.setApprovedByPersonName(existing.getApprovedByPersonName());
    incoming.setApprovedDate(existing.getApprovedDate());
    incoming.setReviewComment(existing.getReviewComment());
  }

  public void sanitizeDisbursementForCreate(BudgetDisbursement entity) {
    entity.setApprovalStatus("DRAFT");
    entity.setWfInstanceId(null);
    entity.setApprovedByPersonId(null);
    entity.setApprovedByPersonName(null);
    entity.setApprovedDate(null);
    entity.setSettledDate(null);
    entity.setApprovedAmount(BigDecimal.ZERO);
    entity.setSettledAmount(BigDecimal.ZERO);
    entity.setPayTransactionId(null);
    entity.setPayEReceiptId(null);
    entity.setPaymentTxCode(null);
    entity.setDvcKbnnDossierNo(null);
    entity.setIsOverdueWarningSent(Boolean.FALSE);
  }

  public void sanitizeDisbursementForUpdate(
      BudgetDisbursement incoming, BudgetDisbursement existing) {
    incoming.setApprovalStatus(existing.getApprovalStatus());
    incoming.setWfInstanceId(existing.getWfInstanceId());
    incoming.setApprovedByPersonId(existing.getApprovedByPersonId());
    incoming.setApprovedByPersonName(existing.getApprovedByPersonName());
    incoming.setApprovedDate(existing.getApprovedDate());
    incoming.setSettledDate(existing.getSettledDate());
    incoming.setApprovedAmount(existing.getApprovedAmount());
    incoming.setSettledAmount(existing.getSettledAmount());
    incoming.setPayTransactionId(existing.getPayTransactionId());
    incoming.setPayEReceiptId(existing.getPayEReceiptId());
    incoming.setPaymentTxCode(existing.getPaymentTxCode());
    incoming.setDvcKbnnDossierNo(existing.getDvcKbnnDossierNo());
    incoming.setIsOverdueWarningSent(existing.getIsOverdueWarningSent());
  }

  public void sanitizeTransactionForCreate(BudgetTransaction entity) {
    entity.setApprovalStatus("DRAFT");
    entity.setWfInstanceId(null);
    entity.setApprovedByPersonId(null);
    entity.setApprovedByPersonName(null);
    entity.setApprovedDate(null);
    entity.setBalanceBeforeAmount(null);
    entity.setBalanceAfterAmount(null);
    entity.setCounterpartTxId(null);
  }

  public void sanitizeTransactionForUpdate(BudgetTransaction incoming, BudgetTransaction existing) {
    incoming.setApprovalStatus(existing.getApprovalStatus());
    incoming.setWfInstanceId(existing.getWfInstanceId());
    incoming.setApprovedByPersonId(existing.getApprovedByPersonId());
    incoming.setApprovedByPersonName(existing.getApprovedByPersonName());
    incoming.setApprovedDate(existing.getApprovedDate());
    incoming.setBalanceBeforeAmount(existing.getBalanceBeforeAmount());
    incoming.setBalanceAfterAmount(existing.getBalanceAfterAmount());
    incoming.setCounterpartTxId(existing.getCounterpartTxId());
  }

  public void sanitizeReportForCreate(BudgetReport entity) {
    entity.setWorkflowStatus("DRAFT");
    entity.setWfInstanceId(null);
    entity.setSubmittedByPersonId(null);
    entity.setSubmittedByPersonName(null);
    entity.setSubmittedDate(null);
    entity.setReviewedByPersonId(null);
    entity.setReviewedByPersonName(null);
    entity.setApprovedByPersonId(null);
    entity.setApprovedByPersonName(null);
    entity.setApprovedDate(null);
    entity.setTreasuryConfirmationCode(null);
    entity.setTreasuryConfirmedDate(null);
  }

  public void sanitizeReportForUpdate(BudgetReport incoming, BudgetReport existing) {
    incoming.setWorkflowStatus(existing.getWorkflowStatus());
    incoming.setWfInstanceId(existing.getWfInstanceId());
    incoming.setSubmittedByPersonId(existing.getSubmittedByPersonId());
    incoming.setSubmittedByPersonName(existing.getSubmittedByPersonName());
    incoming.setSubmittedDate(existing.getSubmittedDate());
    incoming.setReviewedByPersonId(existing.getReviewedByPersonId());
    incoming.setReviewedByPersonName(existing.getReviewedByPersonName());
    incoming.setApprovedByPersonId(existing.getApprovedByPersonId());
    incoming.setApprovedByPersonName(existing.getApprovedByPersonName());
    incoming.setApprovedDate(existing.getApprovedDate());
    incoming.setTreasuryConfirmationCode(existing.getTreasuryConfirmationCode());
    incoming.setTreasuryConfirmedDate(existing.getTreasuryConfirmedDate());
  }
}
