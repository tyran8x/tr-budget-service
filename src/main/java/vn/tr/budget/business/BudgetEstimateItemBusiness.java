package vn.tr.budget.business;

import cn.hutool.core.util.StrUtil;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.dao.service.BudgetEstimateItemService;
import vn.tr.budget.data.criteria.BudgetEstimateItemSearchCriteria;
import vn.tr.budget.data.dto.BudgetEstimateItemData;
import vn.tr.budget.data.mapper.BudgetEstimateItemMapper;
import vn.tr.budget.data.validator.BudgetEstimateItemValidator;
import vn.tr.common.core.domain.data.PagedData;
import vn.tr.common.core.enums.LifecycleStatus;
import vn.tr.common.core.exception.base.EntityNotFoundException;
import vn.tr.common.jpa.audit.AuditRollbackHelper;
import vn.tr.common.jpa.helper.GenericUpsertHelper;
import vn.tr.common.web.data.dto.BulkOperationResult;
import vn.tr.common.web.data.dto.BulkSortOrderData;
import vn.tr.common.web.utils.BusinessLogicHelper;
import vn.tr.common.web.utils.CoreUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Lớp điều phối nghiệp vụ cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
@Service("budgetEstimateItemBusiness")
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BudgetEstimateItemBusiness {

  private final BudgetEstimateItemService budgetEstimateItemService;
  private final BudgetEstimateItemMapper budgetEstimateItemMapper;
  private final BudgetEstimateItemValidator budgetEstimateItemValidator;
  private final GenericUpsertHelper genericUpsertHelper;
  private final AuditRollbackHelper auditRollbackHelper;
  private final BusinessLogicHelper businessLogicHelper;


  public JpaRepository<BudgetEstimateItem, Long> getInjectedRepo() {
    return budgetEstimateItemService.getRepository();
  }

  /** Tạo mới BudgetEstimateItem. */
  public BudgetEstimateItemData create(BudgetEstimateItemData data) {
    data.setId(null);
    validateData(data);
    if (data.getStatus() == null) {
      data.setStatus(LifecycleStatus.ACTIVE);
    }
    if (StrUtil.isBlank(data.getItemType())) {
      data.setItemType("EXPENDITURE");
    }
    if (data.getLevelDepth() == null) {
      data.setLevelDepth(data.getParentId() != null ? 2 : 1);
    }
    if (data.getIsHeader() == null) {
      data.setIsHeader(Boolean.FALSE);
    }
    if (StrUtil.isBlank(data.getUnitOfMeasure())) {
      data.setUnitOfMeasure("VNĐ");
    }
    if (data.getRequireVoucher() == null) {
      data.setRequireVoucher(Boolean.TRUE);
    }
    BudgetEstimateItem entity =
        genericUpsertHelper.upsert(
            data,
            () -> budgetEstimateItemService.findByCodeIgnoreCaseIncludingDeleted(data.getCode()),
            () -> budgetEstimateItemMapper.toEntity(data),
            budgetEstimateItemMapper::updateEntityFromData,
            budgetEstimateItemService.getRepository());

    return budgetEstimateItemMapper.toData(entity);
  }

  /** Cập nhật BudgetEstimateItem. */
  public BudgetEstimateItemData update(Long id, BudgetEstimateItemData data) {
    BudgetEstimateItem existing =
        budgetEstimateItemService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetEstimateItem.class, id));
    data.setId(id);
    validateData(data);
    budgetEstimateItemMapper.updateEntityFromData(data, existing);
    BudgetEstimateItem saved = budgetEstimateItemService.save(existing);
    return budgetEstimateItemMapper.toData(saved);
  }

  /** Xoá mềm BudgetEstimateItem. */
  public void delete(Long id) {
    budgetEstimateItemService
        .findById(id)
        .orElseThrow(() -> new EntityNotFoundException(BudgetEstimateItem.class, id));
    budgetEstimateItemService.delete(id);
  }

  /** Xoá mềm hàng loạt BudgetEstimateItem. */
  public BulkOperationResult<Long> bulkDelete(Collection<Long> ids) {
    return businessLogicHelper.executeBulkDelete(
        ids,
        budgetEstimateItemService::findAllByIds,
        BudgetEstimateItem::getId,
        null,
        null,
        budgetEstimateItemService::deleteByIds,
        "budget.budgetEstimateItem.not.found");
  }

  /** Lấy chi tiết BudgetEstimateItem theo ID. */
  @Transactional(readOnly = true)
  public BudgetEstimateItemData findById(Long id) {
    return budgetEstimateItemService
        .findById(id)
        .map(budgetEstimateItemMapper::toData)
        .orElseThrow(() -> new EntityNotFoundException(BudgetEstimateItem.class, id));
  }

  /** Tìm kiếm BudgetEstimateItem phân trang. */
  @Transactional(readOnly = true)
  public PagedData<BudgetEstimateItemData> findAll(BudgetEstimateItemSearchCriteria criteria) {
    Pageable pageable = CoreUtils.getPageRequest(criteria);
    Page<BudgetEstimateItem> page = budgetEstimateItemService.findAll(criteria, pageable);
    return PagedData.from(page, budgetEstimateItemMapper::toData);
  }

  /** Sao chép BudgetEstimateItem. */
  @Transactional
  public BudgetEstimateItemData cloneById(Long id) {
    BudgetEstimateItem source =
        budgetEstimateItemService
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException(BudgetEstimateItem.class, id));
    BudgetEstimateItemData clonedData = budgetEstimateItemMapper.toData(source);
    clonedData.setId(null);
    clonedData.setCode(source.getCode() + "_COPY_" + System.currentTimeMillis() % 10000);
    clonedData.setName(source.getName() + " (Bản sao)");
    return create(clonedData);
  }

  /** Khôi phục phiên bản lịch sử JaVers. */
  @Transactional
  public BudgetEstimateItemData rollback(Long id, Long version) {
    return auditRollbackHelper.executeRollback(
        BudgetEstimateItem.class,
        id,
        version,
        budgetEstimateItemService::findById,
        budgetEstimateItemService::save,
        budgetEstimateItemMapper::toData,
        budgetEstimateItemMapper::updateEntityFromData,
        BudgetEstimateItemData::setId,
        this::validateData);
  }

  /** Cập nhật thứ tự sắp xếp hàng loạt. */
  @Transactional
  public void bulkUpdateSortOrder(BulkSortOrderData data) {
    if (data == null || data.getUpdates() == null || data.getUpdates().isEmpty()) {
      return;
    }
    for (BulkSortOrderData.SortOrderUpdate item : data.getUpdates()) {
      budgetEstimateItemService
          .findById(item.getId())
          .ifPresent(
              entity -> {
                entity.setSortOrder(item.getSortOrder());
                budgetEstimateItemService.save(entity);
              });
    }
  }

  private void validateData(BudgetEstimateItemData data) {
    vn.tr.common.web.core.BaseBusinessHelper.validateOrThrow(
        budgetEstimateItemValidator, data, "budgetEstimateItemData");
  }

  /** Lấy danh sách cây Dự toán Thu - Chi đa cấp. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> getEstimateTree(String itemType) {
    List<BudgetEstimateItem> all =
        StrUtil.isNotBlank(itemType)
            ? budgetEstimateItemService.getRepo().findByItemTypeOrderByIdAsc(itemType.toUpperCase())
            : budgetEstimateItemService.getRepo().findAll();
    List<Map<String, Object>> result = new ArrayList<>();
    for (BudgetEstimateItem item : all) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", item.getId());
      row.put("code", item.getCode());
      row.put("name", item.getName());
      row.put("parentId", item.getParentId());
      row.put("parentCode", item.getParentCode());
      row.put("parentName", item.getParentName());
      row.put("itemType", item.getItemType());
      row.put("numberingStyle", item.getNumberingStyle());
      row.put("levelDepth", item.getLevelDepth());
      row.put("isHeader", item.getIsHeader());
      row.put("formula", item.getFormula());
      row.put("categoryCode", item.getCategoryCode());
      row.put("categoryName", item.getCategoryName());
      row.put("unitOfMeasure", item.getUnitOfMeasure());
      row.put("normStandardAmount", item.getNormStandardAmount());
      row.put("requireVoucher", item.getRequireVoucher());
      result.add(row);
    }
    return result;
  }

}
