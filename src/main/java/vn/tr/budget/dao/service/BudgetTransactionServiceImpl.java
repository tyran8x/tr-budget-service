package vn.tr.budget.dao.service;

import cn.hutool.core.util.ObjectUtil;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.tr.budget.dao.model.BudgetTransaction;
import vn.tr.budget.data.criteria.BudgetTransactionSearchCriteria;
import vn.tr.common.core.utils.ServiceUtils;
import vn.tr.common.security.abac.OrgSubtreeAuthorizationHelper;

/**
 * Triển khai Service cho BudgetTransaction.
 *
 * @author tyran8x
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetTransactionServiceImpl implements BudgetTransactionService {

  private final BudgetTransactionRepo repo;

  @Autowired(required = false)
  private OrgSubtreeAuthorizationHelper orgSubtreeAuthorizationHelper;

  @Override
  public Optional<BudgetTransaction> findById(Long id) {
    return repo.findById(id);
  }

  @Override
  public BudgetTransaction save(BudgetTransaction entity) {
    return repo.save(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BudgetTransaction> findByIdIncludingDeleted(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    return repo.findByIdIncludingDeletedByAppCode(id, BudgetQueryHelper.resolveCurrentAppCode());
  }

  @Override
  public List<BudgetTransaction> saveAll(List<BudgetTransaction> entities) {
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
  public Page<BudgetTransaction> findAll(BudgetTransactionSearchCriteria searchCriteria, Pageable pageable) {
    BudgetTransactionSearchCriteria criteria =
        ObjectUtil.defaultIfNull(searchCriteria, new BudgetTransactionSearchCriteria());
    OrgSubtreeAuthorizationHelper.OrgSubtreeScope scope =
        orgSubtreeAuthorizationHelper != null
            ? orgSubtreeAuthorizationHelper.resolveCurrentCallerScope()
            : null;
    Specification<BudgetTransaction> spec =
        BudgetTransactionSpecifications.quickSearch(criteria, scope);
    if (pageable == null || pageable.isUnpaged()) {
      Sort sort =
          BudgetQueryHelper.buildSafeSort(
              criteria.getSortBy(),
              criteria.getSortDir(),
              BudgetQueryHelper.TRANSACTION_SORT_FIELDS);
      List<BudgetTransaction> list = repo.findAll(spec, sort);
      return new PageImpl<>(list, Pageable.unpaged(), list.size());
    }
    return repo.findAll(
        spec,
        BudgetQueryHelper.sanitizePageable(pageable, BudgetQueryHelper.TRANSACTION_SORT_FIELDS));
  }

  @Override
  public List<BudgetTransaction> findAll(BudgetTransactionSearchCriteria searchCriteria) {
    return findAll(searchCriteria, Pageable.unpaged()).getContent();
  }

  @Override
  public Optional<BudgetTransaction> findByCodeIgnoreCaseIncludingDeleted(String code) {
    List<BudgetTransaction> results =
        repo.findAllByCodeIgnoreCaseIncludingDeletedSorted(
            code, BudgetQueryHelper.resolveCurrentAppCode());
    return ServiceUtils.checkDuplicateAndReturnFirst(log, "BudgetTransaction", code, results, BudgetTransaction::getId);
  }

  @Override
  public JpaRepository<BudgetTransaction, Long> getRepository() {
    return this.repo;
  }

  @Override
  public BudgetTransactionRepo getRepo() {
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
  public List<BudgetTransaction> findAllByIds(Collection<Long> ids) {
    return findAll(new BudgetTransactionSearchCriteria().setIds(ids));
  }
}
