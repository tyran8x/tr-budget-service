# Báo cáo Kiểm toán Toàn diện — `tr-budget-service` (Quản lý Kinh phí & Ngân sách Đa cấp)

> **Hồ sơ hệ thống**: Microservice `tr-budget-service` (Port `8503`, Service Code `507`, Gateway `/v1/budget`, CSDL `tr_budget` / tiền tố `budget_`) cùng gói Frontend đa framework `@tyran8x/feature-budget` (Vue 3 + React 19) và phân hệ Mobile Flutter (`apps/dds_app`).
> **Ngày kiểm toán**: 2026-11-26 | **Phạm vi**: 16 tài liệu kiểm toán chuyên sâu (chỉ đọc & đánh giá, 0 chỉnh sửa mã nguồn).
> **Kết luận Điều hành (Executive Verdict)**: **CHƯA SẴN SÀNG PRODUCTION (5.01 / 10 — CHẶN GO-LIVE)** — Cần hoàn tất toàn bộ **15 hạng mục CRITICAL (`RM-1`, ~2 tuần)** về bảo mật S2S/secrets, SoD/ABAC, khóa chống chi vượt dự toán, Flyway/Schema drift, và 4 lỗi chí mạng trên Web/Mobile trước khi triển khai thực tế.

---

## 1. Số liệu Nền tảng Đã Xác minh (Ground Truth)

| Hạng mục | Số liệu xác minh trực tiếp từ mã nguồn |
| --- | --- |
| **Backend (`tr-budget-service`)** | Java 25, Spring Boot 4.x (`4.2.0.20261125.2`), Port `8503`, Gateway `/v1/budget`, **7 Entities**, **9 Controllers / 164 REST Endpoints**, **107 mã `@SaCheckPermission`**, 11 Business + 2 Helpers, 7 Services/Repos/Mappers/ExcelServices, 7 `@JaversSpringDataAuditable`, 3 Flyway scripts (`V303_001`..`V303_003`) |
| **Frontend (`@tyran8x/feature-budget`)** | **119 tệp Git**, kiến trúc Headless `src/core` + `src/api` + **8 màn hình Vue 3 (Element Plus)** + **8 màn hình React 19 (Ant Design)**; bọc **156 / 157 endpoint public**; khai báo **73 / 107 mã quyền** (+2 mã ma) |
| **Mobile Flutter (`apps/dds_app`)** | **12 tệp Dart** (11 tệp nguồn + 1 tệp test), 5 tab chính (`BudgetWorkspacePage`), bọc **45 / 157 endpoint public** (41 nối UI, 4 mã chết); khai báo **20 / 107 mã quyền** (`PermissionConstants`); chưa tách thành `packages/features/budget` |
| **Kiểm chứng Thực thi Trực tiếp** | **BE Maven**: `34/34 PASS` (`11.604s`, 100% Mockito unit test, 0 IT) \| **FE Typecheck**: `vue-tsc` & `tsc` `0 lỗi` \| **FE Vitest**: `27/27 PASS` (`70.67s`, phát sinh >4.400 dòng `[Vue warn]`, 0 React test, 0 E2E) \| **Mobile Flutter**: `flutter analyze` `0 issues` (`3.1s`), `flutter test` `6/6 PASS` (`6s`) |
| **Tổng hợp Nợ Kỹ thuật** | **48 hạng mục hợp nhất**: **15 CRITICAL (`TD-C01`..`TD-C15`)**, **18 HIGH (`TD-H01`..`TD-H18`)**, **12 MEDIUM (`TD-M01`..`TD-M12`)**, **3 LOW (`TD-L01`..`TD-L03`)** |

---

## 2. Bảng điểm Trưởng thành 13 Trụ cột Kỹ thuật

| STT | Trụ cột Đánh giá | Tài liệu chi tiết | Điểm (`/10`) | Mức độ Rủi ro |
| --- | --- | --- | --- | --- |
| 1 | Kiến trúc, Đa Tenant, Gateway & CI/CD | [architecture.md](architecture.md) | **5.2 / 10** | CRITICAL (`Jenkinsfile` sai cổng `8306`, lộ secrets) |
| 2 | Mã nguồn Backend, Concurrency & Kiểm thử | [backend-analysis.md](backend-analysis.md) | **4.6 / 10** | CRITICAL (TOCTOU double-spend, bypass `create()`, enum drift) |
| 3 | Nghiệp vụ Luật NSNN & Tự chủ NĐ 60/2021 | [business-analysis.md](business-analysis.md) | **6.1 / 10** | CRITICAL (`NV-02`, `DX-03`, `DX-04`, đếm trùng `ADVANCE_SETTLEMENT`) |
| 4 | Quy trình Phê duyệt, SoD & BPMN | [workflow-analysis.md](workflow-analysis.md) | **3.9 / 10** | CRITICAL (0% SoD, spoof `actorId`, 4/4 callback BPMN 404) |
| 5 | Cơ sở Dữ liệu, Entities & Flyway | [database-analysis.md](database-analysis.md) | **4.3 / 10** | CRITICAL (Flyway chết trên SB4, schema drift nặng, 0 FK/CHECK) |
| 6 | Tích hợp Microservices, Feign, Kafka & Outbox | [microservices-integration.md](microservices-integration.md) | **4.8 / 10** | CRITICAL (lệch DTO webhook thanh toán, 0 Outbox, `synced=true` giả) |
| 7 | Bảo mật (OWASP, Sa-Token, ABAC) & Hiệu năng | [security-performance.md](security-performance.md) | **4.1 / 10** | CRITICAL (6/7 S2S hở `@SaIgnore`, 0% ABAC `nodeId`, 0% Redis cache) |
| 8 | Quản trị Phụ thuộc (`pom.xml`, `tr-common-*`) | [dependency-map.md](dependency-map.md) | **5.5 / 10** | CRITICAL (thiếu starter Flyway & Resilience4j, sai scope JAR) |
| 9 | Frontend (`@features/budget` — Vue 3 & React 19) | [frontend-analysis.md](frontend-analysis.md) | **5.8 / 10** | CRITICAL (fail-open RBAC, kéo-thả mất nhánh, paste Excel 10x/âm) |
| 10 | Mobile Flutter (`apps/dds_app/lib/.../budget`) | [mobile-analysis.md](mobile-analysis.md) | **5.0 / 10** | CRITICAL (`Future.wait(14)` sập khi 403, sai 4 khóa JSON, tạo ID giả) |
| 11 | Phân quyền 107 mã, Tự chủ & SoD Đa tầng | [assignment-permission.md](assignment-permission.md) | **4.8 / 10** | CRITICAL (spoof actor, fail-open FE, 7 điểm lệch quyền BE↔Client) |
| 12 | Danh mục 164 API & Đối chiếu Hợp đồng 1:1 | [api-inventory.md](api-inventory.md) | **5.6 / 10** | HIGH (72.7% body thiếu `@Valid`, 40 API `Map`, 5 lỗi mapping nặng) |
| 13 | Hoàn thiện Tính năng Đa nền tảng | [feature-matrix.md](feature-matrix.md) | **5.4 / 10** | HIGH (lệch parity Vue/React/Mobile, 0% Zod/Confirm/Pagination/E2E) |
| — | **Trung bình Backend (8 trụ cột)** | — | **4.81 / 10** | **Chặn Go-Live** |
| — | **Trung bình Client, Quyền & API (5 trụ cột)** | — | **5.32 / 10** | **Chặn Go-Live** |
| — | **TỔNG ĐIỂM TOÀN HỆ THỐNG (13 trụ cột)** | — | **5.01 / 10** | **CHƯA SẴN SÀNG PRODUCTION** |

---

## 3. Top 10 Rủi ro Chí mạng (P0 Go-Live Blockers)

1. **Hở xác thực 6/7 API S2S nội bộ (`TD-C01`)**: `@SaIgnore` cấp class trên `BudgetInternalController.java:45` vô hiệu hóa `SaSameUtil` cho 6 endpoint ghi/đọc nhạy cảm; `SA_TOKEN_SAME_SECRET` để rỗng & tắt kiểm tra Redis.
2. **Lộ bí mật thật trong Git & `Jenkinsfile` deploy nhầm dịch vụ (`TD-C02`)**: Lộ token Sonatype Maven Central, Nexus, Telegram, DB/Redis; `Jenkinsfile:161-179` triển khai sang cổng `8306` (`dev-planning`) với quyền `root`.
3. **Giả mạo người phê duyệt & 0% kiểm soát SoD 4 mắt (`TD-C03`)**: `BudgetIntegrationOrchestrator:119-176` đọc `actorId`/`actorName` từ HTTP body trước `LoginHelper`; không chặn người lập tự duyệt trên cả 4 thực thể workflow.
4. **IDOR xuyên phòng ban / đơn vị (`TD-C04`)**: Không tiêm `OrgSubtreeAuthorizationHelper` ở bất kỳ lớp Business nào, cho phép user cùng `appCode` thao tác ngân sách của đơn vị khác.
5. **TOCTOU Double-Spend & Bypass Phê duyệt (`TD-C05`, `TD-C06`)**: Kiểm tra vượt dự toán không khóa `PESSIMISTIC_WRITE`, duyệt chi không tái kiểm số dư, tiềm ẩn AB-BA deadlock, `create()` cho truyền sẵn `APPROVED`/`SETTLED` và `update()`/`delete()` không khóa chứng từ đã quyết toán.
6. **Sai lệch Toán học Sổ cái & Mâu thuẫn Enum–Validator (`TD-C07`)**: Đếm trùng `ADVANCE_SETTLEMENT`, `NV-02` tự tạo phân bổ nguồn `APPROVED` từ hư không, `DX-03` hardcode cap 3 đơn vị + `.abs()`, `DX-04` cho quỹ âm & phân phối vô hạn, `RELEASE_HOLD`/`PENDING`/`UNIT_SELF` làm kẹt Validator.
7. **Flyway vô hiệu trên Spring Boot 4 & Schema Drift nặng (`TD-C09`, `TD-C10`)**: Thiếu `spring-boot-starter-flyway`, tắt Flyway bật `ddl-auto=update`, lệch 40+ cột `NUMERIC(18,2)` vs `19,2`, 15 cột mồ côi, 0 `FOREIGN KEY` và 0 `CHECK` constraint số dư.
8. **Đứt gãy BPMN Workflow & Lệch DTO Webhook Thanh toán (`TD-C08`, `TD-C11`)**: 4/4 callback BPMN trả 404; `settleFromPaymentWebhook` đọc 3 khóa luôn `null`, bỏ qua `paidAmount`/`paymentStatus` (thanh toán thất bại vẫn đóng dấu `SETTLED`); không có Outbox & báo `"synced": true` giả cho Planning.
9. **Fail-Open RBAC, Kéo-thả Mất Nhánh & Paste Excel Nhân 10x trên Web FE (`TD-C12`, `TD-C13`, `TD-C14`)**: `hasBudgetPermission` trả `true` khi `undefined` (hỏng trên Pure Cookie Mode + React async flash); kéo cha vào `before`/`after` con xóa vĩnh viễn cả nhánh cây; paste `"1500,5"` biến thành `15005` và số âm `"-1.500.000"` biến thành `0`.
10. **Mobile `Future.wait(14)` Sập Toàn Màn Hình & Sai 4 Khóa JSON (`TD-C15`, `TD-H16`)**: Thiếu 1 quyền gây 403 làm trắng sạch toàn bộ 5 tab; `BudgetUnitConfigModel` sai 4 khóa JSON nên luôn hiện `0đ`; tự bịa `id` giả sau thao tác 1 chạm và không có xác nhận 2 bước cho duyệt chi tiền tỷ.

---

## 4. Điểm sáng Kiến trúc Cần Bảo tồn

- **Lưu trữ tiền tệ bằng `BigDecimal` / `NUMERIC`** ở toàn bộ 7 Entities và CSDL (không có cột `FLOAT`/`DOUBLE`).
- **An toàn trước SpEL RCE & SQL Injection**: `BudgetEstimateFormulaValidator` dùng regex whitelist thuần; xuất Excel dùng `GlobalExcelService` kèm `FormulaSanitizingWriteHandler` chống Excel Formula Injection (`CWE-1236`).
- **Tuân thủ `LP-020` ở tầng Backend Controller**: Không gắn `@SaSharedLookup` trên Controller nghiệp vụ, tách đủ 7 endpoint `GET /*/options` trả về `RefData<K>` bảo vệ bằng `SaMode.OR` và ẩn số tài khoản ngân hàng/MST (có test xác minh trong `BudgetAuditRemediationTest`).
- **Chặn ghi chéo `appCode` ở JPA**: Cả 7 Entities gắn `AppIsolatedListener` và `@Filter("appCodeFilter")`.
- **Web FE & Mobile không có lỗ hổng XSS và sạch lỗi biên dịch tĩnh**: 0 lượt `v-html`/`dangerouslySetInnerHTML`; `vue-tsc`, `tsc` (React 19) và `flutter analyze` đều đạt **0 lỗi**; Mobile `budget_permission_helper.dart` triển khai đúng nguyên tắc **fail-closed**.

---

## 5. Mục lục Đầy đủ 16 Tài liệu Kiểm toán

| # | Tài liệu | Phạm vi & Nội dung chính |
| --- | --- | --- |
| 1 | [README.md](README.md) | Báo cáo Điều hành (Executive Summary), bảng điểm 13 trụ cột, Top 10 rủi ro P0 & mục lục 16 tài liệu |
| 2 | [architecture.md](architecture.md) | Kiến trúc 5 tầng, cấu hình đa tenant, Gateway `/v1/budget`, Docker & CI/CD `Jenkinsfile` |
| 3 | [backend-analysis.md](backend-analysis.md) | 9 Controllers, 11 Business, TOCTOU locking, bất biến sổ cái, Enum–Validator, Formula DSL & Maven Tests |
| 4 | [business-analysis.md](business-analysis.md) | Tuân thủ Luật NSNN 2015, NĐ 60/2021, Mục lục TT 324, 9 nghiệp vụ `NV-01..DX-04`, KBNN & Báo cáo |
| 5 | [workflow-analysis.md](workflow-analysis.md) | Phê duyệt 2 cấp, kiểm soát SoD, giả mạo danh tính, BPMN `tr-workflow-service` & bộ quét SLA |
| 6 | [database-analysis.md](database-analysis.md) | 7 Entities, Flyway `V303_001..003` trên Spring Boot 4, Schema Drift, Locking, `app_code` & Indexes |
| 7 | [microservices-integration.md](microservices-integration.md) | Tích hợp Planning, Payment (Webhook/Kafka), Workflow, Directory, Document, Reporting, AI & Outbox |
| 8 | [security-performance.md](security-performance.md) | OWASP Top 10, Sa-Token/S2S, ABAC/IDOR, Secrets, Injection, N+1, `findAll()` & Redis Caching |
| 9 | [dependency-map.md](dependency-map.md) | `pom.xml`, 13 module `tr-common-*`, Feign Clients đi/đến, Kafka Topics, Redis Keys & Call Graph |
| 10 | [frontend-analysis.md](frontend-analysis.md) | Kiến trúc `@tyran8x/feature-budget`, lõi cây dự toán & Excel paste, đối chiếu 8 màn hình Vue 3 vs React 19 |
| 11 | [mobile-analysis.md](mobile-analysis.md) | Kiến trúc Flutter `budget` (12 tệp, 5 tab), `Future.wait(14)`, contract drift, UX tài chính & `flutter test` |
| 12 | [assignment-permission.md](assignment-permission.md) | Ma trận đầy đủ 107 mã `@SaCheckPermission` (BE ↔ FE ↔ Mobile), Tự chủ NĐ 60, SoD & `LP-020`/`LP-021` |
| 13 | [api-inventory.md](api-inventory.md) | Kiểm kê chi tiết 100% **164 REST Endpoints** (9 Controllers) đối chiếu 1:1 BE ↔ FE ↔ Mobile |
| 14 | [feature-matrix.md](feature-matrix.md) | Ma trận hoàn thiện đa nền tảng (BE / Vue 3 / React 19 / Mobile / Test / Production Readiness) |
| 15 | [technical-debt.md](technical-debt.md) | Sổ đăng ký hợp nhất **48 khoản nợ kỹ thuật (`TD-C01`..`TD-L03`)** có truy xuất `file:line` |
| 16 | [improvement-roadmap.md](improvement-roadmap.md) | Lộ trình khắc phục 4 giai đoạn **`RM-1`..`RM-4` (12 tuần)** kèm ước lượng công & cổng nghiệm thu Go-Live |
