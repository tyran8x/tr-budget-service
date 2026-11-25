# Ma trận Hoàn thiện Tính năng Đa nền tảng (`tr-budget-service`)

> **Phạm vi đánh giá**: Đối chiếu độ hoàn thiện theo từng phân hệ nghiệp vụ giữa **Backend (Spring Boot 4 / Java 25)**, **Frontend Vue 3 (Element Plus)**, **Frontend React 19 (Ant Design)**, **Mobile Flutter (`apps/dds_app`)**, **Kiểm thử Tự động**, và **Mức độ Sẵn sàng Production (Production Readiness)**.
> **Điểm trưởng thành Toàn diện Client & Tích hợp**: **5.4 / 10** *(Chức năng nghiệp vụ tài chính công rất rộng từ Cây dự toán, Tự chủ NĐ 60, Sổ cái 9 nghiệp vụ đến DVC KBNN, nhưng bị cản trở go-live bởi lỗi hợp đồng, fail-open RBAC, thiếu SoD và độ lệch giữa các nền tảng)*.

---

## 1. Ma trận Hoàn thiện Theo Miền Nghiệp vụ

> **Ký hiệu mức độ**:
> - `Đầy đủ`: Đã triển khai chạy được end-to-end, hợp đồng khớp.
> - `Một phần / Có lỗi`: Đã có UI/API nhưng còn lỗi hợp đồng, lệch quyền hoặc thiếu trường quan trọng.
> - `Chưa có`: Chưa hỗ trợ trên nền tảng tương ứng.

| STT | Miền Nghiệp vụ / Tính năng | Backend (`tr-budget-service`) | Vue 3 (`src/vue`) | React 19 (`src/react`) | Mobile Flutter (`dds_app`) | Kiểm thử Tự động | Sẵn sàng Production |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | **Dashboard Điều hành KPI, Scorecard, Burn-rate, MTEF & What-If** | Đầy đủ (10 API, thiếu `@Valid` 3 API) | Một phần (chưa vẽ biểu đồ ECharts dù khai báo lib) | Một phần (chưa vẽ biểu đồ, khác payload `reallocation` với Vue) | Một phần (thiếu `unit-matrix` & `mtef`, GoRouter chặn user chỉ có `DashboardQuery`) | Unit FE + 1 test Mobile | **Chưa đạt** (lệch quyền router Mobile, thiếu biểu đồ trực quan) |
| 2 | **Quản lý Phương án Phân bổ & Tự phê duyệt NĐ 60** | Một phần (thiếu SoD, cho spoof `actorId`, lệch enum `UNIT_SELF`) | Một phần (rơi 8 field modal tạo, timeline BPMN tĩnh cứng, sai quyền `syncToPlan`) | Một phần (timeline BPMN động đúng, thiếu status board, sai quyền `syncToPlan`) | Một phần (chỉ xem/duyệt/từ chối/đồng bộ, sai quyền `syncToPlan`, không có xác nhận 2 bước) | Vitest workflow + Mobile test | **Chặn Go-Live** (`PERM-C01`, `PERM-C02`, `FE-H06`, `FE-H07`) |
| 3 | **Cây Dự toán Thu - Chi & Công thức DSL** | Đầy đủ (19 API, `move` dễ 500 khi parse sai) | Một phần (**lỗi mất nhánh khi kéo-thả `FE-C01`**, DSL bỏ qua `formulaExpression`) | Một phần (**lỗi mất nhánh `FE-C01`**, thiếu `TrCurrencyInput` batch) | Chỉ xem read-only (`revenue-expenditure-tree`) | Vitest tree/batch (chưa bắt được `FE-C01`) | **Chặn Go-Live** (`FE-C01` mất dữ liệu cây) |
| 4 | **Sổ cái Biến động Kép & 9 Nghiệp vụ Điều chỉnh (`NV-01..DX-04`)** | Một phần (auto-approve phá vỡ SoD, `resolveBudgetPersonaRole` đòi role cứng) | Một phần (**thiếu form cho 7/9 nghiệp vụ**, sai quyền `DX-01`, import ngược từ React) | Một phần (**thiếu form cho 7/9 nghiệp vụ**, sai quyền `DX-01`) | Một phần (gửi tiền mẫu hàng tỷ, sai quyền 9 chip, bịa ID giao dịch giả) | Chưa test nghiệp vụ phụ thực tế | **Chặn Go-Live** (`FE-H08`, `MOB-H01`, `MOB-H05`, `PERM-H01`) |
| 5 | **Cam kết chi & Giải ngân Kho bạc (KBNN / C2-02a/NS)** | Một phần (thiếu SoD duyệt chi & status guard) | Một phần (thiếu nút Sửa, thiếu pre-check, `reserveCommitment` sai endpoint, sai quyền DVC KBNN) | Một phần (nút Sửa không map field `FE-H04`, `reserveCommitment` sai endpoint, sai quyền DVC KBNN) | Một phần (điền sẵn `1,25 tỷ` & `allocationId=1`, tự sinh `KBNN-DN-*`, sai quyền C2-02a/NS) | Mobile test cơ bản (mock không bắt lệch field) | **Chặn Go-Live** (`FE-H03`, `FE-H04`, `MOB-H06`, `PERM-C02`) |
| 6 | **Báo cáo Định kỳ, Ma trận Lĩnh vực Động & Đối chiếu KBNN** | Một phần (đối chiếu KBNN dùng trùng quyền `ReportSubmit`, thiếu status guard) | Một phần (dòng điều chỉnh chỉ lưu RAM, hardcode `SEMI_ANNUAL` & mốc `10-23`/`10-30`, thiếu Export/Rollback) | Một phần (tương tự Vue) | Một phần (nút duyệt không lọc trạng thái, hardcode `2026-10-23`, chưa nối nút đối chiếu KBNN) | Mobile test cơ bản | **Chưa đạt** (mất dữ liệu dòng tạm, hardcode kỳ/ngày) |
| 7 | **Mục lục NSNN & Cấu hình Lĩnh vực theo Năm** | Đầy đủ (17 API; `BudgetDataInitializer` seed giá trị ngoài enum `DEDICATED_FUND`) | Một phần (**lỗi gửi nhãn tiếng Việt vào `autonomyType` cả Tạo & Sửa**, lệch cột `Loại`/`Khoản`) | Một phần (**lỗi gửi nhãn tiếng Việt khi Sửa**, lệch cột `Loại`/`Khoản`) | Chỉ xem & nhân bản năm lĩnh vực | Chưa có test riêng | **Chặn Go-Live** (`FE-H02` làm hỏng dữ liệu danh mục) |
| 8 | **Cấu hình Tự chủ Đơn vị (`BudgetUnitConfig`)** | Đầy đủ (15 API, có `check-autonomy`) | Một phần (gửi `allowSelfApprove`, thiếu 7 trường quản trị/KBNN thực) | Một phần (tương tự Vue) | **Hỏng parse JSON** (sai 4 khóa nên luôn hiện `GROUP_2` / `0đ`) | Chưa có contract test | **Chặn Go-Live** (`FE-H05`, `MOB-H03`) |
| 9 | **Import / Export Excel, Spreadsheet Paste & Audit Rollback (JaVers)** | Đầy đủ (7× `GlobalExcelService` + 7× `@JaversSpringDataAuditable`) | Một phần (**lỗi nhân 10x số thập phân & mất số âm** khi paste Excel `FE-C02`; đóng modal sớm; thiếu Rollback ở 3/7 màn hình) | Một phần (**lỗi `FE-C02`**, thiếu `try/catch` tải Excel, thiếu `TrCurrencyInput`, thiếu Rollback ở 3/7 màn hình) | Chưa có | Vitest paste engine (chưa có test case `1500,5` & `-1.500.000`) | **Chặn Go-Live** (`FE-C02` sai lệch số liệu tài chính) |
| 10 | **Phân quyền RBAC, SoD & Bảo mật Đa tầng** | Một phần (107 mã quyền, 0% `@SaSharedLookup`, thiếu SoD, spoof `actorId`) | **Rủi ro cao** (fail-open RBAC, 0% `v-access`, 4 lệch quyền nút) | **Rủi ro cao** (async flash + fail-open RBAC, 4 lệch quyền nút) | Một phần (Fail-closed tốt, nhưng `Future.wait(14)` sập khi 403 & 4 lệch quyền) | Route contract test + Mobile fail-closed test | **Chặn Go-Live** (`PERM-C01..C03`, `MOB-C01`) |
| 11 | **Tích hợp Liên dịch vụ (Planning, Payment, Workflow, Notification, Analytics)** | Có đủ 7 S2S endpoint + orchestrator (6/7 thiếu gọi `SaSameUtil` tường minh) | Có gọi `sync-to-plan` (sai quyền guard) | Có gọi `sync-to-plan` (sai quyền guard) | Có gọi `sync-to-plan` (sai quyền guard) | Chưa có contract/integration test | **Chưa đạt** (`PERM-H03`, `FE-H07`) |
| 12 | **Trải nghiệm Doanh nghiệp (Validation Zod, Xác nhận Xóa, Phân trang, i18n, Offline)** | Có phân trang BE (21 API), Bean Validation mới đạt 27.3% body API | 0% Zod, 0% confirm xóa/từ chối, 0% phân trang server, 0% i18n views, rò dữ liệu demo | Tương tự Vue + thiếu 100% component tests | 0% confirm/biometric tiền tỷ, 0% pull-to-refresh, 0% phân trang, 0% offline cache AES-256 | 27 Vitest (Vue+Core) + 6 Flutter test; **0 React test, 0 E2E** | **Chưa đạt** |

---

## 2. Mức độ Sẵn sàng Theo Nền tảng & Rào cản Go-Live

| Nền tảng | Tỷ lệ độ phủ API Public (157) | Kiểm chứng Build / Test Trực tiếp | Điểm Trưởng thành | Rào cản Go-Live Bắt buộc (P0) |
| --- | --- | --- | --- | --- |
| **Frontend Vue 3** | 156/157 wrapped (~105 UI) | `vue-tsc` **0 lỗi**; Vitest **27/27 PASS** *(nhưng >4.400 dòng Vue warn)* | **5.9 / 10** | Sửa `FE-C01` (kéo-thả mất nhánh), `FE-C02` (paste Excel 10x/âm), `FE-C03` (fail-open RBAC), `FE-H02..H11` |
| **Frontend React 19** | 156/157 wrapped (~100 UI) | `tsc -p tsconfig.react.json` **0 lỗi**; **0 component tests** | **5.6 / 10** | Sửa `FE-C01..C03` (đặc biệt async flash RBAC), `FE-H04` (`updateDisbursement`), `FE-H02/H03/H05/H08`, bổ sung test React |
| **Mobile Flutter (`dds_app`)** | 45/157 wrapped (41 UI) | `flutter analyze` **0 lỗi**; `flutter test` **6/6 PASS** | **5.0 / 10** | Sửa `MOB-C01` (tách `Future.wait(14)` theo quyền & partial error), `MOB-H01..H09`, thêm xác nhận 2 bước |
| **Hợp đồng API & Phân quyền** | 164 BE APIs / 107 Permissions | Đối chiếu tự động 100% 9 Controller vs FE & Mobile | **5.2 / 10** | Chặn spoof `actorId` (`PERM-C01`), bổ sung SoD 4 mắt (`PERM-C02`), đồng bộ 7 điểm lệch quyền & chuyển sang `@SaSharedLookup` |
