package vn.tr.budget.business.helper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetCategoryRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.dao.service.BudgetUnitConfigRepo;
import vn.tr.common.core.enums.LifecycleStatus;

/**
 * Động cơ tính toán Cân đối Dự toán Kinh phí Đa kỳ, Kiểm tra Ma trận Tự chủ Tài chính Đơn vị,
 * Sổ cái Bút toán kép (Giao đầu năm, Bổ sung, Chuyển nguồn liên năm N -> N+1, Điều chuyển nội bộ)
 * và Kiểm soát Giải ngân Kho bạc.
 *
 * @author tyran8x
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BudgetWorkflowEngineHelper {

  private final BudgetAllocationRepo budgetAllocationRepo;
  private final BudgetTransactionRepo budgetTransactionRepo;
  private final BudgetDisbursementRepo budgetDisbursementRepo;
  private final BudgetUnitConfigRepo budgetUnitConfigRepo;
  private final BudgetCategoryRepo budgetCategoryRepo;
  private final BudgetEstimateItemRepo budgetEstimateItemRepo;

  /**
   * Kiểm tra cấu hình tự chủ tài chính của Đơn vị / Phòng ban.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> checkUnitAutonomy(
      Long nodeId, String categoryCode, BigDecimal proposedAmount) {
    Map<String, Object> result = new HashMap<>();
    Optional<BudgetUnitConfig> opt =
        nodeId != null
            ? budgetUnitConfigRepo.findFirstByNodeIdOrderByIdDesc(nodeId)
            : Optional.empty();

    boolean allowCarryOver = true;
    if (categoryCode != null && !categoryCode.isBlank()) {
      List<BudgetCategory> cats =
          budgetCategoryRepo.findAllByCodeIgnoreCaseIncludingDeletedSorted(categoryCode);
      if (!cats.isEmpty() && cats.getFirst().getAllowCarryOver() != null) {
        allowCarryOver = cats.getFirst().getAllowCarryOver();
      }
    }

    if (opt.isEmpty()) {
      result.put("configured", false);
      result.put("canSelfAllocate", false);
      result.put("canSelfTransfer", false);
      result.put("canCarryOver", allowCarryOver);
      result.put("canSelfApprove", false);
      result.put("recommendedApprovalScope", "DIRECTORATE");
      return result;
    }

    BudgetUnitConfig cfg = opt.get();
    boolean canSelfAllocate = Boolean.TRUE.equals(cfg.getCanSelfAllocate());
    boolean canSelfTransfer = Boolean.TRUE.equals(cfg.getCanSelfTransfer());
    boolean canCarryOver = Boolean.TRUE.equals(cfg.getCanCarryOver()) && allowCarryOver;
    BigDecimal limit = cfg.getSelfApproveLimitAmount();

    boolean canSelfApprove = canSelfAllocate;
    if (canSelfApprove
        && limit != null
        && limit.compareTo(BigDecimal.ZERO) > 0
        && proposedAmount != null
        && proposedAmount.compareTo(limit) > 0) {
      canSelfApprove = false;
    }

    result.put("configured", true);
    result.put("unitConfigId", cfg.getId());
    result.put("canSelfAllocate", canSelfAllocate);
    result.put("canSelfTransfer", canSelfTransfer);
    result.put("canCarryOver", canCarryOver);
    result.put("canSelfApprove", canSelfApprove);
    result.put("selfApproveLimitAmount", limit);
    result.put("treasuryAccountNo", cfg.getTreasuryAccountNo());
    result.put("recommendedApprovalScope", canSelfApprove ? "UNIT_SELF" : "DIRECTORATE");
    return result;
  }

  /**
   * Tính toán lại toàn bộ chỉ số Sổ cái Kinh phí và Giải ngân của một Phân bổ Dự toán (BudgetAllocation).
   */
  @Transactional
  public BudgetAllocation recalculateAllocationMetrics(Long allocationId) {
    if (allocationId == null) {
      return null;
    }
    Optional<BudgetAllocation> opt = budgetAllocationRepo.findById(allocationId);
    if (opt.isEmpty()) {
      return null;
    }
    BudgetAllocation alloc = opt.get();

    List<BudgetTransaction> txs =
        budgetTransactionRepo.findByAllocationIdOrderByIdAsc(allocationId);
    BigDecimal txInitial = BigDecimal.ZERO;
    BigDecimal txSupplement = BigDecimal.ZERO;
    BigDecimal txCarryIn = BigDecimal.ZERO;
    BigDecimal txCarryOut = BigDecimal.ZERO;
    BigDecimal txTransferNet = BigDecimal.ZERO;
    BigDecimal txReduction = BigDecimal.ZERO;
    BigDecimal txDisbursed = BigDecimal.ZERO;
    boolean hasInitialTx = false;

    for (BudgetTransaction tx : txs) {
      if ("REJECTED".equalsIgnoreCase(tx.getApprovalStatus())
          || "PENDING".equalsIgnoreCase(tx.getApprovalStatus())) {
        continue;
      }
      BigDecimal amt = safe(tx.getAmount());
      txDisbursed = txDisbursed.add(safe(tx.getDisbursedAmount()));
      String type = tx.getTxType() != null ? tx.getTxType().toUpperCase() : "";
      switch (type) {
        case "INITIAL_ALLOCATION" -> {
          txInitial = txInitial.add(amt);
          hasInitialTx = true;
        }
        case "SUPPLEMENT" -> txSupplement = txSupplement.add(amt);
        case "ADJUSTMENT" -> {
          if (amt.compareTo(BigDecimal.ZERO) >= 0) {
            txSupplement = txSupplement.add(amt);
          } else {
            txReduction = txReduction.add(amt.abs());
          }
        }
        case "CARRY_OVER_IN" -> txCarryIn = txCarryIn.add(amt);
        case "CARRY_OVER_OUT" -> txCarryOut = txCarryOut.add(amt.abs());
        case "INTERNAL_TRANSFER", "TRANSFER_IN", "TRANSFER_OUT" -> txTransferNet =
            txTransferNet.add(amt);
        case "REDUCTION", "SAVING_REDUCTION" -> txReduction = txReduction.add(amt.abs());
        default -> {}
      }
    }

    BigDecimal initial = hasInitialTx ? txInitial : safe(alloc.getInitialAmount());
    BigDecimal supplement =
        txSupplement.compareTo(BigDecimal.ZERO) > 0
            ? txSupplement
            : safe(alloc.getSupplementAmount());
    BigDecimal carryIn =
        txCarryIn.compareTo(BigDecimal.ZERO) > 0 ? txCarryIn : safe(alloc.getCarryOverInAmount());
    BigDecimal carryOut =
        txCarryOut.compareTo(BigDecimal.ZERO) > 0
            ? txCarryOut
            : safe(alloc.getCarryOverOutAmount());
    BigDecimal transferNet =
        txTransferNet.compareTo(BigDecimal.ZERO) != 0
            ? txTransferNet
            : safe(alloc.getTransferNetAmount());
    BigDecimal reduction =
        txReduction.compareTo(BigDecimal.ZERO) > 0 ? txReduction : safe(alloc.getReductionAmount());

    BigDecimal effective =
        initial
            .add(supplement)
            .add(carryIn)
            .subtract(carryOut)
            .add(transferNet)
            .subtract(reduction);
    if (effective.compareTo(BigDecimal.ZERO) < 0) {
      effective = BigDecimal.ZERO;
    }

    List<BudgetDisbursement> disbs =
        budgetDisbursementRepo.findByAllocationIdOrderByIdAsc(allocationId);
    BigDecimal committed = BigDecimal.ZERO;
    BigDecimal advanced = BigDecimal.ZERO;
    BigDecimal disbursed = BigDecimal.ZERO;
    BigDecimal settled = BigDecimal.ZERO;

    for (BudgetDisbursement d : disbs) {
      String status = d.getApprovalStatus() != null ? d.getApprovalStatus().toUpperCase() : "";
      if ("REJECTED".equals(status) || "DRAFT".equals(status)) {
        continue;
      }
      BigDecimal amt = safe(d.getAmount());
      BigDecimal sAmt =
          d.getSettledAmount() != null && d.getSettledAmount().compareTo(BigDecimal.ZERO) > 0
              ? d.getSettledAmount()
              : amt;
      String dtype = d.getDisbursementType() != null ? d.getDisbursementType().toUpperCase() : "";
      switch (dtype) {
        case "COMMITMENT" -> committed = committed.add(amt);
        case "ADVANCE" -> {
          advanced = advanced.add(amt);
          if ("SETTLED".equals(status)) {
            disbursed = disbursed.add(amt);
            settled = settled.add(sAmt);
          }
        }
        case "DIRECT_PAYMENT", "DISBURSEMENT" -> {
          disbursed = disbursed.add(amt);
          if ("SETTLED".equals(status)) {
            settled = settled.add(sAmt);
          }
        }
        case "ADVANCE_SETTLEMENT", "SETTLEMENT" -> {
          disbursed = disbursed.add(amt);
          settled = settled.add(sAmt);
        }
        default -> {
          disbursed = disbursed.add(amt);
          if ("SETTLED".equals(status)) {
            settled = settled.add(sAmt);
          }
        }
      }
    }

    if (disbs.isEmpty()) {
      committed = safe(alloc.getCommittedAmount());
      advanced = safe(alloc.getAdvancedAmount());
      disbursed = safe(alloc.getDisbursedAmount()).max(txDisbursed);
      settled = safe(alloc.getSettledAmount());
    }

    BigDecimal expiredCancelled = safe(alloc.getExpiredCancelledAmount());
    BigDecimal available =
        effective.subtract(committed).subtract(disbursed).subtract(expiredCancelled);
    BigDecimal rate =
        effective.compareTo(BigDecimal.ZERO) > 0
            ? disbursed
                .multiply(new BigDecimal("100"))
                .divide(effective, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    alloc.setInitialAmount(initial);
    alloc.setSupplementAmount(supplement);
    alloc.setCarryOverInAmount(carryIn);
    alloc.setCarryOverOutAmount(carryOut);
    alloc.setTransferNetAmount(transferNet);
    alloc.setReductionAmount(reduction);
    alloc.setEffectiveAmount(effective);
    alloc.setCommittedAmount(committed);
    alloc.setAdvancedAmount(advanced);
    alloc.setDisbursedAmount(disbursed);
    alloc.setSettledAmount(settled);
    alloc.setAvailableAmount(available);
    alloc.setDisbursementRatePercent(rate);

    return budgetAllocationRepo.save(alloc);
  }

  /**
   * Thực thi bút toán kép khi Chuyển nguồn liên năm (N -> N+1) hoặc Điều chuyển nội bộ.
   */
  @Transactional
  public void executeDoubleEntryLedger(BudgetTransaction tx) {
    if (tx == null || tx.getAllocationId() == null) {
      return;
    }
    Optional<BudgetAllocation> optSource = budgetAllocationRepo.findById(tx.getAllocationId());
    if (optSource.isEmpty()) {
      return;
    }
    BudgetAllocation source = optSource.get();
    BigDecimal sourceBalanceBefore =
        tx.getBalanceBeforeAmount() != null
            ? tx.getBalanceBeforeAmount()
            : (source.getAvailableAmount() != null
                ? source.getAvailableAmount()
                : safe(source.getEffectiveAmount()));
    int prevVer = source.getCurrentVersion() != null ? source.getCurrentVersion() : 1;
    source.setCurrentVersion(prevVer + 1);
    budgetAllocationRepo.save(source);

    tx.setPreviousVersion(prevVer);
    tx.setNewVersion(prevVer + 1);

    String type = tx.getTxType() != null ? tx.getTxType().toUpperCase() : "";
    if ("CARRY_OVER_OUT".equals(type)) {
      int targetYear =
          tx.getTargetBudgetYear() != null
              ? tx.getTargetBudgetYear()
              : (source.getBudgetYear() != null ? source.getBudgetYear() + 1 : LocalDate.now().getYear() + 1);
      tx.setTargetBudgetYear(targetYear);

      BudgetAllocation targetAlloc = null;
      if (tx.getCounterpartAllocationId() != null) {
        targetAlloc = budgetAllocationRepo.findById(tx.getCounterpartAllocationId()).orElse(null);
      }
      if (targetAlloc == null) {
        String newCode = source.getCode() + "-CN" + targetYear;
        List<BudgetAllocation> existingNextYear =
            budgetAllocationRepo.findAllByCodeIgnoreCaseIncludingDeletedSorted(newCode);
        if (!existingNextYear.isEmpty()) {
          targetAlloc = existingNextYear.getFirst();
        } else {
          String targetDomainCode =
              tx.getDomainCode() != null && !tx.getDomainCode().isBlank()
                  ? tx.getDomainCode()
                  : source.getDomainCode();
          String targetDomainName =
              tx.getDomainName() != null && !tx.getDomainName().isBlank()
                  ? tx.getDomainName()
                  : source.getDomainName();
          String targetDomainGroupCode =
              tx.getDomainGroupCode() != null && !tx.getDomainGroupCode().isBlank()
                  ? tx.getDomainGroupCode()
                  : source.getDomainGroupCode();
          String targetDomainGroupName =
              tx.getDomainGroupName() != null && !tx.getDomainGroupName().isBlank()
                  ? tx.getDomainGroupName()
                  : source.getDomainGroupName();
          targetAlloc =
              budgetAllocationRepo.save(
                  BudgetAllocation.builder()
                      .code(newCode)
                      .name(source.getName() + " (Nhận chuyển nguồn năm " + source.getBudgetYear() + ")")
                      .description("Phân bổ nhận chuyển nguồn tự động từ " + source.getCode())
                      .budgetYear(targetYear)
                      .periodType("ANNUAL")
                      .nodeId(source.getNodeId())
                      .nodeCode(source.getNodeCode())
                      .nodeName(source.getNodeName())
                      .estimateItemId(source.getEstimateItemId())
                      .estimateItemCode(source.getEstimateItemCode())
                      .estimateItemName(source.getEstimateItemName())
                      .itemType(source.getItemType())
                      .categoryId(source.getCategoryId())
                      .categoryCode(source.getCategoryCode())
                      .categoryName(source.getCategoryName())
                      .domainCode(targetDomainCode)
                      .domainName(targetDomainName)
                      .domainGroupCode(targetDomainGroupCode)
                      .domainGroupName(targetDomainGroupName)
                      .dynamicBreakdownJson(
                          tx.getDynamicBreakdownJson() != null
                              ? tx.getDynamicBreakdownJson()
                              : source.getDynamicBreakdownJson())
                      .subItemCode(source.getSubItemCode())
                      .fundingSourceCode(source.getFundingSourceCode())
                      .fundingSourceName(source.getFundingSourceName())
                      .approvalScope(source.getApprovalScope())
                      .workflowStatus("APPROVED")
                      .currentVersion(1)
                      .proposedAmount(tx.getAmount())
                      .initialAmount(BigDecimal.ZERO)
                      .supplementAmount(BigDecimal.ZERO)
                      .carryOverInAmount(tx.getAmount())
                      .carryOverOutAmount(BigDecimal.ZERO)
                      .transferNetAmount(BigDecimal.ZERO)
                      .reductionAmount(BigDecimal.ZERO)
                      .effectiveAmount(tx.getAmount())
                      .committedAmount(BigDecimal.ZERO)
                      .advancedAmount(BigDecimal.ZERO)
                      .disbursedAmount(safe(tx.getDisbursedAmount()))
                      .settledAmount(BigDecimal.ZERO)
                      .availableAmount(tx.getAmount().subtract(safe(tx.getDisbursedAmount())))
                      .disbursementRatePercent(BigDecimal.ZERO)
                      .status(LifecycleStatus.ACTIVE)
                      .build());
        }
      }

      tx.setCounterpartAllocationId(targetAlloc.getId());
      tx.setCounterpartAllocationCode(targetAlloc.getCode());
      tx.setCounterpartAllocationName(targetAlloc.getName());

      BigDecimal targetBefore = safe(targetAlloc.getAvailableAmount());
      String carryInCode = tx.getCode() + "-IN";
      BudgetTransaction carryInTx = null;
      if (!budgetTransactionRepo.existsByCodeIgnoreCase(carryInCode)) {
        carryInTx =
            budgetTransactionRepo.save(
                BudgetTransaction.builder()
                    .code(carryInCode)
                    .name("Nhận chuyển nguồn từ năm " + source.getBudgetYear() + ": " + tx.getName())
                    .description(tx.getDescription())
                    .allocationId(targetAlloc.getId())
                    .allocationCode(targetAlloc.getCode())
                    .allocationName(targetAlloc.getName())
                    .nodeId(targetAlloc.getNodeId())
                    .nodeCode(targetAlloc.getNodeCode())
                    .nodeName(targetAlloc.getNodeName())
                    .estimateItemId(targetAlloc.getEstimateItemId())
                    .estimateItemCode(targetAlloc.getEstimateItemCode())
                    .estimateItemName(targetAlloc.getEstimateItemName())
                    .categoryId(targetAlloc.getCategoryId())
                    .categoryCode(targetAlloc.getCategoryCode())
                    .categoryName(targetAlloc.getCategoryName())
                    .domainCode(targetAlloc.getDomainCode())
                    .domainName(targetAlloc.getDomainName())
                    .domainGroupCode(targetAlloc.getDomainGroupCode())
                    .domainGroupName(targetAlloc.getDomainGroupName())
                    .dynamicBreakdownJson(targetAlloc.getDynamicBreakdownJson())
                    .fundingSourceCode(targetAlloc.getFundingSourceCode())
                    .fundingSourceName(targetAlloc.getFundingSourceName())
                    .txType("CARRY_OVER_IN")
                    .budgetYear(targetYear)
                    .amount(tx.getAmount().abs())
                    .disbursedAmount(safe(tx.getDisbursedAmount()))
                    .balanceBeforeAmount(targetBefore)
                    .counterpartAllocationId(source.getId())
                    .counterpartAllocationCode(source.getCode())
                    .counterpartAllocationName(source.getName())
                    .decisionNo(tx.getDecisionNo())
                    .decisionDate(tx.getDecisionDate())
                    .txDate(tx.getTxDate() != null ? tx.getTxDate() : LocalDate.now())
                    .reason(tx.getReason())
                    .previousVersion(1)
                    .newVersion(1)
                    .requestedByPersonId(tx.getRequestedByPersonId())
                    .requestedByPersonName(tx.getRequestedByPersonName())
                    .approvedByPersonId(tx.getApprovedByPersonId())
                    .approvedByPersonName(tx.getApprovedByPersonName())
                    .approvalStatus("APPROVED")
                    .status(LifecycleStatus.ACTIVE)
                    .build());
      }
      BudgetAllocation updatedTarget = recalculateAllocationMetrics(targetAlloc.getId());
      if (carryInTx != null && updatedTarget != null) {
        carryInTx.setBalanceAfterAmount(updatedTarget.getAvailableAmount());
        budgetTransactionRepo.save(carryInTx);
      }
    } else if ("INTERNAL_TRANSFER".equals(type) && tx.getCounterpartAllocationId() != null) {
      Optional<BudgetAllocation> optTarget =
          budgetAllocationRepo.findById(tx.getCounterpartAllocationId());
      if (optTarget.isPresent()) {
        BudgetAllocation target = optTarget.get();
        BigDecimal targetBefore = safe(target.getAvailableAmount());
        String transferInCode = tx.getCode() + "-RECV";
        BudgetTransaction recvTx = null;
        if (!budgetTransactionRepo.existsByCodeIgnoreCase(transferInCode)) {
          recvTx =
              budgetTransactionRepo.save(
                  BudgetTransaction.builder()
                      .code(transferInCode)
                      .name("Nhận điều chuyển dự toán nội bộ từ [" + source.getCode() + "]")
                      .description(tx.getDescription())
                      .allocationId(target.getId())
                      .allocationCode(target.getCode())
                      .allocationName(target.getName())
                      .nodeId(target.getNodeId())
                      .nodeCode(target.getNodeCode())
                      .nodeName(target.getNodeName())
                      .estimateItemId(target.getEstimateItemId())
                      .estimateItemCode(target.getEstimateItemCode())
                      .estimateItemName(target.getEstimateItemName())
                      .categoryId(target.getCategoryId())
                      .categoryCode(target.getCategoryCode())
                      .categoryName(target.getCategoryName())
                      .fundingSourceCode(target.getFundingSourceCode())
                      .fundingSourceName(target.getFundingSourceName())
                      .txType("TRANSFER_IN")
                      .budgetYear(target.getBudgetYear())
                      .amount(tx.getAmount().abs())
                      .balanceBeforeAmount(targetBefore)
                      .counterpartAllocationId(source.getId())
                      .counterpartAllocationCode(source.getCode())
                      .counterpartAllocationName(source.getName())
                      .decisionNo(tx.getDecisionNo())
                      .decisionDate(tx.getDecisionDate())
                      .txDate(tx.getTxDate() != null ? tx.getTxDate() : LocalDate.now())
                      .reason(tx.getReason())
                      .previousVersion(
                          target.getCurrentVersion() != null ? target.getCurrentVersion() : 1)
                      .newVersion(
                          (target.getCurrentVersion() != null ? target.getCurrentVersion() : 1) + 1)
                      .requestedByPersonId(tx.getRequestedByPersonId())
                      .requestedByPersonName(tx.getRequestedByPersonName())
                      .approvedByPersonId(tx.getApprovedByPersonId())
                      .approvedByPersonName(tx.getApprovedByPersonName())
                      .approvalStatus("APPROVED")
                      .status(LifecycleStatus.ACTIVE)
                      .build());
        }
        BudgetAllocation updatedTarget = recalculateAllocationMetrics(target.getId());
        if (recvTx != null && updatedTarget != null) {
          recvTx.setBalanceAfterAmount(updatedTarget.getAvailableAmount());
          budgetTransactionRepo.save(recvTx);
        }
      }
    }

    BudgetAllocation updatedSource = recalculateAllocationMetrics(source.getId());
    tx.setBalanceBeforeAmount(sourceBalanceBefore);
    tx.setBalanceAfterAmount(
        updatedSource != null && updatedSource.getAvailableAmount() != null
            ? updatedSource.getAvailableAmount()
            : sourceBalanceBefore);
    budgetTransactionRepo.save(tx);
  }

  /**
   * Kiểm tra xem đầu mục dự toán có bắt buộc chứng từ/hóa đơn khi giải ngân không.
   */
  @Transactional(readOnly = true)
  public boolean isVoucherRequired(Long estimateItemId) {
    if (estimateItemId == null) {
      return false;
    }
    return budgetEstimateItemRepo
        .findById(estimateItemId)
        .map(item -> Boolean.TRUE.equals(item.getRequireVoucher()))
        .orElse(false);
  }

  public BigDecimal safe(BigDecimal val) {
    return val != null ? val : BigDecimal.ZERO;
  }
}
