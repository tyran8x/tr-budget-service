# Danh mục API & Đối chiếu Hợp đồng 1:1 (BE ↔ FE ↔ Mobile)

> **Phạm vi kiểm toán**: Toàn bộ **164 REST Endpoints** trên **9 Controllers** của `tr-budget-service` (Gateway prefix `/v1/budget`, cấu hình định tuyến tại `config/_base/tr-gateway-service.yml:660-664`), đối chiếu 1:1 với **Frontend** (`packages/@features/budget/src/api/endpoints.ts` + 4 facades) và **Mobile Flutter** (`apps/dds_app/lib/api/budget/budget_api.dart`).
> **Điểm trưởng thành Hợp đồng API**: **5.6 / 10** *(100% có `@Operation` OpenAPI và độ phủ wrapper FE đạt 156/157 endpoint public, nhưng 72.7% endpoint nhận `@RequestBody` thiếu `@Valid`, 40 endpoint nhận `Map<String, Object>` phi kiểu hóa, tồn tại nhiều điểm lệch payload/DTO/enum giữa BE ↔ FE ↔ Mobile và 38 endpoint public chưa được nối UI trên bất kỳ client nào)*.

---

## 1. Thống kê Tổng hợp 9 Controllers (164 Endpoints)

| STT | Controller (`vn.tr.budget.controller`) | Số API | `@Operation` | Có `@RequestBody` | Thiếu `@Valid` | Dùng `Map<String, Object>` | Có Phân trang | FE Wrapper | Mobile Wrapper |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `BudgetAllocationController.java` | **26** | 26 / 26 | 12 | **9** | 7 | 3 | 26 / 26 | 5 / 26 |
| 2 | `BudgetCategoryController.java` | **17** | 17 / 17 | 7 | **4** | 2 | 3 | 17 / 17 | 2 / 17 |
| 3 | `BudgetDashboardController.java` | **10** | 10 / 10 | 3 | **3** | 3 | 0 | 10 / 10 | 8 / 10 |
| 4 | `BudgetDisbursementController.java` | **21** | 21 / 21 | 10 | **7** | 5 | 3 | 20 / 21 | 8 / 21 |
| 5 | `BudgetEstimateItemController.java` | **19** | 19 / 19 | 8 | **5** | 3 | 3 | 19 / 19 | 0 / 19 |
| 6 | `BudgetInternalController.java` *(S2S)* | **7** | 7 / 7 | 4 | **4** | 0 | 0 | 0 / 7 *(S2S)* | 0 / 7 *(S2S)* |
| 7 | `BudgetReportController.java` | **21** | 21 / 21 | 10 | **7** | 6 | 3 | 21 / 21 | 8 / 21 |
| 8 | `BudgetTransactionController.java` | **28** | 28 / 28 | 18 | **15** | 13 | 3 | 28 / 28 | 13 / 28 |
| 9 | `BudgetUnitConfigController.java` | **15** | 15 / 15 | 5 | **2** | 1 | 3 | 15 / 15 | 1 / 15 |
| **Tổng** | **9 Controllers** | **164** | **164 (100%)** | **77** | **56 (72.7%)** | **40 (51.9%)** | **21** | **156 / 157 public** | **45 / 157 public** |

---

## 2. Các Khiếm khuyết Hợp đồng & Thiết kế API Trọng yếu

| Mã | Mức độ | Phân loại | Chi tiết & Dẫn chứng |
| --- | --- | --- | --- |
| `API-H01` | HIGH | Thiếu Validation & Schema OpenAPI | **56 / 77 endpoint nhận `@RequestBody` (72.7%) không có `@Valid`** và **40 endpoint nhận `Map<String, Object>` phi kiểu hóa** (gồm toàn bộ 12 nghiệp vụ Sổ cái, 5 luồng workflow Phân bổ, 4 luồng Giải ngân, 6 luồng Báo cáo, 3 luồng Dashboard và 7 endpoint `import`). Swagger/OpenAPI không sinh được schema request/response thực tế; các vị trí parse tay (`BudgetDashboardController.java:114`, `BudgetEstimateItemController.java:239,243`) dễ ném `NumberFormatException` (HTTP 500) |
| `API-H02` | HIGH | Sai Endpoint trên FE | `facade-disbursements-reports.ts:113-118` gọi `POST /disbursements` cho `reserveCommitment()` thay vì endpoint chuyên biệt `POST /disbursements/reserve-commitment` (`BudgetDisbursementController.java:230`) — endpoint public duy nhất (1/157) mà FE không bọc đúng |
| `API-H03` | HIGH | Sai Mapping Field FE ↔ BE | `updateDisbursement()` (`facade-disbursements-reports.ts:105-112`) gửi raw UI fields (`title`, `paymentVoucherNo`, `disbursedAmount`, `unitNodeCode`) lên `PUT /disbursements/{id}`; `createCategory`/`updateCategory` (`L219-276`) gửi nhãn tiếng Việt vào `autonomyType` và đổi nhầm `clauseCode`/`itemCode`; `UnitConfig` (`L285-312`) gửi `allowSelfApprove` và bỏ sót 7 field thực; `createAllocation` Vue làm rơi 8 field người dùng nhập |
| `API-H04` | HIGH | Sai Mapping Field Mobile ↔ BE | `BudgetUnitConfigModel.fromJson` (`budget_types.dart:935-941`) đọc sai 4 khóa JSON (`autonomyType`, `autonomyRatioPercent`, `selfApprovalLimitAmount`, `treasuryAgencyName`); `budget_store.dart:562,765` đọc sai cấu trúc response `res['code']` thay vì `res['transaction']['code']` và tự tạo ID giả |
| `API-H05` | HIGH | Lệch Enum giữa Code Runtime & Enum Java | `BudgetAllocationBusiness.java:122,392` và FE `facade-allocations.ts:167` dùng chuỗi `UNIT_SELF` / `DIRECTORATE`, mâu thuẫn với enum `BudgetApprovalScope.java` (`UNIT_SELF_APPROVAL`, `SUPERIOR_APPROVAL`, `TREASURY_RECONCILED`); `BudgetCategoryModals.vue:58` và `BudgetDataInitializer.java:122` dùng `DEDICATED_FUND` không tồn tại trong `BudgetAutonomyType.java`; Mobile `budget_disbursements_tab.dart:35-42` dùng `'PAYMENT'` thay vì `'DISBURSEMENT'` |
| `API-M01` | MEDIUM | Không phân trang & Bỏ `totalElements` trên Client | 21 endpoint BE có hỗ trợ phân trang (`Pageable`/`SearchCriteria`), nhưng FE `normalizePageContent` (`src/api/mappers.ts:1-11`) bỏ qua `totalElements`/`totalPages` và gọi cố định `size=50..200`; Mobile gọi cố định `size=50` và không phân trang |
| `API-M02` | MEDIUM | 38 Endpoint Public chưa có UI (Orphan / Wrapper-only) | Toàn bộ 7×`bulkDelete`, 7×`clone`, 7×`sort-order`, 7×`options`, 7×`findById` (35 endpoint chuẩn) cùng `GET /allocations/by-plan/{planCode}`, `PUT /estimate-items/{id}/move`, `GET /estimate-items/health-check` và 6 endpoint CRUD/Excel/Rollback của `BudgetReport` chưa được gắn vào giao diện Web hay Mobile; thêm 4 hàm Mobile (`getTargetScopeContext`, `validateTargetScope`, `scanDisbursementSlaAlerts`, `confirmReportTreasuryReconciliation`) không có nút UI |
| `API-M03` | MEDIUM | Thiếu Optimistic Locking (`version`) trong Hợp đồng Client | Toàn bộ 7 DTO trên FE (`src/api/types.ts`) và Mobile (`budget_types.dart`) không truyền và không kiểm tra `version` từ Hibernate `@Version` |

---

## 3. Danh mục Chi tiết Đầy đủ 164 REST Endpoints (9 Controllers)

> **Quy ước cột**:
> - **Gateway Path**: Đường dẫn đầy đủ qua Gateway có tiền tố `/v1/budget` (bảng ghi phần đường dẫn tương đối sau `/v1/budget`).
> - **Flags**: `V` = có `@Valid`, `M` = nhận `Map<String, Object>`, `P` = hỗ trợ phân trang.
> - **FE / Mobile**: `UI` = đã bọc và gọi từ giao diện; `Wrap` = chỉ bọc trong client API, chưa gọi UI; `Dead` = bọc trên Mobile nhưng chưa nối nút UI; `—` = chưa hỗ trợ; `Drift` = lệch đường dẫn/field/quyền.

### 3.1. `BudgetAllocationController` — 26 Endpoints (`/allocations`)

| # | HTTP | Path (`/v1/budget...`) | Method (`Line`) | `@SaCheckPermission` | Flags | FE | Mobile | Ghi chú hợp đồng |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `POST` | `/allocations` | `create` (`L60`) | `BudgetAllocationCreate` | `V` | `UI` (Drift) | `—` | Vue modal làm rơi 8 field (`FE-H06`) |
| 2 | `PUT` | `/allocations/{id}` | `update` (`L68`) | `BudgetAllocationEdit` | `V` | `UI` | `—` | Thiếu `version` (`FE-H01`) |
| 3 | `DELETE` | `/allocations/{id}` | `delete` (`L77`) | `BudgetAllocationDelete` | — | `UI` | `—` | Không có hộp thoại xác nhận (`FE-H10`) |
| 4 | `DELETE` | `/allocations` | `bulkDelete` (`L86`) | `BudgetAllocationBatchDelete` | `V` | `Wrap` | `—` | Chưa có UI chọn xóa hàng loạt |
| 5 | `GET` | `/allocations` | `findAll` (`L94`) | `BudgetAllocationList` | `P` | `UI` | `UI` | FE/Mobile không phân trang (`size=50..200`) |
| 6 | `GET` | `/allocations/options` | `getOptions` (`L120`) | 16 mã `OR` | `P` | `Wrap` | `—` | Đạt `LP-020` (`RefData<K>`, 0 `@SaSharedLookup`), nhưng FE chưa gọi |
| 7 | `GET` | `/allocations/{id}` | `findById` (`L127`) | `BudgetAllocationDetail` | — | `Wrap` | `—` | UI dùng bản ghi trong danh sách |
| 8 | `POST` | `/allocations/{id}/clone` | `cloneById` (`L134`) | `BudgetAllocationClone` | — | `Wrap` | `—` | Chưa có nút UI |
| 9 | `POST` | `/allocations/{id}/rollback` | `rollback` (`L142`) | `BudgetAllocationRollback` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 10 | `PUT` | `/allocations/sort-order` | `bulkUpdateSortOrder` (`L150`) | `BudgetAllocationSort` | — | `Wrap` | `—` | Thiếu `@Valid`, chưa có UI |
| 11 | `GET` | `/allocations/export` | `export` (`L159`) | `BudgetAllocationExport` | `P` | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 12 | `GET` | `/allocations/import-template` | `downloadTemplate` (`L170`) | `BudgetAllocationImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 13 | `POST` | `/allocations/import/validate` | `validateImport` (`L181`) | `BudgetAllocationImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 14 | `POST` | `/allocations/import` | `importData` (`L190`) | `BudgetAllocationImport` | `M` | `UI` | `—` | Thiếu `@Valid` |
| 15 | `POST` | `/allocations/{id}/submit` | `submitAllocation` (`L205`) | `BudgetAllocationSubmit` | `M` | `UI` | `—` | Cho phép spoof `actorId` (`PERM-C01`) |
| 16 | `POST` | `/allocations/{id}/self-approve` | `selfApproveAllocation` (`L214`) | `BudgetAllocationSelfApprove` | `M` | `UI` | `UI` | Lệch chuỗi `UNIT_SELF` vs enum (`API-H05`) |
| 17 | `POST` | `/allocations/{id}/approve` | `approveAllocation` (`L223`) | `BudgetAllocationApprove` | `M` | `UI` | `UI` | Thiếu SoD & status guard (`PERM-C02`) |
| 18 | `POST` | `/allocations/{id}/reject` | `rejectAllocation` (`L232`) | `BudgetAllocationReject` | `M` | `UI` | `UI` | Client gửi lý do cứng không prompt |
| 19 | `POST` | `/allocations/{id}/withdraw` | `withdrawAllocation` (`L241`) | `BudgetAllocationSubmit` | `M` | `UI` | `—` | Thiếu `@Valid` |
| 20 | `POST` | `/allocations/{id}/recalculate` | `recalculate` (`L250`) | `BudgetAllocationApprove` | — | `UI` | `—` | Tái tính số dư khả dụng |
| 21 | `GET` | `/allocations/{id}/workflow-timeline` | `getWorkflowTimeline` (`L257`) | `BudgetAllocationDetail` | — | `UI` (Drift) | `—` | React hiển thị đúng; Vue bỏ qua, vẽ 4 bước tĩnh |
| 22 | `GET` | `/allocations/by-plan/{planCode}` | `getBudgetSnapshotByPlanCode` (`L264`) | `BudgetAllocationList` | — | `Wrap` | `—` | Chưa gọi từ UI |
| 23 | `POST` | `/allocations/{id}/sync-to-plan` | `syncToLinkedPlan` (`L272`) | `BudgetAllocationApprove` | — | `UI` (Drift) | `UI` (Drift) | FE & Mobile guard sai bằng `Edit` → 403 |
| 24 | `POST` | `/allocations/batch` | `batchCreate` (`L280`) | `BudgetAllocationCreate` | — | `UI` | `—` | Thiếu `@Valid` trên `List<BudgetAllocationData>` |
| 25 | `GET` | `/allocations/tree-matrix` | `getAllocationTreeMatrix` (`L288`) | `BudgetAllocationList` | — | `UI` | `—` | Ma trận phân bổ theo cây chỉ tiêu |
| 26 | `POST` | `/allocations/tree-matrix` | `saveAllocationTreeMatrix` (`L299`) | `Create OR Edit` | `M` | `UI` (Drift) | `—` | Vue chỉ cho `Create` bấm Lưu (`FE-H07`) |

### 3.2. `BudgetCategoryController` — 17 Endpoints (`/categories`)

| # | HTTP | Path (`/v1/budget...`) | Method (`Line`) | `@SaCheckPermission` | Flags | FE | Mobile | Ghi chú hợp đồng |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 27 | `POST` | `/categories` | `create` (`L56`) | `BudgetCategoryCreate` | `V` | `UI` (Drift) | `—` | Vue gửi nhãn tiếng Việt vào `autonomyType` (`FE-H02`) |
| 28 | `PUT` | `/categories/{id}` | `update` (`L64`) | `BudgetCategoryEdit` | `V` | `UI` (Drift) | `—` | Cả Vue & React gửi nhãn tiếng Việt vào `autonomyType` |
| 29 | `DELETE` | `/categories/{id}` | `delete` (`L73`) | `BudgetCategoryDelete` | — | `UI` | `—` | Không có hộp thoại xác nhận |
| 30 | `DELETE` | `/categories` | `bulkDelete` (`L82`) | `BudgetCategoryBatchDelete` | `V` | `Wrap` | `—` | Chưa có UI |
| 31 | `GET` | `/categories` | `findAll` (`L90`) | `BudgetCategoryList` | `P` | `UI` (Drift) | `—` | FE map lệch `clauseCode`/`itemCode` (`FE-H02`) |
| 32 | `GET` | `/categories/options` | `getOptions` (`L125`) | 25 mã `OR` | `P` | `Wrap` | `—` | Đạt `LP-020` (`RefData<K>`), nhưng FE chưa dùng cho dropdown |
| 33 | `GET` | `/categories/{id}` | `findById` (`L132`) | `BudgetCategoryDetail` | — | `Wrap` | `—` | Chưa có UI |
| 34 | `POST` | `/categories/{id}/clone` | `cloneById` (`L139`) | `BudgetCategoryClone` | — | `Wrap` | `—` | Chưa có UI |
| 35 | `POST` | `/categories/{id}/rollback` | `rollback` (`L147`) | `BudgetCategoryRollback` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 36 | `PUT` | `/categories/sort-order` | `bulkUpdateSortOrder` (`L155`) | `BudgetCategorySort` | — | `Wrap` | `—` | Thiếu `@Valid`, chưa có UI |
| 37 | `GET` | `/categories/export` | `export` (`L164`) | `BudgetCategoryExport` | `P` | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 38 | `GET` | `/categories/import-template` | `downloadTemplate` (`L175`) | `BudgetCategoryImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 39 | `POST` | `/categories/import/validate` | `validateImport` (`L186`) | `BudgetCategoryImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 40 | `POST` | `/categories/import` | `importData` (`L195`) | `BudgetCategoryImport` | `M` | `UI` | `—` | Thiếu `@Valid` |
| 41 | `GET` | `/categories/year-domain-configs` | `getYearDomainConfigs` (`L216`) | 4 mã `OR` | — | `UI` | `UI` | Mobile nạp trong `Future.wait(14)` |
| 42 | `POST` | `/categories/year-domain-configs` | `saveYearDomainConfig` (`L227`) | `BudgetCategoryCreate` | — | `UI` | `—` | Thiếu `@Valid` trên DTO |
| 43 | `POST` | `/categories/year-domain-configs/clone-year` | `cloneYearDomainConfig` (`L237`) | `BudgetCategoryCreate` | `M` | `UI` | `UI` | Sao chép khung lĩnh vực năm N-1 → N |

### 3.3. `BudgetDashboardController` — 10 Endpoints (`/dashboard`)

| # | HTTP | Path (`/v1/budget...`) | Method (`Line`) | `@SaCheckPermission` | Flags | FE | Mobile | Ghi chú hợp đồng |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 44 | `GET` | `/dashboard/overview` *(alias `/summary`)* | `getOverview` (`L37`) | `BudgetDashboardQuery` | — | `UI` | `UI` | FE gọi alias `/summary` (`endpoints.ts:259`), Mobile gọi `/overview` |
| 45 | `GET` | `/dashboard/unit-matrix` | `getUnitMatrix` (`L51`) | `BudgetDashboardQuery` | — | `UI` | `—` | Ma trận tổng hợp theo đơn vị |
| 46 | `GET` | `/dashboard/target-scope` | `getTargetScopeContext` (`L62`) | `BudgetDashboardQuery` | — | `Wrap` | `Dead` | Cả FE và Mobile đều chưa gọi vào màn hình chính |
| 47 | `POST` | `/dashboard/validate-target-scope` | `validateTargetScopeSelection` (`L80`) | `BudgetDashboardQuery` | `M` | `Wrap` | `Dead` | Thiếu `@Valid`, chưa gắn vào flow lưu |
| 48 | `GET` | `/dashboard/revenue-expenditure-tree` | `getRevenueExpenditureTree` (`L90`) | `BudgetDashboardQuery` | — | `UI` | `UI` | Mobile nạp trong `Future.wait(14)` |
| 49 | `GET` | `/dashboard/disbursement-scorecard` | `getDisbursementScorecard` (`L98`) | `BudgetDashboardQuery` | — | `UI` | `UI` | Mobile fallback dữ liệu giả khi thiếu field (`MOB-H04`) |
| 50 | `POST` | `/dashboard/ai-advisor` | `getAiAdvisorInsights` (`L106`) | `BudgetDashboardQuery` | `M` | `UI` | `UI` | `L114` parse `Integer.valueOf` dễ ném 500 |
| 51 | `GET` | `/dashboard/burn-rate-forecast` | `getBurnRateForecast` (`L120`) | `BudgetDashboardQuery` | — | `UI` | `UI` | Dự báo tốc độ giải ngân cuối năm |
| 52 | `GET` | `/dashboard/mtef-summary` | `getMtefSummary` (`L130`) | `BudgetDashboardQuery` | — | `UI` | `—` | Kế hoạch tài chính trung hạn 3 năm |
| 53 | `POST` | `/dashboard/what-if-simulation` | `simulateWhatIfScenario` (`L140`) | `BudgetDashboardSimulate` | `M` | `UI` | `UI` | Thiếu `@Valid` |

### 3.4. `BudgetDisbursementController` — 21 Endpoints (`/disbursements`)

| # | HTTP | Path (`/v1/budget...`) | Method (`Line`) | `@SaCheckPermission` | Flags | FE | Mobile | Ghi chú hợp đồng |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 54 | `POST` | `/disbursements` | `create` (`L61`) | `BudgetDisbursementCreate` | `V` | `UI` | `—` | FE gọi nhầm endpoint này cho `reserveCommitment` |
| 55 | `PUT` | `/disbursements/{id}` | `update` (`L69`) | `BudgetDisbursementEdit` | `V` | `UI` (Drift) | `—` | React gửi raw UI fields không map (`FE-H04`) |
| 56 | `DELETE` | `/disbursements/{id}` | `delete` (`L78`) | `BudgetDisbursementDelete` | — | `UI` | `—` | Không có xác nhận xóa |
| 57 | `DELETE` | `/disbursements` | `bulkDelete` (`L87`) | `BudgetDisbursementBatchDelete` | `V` | `Wrap` | `—` | Chưa có UI |
| 58 | `GET` | `/disbursements` | `findAll` (`L95`) | `BudgetDisbursementList` | `P` | `UI` | `UI` | Không phân trang server trên client |
| 59 | `GET` | `/disbursements/options` | `getOptions` (`L116`) | 11 mã `OR` | `P` | `Wrap` | `—` | Đạt `LP-020` (đã ẩn TK nhạy cảm), nhưng FE chưa gọi |
| 60 | `GET` | `/disbursements/{id}` | `findById` (`L123`) | `BudgetDisbursementDetail` | — | `Wrap` | `—` | Chưa có UI |
| 61 | `POST` | `/disbursements/{id}/clone` | `cloneById` (`L130`) | `BudgetDisbursementClone` | — | `Wrap` | `—` | Chưa có UI |
| 62 | `POST` | `/disbursements/{id}/rollback` | `rollback` (`L138`) | `BudgetDisbursementRollback` | — | `Wrap` | `—` | `BudgetDisbursementView` chưa nối audit rollback |
| 63 | `PUT` | `/disbursements/sort-order` | `bulkUpdateSortOrder` (`L146`) | `BudgetDisbursementSort` | — | `Wrap` | `—` | Thiếu `@Valid`, chưa có UI |
| 64 | `GET` | `/disbursements/export` | `export` (`L155`) | `BudgetDisbursementExport` | `P` | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 65 | `GET` | `/disbursements/import-template` | `downloadTemplate` (`L166`) | `BudgetDisbursementImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 66 | `POST` | `/disbursements/import/validate` | `validateImport` (`L178`) | `BudgetDisbursementImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 67 | `POST` | `/disbursements/import` | `importData` (`L187`) | `BudgetDisbursementImport` | `M` | `UI` | `—` | Thiếu `@Valid` |
| 68 | `POST` | `/disbursements/{id}/approve` | `approveDisbursement` (`L203`) | `BudgetDisbursementApprove` | `M` | `UI` | `UI` | Thiếu SoD & status guard; Mobile tự bịa `KBNN-DN-...` |
| 69 | `POST` | `/disbursements/{id}/settle` | `settleDisbursement` (`L212`) | `BudgetDisbursementSettle` | `M` | `UI` | `UI` | Thiếu `@Valid` |
| 70 | `POST` | `/disbursements/{id}/reject` | `rejectDisbursement` (`L221`) | `BudgetDisbursementReject` | `M` | `UI` | `UI` | FE/Mobile gửi lý do cứng không prompt |
| 71 | `POST` | `/disbursements/reserve-commitment` | `reserveCommitment` (`L230`) | `BudgetDisbursementCreate` | `M` | **`—` (Drift)** | `UI` | **FE thiếu wrapper — gọi nhầm `POST /disbursements` (`API-H02`)** |
| 72 | `POST` | `/disbursements/scan-sla-alerts` | `scanSlaAlerts` (`L238`) | `BudgetDisbursementApprove` | — | `UI` | `Dead` | Mobile bọc ở `L248` nhưng chưa gắn UI |
| 73 | `POST` | `/disbursements/batch` | `batchCreate` (`L246`) | `BudgetDisbursementCreate` | — | `UI` | `—` | Thiếu `@Valid` |
| 74 | `GET` | `/disbursements/{id}/kbnn-dvc-package` | `exportKbnnDvcPackage` (`L256`) | `Detail OR Approve` | — | `UI` (Drift) | `UI` (Drift) | FE & Mobile guard sai bằng `Export` → 403 |

### 3.5. `BudgetEstimateItemController` — 19 Endpoints (`/estimate-items`)

| # | HTTP | Path (`/v1/budget...`) | Method (`Line`) | `@SaCheckPermission` | Flags | FE | Mobile | Ghi chú hợp đồng |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 75 | `POST` | `/estimate-items` | `create` (`L61`) | `BudgetEstimateItemCreate` | `V` | `Wrap` | `—` | UI dùng `POST /estimate-items/tree` |
| 76 | `PUT` | `/estimate-items/{id}` | `update` (`L69`) | `BudgetEstimateItemEdit` | `V` | `Wrap` | `—` | UI dùng `POST /estimate-items/tree` |
| 77 | `DELETE` | `/estimate-items/{id}` | `delete` (`L78`) | `BudgetEstimateItemDelete` | — | `Wrap` | `—` | UI xóa cục bộ rồi lưu cả cây |
| 78 | `DELETE` | `/estimate-items` | `bulkDelete` (`L87`) | `BudgetEstimateItemBatchDelete` | `V` | `Wrap` | `—` | Chưa có UI |
| 79 | `GET` | `/estimate-items` | `findAll` (`L95`) | `BudgetEstimateItemList` | `P` | `Wrap` | `—` | UI dùng `GET /estimate-items/tree` |
| 80 | `GET` | `/estimate-items/options` | `getOptions` (`L124`) | 19 mã `OR` | `P` | `Wrap` | `—` | Đạt `LP-020` (`RefData<K>`), nhưng FE chưa gọi |
| 81 | `GET` | `/estimate-items/{id}` | `findById` (`L131`) | `BudgetEstimateItemDetail` | — | `Wrap` | `—` | Chưa có UI |
| 82 | `POST` | `/estimate-items/{id}/clone` | `cloneById` (`L138`) | `BudgetEstimateItemClone` | — | `Wrap` | `—` | Chưa có UI |
| 83 | `POST` | `/estimate-items/{id}/rollback` | `rollback` (`L146`) | `BudgetEstimateItemRollback` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 84 | `PUT` | `/estimate-items/sort-order` | `bulkUpdateSortOrder` (`L154`) | `BudgetEstimateItemSort` | — | `Wrap` | `—` | Thiếu `@Valid`, chưa có UI |
| 85 | `GET` | `/estimate-items/export` | `export` (`L163`) | `BudgetEstimateItemExport` | `P` | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 86 | `GET` | `/estimate-items/import-template` | `downloadTemplate` (`L174`) | `BudgetEstimateItemImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 87 | `POST` | `/estimate-items/import/validate` | `validateImport` (`L185`) | `BudgetEstimateItemImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 88 | `POST` | `/estimate-items/import` | `importData` (`L194`) | `BudgetEstimateItemImport` | `M` | `UI` | `—` | Thiếu `@Valid` |
| 89 | `GET` | `/estimate-items/tree` | `getEstimateTree` (`L211`) | 3 mã `OR` | — | `UI` | `—` | Tải toàn bộ cây định mức năm |
| 90 | `POST` | `/estimate-items/tree` | `saveEstimateTree` (`L222`) | `Create OR Edit` | — | `UI` | `—` | Thiếu `@Valid`, ghi đè toàn bộ cây |
| 91 | `PUT` | `/estimate-items/{id}/move` | `moveEstimateNode` (`L232`) | `Edit OR Sort` | `M` | `Wrap` | `—` | `L239,243` parse tay dễ 500; FE kéo-thả client có lỗi mất nhánh |
| 92 | `POST` | `/estimate-items/clone-year` | `cloneYearTree` (`L249`) | `BudgetEstimateItemCreate` | `M` | `UI` | `—` | Thiếu `@Valid` |
| 93 | `GET` | `/estimate-items/health-check` | `evaluateTreeHealth` (`L257`) | `BudgetEstimateItemList` | — | `Wrap` | `—` | FE tự tính health cục bộ, không gọi BE |

### 3.6. `BudgetInternalController` — 7 Endpoints S2S (`/internal/budget`)

| # | HTTP | Path (`/v1/budget...`) | Method (`Line`) | Bảo mật | Flags | FE | Mobile | Ghi chú hợp đồng |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 94 | `POST` | `/internal/budget/allocations/sync-from-plan` | `syncAllocationFromPlan` (`L61`) | `@SaIgnore` *(S2S)* | — | `—` | `—` | Thiếu `@Valid` & thiếu gọi `SaSameUtil` tường minh (`PERM-H03`) |
| 95 | `GET` | `/internal/budget/allocations/by-plan/{planCode}` | `getBudgetSnapshotByPlanCode` (`L68`) | `@SaIgnore` *(S2S)* | — | `—` | `—` | Thiếu gọi `SaSameUtil` tường minh |
| 96 | `POST` | `/internal/budget/transactions/record` | `recordPlanLedgerTransaction` (`L75`) | `@SaIgnore` *(S2S)* | — | `—` | `—` | Thiếu `@Valid` & thiếu gọi `SaSameUtil` tường minh |
| 97 | `POST` | `/internal/budget/disbursements/reserve-commitment` | `reserveCommitment` (`L84`) | `@SaIgnore` *(S2S)* | — | `—` | `—` | Thiếu `@Valid` & thiếu gọi `SaSameUtil` tường minh |
| 98 | `POST` | `/internal/budget/disbursements/{id}/payment-webhook-settle` | `settleDisbursementFromPaymentWebhook` (`L93`) | `@SaIgnore` *(S2S)* | — | `—` | `—` | Thiếu `@Valid` & thiếu gọi `SaSameUtil` tường minh |
| 99 | `GET` | `/internal/budget/dashboard/overview` | `getDashboardOverview` (`L106`) | `@SaIgnore` *(S2S)* | — | `—` | `—` | Thiếu gọi `SaSameUtil` tường minh |
| 100 | `GET` | `/internal/budget/analytics/metrics` | `getInternalAnalyticsMetrics` (`L119`) | `@SaIgnore` + `SaSameUtil` (`L125`) | — | `—` | `—` | Endpoint S2S duy nhất kiểm tra tường minh `SaSameUtil` |

### 3.7. `BudgetReportController` — 21 Endpoints (`/reports`)

| # | HTTP | Path (`/v1/budget...`) | Method (`Line`) | `@SaCheckPermission` | Flags | FE | Mobile | Ghi chú hợp đồng |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | `POST` | `/reports` | `create` (`L57`) | `BudgetReportCreate` | `V` | `Wrap` | `—` | UI tạo qua `generate-consolidated` |
| 102 | `PUT` | `/reports/{id}` | `update` (`L65`) | `BudgetReportEdit` | `V` | `Wrap` | `—` | Chưa có UI sửa báo cáo |
| 103 | `DELETE` | `/reports/{id}` | `delete` (`L74`) | `BudgetReportDelete` | — | `UI` | `—` | Không có hộp thoại xác nhận |
| 104 | `DELETE` | `/reports` | `bulkDelete` (`L83`) | `BudgetReportBatchDelete` | `V` | `Wrap` | `—` | Chưa có UI |
| 105 | `GET` | `/reports` | `findAll` (`L91`) | `BudgetReportList` | `P` | `UI` | `UI` | Không phân trang server trên client |
| 106 | `GET` | `/reports/options` | `getOptions` (`L107`) | 6 mã `OR` | `P` | `Wrap` | `—` | Đạt `LP-020` (`RefData<K>`), nhưng FE chưa gọi |
| 107 | `GET` | `/reports/{id}` | `findById` (`L114`) | `BudgetReportDetail` | — | `Wrap` | `—` | Chưa có UI |
| 108 | `POST` | `/reports/{id}/clone` | `cloneById` (`L121`) | `BudgetReportClone` | — | `Wrap` | `—` | Chưa có UI |
| 109 | `POST` | `/reports/{id}/rollback` | `rollback` (`L129`) | `BudgetReportRollback` | — | `Wrap` | `—` | `BudgetPeriodicReportView` không có `BudgetDataTools` |
| 110 | `PUT` | `/reports/sort-order` | `bulkUpdateSortOrder` (`L137`) | `BudgetReportSort` | — | `Wrap` | `—` | Thiếu `@Valid`, chưa có UI |
| 111 | `GET` | `/reports/export` | `export` (`L146`) | `BudgetReportExport` | `P` | `Wrap` | `—` | Chưa gắn nút Xuất Excel chuẩn cho báo cáo |
| 112 | `GET` | `/reports/import-template` | `downloadTemplate` (`L157`) | `BudgetReportImport` | — | `Wrap` | `—` | Chưa có UI |
| 113 | `POST` | `/reports/import/validate` | `validateImport` (`L169`) | `BudgetReportImport` | — | `Wrap` | `—` | Chưa có UI |
| 114 | `POST` | `/reports/import` | `importData` (`L178`) | `BudgetReportImport` | `M` | `Wrap` | `—` | Thiếu `@Valid`, chưa có UI |
| 115 | `POST` | `/reports/generate-consolidated` | `generateConsolidatedReport` (`L193`) | `BudgetReportConsolidate` | `M` | `UI` | `UI` | FE hardcode `periodType: 'SEMI_ANNUAL'` |
| 116 | `POST` | `/reports/{id}/submit` | `submitReport` (`L204`) | `BudgetReportSubmit` | `M` | `UI` | `UI` | Cho phép spoof `actorId` (`PERM-C01`) |
| 117 | `POST` | `/reports/{id}/treasury-reconciliation` | `confirmTreasuryReconciliation` (`L213`) | `BudgetReportSubmit` | `M` | `UI` | `Dead` | Trùng quyền Submit (`PERM-M01`); Mobile chưa nối nút UI |
| 118 | `POST` | `/reports/{id}/approve` | `approveReport` (`L222`) | `BudgetReportApprove` | `M` | `UI` | `UI` | Mobile không kiểm tra trạng thái trước khi hiện nút duyệt |
| 119 | `POST` | `/reports/{id}/reject` | `rejectReport` (`L231`) | `BudgetReportReject` | `M` | `UI` | `UI` | Thiếu status guard & FE gửi lý do cứng |
| 120 | `GET` | `/reports/kbnn-reconciliation` | `getKbnnReconciliationStatement` (`L240`) | `BudgetReportList` | — | `UI` | `UI` | Mobile tự sinh dữ liệu mẫu khi thiếu field |
| 121 | `GET` | `/reports/dynamic-domain-matrix` | `getDynamicDomainMatrix` (`L253`) | `BudgetReportList` | — | `UI` | `UI` | FE/Mobile hardcode 2 mốc ngày `10-23`/`10-30` |

### 3.8. `BudgetTransactionController` — 28 Endpoints (`/transactions`)

| # | HTTP | Path (`/v1/budget...`) | Method (`Line`) | `@SaCheckPermission` | Flags | FE | Mobile | Ghi chú hợp đồng |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 122 | `POST` | `/transactions` | `create` (`L59`) | `BudgetTransactionCreate` | `V` | `UI` | `—` | FE map sai `counterpartNodeName` (`FE-H06`) |
| 123 | `PUT` | `/transactions/{id}` | `update` (`L67`) | `BudgetTransactionEdit` | `V` | `Wrap` | `—` | Chưa có UI sửa bút toán |
| 124 | `DELETE` | `/transactions/{id}` | `delete` (`L76`) | `BudgetTransactionDelete` | — | `UI` | `—` | Không có hộp thoại xác nhận |
| 125 | `DELETE` | `/transactions` | `bulkDelete` (`L85`) | `BudgetTransactionBatchDelete` | `V` | `Wrap` | `—` | Chưa có UI |
| 126 | `GET` | `/transactions` | `findAll` (`L93`) | `BudgetTransactionList` | `P` | `UI` | `UI` | Không phân trang server trên client |
| 127 | `GET` | `/transactions/options` | `getOptions` (`L114`) | 11 mã `OR` | `P` | `Wrap` | `—` | Đạt `LP-020` (`RefData<K>`), nhưng FE chưa gọi |
| 128 | `GET` | `/transactions/{id}` | `findById` (`L121`) | `BudgetTransactionDetail` | — | `Wrap` | `—` | Chưa có UI |
| 129 | `POST` | `/transactions/{id}/clone` | `cloneById` (`L128`) | `BudgetTransactionClone` | — | `Wrap` | `—` | Chưa có UI |
| 130 | `POST` | `/transactions/{id}/rollback` | `rollback` (`L136`) | `BudgetTransactionRollback` | — | `Wrap` | `—` | `BudgetLedgerTransactionView` chưa truyền `auditEntityId` |
| 131 | `PUT` | `/transactions/sort-order` | `bulkUpdateSortOrder` (`L144`) | `BudgetTransactionSort` | — | `Wrap` | `—` | Thiếu `@Valid`, chưa có UI |
| 132 | `GET` | `/transactions/export` | `export` (`L153`) | `BudgetTransactionExport` | `P` | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 133 | `GET` | `/transactions/import-template` | `downloadTemplate` (`L164`) | `BudgetTransactionImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 134 | `POST` | `/transactions/import/validate` | `validateImport` (`L175`) | `BudgetTransactionImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 135 | `POST` | `/transactions/import` | `importData` (`L184`) | `BudgetTransactionImport` | `M` | `UI` | `—` | Thiếu `@Valid` |
| 136 | `POST` | `/transactions/{id}/approve` | `approveTransaction` (`L199`) | `BudgetTransactionApprove` | `M` | `UI` | `UI` | Mobile có nguy cơ gửi ID giả sau thao tác 1 chạm (`MOB-H01`) |
| 137 | `POST` | `/transactions/{id}/reject` | `rejectTransaction` (`L210`) | `BudgetTransactionReject` | `M` | `UI` | `UI` | FE/Mobile gửi lý do cứng |
| 138 | `POST` | `/transactions/execute-reallocation` | `executeSmartReallocation` (`L219`) | `BudgetTransactionSubmit` | `M` | `UI` | `UI` (Drift) | Vue & React gửi khác tên khóa; Mobile bịa ID sau chạy |
| 139 | `POST` | `/transactions/supplement-content` | `supplementBudgetContent` (`L230`) | `BudgetTransactionSubmit` | `M` | `UI` | `UI` (Drift) | `NV-01`: Mobile guard sai bằng `Create`; auto-approve SoD |
| 140 | `POST` | `/transactions/carry-over-year` | `executeYearCarryOver` (`L241`) | `BudgetTransactionSubmit` | `M` | `UI` (Drift) | `UI` (Drift) | `NV-02`: FE thiếu form fields (`FE-H08`), Mobile guard sai |
| 141 | `POST` | `/transactions/cross-domain-transfer` | `executeCrossDomainTransfer` (`L252`) | `BudgetTransactionSubmit` | `M` | `UI` | `UI` (Drift) | `NV-03`: Mobile thiếu `fromDomainCode`/`toDomainCode`, guard sai |
| 142 | `POST` | `/transactions/saving-reduction` | `executeSavingAndReduction` (`L263`) | `BudgetTransactionSubmit` | `M` | `UI` (Drift) | `UI` (Drift) | `NV-04`: FE thiếu form fields, Mobile guard sai |
| 143 | `POST` | `/transactions/cancel-expired` | `cancelExpiredAllocation` (`L274`) | `BudgetTransactionSubmit` | `M` | `UI` (Drift) | `UI` (Drift) | `NV-05`: FE thiếu form fields, Mobile guard sai |
| 144 | `GET` | `/transactions/operation-role-matrix` | `getOperationRoleMatrix` (`L285`) | `BudgetTransactionList` | — | `UI` | `UI` | Trả ma trận vai trò `NV-01..DX-04` |
| 145 | `POST` | `/transactions/earmark-hold` | `executeEarmarkHold` (`L294`) | `BudgetTransactionSubmit` | `M` | `UI` (Drift) | `UI` (Drift) | `DX-01`: FE guard sai `Approve`, Mobile guard sai `Create`, thiếu form |
| 146 | `POST` | `/transactions/tranche-release` | `executeTrancheRelease` (`L305`) | `BudgetTransactionApprove` | `M` | `UI` (Drift) | `UI` (Drift) | `DX-02`: FE thiếu form fields, Mobile guard sai `Create` |
| 147 | `POST` | `/transactions/batch-payroll-indexation` | `executeBatchPayrollIndexation` (`L316`) | `BudgetTransactionApprove` | `M` | `UI` (Drift) | `UI` (Drift) | `DX-03`: FE thiếu form fields, Mobile guard sai `Create` |
| 148 | `POST` | `/transactions/batch` | `batchCreate` (`L327`) | `BudgetTransactionCreate` | — | `UI` | `—` | Thiếu `@Valid` |
| 149 | `POST` | `/transactions/autonomy-fund-distribution` | `executeAutonomyFundDistribution` (`L335`) | `BudgetTransactionApprove` | `M` | `UI` (Drift) | `UI` (Drift) | `DX-04`: FE thiếu form fields, Mobile guard sai `Create` |

### 3.9. `BudgetUnitConfigController` — 15 Endpoints (`/unit-configs`)

| # | HTTP | Path (`/v1/budget...`) | Method (`Line`) | `@SaCheckPermission` | Flags | FE | Mobile | Ghi chú hợp đồng |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 150 | `POST` | `/unit-configs` | `create` (`L58`) | `BudgetUnitConfigCreate` | `V` | `UI` (Drift) | `—` | FE gửi `allowSelfApprove`, thiếu 7 field thực (`FE-H05`) |
| 151 | `PUT` | `/unit-configs/{id}` | `update` (`L66`) | `BudgetUnitConfigEdit` | `V` | `UI` (Drift) | `—` | Tương tự `POST` (`FE-H05`) |
| 152 | `DELETE` | `/unit-configs/{id}` | `delete` (`L75`) | `BudgetUnitConfigDelete` | — | `UI` | `—` | Không có hộp thoại xác nhận |
| 153 | `DELETE` | `/unit-configs` | `bulkDelete` (`L84`) | `BudgetUnitConfigBatchDelete` | `V` | `Wrap` | `—` | Chưa có UI |
| 154 | `GET` | `/unit-configs` | `findAll` (`L92`) | `BudgetUnitConfigList` | `P` | `UI` | `UI` (Drift) | Mobile đọc sai 4 khóa JSON (`MOB-H03`) |
| 155 | `GET` | `/unit-configs/options` | `getOptions` (`L123`) | 21 mã `OR` | `P` | `Wrap` | `—` | Đạt `LP-020` (đã ẩn TK ngân hàng/MST), nhưng FE chưa gọi |
| 156 | `GET` | `/unit-configs/{id}` | `findById` (`L130`) | `BudgetUnitConfigDetail` | — | `Wrap` | `—` | Chưa có UI |
| 157 | `POST` | `/unit-configs/{id}/clone` | `cloneById` (`L137`) | `BudgetUnitConfigClone` | — | `Wrap` | `—` | Chưa có UI |
| 158 | `POST` | `/unit-configs/{id}/rollback` | `rollback` (`L145`) | `BudgetUnitConfigRollback` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 159 | `PUT` | `/unit-configs/sort-order` | `bulkUpdateSortOrder` (`L153`) | `BudgetUnitConfigSort` | — | `Wrap` | `—` | Thiếu `@Valid`, chưa có UI |
| 160 | `GET` | `/unit-configs/export` | `export` (`L162`) | `BudgetUnitConfigExport` | `P` | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 161 | `GET` | `/unit-configs/import-template` | `downloadTemplate` (`L173`) | `BudgetUnitConfigImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 162 | `POST` | `/unit-configs/import/validate` | `validateImport` (`L184`) | `BudgetUnitConfigImport` | — | `UI` | `—` | Gọi qua `BudgetDataTools` |
| 163 | `POST` | `/unit-configs/import` | `importData` (`L193`) | `BudgetUnitConfigImport` | `M` | `UI` | `—` | Thiếu `@Valid` |
| 164 | `GET` | `/unit-configs/check-autonomy` | `checkAutonomy` (`L216`) | 5 mã `OR` | — | `UI` | `—` | Kiểm tra hạn mức tự phê duyệt trước khi trình/duyệt |
