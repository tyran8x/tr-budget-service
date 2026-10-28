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
| **Base Package** | `vn.tr.budget` | Top packages: `business`, `config`, `controller`, `dao`, `data`, `excel` |
| **Entities (`dao.model`)** | **7** | Thực thể JPA ánh xạ bảng PostgreSQL |
| **REST Controllers / Endpoints** | **8** / **117** | Tổng số lớp Controller và hàm API endpoint |
| **Mã quyền `@SaCheckPermission`** | **77** | Quyền truy cập bảo vệ API theo chuẩn `{EntityName}{Action}` |
| **Business / Service / Repo** | **8** / **21** / **7** | Kèm **7** lớp Dynamic Specifications |
| **DTOs / Criteria / Mappers / Validators** | **14** / **7** / **7** / **7** | Lớp truyền tải dữ liệu, bộ lọc, ánh xạ MapStruct & kiểm tra hợp lệ |
| **Enums / Unit Tests** | **0** / **2** | Danh mục liệt kê & kịch bản kiểm thử tự động |
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

### 3. Danh mục REST Controllers, Endpoints & Phân quyền (`8` Controllers / `117` Endpoints)

| # | Controller | Base Path | Số API | Endpoints tiêu biểu (`Method Path (Hàm)`) | Quyền `@SaCheckPermission` | Mô tả |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `BudgetAllocationController` | `/allocations` | 24 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /{id}` (findById), `GET /{id}/clone` (cloneById), `POST /{id}/rollback` (rollback), ... *(+16 API)* | `BudgetAllocationCreate`, `BudgetAllocationEdit`, `BudgetAllocationDelete`, `BudgetAllocationBatchDelete`, `BudgetAllocationList`, `BudgetAllocationDetail`, `BudgetAllocationClone`, `BudgetAllocationRollback`, ... *(+3)* | Phương án & Quyết định Phân bổ Dự toán Kinh phí Đa kỳ — API quản lý Lập, Thẩm định, Giao & Phân bổ Dự toán Kinh phí theo Năm/Quý/Tháng, Đơn vị và Cây Thu-Chi (kế thừa & nâng cấp từ QlkpPhanBoKinhPhi) |
| 2 | `BudgetCategoryController` | `/categories` | 13 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /{id}` (findById), `GET /{id}/clone` (cloneById), `POST /{id}/rollback` (rollback), ... *(+5 API)* | `BudgetCategoryCreate`, `BudgetCategoryEdit`, `BudgetCategoryDelete`, `BudgetCategoryBatchDelete`, `BudgetCategoryList`, `BudgetCategoryDetail`, `BudgetCategoryClone`, `BudgetCategoryRollback`, ... *(+3)* | Danh mục Loại - Khoản Mục lục Ngân sách & Nguồn Kinh phí — API quản lý Danh mục Loại - Khoản - Mục lục Ngân sách Nhà nước và Nguồn kinh phí (kế thừa & nâng cấp từ QlkpDmLoaiKhoan) |
| 3 | `BudgetDashboardController` | `/dashboard` | 5 | `GET /overview` (getOverview), `GET /unit-matrix` (getUnitMatrix), `GET /revenue-expenditure-tree` (getRevenueExpenditureTree), `GET /disbursement-scorecard` (getDisbursementScorecard), `POST /ai-advisor` (getAiAdvisorInsights) | `BudgetAllocationList` | Dashboard & Báo cáo Điều hành Kinh phí — API thống kê tổng quan Dự toán Thu - Chi, Tiến độ Giải ngân, Ma trận Đơn vị, Bảng điểm Kỷ luật Giải ngân và Cố vấn AI |
| 4 | `BudgetDisbursementController` | `/disbursements` | 17 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /{id}` (findById), `GET /{id}/clone` (cloneById), `POST /{id}/rollback` (rollback), ... *(+9 API)* | `BudgetDisbursementCreate`, `BudgetDisbursementEdit`, `BudgetDisbursementDelete`, `BudgetDisbursementBatchDelete`, `BudgetDisbursementList`, `BudgetDisbursementDetail`, `BudgetDisbursementClone`, `BudgetDisbursementRollback`, ... *(+3)* | Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc & Quyết toán — API quản lý Hồ sơ Cam kết chi, Tạm ứng, Giải ngân Kho bạc Nhà nước, Hoàn ứng & Quyết toán chứng từ |
| 5 | `BudgetEstimateItemController` | `/estimate-items` | 14 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /{id}` (findById), `GET /{id}/clone` (cloneById), `POST /{id}/rollback` (rollback), ... *(+6 API)* | `BudgetEstimateItemCreate`, `BudgetEstimateItemEdit`, `BudgetEstimateItemDelete`, `BudgetEstimateItemBatchDelete`, `BudgetEstimateItemList`, `BudgetEstimateItemDetail`, `BudgetEstimateItemClone`, `BudgetEstimateItemRollback`, ... *(+3)* | Cây Danh mục & Cấu hình Dự toán Thu - Chi Đa cấp — API quản lý Cây Danh mục & Cấu hình Dự toán Thu - Chi đa cấp (kế thừa & nâng cấp từ QlkpDmDuToanThuChi & QlkpCauHinhDuToan) |
| 6 | `BudgetReportController` | `/reports` | 15 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /{id}` (findById), `GET /{id}/clone` (cloneById), `POST /{id}/rollback` (rollback), ... *(+7 API)* | `BudgetReportCreate`, `BudgetReportEdit`, `BudgetReportDelete`, `BudgetReportBatchDelete`, `BudgetReportList`, `BudgetReportDetail`, `BudgetReportClone`, `BudgetReportRollback`, ... *(+3)* | Báo cáo Thực hiện Thu - Chi & Tổng hợp Quyết toán Định kỳ — API quản lý Báo cáo Thực hiện Thu - Chi, Giải ngân & Tổng hợp Quyết toán định kỳ Tháng/Quý/6 tháng/Năm (kế thừa & nâng cấp từ QlkpBaoCaoThuChi & QlkpTongHopThuChi) |
| 7 | `BudgetTransactionController` | `/transactions` | 15 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /{id}` (findById), `GET /{id}/clone` (cloneById), `POST /{id}/rollback` (rollback), ... *(+7 API)* | `BudgetTransactionCreate`, `BudgetTransactionEdit`, `BudgetTransactionDelete`, `BudgetTransactionBatchDelete`, `BudgetTransactionList`, `BudgetTransactionDetail`, `BudgetTransactionClone`, `BudgetTransactionRollback`, ... *(+3)* | Sổ cái Biến động Nguồn Kinh phí Bút toán kép — API quản lý Sổ cái Biến động Nguồn Kinh phí: Giao đầu năm, Bổ sung nguồn, Chuyển nguồn liên năm (N -> N+1), Điều chuyển nội bộ và Thu hồi dự toán |
| 8 | `BudgetUnitConfigController` | `/unit-configs` | 14 | `POST /` (create), `PUT /{id}` (update), `DELETE /{id}` (delete), `DELETE /` (bulkDelete), `GET /` (findAll), `GET /{id}` (findById), `GET /{id}/clone` (cloneById), `POST /{id}/rollback` (rollback), ... *(+6 API)* | `BudgetUnitConfigCreate`, `BudgetUnitConfigEdit`, `BudgetUnitConfigDelete`, `BudgetUnitConfigBatchDelete`, `BudgetUnitConfigList`, `BudgetUnitConfigDetail`, `BudgetUnitConfigClone`, `BudgetUnitConfigRollback`, ... *(+3)* | Cấu hình Đơn vị Sử dụng Ngân sách & Tự chủ Tài chính — API quản lý Cấu hình Đơn vị Sử dụng Ngân sách và Ma trận Phân quyền Tự chủ Tài chính (kế thừa & nâng cấp từ QlkpCauHinhDonVi) |

### 4. Danh mục Tầng Nghiệp vụ (Business), DTO, SearchCriteria, Mapper, Validator & Enum

| Phân lớp | Số lượng | Danh sách Lớp thành phần |
| --- | --- | --- |
| **Business (`*Business`)** | 8 | `BudgetAllocationBusiness`, `BudgetCategoryBusiness`, `BudgetDashboardBusiness`, `BudgetDisbursementBusiness`, `BudgetEstimateItemBusiness`, `BudgetReportBusiness`, `BudgetTransactionBusiness`, `BudgetUnitConfigBusiness` |
| **DAO Services (`*Service` / `*ServiceImpl`)** | 21 | `BudgetAllocationExcelService`, `BudgetAllocationService`, `BudgetAllocationServiceImpl`, `BudgetCategoryExcelService`, `BudgetCategoryService`, `BudgetCategoryServiceImpl`, `BudgetDisbursementExcelService`, `BudgetDisbursementService`, `BudgetDisbursementServiceImpl`, `BudgetEstimateItemExcelService`, `BudgetEstimateItemService`, `BudgetEstimateItemServiceImpl`, `BudgetReportExcelService`, `BudgetReportService`, `BudgetReportServiceImpl`, `BudgetTransactionExcelService`, `BudgetTransactionService`, `BudgetTransactionServiceImpl`, `BudgetUnitConfigExcelService`, `BudgetUnitConfigService`, `BudgetUnitConfigServiceImpl` |
| **Repositories (`*Repo`)** | 7 | `BudgetAllocationRepo`, `BudgetCategoryRepo`, `BudgetDisbursementRepo`, `BudgetEstimateItemRepo`, `BudgetReportRepo`, `BudgetTransactionRepo`, `BudgetUnitConfigRepo` |
| **Specifications (`*Specifications`)** | 7 | `BudgetAllocationSpecifications`, `BudgetCategorySpecifications`, `BudgetDisbursementSpecifications`, `BudgetEstimateItemSpecifications`, `BudgetReportSpecifications`, `BudgetTransactionSpecifications`, `BudgetUnitConfigSpecifications` |
| **DTOs (`*Data` / `*Request` / `*Response`)** | 14 | `BudgetAllocationData`, `BudgetAllocationImportData`, `BudgetCategoryData`, `BudgetCategoryImportData`, `BudgetDisbursementData`, `BudgetDisbursementImportData`, `BudgetEstimateItemData`, `BudgetEstimateItemImportData`, `BudgetReportData`, `BudgetReportImportData`, `BudgetTransactionData`, `BudgetTransactionImportData`, `BudgetUnitConfigData`, `BudgetUnitConfigImportData` |
| **Search Criteria (`*Criteria`)** | 7 | `BudgetAllocationSearchCriteria`, `BudgetCategorySearchCriteria`, `BudgetDisbursementSearchCriteria`, `BudgetEstimateItemSearchCriteria`, `BudgetReportSearchCriteria`, `BudgetTransactionSearchCriteria`, `BudgetUnitConfigSearchCriteria` |
| **Mappers (`data.mapper.*`)** | 7 | `BudgetAllocationMapper`, `BudgetCategoryMapper`, `BudgetDisbursementMapper`, `BudgetEstimateItemMapper`, `BudgetReportMapper`, `BudgetTransactionMapper`, `BudgetUnitConfigMapper` |
| **Validators (`data.validator.*`)** | 7 | `BudgetAllocationValidator`, `BudgetCategoryValidator`, `BudgetDisbursementValidator`, `BudgetEstimateItemValidator`, `BudgetReportValidator`, `BudgetTransactionValidator`, `BudgetUnitConfigValidator` |
| **Enums (`data.enums.*`)** | 0 | — |
| **Unit / Integration Tests** | 2 | `BudgetBusinessFlowTest`, `BudgetWorkflowEngineTest` |

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

Cập nhật lần cuối: 2026-10-28
