# Phân tích Phân quyền, Tự chủ Đơn vị & Phân tách Nhiệm vụ (SoD)

> **Phạm vi kiểm toán**: Toàn bộ **107 mã `@SaCheckPermission`** trên 9 REST Controller của `tr-budget-service`, cấu hình `config/_base/tr-budget-service.yml`, logic phân quyền tự chủ `BudgetUnitConfig` & `BudgetTransactionBusiness.evaluateOperationRoleGate`, lớp kiểm tra quyền FE (`packages/@features/budget/src/core/permissions.ts`, `src/routes/**`, `use-budget-access.ts`), và lớp phân quyền Mobile (`packages/shared/.../permission_constants.dart`, `budget_permission_helper.dart`).
> **Điểm trưởng thành Phân quyền & SoD**: **4.8 / 10** *(Hệ thống định nghĩa đủ 107 mã quyền chi tiết cho 157/164 endpoint nghiệp vụ, có khung tự chủ NĐ 60/2021 và tuân thủ đúng chuẩn `LP-020` khi không gắn `@SaSharedLookup` ở Controller nghiệp vụ mà tách `/options` trả về `RefData<K>`, nhưng vướng lỗ hổng nghiêm trọng: giả mạo danh tính người phê duyệt từ request body, thiếu kiểm soát SoD 4 mắt ở tầng Business, FE fail-open RBAC, FE không gọi `/options` mà gọi `GET /{resource}`, và nhiều mã quyền lệch giữa BE ↔ FE ↔ Mobile)*.

---

## 1. Tổng quan Bề mặt Phân quyền Đa tầng

| Tầng hệ thống | Quy mô mã quyền | Cơ chế thực thi | Trạng thái bảo mật |
| --- | --- | --- | --- |
| **Backend (`tr-budget-service`)** | **107 mã `@SaCheckPermission`** (trên 157 endpoint) + 7 endpoint `@SaIgnore` trong `BudgetInternalController` | Sa-Token annotation + `evaluateOperationRoleGate` (riêng nghiệp vụ sổ cái) | **Rủi ro Cao**: thiếu SoD creator ≠ approver, cho phép spoof `approvedByPersonId`/`actorName` từ body, 6/7 endpoint S2S mất `SaSameUtil` do `@SaIgnore` *(Điểm tốt: tuân thủ `LP-020`, không gắn `@SaSharedLookup` ở business controller)* |
| **Frontend (`@tyran8x/feature-budget`)** | **73 / 107 mã** trong `src/core/permissions.ts` + **2 mã ma** trong `src/core/constants.ts` | `useBudgetAccess().can(...)` (Vue & React); **0 lượt `v-access`** | **Rủi ro Nghiêm trọng**: `hasBudgetPermission` **fail-open** (`undefined → true`), React nhấp nháy toàn quyền ở khung hình đầu, 4 điểm lệch quyền nút vs BE |
| **Mobile Flutter (`apps/dds_app`)** | **20 / 107 mã** trong `PermissionConstants` + `budget_permission_helper.dart` | `canBudgetAction(...)` (**Fail-closed** — Tốt) + GoRouter `budgetAny` | **Rủi ro Cao**: `budgetAny` bỏ sót `BudgetDashboardQuery`, 3 điểm lệch quyền nút vs BE, thiếu kiểm tra trạng thái khi phê duyệt báo cáo |

---

## 2. Lỗ hổng Trọng yếu về Phân quyền, Tự chủ & Phân tách Nhiệm vụ (SoD)

### 2.1. Giả mạo Danh tính Người Phê duyệt qua Request Body (`PERM-C01`)

Tại `tr-business-services/tr-budget-service/src/main/java/vn/tr/budget/business/helper/BudgetIntegrationOrchestrator.java:119-127,164-176`:

- Cả `resolveCurrentActorName(body, ...)` và `resolveCurrentPersonId(body, ...)` đều **ưu tiên đọc trực tiếp `approvedByPersonId`, `submittedByPersonId`, `actorId`, `actorName` từ `Map<String, Object> body` của client gửi lên** trước khi fallback sang `LoginHelper.getLoginUser()` / `UnifiedIdentityResolver.resolveCurrentUser()`.
- Các hàm gọi trực tiếp bao gồm:
  - `BudgetAllocationBusiness.java:352,397,439` (`submitAllocation`, `selfApproveAllocation`, `approveAllocation`)
  - `BudgetDisbursementBusiness.java:440,448` (`approveDisbursement`)
  - `BudgetReportBusiness.java:479,484,581,585` (`generateConsolidatedReport`, `submitReport`)
- **Tác động**: Bất kỳ người dùng đã đăng nhập nào có quyền gọi endpoint đều có thể truyền `{"approvedByPersonId": 1, "actorName": "Giám đốc Sở Tài chính"}` vào JSON body để **làm sai lệch hoàn toàn danh tính người ký duyệt trong DB và dấu vết kiểm toán tài chính**.

### 2.2. Thiếu Kiểm soát Phân tách Nhiệm vụ (SoD — Four-Eyes Principle) & Chuyển Trạng thái (`PERM-C02`)

1. **Phân bổ, Giải ngân & Báo cáo không kiểm tra `creator != approver` và không khóa máy trạng thái**:
   - `BudgetAllocationBusiness.java:424-465` (`approveAllocation`): không so sánh người trình (`submittedByPersonId`) với người duyệt hiện tại và **không kiểm tra `workflowStatus == PENDING_APPROVAL`** (cho phép duyệt thẳng hồ sơ `DRAFT` hoặc `REJECTED`).
   - `BudgetDisbursementBusiness.java:425-475` (`approveDisbursement`): không kiểm tra `requestedByPersonId != currentPersonId` và không kiểm tra trạng thái hiện tại (có thể phê duyệt lại chứng từ đã `REJECTED` hoặc `SETTLED`).
   - `BudgetReportBusiness.java:571-608` (`submitReport`, `rejectReport`) & `BudgetReportController.java:213` (`confirmTreasuryReconciliation`): không có kiểm soát SoD; riêng đối chiếu Kho bạc (`POST /reports/{id}/treasury-reconciliation`) gắn quyền `BudgetReportSubmit` — trùng chính người lập/trình báo cáo!
2. **Tự khởi tạo & tự duyệt một bước trong nghiệp vụ Sổ cái (`NV-01..DX-04`)**:
   - `BudgetTransactionBusiness.java:2252-2253` (`evaluateOperationRoleGate`): nếu người khởi tạo thuộc vai trò `STC_DIRECTOR`, `SuperAdmin`, `UNIT_DIRECTOR` (cho `NV-03`, `DX-04`) hoặc `TREASURY_RECONCILER` (cho `NV-02`, `NV-05`), hệ thống đặt `canAutoApprove = true` → **`effectiveApprovalStatus = "APPROVED"` ngay trong bước khởi tạo**, bỏ qua hoàn toàn cơ chế 2 người ký duyệt (Maker-Checker).
3. **Bẫy xung đột giữa `@SaCheckPermission` và vai trò cứng trong `resolveBudgetPersonaRole` (`PERM-H01`)**:
   - `BudgetTransactionBusiness.java:2272-2305`: Sau khi người dùng đã vượt qua `@SaCheckPermission("BudgetTransactionSubmit")` ở Controller (cho `NV-01..NV-05`, `DX-01`), tầng Business lại chỉ ánh xạ 6 chuỗi vai trò cứng (`ROLE_BUDGET_DIRECTORATE`, `ROLE_BUDGET_FINANCE_APPRAISER`, `ROLE_BUDGET_UNIT_HEAD`, `ROLE_BUDGET_UNIT_ACCOUNTANT`, `ROLE_BUDGET_SPECIALIST`, `TREASURY_RECONCILER`) hoặc fallback bằng `BudgetTransactionApprove`.
   - Nếu một chuyên viên chỉ được cấp quyền `BudgetTransactionSubmit` qua ma trận phân quyền chuẩn mà không gán các mã role cứng cũ này, hàm trả về `null` và ném lỗi `BaseException` từ chối thao tác dù quyền Sa-Token hợp lệ!

### 2.3. Lỗ hổng Fail-Open RBAC trên Frontend (`PERM-C03` / `LP-021`)

- `src/core/permissions.ts:146-174`: `hasBudgetPermission` trả về **`true`** khi `accessCodes` là `null` hoặc `undefined`. Đồng thời `resolveBudgetAccessCodes` trả về `undefined` khi Pinia store chưa kiểm tra quyền và `accessToken` trống (trường hợp chuẩn của **Pure Cookie Mode** dùng `_tr_sec_sid`) → **mở khóa toàn bộ nút nhạy cảm cho người dùng**.
- `src/react/hooks/use-budget-access.ts:16-54`: `storeAccessCodes` khởi tạo là `undefined` và nạp bất đồng bộ bằng `await import('@tyran8x/react-access')` trong `useEffect` → render đầu tiên luôn hiện full nút quyền; nếu import thất bại (`L40` nuốt lỗi), người dùng có toàn quyền UI vĩnh viễn.

### 2.4. Tuân thủ `LP-020` (`/options` vs `@SaSharedLookup`) & Chênh lệch `BudgetUnitConfig` (`PERM-H02`, `PERM-H03`, `PERM-H04`)

1. **Đánh giá đúng chuẩn `LP-020` / `lookup-security-audit` (Điểm sáng BE & Khoảng trống Client — `PERM-H02`)**:
   - Theo quy chuẩn `.agent/skills/lookup-security-audit/SKILL.md:16-20`, với **phân hệ nghiệp vụ (`tr-business-services/*`)**, hệ thống **TUYỆT ĐỐI CẤM gắn `@SaSharedLookup` ở cấp Controller** và **BẮT BUỘC tách `GET /{resource}/options` trả về `RefData<K>` kèm `@SaCheckPermission(mode = SaMode.OR)`**.
   - **Điểm đạt chuẩn trên BE**: `tr-budget-service` có **0 `@SaSharedLookup`**, đã tách đủ 7 endpoint `GET /*/options` trả về `RefData<K>` bảo vệ bằng `SaMode.OR` và ẩn số tài khoản ngân hàng/MST (đã có test kiểm chứng trong `BudgetAuditRemediationTest`).
   - **Khoảng trống thực sự cần khắc phục**: (a) Toàn bộ UI Frontend (`src/vue` & `src/react`) **chưa gọi bất kỳ endpoint `/options` nào** mà gọi thẳng `GET /{resource}` full list — ép phải cấp thừa quyền `*List` xuyên màn hình; (b) 2 endpoint `POST /dashboard/validate-target-scope` (`L80`) và `POST /dashboard/ai-advisor` (`L106`) dùng phương thức `POST` nhưng gắn hậu tố đọc `BudgetDashboardQuery`; (c) danh sách OR 6–25 mã chứa 14 mã `*Options`/`*View` chưa đồng bộ vào FE/Mobile.
2. **Bảo vệ S2S `BudgetInternalController` chưa đồng đều (`PERM-H03`)**:
   - `BudgetInternalController.java:45` đặt `@SaIgnore` ở cấp lớp cho cả 7 endpoint `/internal/budget/**`, nhưng **chỉ duy nhất 1/7 method** (`getInternalAnalyticsMetrics`, `L125`) gọi tường minh `SaSameUtil.checkCurrentRequestToken()`. 6 endpoint ghi dữ liệu quan trọng (gồm `sync-from-plan`, `record`, `reserve-commitment`, `payment-webhook-settle`) chỉ phụ thuộc vào bộ lọc đường dẫn bên ngoài thay vì phòng vệ đa tầng (defense-in-depth).
3. **Bất đồng bộ quyền Tự chủ Đơn vị `BudgetUnitConfig` theo NĐ 60/2021 (`PERM-H04`)**:
   - Backend (`BudgetAllocationBusiness.java:385-391`) kiểm tra `workflowEngineHelper.checkUnitAutonomy(nodeId, categoryCode, initialAmount)` dựa trên `canSelfAllocate`, `selfApproveLimitAmount`, `unitType` và `allowCarryOver`.
   - Tuy nhiên, trên Web FE (`facade-disbursements-reports.ts:285-312`), màn hình cấu hình đơn vị lại gửi trường không tồn tại `allowSelfApprove` và **không hiển thị/không gửi `canSelfAllocate`, `canSelfTransfer`, `approverPersonId`, `chiefAccountantPersonId`**; còn trên Mobile (`budget_types.dart:935-941`), model đọc sai 4 tên khóa JSON nên luôn hiển thị hạn mức tự duyệt `0đ` và nhóm `GROUP_2`.
   - Ngoài ra, `BudgetAllocationBusiness.java:122,392` ghi chuỗi `approvalScope = "UNIT_SELF"` / `"DIRECTORATE"`, mâu thuẫn với enum `BudgetApprovalScope.java` (`UNIT_SELF_APPROVAL`, `SUPERIOR_APPROVAL`, `TREASURY_RECONCILER` / `TREASURY_RECONCILED`).

---

## 3. Bảng Đối chiếu Các Điểm Lệch Mã Quyền Trực tiếp (Gây HTTP 403 / Chặn Sai)

| # | Tác vụ / Màn hình | Quyền yêu cầu trên Backend | Quyền đang kiểm tra trên Client | Nền tảng bị ảnh hưởng | Hậu quả thực tế |
| --- | --- | --- | --- | --- | --- |
| 1 | Đồng bộ Kế hoạch (`POST /allocations/{id}/sync-to-plan`) | `BudgetAllocationApprove` (`BudgetAllocationController.java:271`) | `BudgetAllocationEdit` | Vue (`use-budget-allocation-workspace.ts:367`), React (`BudgetAllocationWorkspaceView.tsx:268`), Mobile (`budget_allocations_tab.dart:38,196`) | Chuyên viên có quyền Edit thấy nút nhưng bấm bị **403**; Lãnh đạo chỉ có quyền Approve bị **ẩn nút** |
| 2 | Xuất gói DVC KBNN C2-02a/NS (`GET /disbursements/{id}/kbnn-dvc-package`) | `BudgetDisbursementDetail OR BudgetDisbursementApprove` (`BudgetDisbursementController.java:253-256`) | `BudgetDisbursementExport` | Vue (`use-budget-disbursement.ts:218`), React (`BudgetDisbursementView.tsx:215`), Mobile (`budget_disbursements_tab.dart:150,295`) | Người có quyền Export nhưng thiếu Detail/Approve bấm bị **403**; Lãnh đạo có quyền Approve bị **ẩn nút** |
| 3 | Tạm giữ / Khoanh dự toán `DX-01` (`POST /transactions/earmark-hold`) | `BudgetTransactionSubmit` (`BudgetTransactionController.java:293`) | `BudgetTransactionApprove` (FE), `BudgetTransactionCreate` (Mobile) | Vue + React (`budget-ledger-constants.ts:104`), Mobile (`budget_ledger_tab.dart:141`) | Chuyên viên Submit bị khóa nút trên Web; người có Create bấm trên Mobile bị **403** |
| 4 | 8 nghiệp vụ phụ Sổ cái khác (`NV-01..NV-05`, `DX-02..DX-04`) | `BudgetTransactionSubmit` (5 NV) / `BudgetTransactionApprove` (3 DX) | `BudgetTransactionCreate` | Mobile (`budget_ledger_tab.dart:141,149`) | Người chỉ có Create thấy toàn bộ 9 chip nhưng bấm đều bị **403** |
| 5 | Lưu ma trận cây phân bổ (`POST /allocations/tree-matrix`) | `BudgetAllocationCreate OR BudgetAllocationEdit` (`BudgetAllocationController.java:297`) | Chỉ kiểm tra `BudgetAllocationCreate` trong `handleSaveMatrix` | Vue (`BudgetAllocationTreeMatrixPanel.vue:123`) | Người chỉ có quyền Edit sửa được lưới nhưng bấm Lưu bị chặn cục bộ |
| 6 | Vào màn hình Dashboard trên Mobile (`/budget/dashboard`) | `BudgetDashboardQuery` (`BudgetDashboardController.java:36`) | GoRouter kiểm tra `PermissionConstants.budgetAny` (**thiếu `BudgetDashboardQuery`**) | Mobile (`permission_constants.dart:629-660`, `app_router.dart:237`) | Lãnh đạo chỉ có quyền xem Dashboard bị GoRouter **chặn không vào được module** |
| 7 | Xem lịch sử thay đổi (Audit History) | Dùng `*Rollback` hoặc `GlobalAuditController` | 2 mã ma `BudgetAllocationHistory`, `BudgetDisbursementHistory` | FE (`src/core/constants.ts:242,252`) | Mã quyền không tồn tại trên Backend → luôn trả về không có quyền khi bật strict RBAC |

---

## 4. Ma trận Đầy đủ 107 Mã `@SaCheckPermission` (BE ↔ FE ↔ Mobile)

Bảng dưới đây thống kê **đủ 107/107 mã quyền** đã trích xuất tự động từ 9 Controller của `tr-budget-service`, đối chiếu với `packages/@features/budget/src/core/permissions.ts` (**73 mã**), `packages/shared/.../permission_constants.dart` (**20 mã**) và trạng thái sử dụng thực tế.

### 4.1. Nhóm Thực thể Chính (8 Nhóm Controller = 107 Mã Duy nhất)

| Nhóm thực thể (Tổng mã BE) | Mã có ở cả BE + FE + Mobile | Mã có ở BE + FE (Thiếu trên Mobile) | Mã chỉ có trên BE (Thiếu cả FE & Mobile) |
| --- | --- | --- | --- |
| **1. BudgetAllocation (17 mã)** | `List`, `Create`, `Edit`, `Approve`, `SelfApprove` (5) | `Detail`, `Delete`, `Export`, `Import`, `Rollback`, `Submit`, `Reject` (7) | `BatchDelete`, `Clone`, `Options`, `Sort`, `View` (5) |
| **2. BudgetCategory (13 mã)** | `List` (1) | `Detail`, `Create`, `Edit`, `Delete`, `Export`, `Import`, `Rollback` (7) | `BatchDelete`, `Clone`, `Options`, `Sort`, `View` (5) |
| **3. BudgetDashboard (2 mã)** | *(0 — thiếu hoàn toàn trên Mobile)* | `Query`, `Simulate` (2) | *(0)* |
| **4. BudgetDisbursement (16 mã)** | `List`, `Create`, `Edit`, `Approve`, `Settle`, `Export` (6) | `Detail`, `Delete`, `Import`, `Rollback`, `Reject` (5) | `BatchDelete`, `Clone`, `Options`, `Sort`, `View` (5) |
| **5. BudgetEstimateItem (13 mã)** | *(0 — Mobile dùng `dashboard/revenue-expenditure-tree`)* | `List`, `Detail`, `Create`, `Edit`, `Delete`, `Sort`, `Export`, `Import`, `Rollback` (9) | `BatchDelete`, `Clone`, `Options`, `View` (4) |
| **6. BudgetReport (17 mã)** | `List`, `Approve`, `Consolidate` (3) | `Detail`, `Create`, `Edit`, `Delete`, `Export`, `Import`, `Rollback`, `Submit`, `Reject` (9) | `BatchDelete`, `Clone`, `Options`, `Sort`, `View` (5) |
| **7. BudgetTransaction (16 mã)** | `List`, `Create`, `Edit`, `Approve` (4) | `Detail`, `Delete`, `Export`, `Import`, `Rollback`, `Submit`, `Reject` (7) | `BatchDelete`, `Clone`, `Options`, `Sort`, `View` (5) |
| **8. BudgetUnitConfig (13 mã)** | `List` (1) | `Detail`, `Create`, `Edit`, `Delete`, `Export`, `Import`, `Rollback` (7) | `BatchDelete`, `Clone`, `Options`, `Sort`, `View` (5) |

### 4.2. Danh mục Kiểm đếm Chính xác Đủ 107 Mã `@SaCheckPermission`

| STT nhóm | Tiền tố Entity | Số mã BE | Liệt kê đầy đủ từng mã quyền (tổng = **107 mã**) | Có trên FE (`permissions.ts`) | Có trên Mobile (`PermissionConstants`) |
| --- | --- | --- | --- | --- | --- |
| 1 | `BudgetAllocation*` | **17** | `Approve`, `BatchDelete`, `Clone`, `Create`, `Delete`, `Detail`, `Edit`, `Export`, `Import`, `List`, `Options`, `Reject`, `Rollback`, `SelfApprove`, `Sort`, `Submit`, `View` | 12 / 17 (thiếu `BatchDelete`, `Clone`, `Options`, `Sort`, `View`) | 5 / 17 (`List`, `Create`, `Edit`, `Approve`, `SelfApprove`) |
| 2 | `BudgetCategory*` | **13** | `BatchDelete`, `Clone`, `Create`, `Delete`, `Detail`, `Edit`, `Export`, `Import`, `List`, `Options`, `Rollback`, `Sort`, `View` | 8 / 13 (thiếu `BatchDelete`, `Clone`, `Options`, `Sort`, `View`) | 1 / 13 (`List`) |
| 3 | `BudgetDashboard*` | **2** | `Query`, `Simulate` | 2 / 2 | **0 / 2** *(Lỗ hổng `MOB-H07`)* |
| 4 | `BudgetDisbursement*` | **16** | `Approve`, `BatchDelete`, `Clone`, `Create`, `Delete`, `Detail`, `Edit`, `Export`, `Import`, `List`, `Options`, `Reject`, `Rollback`, `Settle`, `Sort`, `View` | 11 / 16 (thiếu `BatchDelete`, `Clone`, `Options`, `Sort`, `View`) | 6 / 16 (`List`, `Create`, `Edit`, `Approve`, `Settle`, `Export`) |
| 5 | `BudgetEstimateItem*` | **13** | `BatchDelete`, `Clone`, `Create`, `Delete`, `Detail`, `Edit`, `Export`, `Import`, `List`, `Options`, `Rollback`, `Sort`, `View` | 9 / 13 (thiếu `BatchDelete`, `Clone`, `Options`, `View`) | 0 / 13 |
| 6 | `BudgetReport*` | **17** | `Approve`, `BatchDelete`, `Clone`, `Consolidate`, `Create`, `Delete`, `Detail`, `Edit`, `Export`, `Import`, `List`, `Options`, `Reject`, `Rollback`, `Sort`, `Submit`, `View` | 12 / 17 (thiếu `BatchDelete`, `Clone`, `Options`, `Sort`, `View`) | 3 / 17 (`List`, `Approve`, `Consolidate`) |
| 7 | `BudgetTransaction*` | **16** | `Approve`, `BatchDelete`, `Clone`, `Create`, `Delete`, `Detail`, `Edit`, `Export`, `Import`, `List`, `Options`, `Reject`, `Rollback`, `Sort`, `Submit`, `View` | 11 / 16 (thiếu `BatchDelete`, `Clone`, `Options`, `Sort`, `View`) | 4 / 16 (`List`, `Create`, `Edit`, `Approve`) |
| 8 | `BudgetUnitConfig*` | **13** | `BatchDelete`, `Clone`, `Create`, `Delete`, `Detail`, `Edit`, `Export`, `Import`, `List`, `Options`, `Rollback`, `Sort`, `View` | 8 / 13 (thiếu `BatchDelete`, `Clone`, `Options`, `Sort`, `View`) | 1 / 13 (`List`) |
| **Tổng cộng** | **8 nhóm** | **107** | **107 mã duy nhất trên BE** *(17+13+2+16+13+17+16+13 = 107)* | **73 / 107 (68.2%)** *(+ 2 mã ma ở `constants.ts`)* | **20 / 107 (18.7%)** |

---

## 5. Tổng hợp Phát hiện Phân quyền & SoD

| Mã | Mức độ | Tóm tắt phát hiện | Vị trí chính |
| --- | --- | --- | --- |
| `PERM-C01` | CRITICAL | `resolveCurrentActorName` & `resolveCurrentPersonId` ưu tiên đọc danh tính từ client body trước `LoginHelper` → giả mạo người ký duyệt | `BudgetIntegrationOrchestrator.java:119-127,164-176` |
| `PERM-C02` | CRITICAL | Không có kiểm soát SoD 4 mắt (`creator != approver`) & thiếu khóa chuyển trạng thái ở Phân bổ/Giải ngân/Báo cáo; nghiệp vụ Sổ cái tự duyệt 1 bước | `BudgetAllocationBusiness.java:424`, `BudgetDisbursementBusiness.java:425`, `BudgetTransactionBusiness.java:2252` |
| `PERM-C03` | CRITICAL | Frontend fail-open RBAC (`hasBudgetPermission` trả `true` khi `undefined`), hỏng trên Pure Cookie Mode và nhấp nháy toàn quyền trên React | `src/core/permissions.ts:146-174`, `src/react/hooks/use-budget-access.ts:16-54` |
| `PERM-H01` | HIGH | Xung đột `resolveBudgetPersonaRole` đòi mã role cứng `ROLE_BUDGET_*` dù user đã có `@SaCheckPermission("BudgetTransactionSubmit")` | `BudgetTransactionBusiness.java:2272-2305` |
| `PERM-H02` | HIGH | FE chưa gọi 7 endpoint `/options` (`RefData<K>`) mà gọi thẳng `GET /{resource}` full list (ép cấp thừa `*List`); 2 endpoint `POST /dashboard/*` gắn hậu tố đọc `BudgetDashboardQuery` *(Lưu ý: BE đã tuân thủ đúng `LP-020` khi không gắn `@SaSharedLookup`)* | `endpoints.ts:358-388`, `BudgetDashboardController.java:80,106` |
| `PERM-H03` | HIGH | 6/7 endpoint S2S dưới `@SaIgnore` của `BudgetInternalController` không gọi tường minh `SaSameUtil.checkCurrentRequestToken()` | `BudgetInternalController.java:45-125` |
| `PERM-H04` | HIGH | Cấu hình tự chủ `BudgetUnitConfig` bị đứt gãy trên cả Web (gửi `allowSelfApprove`, thiếu 7 field) và Mobile (sai 4 khóa JSON) | `facade-disbursements-reports.ts:285-312`, `budget_types.dart:935-941` |
| `PERM-H05` | HIGH | 7 điểm lệch mã quyền giữa BE vs FE/Mobile (`syncToPlan`, `kbnnDvcPackage`, `DX-01`, 8 NV sổ cái Mobile, `saveMatrix`, `budgetAny`, 2 mã ma `*History`) | Xem bảng Mục 3 |
| `PERM-M01` | MEDIUM | `POST /reports/{id}/treasury-reconciliation` gắn `BudgetReportSubmit` trùng người lập báo cáo; thiếu 34 mã quyền BE trong `FE permissions.ts` | `BudgetReportController.java:213`, `src/core/permissions.ts` |
