# Đánh giá Bảo mật (OWASP, Sa-Token, ABAC/SoD) & Hiệu năng (`tr-budget-service`)

> **Phạm vi kiểm toán**: Bảo mật ứng dụng theo OWASP Top 10 / API Security Top 10, Sa-Token (`@SaCheckPermission`, `@SaIgnore`, `SaSameUtil`, `@SaSharedLookup` — `LP-020`), cách ly đa ứng dụng (`appCodeFilter`, `AppIsolatedListener`), phân quyền dữ liệu theo đơn vị ABAC/IDOR & SoD tài chính (`LP-021`), Formula/Excel/Prompt Injection, quản lý bí mật, CI/CD, và đánh giá hiệu năng truy vấn/cây dự toán/sổ cái/caching.
>
> **Ngày kiểm toán**: 2026-11-26 | **Chế độ**: Chỉ đọc & Đánh giá (không sửa mã nguồn).

## 1. Tổng quan Điểm số & Nhận định

| Tiêu chí | Điểm (`/10`) | Nhận định nhanh |
| --- | --- | --- |
| Xác thực S2S, Same-Token & Bảo mật Cấu hình/Secrets | 2.5 / 10 | `@SaIgnore` trên `BudgetInternalController` bỏ qua `SaSameUtil` ở 6/7 endpoint S2S; `SA_TOKEN_SAME_SECRET` rỗng + `is-check-redis: false`; lộ mật khẩu/token thật ở 5+ tệp cấu hình và CI. |
| Phân quyền RBAC, ABAC (`nodeId` IDOR), SoD (`LP-021`) & Lookup (`LP-020`) | 3.5 / 10 | Có `@SaCheckPermission` ở đa số endpoint UI và ẩn TK ngân hàng ở `/options`; nhưng **0** kiểm tra ABAC `nodeId` (IDOR xuyên phòng ban), **0** SoD, vi phạm `LP-020` (0 `@SaSharedLookup`), `BudgetQueryHelper` fail-open. |
| Bảo vệ Input, Injection (Formula, Excel, SQL, Prompt) | 6.2 / 10 | An toàn trước SpEL RCE (regex whitelist) và SQL Injection (JPA/Criteria); `GlobalExcelService` có `FormulaSanitizingWriteHandler` chống Excel injection; nhưng thiếu `@Valid` diện rộng, upload 100MB không kiểm loại file/số dòng và raw prompt `AiClient`. |
| Hiệu năng Truy vấn, Cây Dự toán, Sổ cái & Caching | 4.2 / 10 | Hàng loạt lệnh `repo.findAll()` không giới hạn, N+1 DB & N+1 Feign trong vòng lặp, thuật toán `O(N^2)`/`O(N*M)`, thiếu cycle guard trên dashboard và **0%** sử dụng Redis cache dù đã nạp `tr-common-redis`. |
| **Tổng điểm Bảo mật & Hiệu năng** | **4.1 / 10** | **Rủi ro nghiêm trọng về lộ bí mật, bỏ trống xác thực S2S nội bộ, IDOR xuyên đơn vị và nút cổ chai hiệu năng khi dữ liệu ngân sách lớn.** |

## 2. Kiểm toán Bảo mật Chuyên sâu

### 2.1 `@SaIgnore` Vô hiệu hóa Xác thực S2S trên 6/7 Endpoint Nội bộ (`SEC-C01`)

- `BudgetInternalController.java:45` gắn `@SaIgnore` ở **cấp độ class**.
- Trong cơ chế `SaInterceptor.preHandle()` của Sa-Token, khi Controller có `@SaIgnore`, interceptor **bỏ qua toàn bộ hàm `auth` trong `SecurityConfig.java:99-112`** (nơi lẽ ra gọi `SaSameUtil.checkCurrentRequestToken()` cho `/internal/**`).
- Hệ quả: Chỉ duy nhất endpoint `getInternalAnalyticsMetrics` (`BudgetInternalController.java:118-125`) tự gọi tường minh `SaSameUtil.checkCurrentRequestToken()`. **Cả 6 endpoint nội bộ còn lại** (`sync-from-plan`, `by-plan`, `transactions/record`, `reserve-commitment`, `payment-webhook-settle`, `dashboard/overview`, `lines 61-112`) **hoàn toàn không xác thực Same-Token lẫn phiên người dùng!**
- Mặc dù `GatewaySecurityFilter:174` chặn truy cập `/internal/` từ ngoài API Gateway (trả về 403), nhưng bất kỳ pod, container hoặc máy chủ nào trong mạng nội bộ/LAN bị xâm nhập đều có thể tự do ghi sổ cái, tạo cam kết chi và quyết toán giả mạo mà không cần bất kỳ token nào!
- **Bồi thêm rủi ro Same-Token**: `config/_base/tr-budget-service.yml:140,142` đặt `sa-token.same-token.secret-key: ${SA_TOKEN_SAME_SECRET:}` (**chuỗi rỗng**) và `is-check-redis: false`, làm suy yếu cơ chế kiểm chứng Same-Token liên pod.

### 2.2 Lộ Bí mật Nghiêm trọng & Phơi bày Actuator (`SEC-C02`)

- **Lộ Credentials trong Git**:
  - `settings.xml:6-25`: Plaintext Nexus (`tr-deloyment` / `DnictPro@123`) và **Sonatype Maven Central** (`central`: `qZWt17` / `3vHJBiq1727jj7DRI7Cc4qhb9SssUXtb1`), kết nối Nexus qua HTTP không mã hóa (`lines 33,46,97`).
  - `Jenkinsfile:29-30`: Hardcode live Telegram Bot Token (`8423245923:AAFHSTIpl6A5vsbkRbYDMqkPw73t5PKP34E`) và Chat ID (`-1003054884531`); dòng `32` tắt kiểm chứng chứng chỉ SSL Maven; dòng `161` chạy container bằng quyền `root`.
  - `config/ict-dng/tr-budget-service-ict-dng.yml`, `config/dev/tr-budget-service-dev.yml`, `config/_base/tr-budget-service.yml`, `application.yml`: Chứa mật khẩu DB/Redis cứng (`DnictPro@123`, `IctDng2026!@#`, `DnictProSecurePass123!-ICT-DNG`).
- **Actuator (đánh giá chính xác, tránh dương tính giả)**:
  - *Điểm bảo vệ đang có*: `SecurityConfig.java:180-243` đăng ký `SaServletFilter` bảo vệ `/actuator/**` (yêu cầu `ROLE_SYS_ADMIN` / `ActuatorManage`, `SAME-TOKEN`, hoặc Basic Auth với mật khẩu sinh ngẫu nhiên nếu cấu hình dùng mật khẩu cũ `DnictPro@123`).
  - *Điểm hở còn lại*: `SecurityConfig.java:183` miễn xác thực cho `/actuator/health` và `/actuator/health/**`, trong khi `config/_base/tr-budget-service.yml:239` bật `management.endpoint.health.show-details: always` -> lộ công khai chi tiết hạ tầng DB/Redis/Kafka/ổ đĩa; đồng thời `application.yml:201,238` và `config/dev/tr-budget-service-dev.yml:92` đặt `/actuator/**` vào `security.excludes` và mở `loggers`, `refresh`, `*` (tăng bề mặt tấn công nếu `security.actuator.auth-enabled` bị tắt nhầm).

### 2.3 Lỗ hổng IDOR / Thiếu ABAC Đơn vị (`nodeId`) & SoD Tài chính (`SEC-C03` — `LP-021`)

- **Hoàn toàn thiếu ABAC theo `nodeId`**: `tr-common-security` đã cung cấp sẵn bean `OrgSubtreeAuthorizationHelper` (`SecurityConfig.java:58`), nhưng `tr-budget-service` **không tiêm và không gọi ở bất kỳ lớp Business nào**.
- Hệ quả: Mọi người dùng thuộc cùng `appCode` chỉ cần có quyền chức năng (`BudgetAllocationDetail`, `BudgetAllocationEdit`, `BudgetDisbursementCreate`, `BudgetTransactionCreate`...) đều có thể xem, sửa, lập lệnh chi hoặc trình duyệt ngân sách của **bất kỳ phòng ban/đơn vị trực thuộc nào khác** chỉ bằng cách thay đổi `id` hoặc `nodeId` (IDOR nghiêm trọng).
- **Mass Assignment & Bypass Workflow**: Đã chứng minh tại `BE-C02` và `WF-C01` — người dùng có thể tự gửi `approvalStatus: "APPROVED"`, `availableAmount`, `approvedByPersonId` hoặc giả mạo `actorId`/`actorName` trong request body.
- **Cách ly đa ứng dụng (`appCodeFilter` & `AppIsolatedListener`)**:
  - *Điểm tốt*: `AppIsolatedListener.java:69-87` chặn thành công hành vi ghi chéo `appCode` ở `@PrePersist`/`@PreUpdate` đối với người dùng thường (không phải SuperAdmin/S2S).
  - *Điểm hở (`SEC-H01`)*: `BudgetQueryHelper.resolveCurrentAppCode():167-170` thiết kế **fail-open** — khi `HeaderContext.getAppCode()` bị `null`, hàm trả về `"budget"` thay vì ném lỗi `ServiceException`; kết hợp với bulk JPQL `softDeleteByIds` thiếu điều kiện `app_code` (`DB-H01`) và `@Scheduled` SLA quét toàn bộ tenants (`WF-H01`).

### 2.4 Tuân thủ `LP-020` (`@SaSharedLookup`), Input Validation & Injection (`SEC-H02`)

| Hạng mục kiểm tra | Trạng thái | Bằng chứng (`file:line`) |
| --- | --- | --- |
| **`LP-020` `@SaSharedLookup`** | **KHÔNG ĐẠT** | Toàn bộ 7 endpoint `/options` bỏ qua `@SaSharedLookup` và hardcode danh sách 6–25 mã quyền trong `@SaCheckPermission(mode = SaMode.OR)`. *(Điểm cộng: `BudgetUnitConfigBusiness.getOptions` và `BudgetDisbursementBusiness.getOptions` đã ẩn số tài khoản ngân hàng và mã số thuế, đã có test xác minh trong `BudgetAuditRemediationTest`.)* |
| **SpEL / Formula Injection** | **AN TOÀN (RCE)** | `BudgetEstimateFormulaValidator.java` dùng regex whitelist, không gọi `SpelExpressionParser`/`ScriptEngine`. Vẫn cần chặn vòng tham chiếu gián tiếp và ký tự `+`/`-` đầu chuỗi. |
| **Excel Formula Injection & Import DoS** | **MỘT PHẦN** | Xuất Excel dùng `GlobalExcelService` (`tr-common-excel`) đã có `FormulaSanitizingWriteHandler` và trần `MAX_EXPORT_ROWS = 50_000`. Tuy nhiên `BudgetExcelControllerHelper.importData` chưa giới hạn số dòng tối đa, chưa kiểm tra magic bytes/phần mở rộng file, trong khi `application.yml:47-48` cho phép multipart lên tới **100MB** (nguy cơ Zip Bomb / OOM). |
| **Bean Validation (`@Valid`)** | **YẾU** | Thiếu `@Valid` trên `POST /import`, `POST /batch`, `POST /tree`, `PUT /sort-order`, `POST /year-domain-configs`; 20+ endpoint workflow/NV/DX nhận thẳng `Map<String, Object>` không có schema validation; bản thân các DTO chưa gắn `@NotBlank`/`@Positive`/`@Size`. |
| **AI Prompt Injection** | **RỦI RO** | `BudgetDashboardBusiness.java:997` nội suy trực tiếp chuỗi nghiệp vụ/tên đơn vị vào prompt gửi `AiClient` mà không bọc phân tách hoặc lọc ký tự điều khiển. |
| **Rate Limiting & Mã hóa Trường Nhạy cảm** | **KHÔNG DÙNG** | `pom.xml:66,78` nạp `tr-common-encrypt` và `tr-common-ratelimiter`, nhưng toàn bộ mã nguồn có **0** `@RateLimiter` (nguy cơ spam export/batch/AI) và **0** `@EncryptField` (số tài khoản ngân hàng, MST, CCCD lưu plaintext). |

## 3. Đánh giá Hiệu năng & Khả năng Mở rộng

### 3.1 Các Nút cổ chai Hiệu năng Phát hiện (`PERF-H01`, `PERF-H02`)

1. **Tải toàn bộ bảng vào RAM (`repo.findAll()` không phân trang)**:
   - `BudgetEstimateItemBusiness.java:292`, `BudgetEstimateTreeSyncBusiness.java:66,132`, `BudgetEstimateTreeOpsBusiness.java:74,134`, `BudgetBatchEntryBusiness.java:88,159,166`, `BudgetIntegrationOrchestrator.java:714`.
   - Khi hệ thống vận hành 3–5 năm trên nhiều đơn vị sự nghiệp, số dòng tăng lên hàng chục/trăm nghìn sẽ gây tràn Heap (đặc biệt với `tr-budget-service.sh:14` chỉ cấp `-Xmx512m` + `SerialGC`!).
2. **Tải toàn bộ bản ghi chỉ để đếm `.size()` & Bỏ qua bộ lọc (`BudgetDashboardBusiness.java:175-189`)**:
   - `getOverview()` tải toàn bộ danh sách `BudgetTransaction` và `BudgetDisbursement` của cả năm lên bộ nhớ chỉ để đếm số lượng (`.size()`) thay vì dùng `SELECT COUNT(*)` / `GROUP BY` ở PostgreSQL, đồng thời **bỏ qua** bộ lọc `nodeId`/`agencyCode` khi tính một số chỉ số.
3. **Truy vấn N+1 DB, N+1 Feign & Vòng lặp `O(N*M)` / `O(N^2)`**:
   - `BudgetReportBusiness.generateKbnnReconciliationStatement:727,759`: Gọi `budgetUnitConfigRepo.findFirstByNodeIdOrderByIdDesc(...)` bên trong vòng lặp (N+1 DB query) và lồng vòng lặp đối chiếu phân bổ × giải ngân `O(N*M)`.
   - `BudgetEstimateTreeSyncBusiness.refreshDescendantPaths:339-352`: Duyệt đệ quy `O(N^2)` kèm `save()` từng bản ghi một (N+1 UPDATE) và không có cycle guard.
   - Các hàm `enrichData()` chạy bên trong trang kết quả `findAll()` kích hoạt chuỗi gọi Feign N+1 sang `tr-directory-service` / `tr-document-service` mỗi khi cột snapshot bị trống.
   - `BudgetDashboardBusiness.rollupEstimateTreeMetrics:799-823`: Thiếu `visited` cycle guard -> nguy cơ `StackOverflowError`.
4. **0% Tận dụng Redis Cache & Gọi Feign trong DB Transaction**:
   - Đã kéo `tr-common-redis` và cấu hình TTL trong YAML nhưng **không có bất kỳ `@Cacheable` nào** cho danh mục ít biến động (`BudgetCategory`, `BudgetUnitConfig`, `BudgetEstimateItem` theo năm).
   - Các hàm đồng bộ Feign (`syncAllocationToLinkedPlan`, `syncDisbursementWithPaymentService`, `notifyDisbursementEvent`) chạy **bên trong `@Transactional`**, giữ kết nối HikariCP và khóa dòng `PESSIMISTIC_WRITE` trong khi chờ I/O mạng -> dễ gây cạn connection pool khi dịch vụ đích chậm.

## 4. Danh mục Phát hiện Bảo mật & Hiệu năng

| Mã lỗi | Mức độ | Vị trí (`file:line`) | Mô tả vấn đề | Khuyến nghị khắc phục |
| --- | --- | --- | --- | --- |
| `SEC-C01` | **CRITICAL** | `BudgetInternalController.java:45,61-112`; `config/_base/tr-budget-service.yml:140,142` | `@SaIgnore` ở cấp class bỏ qua `SaSameUtil` của `SecurityConfig` cho 6/7 endpoint S2S; `SA_TOKEN_SAME_SECRET` rỗng & `is-check-redis: false`. | Gỡ `@SaIgnore` khỏi class `BudgetInternalController` (hoặc gọi `SaSameUtil.checkCurrentRequestToken()` bắt buộc cho mọi endpoint nội bộ) và cấu hình `SA_TOKEN_SAME_SECRET` mạnh + bật `is-check-redis: true`. |
| `SEC-C02` | **CRITICAL** | `settings.xml:6-25`; `Jenkinsfile:29-32,161`; `config/{_base,dev,ict-dng}/tr-budget-service*.yml` | Lộ credentials Maven Central, Nexus, Telegram Bot, PostgreSQL, Redis trong Git; `health.show-details: always` phơi bày thông tin hạ tầng; container Jenkins chạy `root`. | Xoay vòng ngay toàn bộ secret đã lộ, di chuyển sang Secret Manager/Vault, đặt `health.show-details: when_authorized` và giới hạn `exposure.include: health,info,prometheus`. |
| `SEC-C03` | **CRITICAL** | Toàn bộ tầng Business (`BudgetAllocation`, `BudgetTransaction`, `BudgetDisbursement`, `BudgetReport`) | Hoàn toàn thiếu kiểm tra ABAC `nodeId` (`OrgSubtreeAuthorizationHelper` không được dùng) -> IDOR xuyên phòng ban trong cùng tenant; thiếu SoD & cho phép Mass Assignment trạng thái. | Tích hợp `OrgSubtreeAuthorizationHelper` vào mọi thao tác đọc/ghi theo `nodeId` và Criteria Specification; tách DTO đầu vào chỉ chứa trường nghiệp vụ cho phép. |
| `SEC-H01` | **HIGH** | `BudgetQueryHelper.java:167-170`; 7 `*Repo.java:37-42` | `resolveCurrentAppCode()` fail-open về `"budget"` khi context rỗng; bulk `softDeleteByIds` bỏ qua `app_code`. | Đổi `resolveCurrentAppCode()` sang fail-closed (ném `ServiceException` khi thiếu `appCode`, trừ ngữ cảnh S2S/batch có truyền tenant tường minh). |
| `SEC-H02` | **HIGH** | 7 Controller `/options`; `BudgetExcelControllerHelper.java`; `application.yml:47-48`; `BudgetDashboardBusiness.java:997` | Vi phạm `LP-020` (thiếu `@SaSharedLookup`); thiếu `@Valid` & giới hạn dòng/dung lượng import Excel (100MB); không dùng `@RateLimiter`; nguy cơ Prompt Injection ở `AiClient`. | Chuyển 7 `/options` sang `@SaSharedLookup`, giảm giới hạn upload xuống `10-20MB` + chặn số dòng import, thêm `@RateLimiter` cho export/batch/AI và sanitize input AI. |
| `PERF-H01` | **HIGH** | `BudgetEstimateItemBusiness:292`; `BudgetEstimateTreeSyncBusiness:66,132,339-352`; `BudgetDashboardBusiness:175-189,799-823`; `BudgetReportBusiness:727,759` | Nhiều lệnh `findAll()` tải toàn bộ bảng, N+1 DB/Feign, `O(N*M)` / `O(N^2)`, đếm `.size()` trên RAM thay vì SQL aggregate, thiếu cycle guard dashboard. | Chuyển tổng hợp dashboard sang SQL `GROUP BY` / `SUM` / `COUNT`, prefetch Map theo `Set<Long> nodeIds`, batch `saveAll()`, thêm `visited` guard và lọc bắt buộc theo `budgetYear` + `appCode`. |
| `PERF-H02` | **HIGH** | Toàn bộ `tr-budget-service`; `BudgetDisbursementBusiness:453,995`; `tr-budget-service.sh:14` | 0% sử dụng Redis Cache cho danh mục/cấu hình; gọi Feign đồng bộ bên trong `@Transactional`; startup script dùng `SerialGC` & `-Xmx512m`. | Bổ sung `@Cacheable`/`@CacheEvict` (phân tách key theo `appCode`) cho `BudgetCategory` & `BudgetUnitConfig`, đẩy Feign/Kafka ra ngoài transaction (afterCommit/Outbox) và nâng JVM lên G1GC. |
