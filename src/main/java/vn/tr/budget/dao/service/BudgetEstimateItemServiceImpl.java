package vn.tr.budget.dao.service;

import cn.hutool.core.util.ObjectUtil;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.tr.budget.dao.model.BudgetEstimateItem;
import vn.tr.budget.data.criteria.BudgetEstimateItemSearchCriteria;
import vn.tr.common.core.utils.ServiceUtils;

/**
 * Triển khai Service cho BudgetEstimateItem.
 *
 * @author tyran8x
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetEstimateItemServiceImpl implements BudgetEstimateItemService {

  private final BudgetEstimateItemRepo repo;

  @Override
  public Optional<BudgetEstimateItem> findById(Long id) {
    return repo.findById(id);
  }

  @Override
  public BudgetEstimateItem save(BudgetEstimateItem entity) {
    return repo.save(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BudgetEstimateItem> findByIdIncludingDeleted(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    return repo.findByIdIncludingDeletedByAppCode(id, BudgetQueryHelper.resolveCurrentAppCode());
  }

  @Override
  public List<BudgetEstimateItem> saveAll(List<BudgetEstimateItem> entities) {
    return repo.saveAll(entities);
  }

  @Override
  public void delete(Long id) {
    repo.deleteById(id);
  }

  @Override
  @Transactional
  public void deleteByIds(Collection<Long> ids) {
    if (ObjectUtil.isNotEmpty(ids)) {
      repo.softDeleteByIds(ids, BudgetQueryHelper.resolveCurrentAppCode());
    }
  }

  @Override
  public Page<BudgetEstimateItem> findAll(BudgetEstimateItemSearchCriteria searchCriteria, Pageable pageable) {
    BudgetEstimateItemSearchCriteria criteria =
        ObjectUtil.defaultIfNull(searchCriteria, new BudgetEstimateItemSearchCriteria());
    Specification<BudgetEstimateItem> spec = BudgetEstimateItemSpecifications.quickSearch(criteria);
    if (pageable == null || pageable.isUnpaged()) {
      Sort sort =
          BudgetQueryHelper.buildSafeSort(
              criteria.getSortBy(),
              criteria.getSortDir(),
              BudgetQueryHelper.ESTIMATE_ITEM_SORT_FIELDS);
      List<BudgetEstimateItem> list = repo.findAll(spec, sort);
      return new PageImpl<>(list, Pageable.unpaged(), list.size());
    }
    return repo.findAll(
        spec,
        BudgetQueryHelper.sanitizePageable(pageable, BudgetQueryHelper.ESTIMATE_ITEM_SORT_FIELDS));
  }

  @Override
  public List<BudgetEstimateItem> findAll(BudgetEstimateItemSearchCriteria searchCriteria) {
    return findAll(searchCriteria, Pageable.unpaged()).getContent();
  }

  @Override
  public Optional<BudgetEstimateItem> findByCodeIgnoreCaseIncludingDeleted(String code) {
    List<BudgetEstimateItem> results =
        repo.findAllByCodeIgnoreCaseIncludingDeletedSorted(
            code, BudgetQueryHelper.resolveCurrentAppCode());
    return ServiceUtils.checkDuplicateAndReturnFirst(log, "BudgetEstimateItem", code, results, BudgetEstimateItem::getId);
  }

  @Override
  public JpaRepository<BudgetEstimateItem, Long> getRepository() {
    return this.repo;
  }

  @Override
  public BudgetEstimateItemRepo getRepo() {
    return this.repo;
  }

  @Override
  public boolean existsByIdNotAndCodeIgnoreCase(Long id, String code) {
    return repo.existsByIdNotAndCodeIgnoreCase(id, code);
  }

  @Override
  public boolean existsByIdNotAndNameIgnoreCase(Long id, String name) {
    return repo.existsByIdNotAndNameIgnoreCase(id, name);
  }

  @Override
  public boolean existsByCodeIgnoreCase(String code) {
    return repo.existsByCodeIgnoreCase(code);
  }

  @Override
  public boolean existsByNameIgnoreCase(String name) {
    return repo.existsByNameIgnoreCase(name);
  }

  @Override
  public boolean existsById(Long id) {
    return repo.existsById(id);
  }

  @Override
  public List<BudgetEstimateItem> findAllByIds(Collection<Long> ids) {
    return findAll(new BudgetEstimateItemSearchCriteria().setIds(ids));
  }

  @Override
  public boolean existsDuplicateCodeInYear(String code, Integer budgetYear, Long excludeId) {
    return repo.existsDuplicateCodeInYear(code, budgetYear, excludeId);
  }
}
