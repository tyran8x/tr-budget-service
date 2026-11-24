# 💰 TR Budget Service — Hệ thống Quản lý Kinh phí Đa cấp, Cây Dự toán Thu - Chi, Phân bổ, Chuyển nguồn & Giải ngân Quyết toán

![Trạng thái](https://img.shields.io/badge/Trạng_thái-Production_Ready-brightgreen)
![Port](https://img.shields.io/badge/Port-8503-blue)
![Mã dịch vụ](https://img.shields.io/badge/Service_Code-507-purple)
![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.x-brightgreen)

> Quản lý danh mục Loại - Khoản Mục lục Ngân sách (`BudgetCategory`), Cây Dự toán Thu - Chi đa cấp (`BudgetEstimateItem`), Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính (`BudgetUnitConfig`), Phân bổ Kinh phí đa kỳ (`BudgetAllocation`), Sổ cái Biến động Nguồn bút toán kép: Giao đầu năm, Bổ sung nguồn, Chuyển nguồn liên năm, Điều chuyển nội bộ (`BudgetTransaction`), Kiểm soát Giải ngân Kho bạc & Quyết toán (`BudgetDisbursement`) và Báo cáo Thực hiện Thu - Chi định kỳ (`BudgetReport`).

---

## 🏛️ Kiến trúc Phân lớp Chuẩn (Layered Architecture)

Dịch vụ tuân thủ kiến trúc phân lớp 5 tầng tiêu chuẩn của hệ thống `tr-pro-app`:

```text
tr-budget-service/
├── src/main/java/vn/tr/budget/
│   ├── business/           # Tầng điều phối nghiệp vụ, giao dịch (@Transactional), tích hợp liên dịch vụ
│   ├── config/             # Cấu hình Spring Beans, Security, OpenAPI, Async, Feign
│   ├── controller/         # Tầng REST API (@RestController), kiểm tra phân quyền (@SaCheckPermission)
│   ├── dao/
│   │   ├── model/          # Thực thể cơ sở dữ liệu JPA (@Entity, @Table, kế thừa BaseEntity)
│   │   └── service/        # Tầng truy xuất dữ liệu (*Repo, *Service, *ServiceImpl, *Specifications)
│   └── data/
│       ├── criteria/       # Bộ tiêu chí tìm kiếm và lọc động (*SearchCriteria)
│       ├── dto/            # Đối tượng truyền tải dữ liệu (*Data, *Request, *Response)
│       ├── enums/          # Danh mục liệt kê trạng thái và loại nghiệp vụ
│       ├── mapper/         # Chuyển đổi dữ liệu MapStruct & FastExcel (*Mapper)
│       └── validator/      # Kiểm tra quy tắc nghiệp vụ đầu vào (*Validator)
├── src/test/java/          # Kịch bản kiểm thử Unit Test & Integration Test
└── pom.xml                 # Cấu hình phụ thuộc Maven
```

---

<!-- AUTO-GENERATED-BE-README:START -->

## 📊 Chỉ mục Mã nguồn & API Tự động (Auto-Generated Codebase Index)

> Khối này được trích xuất tự động từ mã nguồn Java và `config/_base` bởi `scripts/ops/update_be_readmes.py`. AI Agent có thể tra cứu trực tiếp toàn bộ Entities, Bảng DB, Controllers, Endpoints, Quyền `@SaCheckPermission`, Business, DTOs, SearchCriteria và Enums tại đây mà không cần tốn token quét thư mục.

### 1. Thông số Cấu hình & Thống kê Thành phần

| Thuộc tính / Thành phần | Giá trị / Số lượng | Chi tiết |
| --- | --- | --- |
| **Mã dịch vụ / Port** | `507` / **`8503`** | Gateway Base Path: `/v1/budget` |
| **Database / Table Prefix** | `tr_budget` / `budget_` | AppCode: `budget` (Shorthand: `Budget`) |
| **Base Package** | `vn.tr.budget` | Top packages: `business`, `config`, `controller`, `dao`, `data`, `excel`, `kafka` |
| **Entities (`dao.model`)** | **7** | Thực thể JPA ánh xạ bảng PostgreSQL |
| **REST Controllers / Endpoints** | **9** / **164** | Tổng số lớp Controller và hàm API endpoint |
| **Mã quyền `@SaCheckPermission`** | **107** | Quyền truy cập bảo vệ API theo chuẩn `{EntityName}{Action}` |
| **Business / Service / Repo** | **11** / **21** / **7** | Kèm **7** lớp Dynamic Specifications |
| **DTOs / Criteria / Mappers / Validators** | **15** / **7** / **7** / **8** | Lớp truyền tải dữ liệu, bộ lọc, ánh xạ MapStruct & kiểm tra hợp lệ |
| **Enums / Unit Tests** | **6** / **7** | Danh mục liệt kê & kịch bản kiểm thử tự động |
| **Phụ thuộc `tr-common-*`** | **13** | `tr-common-core`, `tr-common-jpa`, `tr-common-web`, `tr-common-redis`, `tr-common-kafka`, `tr-common-log`, `tr-common-security`, `tr-common-satoken`, `tr-common-encrypt`, `tr-common-feign`, `tr-common-excel`, `tr-common-ratelimiter`, `tr-common-doc` |

### 2. Danh mục Thực thể CSDL (`7` Entities)

| # | Entity Class | Tên Bảng (`@Table`) | Package | Mô tả Nghiệp vụ |
| --- | --- | --- | --- | --- |
| 1 | `BudgetAllocation` | `budget_allocation` | `vn.tr.budget.dao.model` | Entity đại diện cho Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ (BudgetAllocation). |
| 2 | `BudgetCategory` | `budget_category` | `vn.tr.budget.dao.model` | Entity đại diện cho Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí (BudgetCategory). |
| 3 | `BudgetDisbursement` | `budget_disbursement` | `vn.tr.budget.dao.model` | Entity đại diện cho Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán (BudgetDisbursement). |
| 4 | `BudgetEstimateItem` | `budget_estimate_item` | `vn.tr.budget.dao.model` | Entity đại diện cho Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp (BudgetEstimateItem). |
| 5 | `BudgetReport` | `budget_report` | `vn.tr.budget.dao.model` | Entity đại diện cho Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ (BudgetReport). |
| 6 | `BudgetTransaction` | `budget_transaction` | `vn.tr.budget.dao.model` | Entity đại diện cho Sổ cái Biến động Nguồn Kinh phí Bút toán kép (BudgetTransaction). |
| 7 | `BudgetUnitConfig` | `budget_unit_config` | `vn.tr.budget.dao.model` | Entity đại diện cho Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính (BudgetUnitConfig). |

### 3. Danh mục REST Controllers, Endpoints & Phân quyền (`9` Controllers / `164` Endpoints)

| # | Controller | Base Path | Số API | Endpoints tiêu biểu (`Method Path (Hàm)`) | Quyền `@SaCheckPermission` | Mô tả |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `BudgetAllocationController` | `/allocations` | 26 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /options` (getOptions), `GET /{id}` (findById), `POST /{id}/clone` (cloneById), ... *(+18 API)* | `BudgetAllocationCreate`, `BudgetAllocationEdit`, `BudgetAllocationDelete`, `BudgetAllocationBatchDelete`, `BudgetAllocationList`, `BudgetAllocationOptions`, `BudgetAllocationView`, `BudgetAllocationDetail`, ... *(+21)* | Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ — API quản lý Lập, Thẩm định, Giao & Phân bổ Dự toán Kinh phí theo Năm/Quý/Tháng, Đơn vị và Cây Thu-Chi |
| 2 | `BudgetCategoryController` | `/categories` | 17 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /options` (getOptions), `GET /{id}` (findById), `POST /{id}/clone` (cloneById), ... *(+9 API)* | `BudgetCategoryCreate`, `BudgetCategoryEdit`, `BudgetCategoryDelete`, `BudgetCategoryBatchDelete`, `BudgetCategoryList`, `BudgetCategoryOptions`, `BudgetCategoryView`, `BudgetCategoryDetail`, ... *(+26)* | Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí — API quản lý Danh mục Loại - Khoản - Mục lục Ngân sách Nhà nước và Nguồn kinh phí |
| 3 | `BudgetDashboardController` | `/dashboard` | 10 | `GET /overview` (getOverview), `GET /unit-matrix` (getUnitMatrix), `GET /target-scope` (getTargetScopeContext), `POST /validate-target-scope` (validateTargetScopeSelection), `GET /revenue-expenditure-tree` (getRevenueExpenditureTree), `GET /disbursement-scorecard` (getDisbursementScorecard), `POST /ai-advisor` (getAiAdvisorInsights), `GET /burn-rate-forecast` (getBurnRateForecast), ... *(+2 API)* | `BudgetDashboardQuery`, `BudgetDashboardSimulate` | Dashboard & Báo cáo Điều hành Kinh phí — API thống kê tổng quan Dự toán Thu - Chi, Tiến độ Giải ngân, Ma trận Đơn vị, Bảng điểm Kỷ luật Giải ngân và Cố vấn AI |
| 4 | `BudgetDisbursementController` | `/disbursements` | 21 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /options` (getOptions), `GET /{id}` (findById), `POST /{id}/clone` (cloneById), ... *(+13 API)* | `BudgetDisbursementCreate`, `BudgetDisbursementEdit`, `BudgetDisbursementDelete`, `BudgetDisbursementBatchDelete`, `BudgetDisbursementList`, `BudgetDisbursementOptions`, `BudgetDisbursementView`, `BudgetDisbursementDetail`, ... *(+15)* | Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán — API quản lý Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc Nhà nước, Hoàn ứng & Quyết toán chứng từ |
| 5 | `BudgetEstimateItemController` | `/estimate-items` | 19 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /options` (getOptions), `GET /{id}` (findById), `POST /{id}/clone` (cloneById), ... *(+11 API)* | `BudgetEstimateItemCreate`, `BudgetEstimateItemEdit`, `BudgetEstimateItemDelete`, `BudgetEstimateItemBatchDelete`, `BudgetEstimateItemList`, `BudgetEstimateItemOptions`, `BudgetEstimateItemView`, `BudgetEstimateItemDetail`, ... *(+20)* | Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp — API quản lý Cây Danh mục & Cấu hình Dự toán Thu - Chi đa cấp |
| 6 | `BudgetInternalController` | `/internal/budget` | 7 | `POST /allocations/sync-from-plan` (syncAllocationFromPlan), `GET /allocations/by-plan/{planCode}` (getBudgetSnapshotByPlanCode), `POST /transactions/record` (recordPlanLedgerTransaction), `POST /disbursements/reserve-commitment` (reserveCommitment), `POST /disbursements/{id}/payment-webhook-settle` (settleDisbursementFromPaymentWebhook), `GET /dashboard/overview` (getDashboardOverview), `GET /analytics/metrics` (getInternalAnalyticsMetrics) | — | Internal Budget S2S API — API nội bộ liên thông Kinh phí - Kế hoạch - Thanh toán (Sa-Token Same-Token) |
| 7 | `BudgetReportController` | `/reports` | 21 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /options` (getOptions), `GET /{id}` (findById), `POST /{id}/clone` (cloneById), ... *(+13 API)* | `BudgetReportCreate`, `BudgetReportEdit`, `BudgetReportDelete`, `BudgetReportBatchDelete`, `BudgetReportList`, `BudgetReportOptions`, `BudgetReportView`, `BudgetReportDetail`, ... *(+11)* | Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ — API quản lý Báo cáo Thực hiện Thu - Chi, Giải ngân & Tổng hợp Quyết toán định kỳ Tháng/Quý/6 tháng/Năm |
| 8 | `BudgetTransactionController` | `/transactions` | 28 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /options` (getOptions), `GET /{id}` (findById), `POST /{id}/clone` (cloneById), ... *(+20 API)* | `BudgetTransactionCreate`, `BudgetTransactionEdit`, `BudgetTransactionDelete`, `BudgetTransactionBatchDelete`, `BudgetTransactionList`, `BudgetTransactionOptions`, `BudgetTransactionView`, `BudgetTransactionDetail`, ... *(+15)* | Sổ cái Biến động Nguồn Kinh phí Bút toán kép — API quản lý Sổ cái Biến động Nguồn Kinh phí: Giao đầu năm, Bổ sung nguồn, Chuyển nguồn liên năm (N -> N+1), Điều chuyển nội bộ và Thu hồi dự toán |
| 9 | `BudgetUnitConfigController` | `/unit-configs` | 15 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /options` (getOptions), `GET /{id}` (findById), `POST /{id}/clone` (cloneById), ... *(+7 API)* | `BudgetUnitConfigCreate`, `BudgetUnitConfigEdit`, `BudgetUnitConfigDelete`, `BudgetUnitConfigBatchDelete`, `BudgetUnitConfigList`, `BudgetUnitConfigOptions`, `BudgetUnitConfigView`, `BudgetUnitConfigDetail`, ... *(+22)* | Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính — API quản lý Cấu hình Đơn vị Sử dụng Ngân sách và Ma trận Phân quyền Tự chủ Tài chính |

### 4. Danh mục Tầng Nghiệp vụ (Business), DTO, SearchCriteria, Mapper, Validator & Enum

| Phân lớp | Số lượng | Danh sách Lớp thành phần |
| --- | --- | --- |
| **Business (`*Business`)** | 11 | `BudgetAllocationBusiness`, `BudgetBatchEntryBusiness`, `BudgetCategoryBusiness`, `BudgetDashboardBusiness`, `BudgetDisbursementBusiness`, `BudgetEstimateItemBusiness`, `BudgetEstimateTreeOpsBusiness`, `BudgetEstimateTreeSyncBusiness`, `BudgetReportBusiness`, `BudgetTransactionBusiness`, `BudgetUnitConfigBusiness` |
| **DAO Services (`*Service` / `*ServiceImpl`)** | 21 | `BudgetAllocationExcelService`, `BudgetAllocationService`, `BudgetAllocationServiceImpl`, `BudgetCategoryExcelService`, `BudgetCategoryService`, `BudgetCategoryServiceImpl`, `BudgetDisbursementExcelService`, `BudgetDisbursementService`, `BudgetDisbursementServiceImpl`, `BudgetEstimateItemExcelService`, `BudgetEstimateItemService`, `BudgetEstimateItemServiceImpl`, `BudgetReportExcelService`, `BudgetReportService`, `BudgetReportServiceImpl`, `BudgetTransactionExcelService`, `BudgetTransactionService`, `BudgetTransactionServiceImpl`, `BudgetUnitConfigExcelService`, `BudgetUnitConfigService`, `BudgetUnitConfigServiceImpl` |
| **Repositories (`*Repo`)** | 7 | `BudgetAllocationRepo`, `BudgetCategoryRepo`, `BudgetDisbursementRepo`, `BudgetEstimateItemRepo`, `BudgetReportRepo`, `BudgetTransactionRepo`, `BudgetUnitConfigRepo` |
| **Specifications (`*Specifications`)** | 7 | `BudgetAllocationSpecifications`, `BudgetCategorySpecifications`, `BudgetDisbursementSpecifications`, `BudgetEstimateItemSpecifications`, `BudgetReportSpecifications`, `BudgetTransactionSpecifications`, `BudgetUnitConfigSpecifications` |
| **DTOs (`*Data` / `*Request` / `*Response`)** | 15 | `BudgetAllocationData`, `BudgetAllocationImportData`, `BudgetCategoryData`, `BudgetCategoryImportData`, `BudgetDisbursementData`, `BudgetDisbursementImportData`, `BudgetEstimateItemData`, `BudgetEstimateItemImportData`, `BudgetEstimateTreeSaveRequest`, `BudgetReportData`, `BudgetReportImportData`, `BudgetTransactionData`, `BudgetTransactionImportData`, `BudgetUnitConfigData`, `BudgetUnitConfigImportData` |
| **Search Criteria (`*Criteria`)** | 7 | `BudgetAllocationSearchCriteria`, `BudgetCategorySearchCriteria`, `BudgetDisbursementSearchCriteria`, `BudgetEstimateItemSearchCriteria`, `BudgetReportSearchCriteria`, `BudgetTransactionSearchCriteria`, `BudgetUnitConfigSearchCriteria` |
| **Mappers (`data.mapper.*`)** | 7 | `BudgetAllocationMapper`, `BudgetCategoryMapper`, `BudgetDisbursementMapper`, `BudgetEstimateItemMapper`, `BudgetReportMapper`, `BudgetTransactionMapper`, `BudgetUnitConfigMapper` |
| **Validators (`data.validator.*`)** | 8 | `BudgetAllocationValidator`, `BudgetCategoryValidator`, `BudgetDisbursementValidator`, `BudgetEstimateFormulaValidator`, `BudgetEstimateItemValidator`, `BudgetReportValidator`, `BudgetTransactionValidator`, `BudgetUnitConfigValidator` |
| **Enums (`data.enums.*`)** | 6 | `BudgetApprovalScope`, `BudgetAutonomyType`, `BudgetDisbursementType`, `BudgetPeriodType`, `BudgetTransactionType`, `BudgetWorkflowStatus` |
| **Unit / Integration Tests** | 7 | `BudgetAuditRemediationTest`, `BudgetBusinessFlowTest`, `BudgetDashboardAndWebhookTest`, `BudgetInternalAnalyticsAndFactPublishTest`, `BudgetTargetScopeValidatorTest`, `BudgetTreeBuilderAndBatchTest`, `BudgetWorkflowEngineTest` |

<!-- AUTO-GENERATED-BE-README:END -->

---

## 🛠️ Tích hợp Hạ tầng Dùng chung (`tr-common`)

- **Bảo mật & Phân quyền (`tr-common-security`, `tr-common-satoken`)**: Bảo vệ toàn bộ endpoint bằng `@SaCheckPermission`, xác thực nội bộ S2S bằng `Same-Token`.
- **Kiểm toán & Lịch sử Phiên bản (`tr-common-jpa`, `tr-common-log`)**: Tự động ghi nhận người tạo/cập nhật, lịch sử thay đổi thực thể qua JaVers (`/audit/{entity}/{id}`) và nhật ký thao tác `@Log`.
- **Nhập/Xuất Excel (`tr-common-excel`)**: Hỗ trợ xuất dữ liệu tốc độ cao và luồng Import Wizard 3 bước qua `GlobalExcelService`.
- **Truy vết Phân tán (`tr-common-core`)**: Mọi phản hồi `R<T>` đều đính kèm `traceId` chuẩn W3C UUIDv7 32-hex xuyên suốt luồng đồng bộ và bất đồng bộ.

---

## 🚀 Hướng dẫn Khởi chạy & Kiểm thử Cục bộ

```bash
# 1. Kiểm tra biên dịch mã nguồn và test
mvn test-compile -f tr-business-services/tr-budget-service/pom.xml

# 2. Chạy bộ kiểm thử tự động (Unit Tests)
mvn test -f tr-business-services/tr-budget-service/pom.xml

# 3. Khởi chạy microservice với cấu hình môi trường dev
mvn spring-boot:run -f tr-business-services/tr-budget-service/pom.xml -Dspring-boot.run.profiles=dev
```

- **Swagger UI trực tiếp**: `http://localhost:8503/swagger-ui/index.html`
- **OpenAPI JSON Specs**: `http://localhost:8503/v3/api-docs`
- **API Gateway Aggregator**: `http://localhost:8000/doc.html`

---

Cập nhật lần cuối: 2026-11-24
