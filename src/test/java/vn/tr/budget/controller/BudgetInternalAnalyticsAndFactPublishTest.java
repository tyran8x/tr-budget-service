package vn.tr.budget.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.same.SaSameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import vn.tr.budget.business.BudgetAllocationBusiness;
import vn.tr.budget.business.BudgetDashboardBusiness;
import vn.tr.budget.business.BudgetDisbursementBusiness;
import vn.tr.budget.business.BudgetTransactionBusiness;
import vn.tr.budget.dao.model.BudgetAllocation;
import vn.tr.budget.dao.model.BudgetDisbursement;
import vn.tr.budget.dao.service.BudgetAllocationRepo;
import vn.tr.budget.dao.service.BudgetDisbursementRepo;
import vn.tr.budget.dao.service.BudgetEstimateItemRepo;
import vn.tr.budget.dao.service.BudgetTransactionRepo;
import vn.tr.budget.kafka.BudgetFactMetricPublisher;
import vn.tr.common.core.constant.ReportingKafkaConstants;
import vn.tr.common.core.constant.ServiceNameConstants;
import vn.tr.common.core.context.HeaderContext;
import vn.tr.common.core.domain.R;
import vn.tr.common.core.event.reporting.DomainFactMetricEvent;
import vn.tr.common.feign.dto.InternalAnalyticsMetricData;

/**
 * Kiểm thử đơn vị cho {@link BudgetInternalController}, {@link BudgetDashboardBusiness} và {@link
 * BudgetFactMetricPublisher}: cô lập {@code app_code} fail-closed, truy vấn SQL {@code GROUP BY}
 * không gọi {@code findAll()}, và phát sự kiện Kafka {@link DomainFactMetricEvent}.
 *
 * @author tyran8x
 */
@ExtendWith(MockitoExtension.class)
class BudgetInternalAnalyticsAndFactPublishTest {

  @Mock private BudgetAllocationRepo allocationRepo;
  @Mock private BudgetTransactionRepo transactionRepo;
  @Mock private BudgetDisbursementRepo disbursementRepo;
  @Mock private BudgetEstimateItemRepo estimateItemRepo;
  @Mock private BudgetAllocationBusiness allocationBusiness;
  @Mock private BudgetDisbursementBusiness disbursementBusiness;
  @Mock private BudgetTransactionBusiness transactionBusiness;

  private BudgetDashboardBusiness dashboardBusiness;
  private BudgetInternalController controller;

  @BeforeEach
  void setUp() {
    dashboardBusiness =
        new BudgetDashboardBusiness(
            allocationRepo, transactionRepo, disbursementRepo, estimateItemRepo);
    controller =
        new BudgetInternalController(
            allocationBusiness,
            disbursementBusiness,
            transactionBusiness,
            dashboardBusiness,
            new ObjectMapper());
  }

  @AfterEach
  void tearDown() {
    HeaderContext.clear();
  }

  @Test
  @DisplayName("GET /internal/budget/analytics/metrics fail-closed khi thiếu X-App-Code")
  void shouldFailClosedWhenAppCodeMissing() {
    HeaderContext.clear();
    try (MockedStatic<SaSameUtil> sameMock = mockStatic(SaSameUtil.class)) {
      R<InternalAnalyticsMetricData> res =
          controller.getInternalAnalyticsMetrics(null, 2026, "YEAR");

      sameMock.verify(SaSameUtil::checkCurrentRequestToken);
      assertThat(res.getData().getTotalRecords()).isZero();
      assertThat(res.getData().getBreakdownByUnit()).isEmpty();
      verify(allocationRepo, never()).findAll();
      verify(allocationRepo, never()).summarizeInternalAnalytics(any(), any(), any(), any());
    }
  }

  @Test
  @DisplayName("GET /internal/budget/analytics/metrics lọc app_code bằng SQL GROUP BY và không gọi findAll()")
  void shouldAggregateBudgetMetricsByAppCodeViaSql() {
    HeaderContext.setAppCode("sct");
    when(allocationRepo.summarizeInternalAnalytics(2026, "ANNUAL", "VAN_PHONG_SO", "sct"))
        .thenReturn(
            List.<Object[]>of(
                new Object[] {
                  4L,
                  3L,
                  1L,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  new BigDecimal("1000000000"),
                  new BigDecimal("1000000000"),
                  new BigDecimal("100000000"),
                  new BigDecimal("600000000"),
                  new BigDecimal("500000000"),
                  new BigDecimal("300000000")
                }));
    when(transactionRepo.countInternalAnalytics(2026, "VAN_PHONG_SO", "sct")).thenReturn(8L);
    when(disbursementRepo.summarizeInternalAnalytics(2026, "VAN_PHONG_SO", "sct"))
        .thenReturn(List.<Object[]>of(new Object[] {5L, 5L}));
    when(allocationRepo.aggregateInternalAnalyticsByUnit(2026, "ANNUAL", "VAN_PHONG_SO", "sct"))
        .thenReturn(
            List.<Object[]>of(
                new Object[] {
                  "VAN_PHONG_SO",
                  "Văn phòng Sở",
                  4L,
                  new BigDecimal("1000000000"),
                  new BigDecimal("1000000000"),
                  new BigDecimal("600000000"),
                  new BigDecimal("500000000"),
                  new BigDecimal("300000000")
                }));

    try (MockedStatic<SaSameUtil> sameMock = mockStatic(SaSameUtil.class)) {
      R<InternalAnalyticsMetricData> res =
          controller.getInternalAnalyticsMetrics("VAN_PHONG_SO", 2026, "ANNUAL");

      sameMock.verify(SaSameUtil::checkCurrentRequestToken);
      InternalAnalyticsMetricData data = res.getData();
      assertThat(data.getSourceService()).isEqualTo(ServiceNameConstants.BUDGET_SERVICE);
      assertThat(data.getAppCode()).isEqualTo("sct");
      assertThat(data.getTotalRecords()).isEqualTo(4L);
      assertThat(data.getCompletedRecords()).isEqualTo(3L);
      assertThat(data.getCompletionRate()).isEqualTo(60.0);
      assertThat(data.getBreakdownByUnit()).hasSize(1);
      assertThat(data.getMetrics()).containsEntry("totalTransactions", 8L);
      verify(allocationRepo, never()).findAll();
      verify(estimateItemRepo, never()).findAll();
    }
  }

  @Test
  @DisplayName("GET /internal/budget/analytics/metrics hỗ trợ SYSTEM bypass tường minh")
  void shouldSupportExplicitSystemBypass() {
    HeaderContext.setAppCode("SYSTEM");
    when(allocationRepo.summarizeInternalAnalytics(eq(2026), isNull(), isNull(), isNull()))
        .thenReturn(
            List.<Object[]>of(
                new Object[] {
                  2L,
                  2L,
                  0L,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  new BigDecimal("500"),
                  new BigDecimal("500"),
                  BigDecimal.ZERO,
                  new BigDecimal("250"),
                  new BigDecimal("250"),
                  new BigDecimal("250")
                }));
    when(transactionRepo.countInternalAnalytics(eq(2026), isNull(), isNull())).thenReturn(2L);
    when(disbursementRepo.summarizeInternalAnalytics(eq(2026), isNull(), isNull()))
        .thenReturn(List.<Object[]>of(new Object[] {2L, 2L}));
    when(allocationRepo.aggregateInternalAnalyticsByUnit(eq(2026), isNull(), isNull(), isNull()))
        .thenReturn(List.of());

    try (MockedStatic<SaSameUtil> sameMock = mockStatic(SaSameUtil.class)) {
      R<InternalAnalyticsMetricData> res =
          controller.getInternalAnalyticsMetrics(null, 2026, "YEAR");
      assertThat(res.getData().getAppCode()).isEqualTo("SYSTEM");
      assertThat(res.getData().getTotalRecords()).isEqualTo(2L);
    }
  }

  @Test
  @DisplayName("BudgetFactMetricPublisher phát DomainFactMetricEvent hợp lệ khi giải ngân/quyết toán")
  @SuppressWarnings("unchecked")
  void shouldPublishBudgetFactMetricEventToKafka() {
    KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    ObjectProvider<KafkaTemplate<String, Object>> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(kafkaTemplate);

    BudgetFactMetricPublisher publisher = new BudgetFactMetricPublisher(provider);
    BudgetDisbursement disb =
        BudgetDisbursement.builder()
            .id(88L)
            .code("GN-2026-88")
            .appCode("sct")
            .nodeCode("VAN_PHONG_SO")
            .budgetYear(2026)
            .amount(new BigDecimal("200000000"))
            .settledAmount(new BigDecimal("200000000"))
            .approvalStatus("SETTLED")
            .build();
    BudgetAllocation alloc =
        BudgetAllocation.builder()
            .id(1L)
            .appCode("sct")
            .nodeCode("VAN_PHONG_SO")
            .budgetYear(2026)
            .effectiveAmount(new BigDecimal("1000000000"))
            .disbursedAmount(new BigDecimal("600000000"))
            .build();

    publisher.publishDisbursementSnapshot(disb, alloc, "DISBURSEMENT_SETTLED");

    ArgumentCaptor<DomainFactMetricEvent> captor =
        ArgumentCaptor.forClass(DomainFactMetricEvent.class);
    verify(kafkaTemplate)
        .send(
            eq(ReportingKafkaConstants.TOPIC_DOMAIN_FACT_METRICS),
            eq("sct:BUDGET:VAN_PHONG_SO"),
            captor.capture());

    DomainFactMetricEvent event = captor.getValue();
    assertThat(event.getEventId()).isNotBlank();
    assertThat(event.getAppCode()).isEqualTo("sct");
    assertThat(event.getSourceService()).isEqualTo(ServiceNameConstants.BUDGET_SERVICE);
    assertThat(event.getProviderCode()).isEqualTo("BUDGET");
    assertThat(event.getCompletionRate()).isEqualTo(60.0);
    assertThat(event.getMetricItems()).hasSize(3);
  }
}
