# Đánh giá Mã nguồn Backend, Concurrency, Chính xác Số học & Kiểm thử (`tr-budget-service`)

> **Phạm vi kiểm toán**: Toàn bộ mã nguồn Java (`src/main/java/vn/tr/budget/**` và `src/test/java/vn/tr/budget/**`) gồm 9 Controllers (164 endpoints), 11 Business + 2 Helpers, 7 Services/Repos/Mappers, 4 Specifications, 8 Validators, 3 ExcelServices, 2 Kafka classes và kết quả thực thi `mvn test` thực tế.
>
> **Ngày kiểm toán**: 2026-11-26 | **Chế độ**: Chỉ đọc & Đánh giá (không sửa mã nguồn).

## 1. Tổng quan Điểm số & Kiểm kê Thành phần

| Tiêu chí | Điểm (`/10`) | Nhận định nhanh |
| --- | --- | --- |
| Cấu trúc Controller / Service / Repo / Mapper | 5.8 / 10 | Đầy đủ CRUD, Audit, Excel, Lookup, Tree, Batch; nhưng thiếu `@Valid` diện rộng, nhận raw `Map<String, Object>` trên 20+ endpoint, 2 Business phình to thành God classes. |
| Concurrency, Locking & Toàn vẹn Số dư | 2.5 / 10 | TOCTOU race condition khi kiểm tra vượt dự toán, duyệt chi không tái kiểm số dư, tiềm ẩn AB-BA deadlock khi điều chuyển song song, cho phép sửa/xóa bản ghi đã duyệt/quyết toán. |
| Tính đúng đắn Logic Kế toán & Enum/Validator | 3.5 / 10 | Tự mâu thuẫn giữa Business và Validator (`RELEASE_HOLD`/`PENDING`/`DIRECTORATE`/`SUBMITTED`/`UNIT_SELF`), đếm trùng `ADVANCE_SETTLEMENT`, sai logic trong nhiều nghiệp vụ NV/DX và báo cáo. |
| An toàn Biểu thức & Số học `BigDecimal` | 6.5 / 10 | Không dùng SpEL (an toàn trước RCE); dùng `BigDecimal` với `RoundingMode.HALF_UP`; nhưng công thức tùy biến chưa được thực thi ở backend và thiếu phát hiện vòng lặp gián tiếp. |
| Chất lượng Kiểm thử Tự động (Unit/Integration) | 4.5 / 10 | 34/34 unit test chạy nhanh và PASS; nhưng 100% là Mockito unit test thuần, **0** test DB/Flyway/Locking/Security/Controller thực tế. |
| **Tổng điểm Backend** | **4.6 / 10** | **Tồn tại nhiều khiếm khuyết Critical về chống chi vượt dự toán, tính bất biến sổ cái và tự mâu thuẫn trạng thái.** |

### Thống kê Thực tế Mã nguồn

- **9 Controllers (164 endpoints)**: `BudgetAllocationController` (22), `BudgetCategoryController` (18), `BudgetDashboardController` (15), `BudgetDisbursementController` (24), `BudgetEstimateItemController` (21), `BudgetInternalController` (7), `BudgetReportController` (18), `BudgetTransactionController` (25), `BudgetUnitConfigController` (14).
- **11 Business + 2 Helpers**: Nổi bật hai God classes là `BudgetTransactionBusiness` (**2.651 LOC**) và `BudgetReportBusiness` (**1.568 LOC**), cùng `BudgetDisbursementBusiness` (1.112 LOC), `BudgetDashboardBusiness` (1.031 LOC), `BudgetIntegrationOrchestrator` (902 LOC), `BudgetAllocationBusiness` (747 LOC), `BudgetWorkflowEngineHelper` (700 LOC).
- **7 Services / 7 Repos / 7 Mappers / 4 Specifications / 8 Validators / 3 ExcelServices / 2 Kafka classes**.

## 2. Kết quả Thực thi Maven Test Thực tế

Lệnh đã chạy trực tiếp trên workspace:

```bash
mvn -f tr-business-services/tr-budget-service/pom.xml test -B
```

Kết quả thực tế: **`BUILD SUCCESS`** trong **`11.604 s`** — **`Tests run: 34, Failures: 0, Errors: 0, Skipped: 0`**.

| Bộ kiểm thử (`src/test/java/...`) | Số test | Kết quả | Thời gian | Loại test |
| --- | --- | --- | --- | --- |
| `BudgetAuditRemediationTest` | 4 | PASS | 2.537 s | Mockito Unit |
| `BudgetBusinessFlowTest` | 9 | PASS | 0.468 s | Mockito Unit |
| `BudgetDashboardAndWebhookTest` | 6 | PASS | 0.900 s | Mockito Unit |
| `BudgetTreeBuilderAndBatchTest` | 5 | PASS | 0.108 s | Mockito Unit |
| `BudgetWorkflowEngineTest` | 3 | PASS | 0.016 s | Mockito Unit |
| `BudgetInternalAnalyticsAndFactPublishTest` | 4 | PASS | 0.199 s | Mockito Unit |
| `BudgetTargetScopeValidatorTest` | 3 | PASS | 0.131 s | Mockito Unit |

Cảnh báo Maven ghi nhận trong quá trình chạy:

- `'build.plugins.plugin.version' for org.sonatype.central:central-publishing-maven-plugin is missing. @ line 160, column 21` (`pom.xml:160-166`).
- **Khoảng trống kiểm thử lớn**: Hoàn toàn **không có** `@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest`, Testcontainers PostgreSQL/Flyway schema validation hay concurrency stress test. Do toàn bộ test đều mock Repository/Validator, các lỗi nghiêm trọng như mâu thuẫn enum-validator, lệch schema Flyway hay race condition đều lọt qua CI.

## 3. Phân tích Chuyên sâu Concurrency, Locking & Chống Chi Vượt Dự toán

### 3.1 Lỗi TOCTOU Race Condition & Double-Spend (`BE-C01`)

Luồng tạo hồ sơ giải ngân và chuyển tiền hiện thực hiện kiểm tra số dư **trước** khi khóa hàng (row lock):

1. `BudgetDisbursementBusiness.validateOverBudgetAndVoucherGates:398` và `BudgetTransactionBusiness.validateLedgerBalanceBeforeTransfer:506` đọc `BudgetAllocation` bằng `findById()` thông thường (**không có `PESSIMISTIC_WRITE`**).
2. `BudgetWorkflowEngineHelper.recalculateAllocationMetrics:157` chỉ gọi `findByIdForUpdate(allocationId)` **sau khi** bản ghi `BudgetDisbursement` / `BudgetTransaction` đã được `save()` xuống CSDL.
3. Dưới tải đồng thời, 2 giao dịch cùng đọc `availableAmount = 100.000.000`, cả hai đều qua cổng kiểm tra, cùng ghi nhận `80.000.000`, đẩy số dư khả dụng xuống **`-60.000.000`** (`recalculateAllocationMetrics` không ném ngoại lệ khi số dư âm!).

### 3.2 Duyệt Chi Không Tái Kiểm Vượt Dự toán (`BE-C01`)

Ngay cả khi **không có concurrency**:

- `recalculateAllocationMetrics:274` bỏ qua bản ghi `BudgetDisbursement` ở trạng thái `DRAFT`.
- Người dùng có thể tạo 3 hồ sơ `DRAFT` cùng trỏ vào một `BudgetAllocation` (mỗi hồ sơ bằng 80% dự toán khả dụng); cả 3 đều vượt qua `validateOverBudgetAndVoucherGates` lúc `create()`.
- Khi gọi `BudgetDisbursementBusiness.approveDisbursement:425-475`, hệ thống **không gọi lại** `validateOverBudgetAndVoucherGates()` trước khi chuyển sang `APPROVED` -> vượt dự toán 240%!

### 3.3 Nguy cơ AB-BA Deadlock Trong Bút toán Kép (`BE-C01`)

- Tại `BudgetWorkflowEngineHelper.executeDoubleEntryLedger:593,606`, khi xử lý `INTERNAL_TRANSFER`, hệ thống khóa `targetAllocationId` trước rồi mới khóa `sourceAllocationId` mà **không sắp xếp thứ tự khóa xác định theo ID tăng dần** (`Math.min(id1, id2)` trước `Math.max(id1, id2)`). Hai lệnh điều chuyển ngược chiều `A -> B` và `B -> A` chạy song song sẽ gây deadlock PostgreSQL.

### 3.4 Bỏ qua Phê duyệt Lúc Tạo & Sửa/Xóa Sổ cái Đã Ghi Sổ (`BE-C02`)

- **Bypass Approval trên `create()`**: `BudgetTransactionBusiness.create:119-137` và `BudgetDisbursementBusiness.create:115-136` chỉ gán mặc định `"DRAFT"` nếu trường `approvalStatus` đang rỗng. Người dùng chỉ có quyền `*Create` có thể gửi `"approvalStatus": "APPROVED"` hoặc `"SETTLED"` trong JSON để tự duyệt và kích hoạt ngay `executeDoubleEntryLedger` / trừ số dư!
- **Thiếu bảo vệ bất biến trên `update()` / `delete()` / `rollback()`**:
  - `BudgetAllocationBusiness.update:136`, `BudgetTransactionBusiness.update:142`, `BudgetDisbursementBusiness.update:141`, `BudgetReportBusiness.update:133` **không kiểm tra** trạng thái hiện tại (`APPROVED`, `EXECUTING`, `SETTLED`, `LOCKED_SETTLED`), cho phép chỉnh sửa trực tiếp số tiền, trạng thái và người duyệt trên chứng từ đã hoàn tất.
  - Các hàm `delete()` / `bulkDelete()` và `rollback(id, version)` trên cả 4 thực thể workflow đều không chặn bản ghi đã khóa/đã quyết toán; riêng `rollback()` không kích hoạt `recalculateAllocationMetrics()`, làm sai lệch vĩnh viễn số dư phân bổ với sổ cái.
  - Các DTO (`BudgetAllocationData`, `BudgetDisbursementData`, `BudgetTransactionData`) mở toàn bộ trường tính toán (`effectiveAmount`, `availableAmount`, `disbursedAmount`, `workflowStatus`, `approvedByPersonId`) cho MapStruct map trực tiếp từ request (Mass Assignment).

## 4. Lỗi Tự Mâu Thuẫn Enum, Validator & Sổ cái Kế toán

### 4.1 Mâu thuẫn Giá trị Enum Làm Hỏng Luồng Nghiệp vụ (`BE-C03`)

| Nguồn sinh chuỗi | Giá trị Business ghi ra | Giá trị Validator cho phép | Hệ quả thực tế |
| --- | --- | --- | --- |
| `BudgetTransactionBusiness:1452`; `BudgetWorkflowEngineHelper:235` | `"RELEASE_HOLD"` | `BudgetTransactionType:26` chỉ có `"EARMARK_RELEASE"` (`BudgetTransactionValidator:69`) | Thao tác giải tỏa tạm giữ (`DX-01`) bị Validator **chặn hoàn toàn** khi gọi `create()`. |
| `BudgetTransactionBusiness:2253` | `"PENDING"` (cho `UNIT_ACCOUNTANT`) | `BudgetWorkflowStatus` chỉ có `"PENDING_APPROVAL"` (`BudgetTransactionValidator:80`) | Kế toán đơn vị gửi thao tác NV/DX bị Validator **từ chối** với lỗi trạng thái không hợp lệ. |
| `BudgetAllocationBusiness:122,348,392` | `"DIRECTORATE"`, `"UNIT_SELF"`, `"SUBMITTED"` | `BudgetApprovalScope` chỉ có `UNIT_SELF_APPROVAL`, `SUPERIOR_APPROVAL`, `TREASURY_RECONCILED`; `BudgetWorkflowStatus` chỉ có `PENDING_APPROVAL` (`BudgetAllocationValidator:102,113`) | Luồng tạo mới & trình duyệt phân bổ tự đánh dấu chuỗi sai chuẩn, khiến lần `update()` tiếp theo bị Validator chặn. |
| `BudgetDataInitializer:139,180,221` | Công thức `"SUM_CHILDREN"`, nhóm `"DEDICATED_FUND"`, kỳ `"FIRST_HALF"` | Không nằm trong tập hợp lệ của `BudgetEstimateFormulaValidator` / Enums | Dữ liệu seed mẫu không hợp lệ nếu người dùng bấm Lưu lại trên UI. |

### 4.2 Lỗi Tính toán Số dư & Sổ cái (`BE-C03`)

- **Đếm trùng `ADVANCE_SETTLEMENT` (`BudgetWorkflowEngineHelper:302-306`)**: Khi giải ngân tạm ứng (`ADVANCE`), tiền đã được cộng vào `disbursed` (với trạng thái không nằm trong danh sách loại trừ). Khi lập hồ sơ thanh toán tạm ứng (`ADVANCE_SETTLEMENT`), nhánh `else` tiếp tục cộng `amt` vào `disbursed` lần thứ hai thay vì bù trừ vào `advancePending`!
- **Dùng blacklist thay vì whitelist trạng thái (`BudgetWorkflowEngineHelper:183-189,274`)**: Chỉ loại trừ `DRAFT`, `REJECTED`, `CANCELLED` khiến các bản ghi `PENDING_APPROVAL` / `PENDING_FINANCE` (chưa được duyệt) đã bị tính vào số tiền giải ngân và số dư hiệu lực!
- **Dùng `.abs()` cho điều chỉnh giảm (`BudgetWorkflowEngineHelper:197-198`)**: Khi `PAYROLL_INDEXATION` hoặc `SUPPLEMENT` có số tiền âm (giảm biên chế / giảm dự toán), hàm dùng `amt.abs()` làm **tăng** dự toán thay vì giảm.
- **Thiếu kiểm tra số dư cho 3 loại bút toán giảm (`BudgetTransactionBusiness:506`)**: `validateLedgerBalanceBeforeTransfer` bỏ sót `WITHDRAWAL`, `ADJUSTMENT` và `EARMARK_HOLD`.
- **Fallback ngầm nguy hiểm (`BudgetTransactionBusiness.resolveExistingAllocation:2352-2356`)**: Nếu client bỏ trống hoặc truyền sai `nodeId`, hệ thống âm thầm lấy **bản ghi phân bổ không phải thu đầu tiên của cả năm** (có thể thuộc đơn vị khác!) để ghi bút toán.
- **Thao tác đột biến trong HTTP `GET` (`BudgetDisbursementController:256`, `BudgetDisbursementBusiness:692-722`)**: Endpoint `GET /{id}/kbnn-dvc-package` tự ghi `dvcKbnnDossierNo` vào CSDL và hardcode số tài khoản giả (`3712.1.1054321`, `118000123456`, `VietinBank Chi nhánh Đà Nẵng`) nếu đơn vị chưa cấu hình.

## 5. Đánh giá 8 Validators & Rủi ro Formula/SpEL Injection

### 5.1 Kiểm toán `BudgetEstimateFormulaValidator`

- **An toàn trước SpEL / Code Execution**: `BudgetEstimateFormulaValidator.java` **không sử dụng** `SpelExpressionParser`, `ScriptEngine` hay `MVEL`. Validator phân tích công thức bằng regex whitelist thuần (`SUM([CODE])`, `AVG(...)`, `MIN(...)`, `MAX(...)`, `ROUND(...)`, toán tử `+ - * / ( )`, tham chiếu `[CODE]`, hằng số số học). **Kết luận: Không có lỗ hổng RCE / SpEL Injection.**
- **Các điểm yếu còn tồn tại (`BE-H02`)**:
  1. **Không có engine tính công thức ở Backend**: Backend mới chỉ cộng tổng con (`rollupNormAmount`), hoàn toàn chưa thực thi biểu thức DSL người dùng khai báo -> sai lệch giữa công thức lưu và số tổng hợp thực tế.
  2. **Không phát hiện vòng tham chiếu gián tiếp**: Chỉ kiểm tra tự tham chiếu trực tiếp (`A -> [A]`), bỏ lọt chu trình `A -> [B] -> [A]`.
  3. **Không kiểm tra tồn tại mã mục tiêu**: Cho phép lưu `[NON_EXISTENT_CODE]` không tồn tại trong cây cùng năm.
  4. **Cho phép dấu `+` / `-` đứng đầu công thức**: Nếu chuỗi được xuất ra ô Excel mà không đi qua bộ lọc sanitize sẽ có nguy cơ công thức ngoài ý muốn.
  5. **Từ chối công thức mẫu `"SUM_CHILDREN"`** do chính `BudgetDataInitializer` sinh ra.

### 5.2 Kiểm tra `BigDecimal` Precision & Rounding

- **Điểm tốt**: Tất cả phép tính tài chính trong Entities và `BudgetWorkflowEngineHelper` dùng `BigDecimal` (không dùng `double`/`float` cho tiền tệ) và dùng `RoundingMode.HALF_UP` cho phép chia tỷ lệ (`BudgetDashboardBusiness`, `BudgetReportBusiness`, `BudgetTransactionBusiness`).
- **Điểm yếu (`BE-M01`)**:
  - `BudgetReportBusiness` và `BudgetDashboardBusiness` dùng `double`/`Double` cho một số KPI phần trăm trung gian, dễ lệch làm tròn khi đối chiếu báo cáo tài chính.
  - Thiếu chuẩn hóa `scale(2, RoundingMode.HALF_UP)` trước khi gán ngược vào cột `NUMERIC(19, 2)` trên các phép nhân tỷ lệ quỹ tự chủ (`DX-04`), có thể phát sinh số dư làm tròn 1 đồng giữa tổng các quỹ và phần chênh lệch thu-chi.

## 6. Đánh giá 7 Repositories, Specifications, Mappers & Batch/Tree Ops

- **Lỗ hổng Repository (`BE-H01` / liên kết `DB-H01`)**:
  - Cả 7 `*Repo.java` đều có `findAllByCodeIgnoreCaseIncludingDeletedSorted` dùng JPQL `WHERE a.code = :code` (phân biệt hoa thường và bị `@SQLRestriction("deleted_at IS NULL")` chặn sạch bản ghi đã xóa mềm -> làm hỏng luồng restore của `GenericUpsertHelper` và `BudgetEstimateTreeSyncBusiness:185`).
  - Cả 7 `*Repo.java` có `softDeleteByIds` dùng bulk JPQL `UPDATE` không lọc `app_code`.
- **Tree & Batch Operations (`BE-H03`)**:
  - `BudgetEstimateTreeOpsBusiness` (`lines 74, 134`), `BudgetEstimateTreeSyncBusiness` (`lines 66, 132`) và `BudgetBatchEntryBusiness` (`lines 88, 159, 166`) gọi `repo.findAll()` không giới hạn kích thước.
  - `BudgetEstimateTreeSyncBusiness.refreshDescendantPaths:339-352` cập nhật đệ quy `O(N^2)` với `N+1` lệnh `save()` và thiếu bộ chặn vòng lặp (`visited`).
  - `BudgetDashboardBusiness.rollupEstimateTreeMetrics:799-823` cũng thiếu `visited` cycle guard (khác với `BudgetEstimateItemBusiness.rollupNormAmount:387` đã có), dễ gây `StackOverflowError` nếu dữ liệu cây bị hỏng.

## 7. Danh mục Phát hiện & Xếp hạng Mức độ

| Mã lỗi | Mức độ | Vị trí (`file:line`) | Mô tả vấn đề | Khuyến nghị khắc phục |
| --- | --- | --- | --- | --- |
| `BE-C01` | **CRITICAL** | `BudgetDisbursementBusiness.java:398,425-475`; `BudgetTransactionBusiness.java:506`; `BudgetWorkflowEngineHelper.java:157,593,606` | TOCTOU double-spend (kiểm số dư không khóa hàng), duyệt chi không tái kiểm vượt dự toán, nguy cơ AB-BA deadlock khi chuyển khoản ngược chiều. | Khóa `findByIdForUpdate` theo thứ tự ID tăng dần **trước** khi kiểm tra số dư ở cả `create()` và `approve()`; ném lỗi ngay nếu `availableAmount < 0`. |
| `BE-C02` | **CRITICAL** | `BudgetTransactionBusiness.java:119-142`; `BudgetDisbursementBusiness.java:115-141`; `BudgetAllocationBusiness.java:136`; `BudgetReportBusiness.java:133` | Bypass phê duyệt khi `create()` truyền sẵn `APPROVED`/`SETTLED`; không chặn sửa/xóa/rollback bản ghi đã duyệt/quyết toán; mass assignment các cột tài chính & workflow. | Ép cứng `DRAFT` lúc `create()`, tách Create/Update Request DTO khỏi Response DTO, khóa bất biến trạng thái cuối và bắt buộc `recalculate` khi rollback. |
| `BE-C03` | **CRITICAL** | `BudgetTransactionBusiness.java:1452,2253,2352-2356`; `BudgetAllocationBusiness.java:122,348,392`; `BudgetWorkflowEngineHelper.java:183-198,235,274,302-306` | Tự mâu thuẫn enum/validator làm gãy nghiệp vụ; tính trùng `ADVANCE_SETTLEMENT`; dùng blacklist status; dùng `.abs()` sai cho giảm dự toán; fallback ngầm sai đơn vị. | Thống nhất 100% hằng số về các lớp `Enum`, chuyển sang whitelist trạng thái (`APPROVED`/`EXECUTING`/`SETTLED`), sửa công thức `ADVANCE_SETTLEMENT` & bỏ `.abs()`. |
| `BE-H01` | **HIGH** | 7 `*Repo.java:33-42` | `findAllByCodeIgnoreCaseIncludingDeletedSorted` sai case & bị `@SQLRestriction` chặn; `softDeleteByIds` bỏ qua `app_code`. | Viết lại bằng native SQL `LOWER(code) = LOWER(:code) AND app_code = :appCode` cho restore và bổ sung `AND app_code = :appCode` cho bulk soft delete. |
| `BE-H02` | **HIGH** | `BudgetEstimateFormulaValidator.java`; `BudgetDisbursementController.java:256`; `BudgetDisbursementBusiness.java:692-722` | Formula DSL chưa có evaluation engine, thiếu kiểm tra chu trình gián tiếp & tồn tại mã; `GET /kbnn-dvc-package` ghi CSDL và hardcode số tài khoản giả. | Bổ sung graph cycle detection + code existence check; chuyển xuất gói DVC có sinh mã hồ sơ sang `POST` và báo lỗi khi thiếu cấu hình tài khoản đơn vị. |
| `BE-H03` | **HIGH** | `BudgetTransactionBusiness.java` (2.651 LOC); `BudgetReportBusiness.java` (1.568 LOC); `src/test/java/**` | 2 God classes vượt xa ngưỡng bảo trì; 0 test tích hợp DB/Flyway/Concurrency/Security. | Tách `BudgetTransactionBusiness` theo nhóm NV/DX (`CarryOver`, `Transfer`, `Earmark`, `Payroll`, `Autonomy`); bổ sung Testcontainers PostgreSQL + concurrency & MockMvc tests. |
| `BE-M01` | **MEDIUM** | `BudgetDashboardBusiness.java:799-823`; `BudgetEstimateTreeSyncBusiness.java:339-352` | Thiếu cycle guard khi đệ quy rollup dashboard, cập nhật `fullPath` O(N^2) với N+1 `save()`, làm tròn tỷ lệ chưa nhất quán `scale(2)`. | Thêm `Set<Long> visited` guard, chuyển sang `saveAll()` theo batch và chuẩn hóa `BigDecimal.setScale(2, RoundingMode.HALF_UP)`. |
