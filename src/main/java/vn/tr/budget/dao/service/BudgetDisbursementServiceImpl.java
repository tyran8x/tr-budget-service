package vn.tr.budget.dao.service;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
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
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.data.criteria.BudgetDisbursementSearchCriteria;
import vn.tr.common.core.utils.ServiceUtils;

/**
 * Triển khai Service cho BudgetDisbursement.
 *
 * @author tyran8x
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetDisbursementServiceImpl implements BudgetDisbursementService {

  private final BudgetDisbursementRepo repo;

  @Override
  public Optional<BudgetDisbursement> findById(Long id) {
    return repo.findById(id);
  }

  @Override
  public BudgetDisbursement save(BudgetDisbursement entity) {
    return repo.save(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BudgetDisbursement> findByIdIncludingDeleted(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    return repo.findByIdIncludingDeleted(id);
  }

  @Override
  public List<BudgetDisbursement> saveAll(List<BudgetDisbursement> entities) {
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
  public Page<BudgetDisbursement> findAll(BudgetDisbursementSearchCriteria searchCriteria, Pageable pageable) {
    BudgetDisbursementSearchCriteria criteria =
        ObjectUtil.defaultIfNull(searchCriteria, new BudgetDisbursementSearchCriteria());
    Specification<BudgetDisbursement> spec = BudgetDisbursementSpecifications.quickSearch(criteria);
    if (pageable == null || pageable.isUnpaged()) {
      Sort sort = Sort.unsorted();
      if (StrUtil.isNotEmpty(criteria.getSortBy())) {
        Sort.Direction direction =
            "ASC".equalsIgnoreCase(criteria.getSortDir())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        sort = Sort.by(direction, criteria.getSortBy());
      }
      List<BudgetDisbursement> list = repo.findAll(spec, sort);
      return new PageImpl<>(list, Pageable.unpaged(), list.size());
    }
    return repo.findAll(spec, pageable);
  }

  @Override
  public List<BudgetDisbursement> findAll(BudgetDisbursementSearchCriteria searchCriteria) {
    return findAll(searchCriteria, Pageable.unpaged()).getContent();
  }

  @Override
  public Optional<BudgetDisbursement> findByCodeIgnoreCaseIncludingDeleted(String code) {
    List<BudgetDisbursement> results = repo.findAllByCodeIgnoreCaseIncludingDeletedSorted(code);
    return ServiceUtils.checkDuplicateAndReturnFirst(log, "BudgetDisbursement", code, results, BudgetDisbursement::getId);
  }

  @Override
  public JpaRepository<BudgetDisbursement, Long> getRepository() {
    return this.repo;
  }

  @Override
  public BudgetDisbursementRepo getRepo() {
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
  public List<BudgetDisbursement> findAllByIds(Collection<Long> ids) {
    return findAll(new BudgetDisbursementSearchCriteria().setIds(ids));
  }
}
