package vn.tr.budget.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetEstimateItemService;
import vn.tr.budget.dao.service.BudgetUnitConfigRepo;
import vn.tr.budget.data.dto.BudgetAllocationData;
import vn.tr.budget.data.dto.BudgetEstimateItemData;
import vn.tr.budget.data.dto.BudgetEstimateTreeSaveRequest;
import vn.tr.budget.data.dto.BudgetEstimateTreeSaveRequest.TreeNodePayload;
import vn.tr.budget.data.mapper.BudgetEstimateItemMapper;
import vn.tr.budget.data.validator.BudgetEstimateFormulaValidator;
import vn.tr.budget.data.validator.BudgetEstimateItemValidator;
import vn.tr.common.core.exception.ServiceException;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.web.utils.BusinessLogicHelper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BudgetTreeBuilderAndBatchTest {

  @Mock private BudgetEstimateItemService estimateItemService;
  @Mock private BudgetEstimateItemRepo estimateItemRepo;
  @Mock private BudgetEstimateItemMapper estimateItemMapper;
  @Mock private BudgetAllocationRepo allocationRepo;
  @Mock private BudgetUnitConfigRepo unitConfigRepo;

  private BudgetEstimateItemBusiness estimateItemBusiness;
  private BudgetEstimateTreeOpsBusiness treeOpsBusiness;
  private BudgetEstimateTreeSyncBusiness treeSyncBusiness;

  @BeforeEach
  void setUp() {
    when(estimateItemService.getRepo()).thenReturn(estimateItemRepo);
    when(estimateItemService.getRepository()).thenReturn(estimateItemRepo);
    estimateItemBusiness =
        new BudgetEstimateItemBusiness(
            estimateItemService,
            estimateItemMapper,
            new BudgetEstimateItemValidator(estimateItemService),
            mock(GenericUpsertHelper.class),
            mock(AuditRollbackHelper.class),
            mock(BusinessLogicHelper.class));
    treeOpsBusiness = new BudgetEstimateTreeOpsBusiness(estimateItemService, estimateItemBusiness);
    treeSyncBusiness =
        new BudgetEstimateTreeSyncBusiness(
            estimateItemService, estimateItemBusiness, treeOpsBusiness, estimateItemMapper);
  }

  @Test
  @DisplayName("Smart Diff-Based saveTree giữ nguyên ID node cũ, gán parentId cho node mới và soft-delete node gỡ bỏ")
  void shouldSyncEstimateTreeWithDiffUpsertAndSoftDelete() {
    BudgetEstimateItem existingRoot =
        BudgetEstimateItem.builder()
            .id(10L)
            .code("CHI-ROOT")
            .name("Chi thường xuyên")
            .itemType("EXPENDITURE")
            .budgetYear(2026)
            .isHeader(true)
            .build();
    BudgetEstimateItem obsoleteLeaf =
        BudgetEstimateItem.builder()
            .id(99L)
            .code("CHI-OLD")
            .name("Mục cũ cần gỡ")
            .parentId(10L)
            .itemType("EXPENDITURE")
            .budgetYear(2026)
            .build();

    List<BudgetEstimateItem> store = new ArrayList<>(List.of(existingRoot, obsoleteLeaf));
    AtomicLong seq = new AtomicLong(200L);
    when(estimateItemRepo.findAll()).thenAnswer(inv -> store);
    when(estimateItemService.save(any(BudgetEstimateItem.class)))
        .thenAnswer(
            inv -> {
              BudgetEstimateItem entity = inv.getArgument(0);
              if (entity.getId() == null) {
                entity.setId(seq.incrementAndGet());
                store.add(entity);
              }
              return entity;
            });

    BudgetEstimateTreeSaveRequest req =
        BudgetEstimateTreeSaveRequest.builder()
            .budgetYear(2026)
            .itemType("EXPENDITURE")
            .pruneRemoved(true)
            .nodes(
                List.of(
                    TreeNodePayload.builder()
                        .id(10L)
                        .code("CHI-ROOT")
                        .name("Chi thường xuyên (Đã sửa)")
                        .isHeader(true)
                        .numberingStyle("ROMAN")
                        .children(
                            List.of(
                                TreeNodePayload.builder()
                                    .id(-1L)
                                    .code("CHI-CNTT")
                                    .name("Chi chuyển đổi số")
                                    .categoryCode("LK-340-341")
                                    .normStandardAmount(new BigDecimal("500000000"))
                                    .formula("HEADCOUNT * BASE_SALARY * 12")
                                    .build()))
                        .build()))
            .build();

    Map<String, Object> result = treeSyncBusiness.saveTree(req);
    assertThat(result.get("createdCount")).isEqualTo(1);
    assertThat(result.get("updatedCount")).isEqualTo(1);
    assertThat(result.get("prunedCount")).isEqualTo(1);
    verify(estimateItemService).deleteByIds(List.of(99L));

    BudgetEstimateItem newChild =
        store.stream().filter(i -> "CHI-CNTT".equals(i.getCode())).findFirst().orElseThrow();
    assertThat(newChild.getParentId()).isEqualTo(10L);
    assertThat(newChild.getLevelDepth()).isEqualTo(2);
    assertThat(newChild.getTreePath()).isEqualTo("/CHI-ROOT/CHI-CNTT");
  }

  @Test
  @DisplayName("moveNode chặn chuyển nút cha làm con của nút cháu (circular reference)")
  void shouldRejectCircularReferenceInMoveNode() {
    BudgetEstimateItem root = BudgetEstimateItem.builder().id(1L).code("R").build();
    BudgetEstimateItem child = BudgetEstimateItem.builder().id(2L).code("C").parentId(1L).build();
    BudgetEstimateItem grandChild = BudgetEstimateItem.builder().id(3L).code("GC").parentId(2L).build();

    when(estimateItemService.findById(1L)).thenReturn(Optional.of(root));
    when(estimateItemRepo.findAll()).thenReturn(List.of(root, child, grandChild));

    assertThatThrownBy(() -> treeSyncBusiness.moveNode(1L, 3L, 1))
        .isInstanceOf(ServiceException.class)
        .hasMessageContaining("vòng lặp");
  }

  @Test
  @DisplayName("BudgetEstimateFormulaValidator cho phép công thức hợp lệ và chặn injection / tự tham chiếu / chia 0")
  void shouldValidateFormulaSafely() {
    assertThat(BudgetEstimateFormulaValidator.inspectFormula("SUM(CHILDREN)", "N1")).isEmpty();
    assertThat(BudgetEstimateFormulaValidator.inspectFormula("[CHI-LUONG] * 0.25 + BASE_SALARY", "N2"))
        .isEmpty();
    assertThat(BudgetEstimateFormulaValidator.inspectFormula("ROUND(HEADCOUNT * NORM / 12, 2)", "N3"))
        .isEmpty();

    assertThat(BudgetEstimateFormulaValidator.inspectFormula("1 + 1; DROP TABLE x", "N1")).isNotEmpty();
    assertThat(BudgetEstimateFormulaValidator.inspectFormula("[N1] * 2", "N1")).isNotEmpty();
    assertThat(BudgetEstimateFormulaValidator.inspectFormula("NORM / 0", "N1")).isNotEmpty();
    assertThat(BudgetEstimateFormulaValidator.inspectFormula("SUM(CHILDREN", "N1")).isNotEmpty();
  }

  @Test
  @DisplayName("cloneYearTree nhân bản 2025 -> 2026 với hệ số giảm -10% và evaluateTreeHealth phát hiện thiếu mục lục")
  void shouldCloneYearTreeWithAdjustmentAndCheckHealth() {
    BudgetEstimateItem p25 =
        BudgetEstimateItem.builder()
            .id(1L)
            .code("EXP-GRP")
            .name("Nhóm Chi")
            .budgetYear(2025)
            .itemType("EXPENDITURE")
            .isHeader(true)
            .levelDepth(1)
            .build();
    BudgetEstimateItem c25 =
        BudgetEstimateItem.builder()
            .id(2L)
            .code("EXP-ITEM")
            .name("Chi tiết")
            .parentId(1L)
            .parentCode("EXP-GRP")
            .budgetYear(2025)
            .itemType("EXPENDITURE")
            .normStandardAmount(new BigDecimal("1000.00"))
            .maxCeilingAmount(new BigDecimal("800.00"))
            .levelDepth(2)
            .build();

    List<BudgetEstimateItem> repoStore = new ArrayList<>(List.of(p25, c25));
    AtomicLong seq = new AtomicLong(10L);
    when(estimateItemRepo.findAll()).thenAnswer(inv -> repoStore);
    when(estimateItemService.save(any(BudgetEstimateItem.class)))
        .thenAnswer(
            inv -> {
              BudgetEstimateItem item = inv.getArgument(0);
              if (item.getId() == null) {
                item.setId(seq.incrementAndGet());
                repoStore.add(item);
              }
              return item;
            });

    Map<String, Object> cloned =
        treeOpsBusiness.cloneYearTree(
            Map.of("sourceYear", 2025, "targetYear", 2026, "adjustmentRatePercent", -10));

    assertThat(cloned.get("clonedCount")).isEqualTo(2);
    BudgetEstimateItem c26 =
        repoStore.stream()
            .filter(i -> Integer.valueOf(2026).equals(i.getBudgetYear()) && "EXP-ITEM".equals(i.getCode()))
            .findFirst()
            .orElseThrow();
    assertThat(c26.getNormStandardAmount()).isEqualByComparingTo("900.00");
    assertThat(c26.getParentId()).isNotEqualTo(1L);

    Map<String, Object> health = treeOpsBusiness.evaluateTreeHealth(2026);
    assertThat((int) health.get("issueCount")).isGreaterThanOrEqualTo(2); // thiếu category + vượt trần 900 > 720
  }

  @Test
  @DisplayName("saveAllocationTreeMatrix cập nhật DRAFT, bỏ qua APPROVED và batchSaveAllocations atomic")
  void shouldSaveAllocationTreeMatrixAndAtomicBatch() {
    BudgetAllocationBusiness allocBiz = mock(BudgetAllocationBusiness.class);
    BudgetBatchEntryBusiness batchBiz =
        new BudgetBatchEntryBusiness(
            allocBiz,
            mock(BudgetTransactionBusiness.class),
            mock(BudgetDisbursementBusiness.class),
            estimateItemBusiness,
            allocationRepo,
            unitConfigRepo,
            estimateItemRepo);

    when(unitConfigRepo.findAll())
        .thenReturn(
            List.of(
                BudgetUnitConfig.builder().id(1L).nodeId(100L).nodeCode("DV01").nodeName("Đơn vị 01").build(),
                BudgetUnitConfig.builder().id(2L).nodeId(101L).nodeCode("DV02").nodeName("Đơn vị 02").build()));
    when(estimateItemRepo.findAll())
        .thenReturn(
            List.of(BudgetEstimateItem.builder().id(5L).code("CHI-01").name("Chi TX").budgetYear(2026).build()));
    when(allocationRepo.findByBudgetYearOrderByIdAsc(2026))
        .thenReturn(
            List.of(
                BudgetAllocation.builder()
                    .id(11L)
                    .code("PB-DRAFT")
                    .budgetYear(2026)
                    .periodType("ANNUAL")
                    .estimateItemCode("CHI-01")
                    .nodeCode("DV01")
                    .workflowStatus("DRAFT")
                    .initialAmount(new BigDecimal("100"))
                    .build(),
                BudgetAllocation.builder()
                    .id(12L)
                    .code("PB-APP")
                    .budgetYear(2026)
                    .periodType("ANNUAL")
                    .estimateItemCode("CHI-01")
                    .nodeCode("DV02")
                    .workflowStatus("APPROVED")
                    .initialAmount(new BigDecimal("200"))
                    .build()));

    Map<String, Object> matrixRes =
        batchBiz.saveAllocationTreeMatrix(
            Map.of(
                "budgetYear", 2026,
                "periodType", "ANNUAL",
                "cells",
                    List.of(
                        Map.of("estimateItemCode", "CHI-01", "nodeCode", "DV01", "allocatedAmount", 150),
                        Map.of("estimateItemCode", "CHI-01", "nodeCode", "DV02", "allocatedAmount", 999))));

    assertThat(matrixRes.get("updatedCount")).isEqualTo(1);
    assertThat(matrixRes.get("skippedCount")).isEqualTo(1);
    verify(allocBiz).update(any(), any(BudgetAllocationData.class));

    when(allocBiz.create(any(BudgetAllocationData.class)))
        .thenAnswer(inv -> inv.getArgument(0))
        .thenThrow(new ServiceException("Mã bị trùng"));

    assertThatThrownBy(
            () ->
                batchBiz.batchSaveAllocations(
                    List.of(
                        BudgetAllocationData.builder().code("A1").initialAmount(BigDecimal.TEN).build(),
                        BudgetAllocationData.builder().code("A2").initialAmount(BigDecimal.ONE).build())))
        .isInstanceOf(ServiceException.class)
        .hasMessageContaining("Dòng 2 [A2]");
  }
}
