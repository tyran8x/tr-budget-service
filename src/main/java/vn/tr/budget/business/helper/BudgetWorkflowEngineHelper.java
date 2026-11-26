package vn.tr.budget.business.helper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetCategory;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetCategoryRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.dao.service.BudgetUnitConfigRepo;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.core.exception.base.BaseException;

/**
 * Động cơ tính toán Cân đối Dự toán Kinh phí Đa kỳ, Kiểm tra Ma trận Tự chủ Tài chính Đơn vị, Sổ
 * cái Bút toán kép (Giao đầu năm, Bổ sung, Chuyển nguồn liên năm N -> N+1, Điều chuyển nội bộ) và
 * Kiểm soát Giải ngân Kho bạc.
 *
 * <p>Cưỡng chế khóa bi quan theo thứ tự ID tăng dần ({@link #lockAllocationsInDeterministicOrder})
 * chống TOCTOU double-spend & AB-BA deadlock, tính số dư theo whitelist trạng thái đã duyệt, và
 * chuẩn hóa làm tròn tiền tệ {@code BigDecimal.setScale(2, RoundingMode.HALF_UP)}.
 *
 * @author tyran8x
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BudgetWorkflowEngineHelper {

  private static final Set<String> EFFECTIVE_TX_STATUSES =
      Set.of("APPROVED", "SELF_APPROVED", "SETTLED", "EXECUTING", "LOCKED_SETTLED");

  private static final Set<String> EFFECTIVE_DISB_STATUSES =
      Set.of("COMMITTED", "APPROVED", "APPROVED_DISBURSED", "SETTLED", "EXECUTING", "LOCKED_SETTLED");

  private final BudgetAllocationRepo budgetAllocationRepo;
  private final BudgetTransactionRepo budgetTransactionRepo;
  private final BudgetDisbursementRepo budgetDisbursementRepo;
  private final BudgetUnitConfigRepo budgetUnitConfigRepo;
  private final BudgetCategoryRepo budgetCategoryRepo;
  private final BudgetEstimateItemRepo budgetEstimateItemRepo;

  /**
   * Khóa bi quan ({@code PESSIMISTIC_WRITE / SELECT ... FOR UPDATE}) các bản ghi {@link
   * BudgetAllocation} theo thứ tự ID tăng dần tất định nhằm loại bỏ hoàn toàn AB-BA deadlock và
   * TOCTOU double-spend.
   */
  @Transactional
  public Map<Long, BudgetAllocation> lockAllocationsInDeterministicOrder(Long... allocationIds) {
    if (allocationIds == null || allocationIds.length == 0) {
      return Collections.emptyMap();
    }
    TreeSet<Long> sortedUniqueIds = new TreeSet<>();
    for (Long id : allocationIds) {
      if (id != null) {
        sortedUniqueIds.add(id);
      }
    }
    Map<Long, BudgetAllocation> locked = new LinkedHashMap<>();
    for (Long id : sortedUniqueIds) {
      Optional<BudgetAllocation> opt = budgetAllocationRepo.findByIdForUpdate(id);
      if (opt == null || opt.isEmpty()) {
        opt = budgetAllocationRepo.findById(id);
      }
      if (opt != null && opt.isPresent()) {
        locked.put(id, opt.get());
      }
    }
    return locked;
  }

  /** Khóa bi quan một bản ghi {@link BudgetAllocation} trước khi kiểm tra số dư khả dụng. */
  @Transactional
  public Optional<BudgetAllocation> lockAllocation(Long allocationId) {
    if (allocationId == null) {
      return Optional.empty();
    }
    Map<Long, BudgetAllocation> locked = lockAllocationsInDeterministicOrder(allocationId);
    return Optional.ofNullable(locked.get(allocationId));
  }

  /** Kiểm tra cấu hình tự chủ tài chính của Đơn vị / Phòng ban. */
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
      if (cats != null && !cats.isEmpty() && cats.getFirst().getAllowCarryOver() != null) {
        allowCarryOver = cats.getFirst().getAllowCarryOver();
      }
    }

    if (opt.isEmpty()) {
      result.put("configured", false);
      result.put("canSelfAllocate", false);
      result.put("canSelfTransfer", false);
      result.put("canCarryOver", allowCarryOver);
      result.put("canSelfApprove", false);
      result.put("recommendedApprovalScope", "SUPERIOR_APPROVAL");
      return result;
    }

    BudgetUnitConfig cfg = opt.get();
    boolean canSelfAllocate = Boolean.TRUE.equals(cfg.getCanSelfAllocate());
    boolean canSelfTransfer = Boolean.TRUE.equals(cfg.getCanSelfTransfer());
    boolean canCarryOver = Boolean.TRUE.equals(cfg.getCanCarryOver()) && allowCarryOver;
    BigDecimal limit = cfg.getSelfApproveLimitAmount();

    boolean categoryAllowed = true;
    String denialReason = null;
    String allowedCodesRaw = cfg.getAllowedCategoryCodesJson();
    if (allowedCodesRaw != null
        && !allowedCodesRaw.isBlank()
        && categoryCode != null
        && !categoryCode.isBlank()) {
      List<String> allowedCodes = parseAllowedCategoryCodes(allowedCodesRaw);
      if (!allowedCodes.isEmpty() && !allowedCodes.contains(categoryCode.trim().toUpperCase())) {
        categoryAllowed = false;
        canSelfAllocate = false;
        denialReason =
            "Mã mục lục ngân sách ["
                + categoryCode
                + "] không nằm trong phạm vi tự chủ được phép ("
                + allowedCodesRaw
                + ") của đơn vị.";
      }
    }

    boolean canSelfApprove = canSelfAllocate && categoryAllowed;
    if (canSelfApprove
        && limit != null
        && limit.compareTo(BigDecimal.ZERO) > 0
        && proposedAmount != null
        && proposedAmount.compareTo(limit) > 0) {
      canSelfApprove = false;
      denialReason =
          "Số tiền đề xuất vượt hạn mức tự phê duyệt ("
              + scaleMoney(limit).toPlainString()
              + " VNĐ) của đơn vị.";
    }

    result.put("configured", true);
    result.put("unitConfigId", cfg.getId());
    result.put("unitType", cfg.getUnitType());
    result.put("categoryAllowed", categoryAllowed);
    result.put("canSelfAllocate", canSelfAllocate);
    result.put("canSelfTransfer", canSelfTransfer);
    result.put("canCarryOver", canCarryOver);
    result.put("canSelfApprove", canSelfApprove);
    result.put("selfApproveLimitAmount", limit);
    result.put("treasuryAccountNo", cfg.getTreasuryAccountNo());
    result.put(
        "recommendedApprovalScope",
        canSelfApprove ? "UNIT_SELF_APPROVAL" : "SUPERIOR_APPROVAL");
    if (denialReason != null) {
      result.put("reason", denialReason);
      result.put("autonomyDenialReason", denialReason);
    }
    return result;
  }

  private List<String> parseAllowedCategoryCodes(String raw) {
    List<String> codes = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return codes;
    }
    String cleaned = raw.replace("[", "").replace("]", "").replace("\"", "").replace("'", "");
    String[] tokens = cleaned.split("[,;|]");
    for (String token : tokens) {
      String trimmed = token.trim();
      if (!trimmed.isEmpty()) {
        codes.add(trimmed.toUpperCase());
      }
    }
    return codes;
  }

  /**
   * Tính toán lại toàn bộ chỉ số Sổ cái Kinh phí và Giải ngân của một Phân bổ Dự toán
   * ({@link BudgetAllocation}) theo danh sách trắng trạng thái đã duyệt.
   */
  @Transactional
  public BudgetAllocation recalculateAllocationMetrics(Long allocationId) {
    if (allocationId == null) {
      return null;
    }
    Optional<BudgetAllocation> opt = lockAllocation(allocationId);
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
    BigDecimal txExpiredCancelled = BigDecimal.ZERO;
    BigDecimal txHeld = BigDecimal.ZERO;
    BigDecimal txFundDistributed = BigDecimal.ZERO;
    boolean hasInitialTx = false;
    boolean hasWithdrawalTx = false;
    boolean hasHoldTx = false;

    if (txs != null) {
      for (BudgetTransaction tx : txs) {
        String approvalStatus =
            tx.getApprovalStatus() != null ? tx.getApprovalStatus().trim().toUpperCase() : "";
        if (!EFFECTIVE_TX_STATUSES.contains(approvalStatus)) {
          continue;
        }
        BigDecimal amt = safe(tx.getAmount());
        String type = tx.getTxType() != null ? tx.getTxType().trim().toUpperCase() : "";
        switch (type) {
          case "INITIAL_ALLOCATION" -> {
            txInitial = txInitial.add(amt);
            hasInitialTx = true;
          }
          case "SUPPLEMENT", "ADDITIONAL", "BATCH_PAYROLL_INDEXATION" -> {
            // Không ép .abs(): hỗ trợ cả bổ sung dương và điều chỉnh giảm biên chế âm (TD-C07)
            if (amt.compareTo(BigDecimal.ZERO) >= 0) {
              txSupplement = txSupplement.add(amt);
            } else {
              txReduction = txReduction.add(amt.abs());
            }
          }
          case "TRANCHE_RELEASE" -> {
            txHeld = txHeld.subtract(amt.abs());
            hasHoldTx = true;
          }
          case "WITHDRAWAL", "EXPIRED_CANCEL" -> {
            txExpiredCancelled = txExpiredCancelled.add(amt.abs());
            hasWithdrawalTx = true;
          }
          case "AUTONOMY_FUND_DISTRIBUTION" -> {
            // Trừ số dư thặng dư đã trích lập quỹ tự chủ khỏi khả dụng để chống phân phối vô hạn (DX-04)
            txFundDistributed = txFundDistributed.add(amt.abs());
          }
          case "ADJUSTMENT" -> {
            if (amt.compareTo(BigDecimal.ZERO) >= 0) {
              txSupplement = txSupplement.add(amt);
            } else {
              txReduction = txReduction.add(amt.abs());
            }
          }
          case "CARRY_OVER_IN" -> txCarryIn = txCarryIn.add(amt.abs());
          case "CARRY_OVER_OUT" -> txCarryOut = txCarryOut.add(amt.abs());
          case "TRANSFER_IN" -> txTransferNet = txTransferNet.add(amt.abs());
          case "TRANSFER_OUT" -> txTransferNet = txTransferNet.subtract(amt.abs());
          case "INTERNAL_TRANSFER" -> {
            if ("CREDIT".equalsIgnoreCase(tx.getEntryDirection())) {
              txTransferNet = txTransferNet.add(amt.abs());
            } else if ("DEBIT".equalsIgnoreCase(tx.getEntryDirection())) {
              txTransferNet = txTransferNet.subtract(amt.abs());
            } else {
              txTransferNet = txTransferNet.add(amt);
            }
          }
          case "REDUCTION", "SAVING_REDUCTION" -> txReduction = txReduction.add(amt.abs());
          case "EARMARK_HOLD" -> {
            txHeld = txHeld.add(amt.abs());
            hasHoldTx = true;
          }
          case "EARMARK_RELEASE" -> {
            txHeld = txHeld.subtract(amt.abs());
            hasHoldTx = true;
          }
          default -> {}
        }
      }
    }

    BigDecimal initial =
        scaleMoney(
            hasInitialTx && txInitial.compareTo(BigDecimal.ZERO) > 0
                ? txInitial
                : safe(alloc.getInitialAmount()));
    BigDecimal supplement = scaleMoney(txSupplement);
    BigDecimal carryIn = scaleMoney(txCarryIn);
    BigDecimal carryOut = scaleMoney(txCarryOut);
    BigDecimal transferNet = scaleMoney(txTransferNet);
    BigDecimal reduction = scaleMoney(txReduction);

    BigDecimal effective =
        scaleMoney(
            initial
                .add(supplement)
                .add(carryIn)
                .subtract(carryOut)
                .add(transferNet)
                .subtract(reduction));
    if (effective.compareTo(BigDecimal.ZERO) < 0) {
      effective = scaleMoney(BigDecimal.ZERO);
    }

    List<BudgetDisbursement> disbs =
        budgetDisbursementRepo.findByAllocationIdOrderByIdAsc(allocationId);
    BigDecimal committed = BigDecimal.ZERO;
    BigDecimal advanced = BigDecimal.ZERO;
    BigDecimal advanceSettlementDeduction = BigDecimal.ZERO;
    BigDecimal disbursed = BigDecimal.ZERO;
    BigDecimal settled = BigDecimal.ZERO;

    if (disbs != null) {
      for (BudgetDisbursement d : disbs) {
        String status =
            d.getApprovalStatus() != null ? d.getApprovalStatus().trim().toUpperCase() : "";
        if (!EFFECTIVE_DISB_STATUSES.contains(status)) {
          continue;
        }
        BigDecimal amt = safe(d.getAmount());
        BigDecimal sAmt =
            d.getSettledAmount() != null && d.getSettledAmount().compareTo(BigDecimal.ZERO) > 0
                ? d.getSettledAmount()
                : amt;
        String dtype =
            d.getDisbursementType() != null ? d.getDisbursementType().trim().toUpperCase() : "";
        switch (dtype) {
          case "COMMITMENT" -> committed = committed.add(amt);
          case "ADVANCE" -> {
            disbursed = disbursed.add(amt);
            if ("SETTLED".equals(status)) {
              settled = settled.add(sAmt);
              if (amt.compareTo(sAmt) > 0) {
                advanced = advanced.add(amt.subtract(sAmt));
              }
            } else {
              advanced = advanced.add(amt);
            }
          }
          case "DIRECT_PAYMENT", "DISBURSEMENT" -> {
            disbursed = disbursed.add(amt);
            if ("SETTLED".equals(status)) {
              settled = settled.add(sAmt);
            }
          }
          case "ADVANCE_SETTLEMENT" -> {
            // Thanh toán hoàn tạm ứng KHÔNG cộng trùng vào disbursed vì khoản ADVANCE gốc đã tính
            // vào disbursed; chỉ ghi nhận nghiệm thu (settled) và khấu trừ dư tạm ứng (advanced).
            settled = settled.add(sAmt);
            advanceSettlementDeduction = advanceSettlementDeduction.add(sAmt);
          }
          case "SETTLEMENT" -> {
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
    }

    if (advanceSettlementDeduction.compareTo(BigDecimal.ZERO) > 0) {
      advanced = advanced.subtract(advanceSettlementDeduction).max(BigDecimal.ZERO);
    }

    if (hasHoldTx) {
      committed = committed.add(txHeld.max(BigDecimal.ZERO));
    }

    BigDecimal expiredCancelled =
        scaleMoney(hasWithdrawalTx ? txExpiredCancelled : safe(alloc.getExpiredCancelledAmount()));
    committed = scaleMoney(committed);
    advanced = scaleMoney(advanced);
    disbursed = scaleMoney(disbursed);
    settled = scaleMoney(settled);
    txFundDistributed = scaleMoney(txFundDistributed);

    BigDecimal available =
        scaleMoney(
            effective
                .subtract(committed)
                .subtract(disbursed)
                .subtract(expiredCancelled)
                .subtract(txFundDistributed));
    BigDecimal rate =
        effective.compareTo(BigDecimal.ZERO) > 0
            ? disbursed
                .multiply(new BigDecimal("100"))
                .divide(effective, 2, RoundingMode.HALF_UP)
            : scaleMoney(BigDecimal.ZERO);

    alloc.setInitialAmount(initial);
    alloc.setSupplementAmount(supplement);
    alloc.setCarryOverInAmount(carryIn);
    alloc.setCarryOverOutAmount(carryOut);
    alloc.setTransferNetAmount(transferNet);
    alloc.setReductionAmount(reduction);
    alloc.setExpiredCancelledAmount(expiredCancelled);
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
   * Tính tổng số dư đang bị tạm giữ (EARMARK_HOLD trừ EARMARK_RELEASE/TRANCHE_RELEASE) đã duyệt của
   * một Phân bổ.
   */
  @Transactional(readOnly = true)
  public BigDecimal computeCurrentEarmarkHeldAmount(Long allocationId) {
    if (allocationId == null) {
      return scaleMoney(BigDecimal.ZERO);
    }
    List<BudgetTransaction> txs =
        budgetTransactionRepo.findByAllocationIdOrderByIdAsc(allocationId);
    BigDecimal held = BigDecimal.ZERO;
    if (txs != null) {
      for (BudgetTransaction tx : txs) {
        String st = tx.getApprovalStatus() != null ? tx.getApprovalStatus().trim().toUpperCase() : "";
        if (!EFFECTIVE_TX_STATUSES.contains(st)) {
          continue;
        }
        String type = tx.getTxType() != null ? tx.getTxType().trim().toUpperCase() : "";
        BigDecimal amt = safe(tx.getAmount()).abs();
        if ("EARMARK_HOLD".equals(type)) {
          held = held.add(amt);
        } else if ("EARMARK_RELEASE".equals(type) || "TRANCHE_RELEASE".equals(type)) {
          held = held.subtract(amt);
        }
      }
    }
    return scaleMoney(held.max(BigDecimal.ZERO));
  }

  /**
   * Thực thi bút toán kép khi Chuyển nguồn liên năm (N -> N+1) hoặc Điều chuyển nội bộ. Khóa bi
   * quan cả phân bổ nguồn và phân bổ đích theo thứ tự ID tăng dần trước khi đối chiếu số dư và ghi
   * sổ cái.
   */
  @Transactional
  public void executeDoubleEntryLedger(BudgetTransaction tx) {
    if (tx == null || tx.getAllocationId() == null) {
      return;
    }
    Map<Long, BudgetAllocation> lockedAllocs =
        lockAllocationsInDeterministicOrder(tx.getAllocationId(), tx.getCounterpartAllocationId());
    BudgetAllocation source = lockedAllocs.get(tx.getAllocationId());
    if (source == null) {
      return;
    }

    String type = tx.getTxType() != null ? tx.getTxType().trim().toUpperCase() : "";
    BigDecimal sourceAvailable =
        source.getAvailableAmount() != null
            ? scaleMoney(source.getAvailableAmount())
            : scaleMoney(source.getEffectiveAmount());
    BigDecimal txAbs = scaleMoney(safe(tx.getAmount()).abs());

    // Bảo vệ bất biến số dư dương khi ghi nợ (DEBIT) từ phân bổ nguồn
    if (("CARRY_OVER_OUT".equals(type)
            || "INTERNAL_TRANSFER".equals(type)
            || "TRANSFER_OUT".equals(type)
            || "WITHDRAWAL".equals(type)
            || "REDUCTION".equals(type)
            || "EARMARK_HOLD".equals(type)
            || "AUTONOMY_FUND_DISTRIBUTION".equals(type))
        && txAbs.compareTo(sourceAvailable) > 0) {
      throw new BaseException(
          "Số tiền biến động ("
              + txAbs.toPlainString()
              + " VNĐ) vượt quá số dư khả dụng còn lại ("
              + sourceAvailable.toPlainString()
              + " VNĐ) của phân bổ ["
              + source.getCode()
              + "].");
    }

    BigDecimal sourceBalanceBefore =
        tx.getBalanceBeforeAmount() != null ? scaleMoney(tx.getBalanceBeforeAmount()) : sourceAvailable;
    int prevVer = source.getCurrentVersion() != null ? source.getCurrentVersion() : 1;
    source.setCurrentVersion(prevVer + 1);
    budgetAllocationRepo.save(source);

    tx.setPreviousVersion(prevVer);
    tx.setNewVersion(prevVer + 1);

    if ("CARRY_OVER_OUT".equals(type)) {
      tx.setEntryDirection("DEBIT");
      int sourceYear = source.getBudgetYear() != null ? source.getBudgetYear() : LocalDate.now().getYear();
      int targetYear = tx.getTargetBudgetYear() != null ? tx.getTargetBudgetYear() : (sourceYear + 1);
      if (targetYear <= sourceYear) {
        throw new BaseException(
            "Năm đích chuyển nguồn ("
                + targetYear
                + ") phải lớn hơn năm ngân sách nguồn ("
                + sourceYear
                + ").");
      }
      tx.setTargetBudgetYear(targetYear);

      BudgetAllocation targetAlloc =
          tx.getCounterpartAllocationId() != null
              ? lockedAllocs.get(tx.getCounterpartAllocationId())
              : null;
      if (targetAlloc == null) {
        String newCode = source.getCode() + "-CN" + targetYear;
        List<BudgetAllocation> existingNextYear =
            budgetAllocationRepo.findAllByCodeIgnoreCaseIncludingDeletedSorted(newCode);
        if (existingNextYear != null && !existingNextYear.isEmpty()) {
          targetAlloc =
              lockAllocation(existingNextYear.getFirst().getId())
                  .orElse(existingNextYear.getFirst());
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
                      .name(
                          source.getName()
                              + " (Nhận chuyển nguồn năm "
                              + source.getBudgetYear()
                              + ")")
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
                      .approvalScope(normalizeApprovalScope(source.getApprovalScope()))
                      .workflowStatus("APPROVED")
                      .currentVersion(1)
                      .proposedAmount(txAbs)
                      .initialAmount(scaleMoney(BigDecimal.ZERO))
                      .supplementAmount(scaleMoney(BigDecimal.ZERO))
                      .carryOverInAmount(txAbs)
                      .carryOverOutAmount(scaleMoney(BigDecimal.ZERO))
                      .transferNetAmount(scaleMoney(BigDecimal.ZERO))
                      .reductionAmount(scaleMoney(BigDecimal.ZERO))
                      .effectiveAmount(txAbs)
                      .committedAmount(scaleMoney(BigDecimal.ZERO))
                      .advancedAmount(scaleMoney(BigDecimal.ZERO))
                      .disbursedAmount(scaleMoney(BigDecimal.ZERO))
                      .settledAmount(scaleMoney(BigDecimal.ZERO))
                      .availableAmount(txAbs)
                      .disbursementRatePercent(scaleMoney(BigDecimal.ZERO))
                      .status(LifecycleStatus.ACTIVE)
                      .build());
        }
      }

      tx.setCounterpartAllocationId(targetAlloc.getId());
      tx.setCounterpartAllocationCode(targetAlloc.getCode());
      tx.setCounterpartAllocationName(targetAlloc.getName());

      BigDecimal targetBefore = scaleMoney(safe(targetAlloc.getAvailableAmount()));
      String carryInCode = tx.getCode() + "-IN";
      BudgetTransaction carryInTx = null;
      if (!budgetTransactionRepo.existsByCodeIgnoreCase(carryInCode)) {
        carryInTx =
            budgetTransactionRepo.save(
                BudgetTransaction.builder()
                    .code(carryInCode)
                    .name(
                        "Nhận chuyển nguồn từ năm "
                            + source.getBudgetYear()
                            + ": "
                            + tx.getName())
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
                    .entryDirection("CREDIT")
                    .budgetYear(targetYear)
                    .amount(txAbs)
                    .balanceBeforeAmount(targetBefore)
                    .counterpartAllocationId(source.getId())
                    .counterpartAllocationCode(source.getCode())
                    .counterpartAllocationName(source.getName())
                    .counterpartTxId(tx.getId())
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
      if (carryInTx != null) {
        if (updatedTarget != null) {
          carryInTx.setBalanceAfterAmount(updatedTarget.getAvailableAmount());
          carryInTx = budgetTransactionRepo.save(carryInTx);
        }
        if (carryInTx.getId() != null) {
          tx.setCounterpartTxId(carryInTx.getId());
        }
      }
    } else if ("INTERNAL_TRANSFER".equals(type) && tx.getCounterpartAllocationId() != null) {
      tx.setEntryDirection("DEBIT");
      tx.setAmount(txAbs.negate());
      BudgetAllocation target = lockedAllocs.get(tx.getCounterpartAllocationId());
      if (target != null) {
        BigDecimal targetBefore = scaleMoney(safe(target.getAvailableAmount()));
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
                      .entryDirection("CREDIT")
                      .budgetYear(target.getBudgetYear())
                      .amount(txAbs)
                      .balanceBeforeAmount(targetBefore)
                      .counterpartAllocationId(source.getId())
                      .counterpartAllocationCode(source.getCode())
                      .counterpartAllocationName(source.getName())
                      .counterpartTxId(tx.getId())
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
        if (recvTx != null) {
          if (updatedTarget != null) {
            recvTx.setBalanceAfterAmount(updatedTarget.getAvailableAmount());
            recvTx = budgetTransactionRepo.save(recvTx);
          }
          if (recvTx.getId() != null) {
            tx.setCounterpartTxId(recvTx.getId());
          }
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

  /** Kiểm tra xem đầu mục dự toán có bắt buộc chứng từ/hóa đơn khi giải ngân không. */
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

  /**
   * Ghi bút toán Sổ cái ban đầu (INITIAL_ALLOCATION) một lần duy nhất (idempotent) khi Phân bổ được
   * phê duyệt.
   */
  @Transactional
  public void ensureInitialAllocationTransaction(BudgetAllocation alloc) {
    if (alloc == null
        || alloc.getId() == null
        || alloc.getInitialAmount() == null
        || alloc.getInitialAmount().compareTo(BigDecimal.ZERO) <= 0
        || alloc.getCode() == null) {
      return;
    }
    String initCode = alloc.getCode() + "-INIT";
    if (budgetTransactionRepo.existsByCodeIgnoreCase(initCode)) {
      return;
    }
    budgetTransactionRepo.save(
        BudgetTransaction.builder()
            .code(initCode)
            .name("Giao dự toán đầu năm [" + alloc.getCode() + "]")
            .allocationId(alloc.getId())
            .allocationCode(alloc.getCode())
            .allocationName(alloc.getName())
            .nodeId(alloc.getNodeId())
            .nodeCode(alloc.getNodeCode())
            .nodeName(alloc.getNodeName())
            .estimateItemId(alloc.getEstimateItemId())
            .estimateItemCode(alloc.getEstimateItemCode())
            .estimateItemName(alloc.getEstimateItemName())
            .categoryId(alloc.getCategoryId())
            .categoryCode(alloc.getCategoryCode())
            .categoryName(alloc.getCategoryName())
            .domainCode(alloc.getDomainCode())
            .domainName(alloc.getDomainName())
            .domainGroupCode(alloc.getDomainGroupCode())
            .domainGroupName(alloc.getDomainGroupName())
            .fundingSourceCode(alloc.getFundingSourceCode())
            .fundingSourceName(alloc.getFundingSourceName())
            .txType("INITIAL_ALLOCATION")
            .entryDirection("CREDIT")
            .budgetYear(alloc.getBudgetYear())
            .amount(scaleMoney(alloc.getInitialAmount()))
            .balanceBeforeAmount(scaleMoney(BigDecimal.ZERO))
            .balanceAfterAmount(
                scaleMoney(
                    alloc.getEffectiveAmount() != null
                        ? alloc.getEffectiveAmount()
                        : alloc.getInitialAmount()))
            .txDate(LocalDate.now())
            .reason("Tự động ghi nhận bút toán giao dự toán đầu năm khi phê duyệt phân bổ")
            .previousVersion(1)
            .newVersion(alloc.getCurrentVersion() != null ? alloc.getCurrentVersion() : 1)
            .approvedByPersonId(alloc.getApprovedByPersonId())
            .approvedByPersonName(alloc.getApprovedByPersonName())
            .approvalStatus("APPROVED")
            .status(LifecycleStatus.ACTIVE)
            .build());
  }

  public String normalizeApprovalScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return "SUPERIOR_APPROVAL";
    }
    String upper = scope.trim().toUpperCase();
    if ("UNIT_SELF".equals(upper)) {
      return "UNIT_SELF_APPROVAL";
    }
    if ("DIRECTORATE".equals(upper)) {
      return "SUPERIOR_APPROVAL";
    }
    return upper;
  }

  public BigDecimal scaleMoney(BigDecimal val) {
    return safe(val).setScale(2, RoundingMode.HALF_UP);
  }

  public BigDecimal safe(BigDecimal val) {
    return val != null ? val : BigDecimal.ZERO;
  }
}
