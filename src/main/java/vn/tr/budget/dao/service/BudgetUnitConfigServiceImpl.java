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
import vn.tr.budget.dao.model.BudgetUnitConfig;
import vn.tr.budget.data.criteria.BudgetUnitConfigSearchCriteria;
import vn.tr.common.core.utils.ServiceUtils;

/**
 * Triển khai Service cho BudgetUnitConfig.
 *
 * @author tyran8x
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetUnitConfigServiceImpl implements BudgetUnitConfigService {

  private final BudgetUnitConfigRepo repo;

  @Override
  public Optional<BudgetUnitConfig> findById(Long id) {
    return repo.findById(id);
  }

  @Override
  public BudgetUnitConfig save(BudgetUnitConfig entity) {
    return repo.save(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BudgetUnitConfig> findByIdIncludingDeleted(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    return repo.findByIdIncludingDeletedByAppCode(id, BudgetQueryHelper.resolveCurrentAppCode());
  }

  @Override
  public List<BudgetUnitConfig> saveAll(List<BudgetUnitConfig> entities) {
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
      repo.softDeleteByIds(ids);
    }
  }

  @Override
  public Page<BudgetUnitConfig> findAll(BudgetUnitConfigSearchCriteria searchCriteria, Pageable pageable) {
    BudgetUnitConfigSearchCriteria criteria =
        ObjectUtil.defaultIfNull(searchCriteria, new BudgetUnitConfigSearchCriteria());
    Specification<BudgetUnitConfig> spec = BudgetUnitConfigSpecifications.quickSearch(criteria);
    if (pageable == null || pageable.isUnpaged()) {
      Sort sort =
          BudgetQueryHelper.buildSafeSort(
              criteria.getSortBy(),
              criteria.getSortDir(),
              BudgetQueryHelper.UNIT_CONFIG_SORT_FIELDS);
      List<BudgetUnitConfig> list = repo.findAll(spec, sort);
      return new PageImpl<>(list, Pageable.unpaged(), list.size());
    }
    return repo.findAll(
        spec,
        BudgetQueryHelper.sanitizePageable(pageable, BudgetQueryHelper.UNIT_CONFIG_SORT_FIELDS));
  }

  @Override
  public List<BudgetUnitConfig> findAll(BudgetUnitConfigSearchCriteria searchCriteria) {
    return findAll(searchCriteria, Pageable.unpaged()).getContent();
  }

  @Override
  public Optional<BudgetUnitConfig> findByCodeIgnoreCaseIncludingDeleted(String code) {
    List<BudgetUnitConfig> results = repo.findAllByCodeIgnoreCaseIncludingDeletedSorted(code);
    return ServiceUtils.checkDuplicateAndReturnFirst(log, "BudgetUnitConfig", code, results, BudgetUnitConfig::getId);
  }

  @Override
  public JpaRepository<BudgetUnitConfig, Long> getRepository() {
    return this.repo;
  }

  @Override
  public BudgetUnitConfigRepo getRepo() {
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
  public List<BudgetUnitConfig> findAllByIds(Collection<Long> ids) {
    return findAll(new BudgetUnitConfigSearchCriteria().setIds(ids));
  }
}
