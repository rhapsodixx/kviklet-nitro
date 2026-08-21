# AI Query Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add revision-scoped OpenRouter AI query review for relational `SingleExecution` requests, with per-connection Off/Optional/Mandatory modes and a Mandatory execution gate that fails closed on provider failure.

**Architecture:** Persist first-class AI review attempts and overrides keyed by a SHA-256 revision fingerprint. After create/edit commits, an after-commit listener starts an async OpenRouter review. Human `resolveReviewStatus()` stays unchanged; Mandatory AI is an additional check in the shared execution gate. Optional mode is display-only.

**Tech Stack:** Kotlin / Spring Boot 3.4, Liquibase, Jackson, MockWebServer, React 18 + TypeScript + Vite + Zod + Vitest, Playwright.

**Spec:** [`docs/superpowers/specs/2026-08-21-ai-query-review-design.md`](../specs/2026-08-21-ai-query-review-design.md)

## Global Constraints

- Coverage is relational datasource `SingleExecution` only (PostgreSQL, MySQL, MariaDB, MSSQL). TemporaryAccess, Dump, MongoDB, Kubernetes are ignored.
- Primary model `qwen/qwen3-coder-plus`; fallback `openai/gpt-4o` via OpenRouter `models` list.
- Every OpenRouter call sets `provider.zdr=true`, `provider.data_collection="deny"`, `provider.require_parameters=true`, and JSON Schema structured output.
- Context sent: engine, SQL, title, description, request type only — no schema, credentials, EXPLAIN, or results.
- AI rejection never writes a human `REJECT` event; fix by editing → new fingerprint → re-review.
- Override is allowed only for current-revision `FAILED` attempts (including timed-out pending marked failed); never for successful `REJECTED`.
- Logs omit SQL, prompts, provider bodies, and API keys.
- API key is environment-only (`kviklet.ai-review.openrouter.api-key`); never stored in the configuration table or returned by API.
- Timeout default `60s`; max prompt chars `50000`.
- Do not change existing human approval tallies or `resolveReviewStatus()` semantics.

---

## File structure (create / modify)

### Create

| Path | Responsibility |
| --- | --- |
| `backend/src/main/resources/changelog/049-ai-query-review.yaml` | Schema for mode column, attempts, overrides |
| `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiReviewProperties.kt` | OpenRouter config properties |
| `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiReviewEnums.kt` | Mode, attempt status, severity, error category enums |
| `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/RevisionFingerprint.kt` | Canonical fingerprint hashing |
| `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiReviewVerdictNormalizer.kt` | Findings → status |
| `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiReviewPromptBuilder.kt` | Engine-specific prompts + policy version |
| `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/OpenRouterClient.kt` | HTTP client + structured response parse |
| `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiQueryReviewGate.kt` | Effective gate decision |
| `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiQueryReviewService.kt` | Start/retry/override/load current review |
| `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiQueryReviewCoordinator.kt` | After-commit listener + async dispatch |
| `backend/src/main/kotlin/dev/kviklet/kviklet/db/AiQueryReview.kt` | Entities, repositories, adapters |
| `backend/src/main/kotlin/dev/kviklet/kviklet/service/RequestRevisionChangedEvent.kt` | Domain event after create/edit |
| `backend/src/test/kotlin/dev/kviklet/kviklet/service/aireview/*` | Unit/integration tests |
| `frontend/src/routes/Review/AiQueryReviewPanel.tsx` | Detail panel UI |
| `frontend/src/routes/Review/AiQueryReviewPanel.test.tsx` | Panel state tests |
| `frontend/src/routes/Review/AiReviewBadge.tsx` | Sidebar badge |

### Modify

| Path | Change |
| --- | --- |
| `backend/src/main/resources/changelog/000-changelog.yaml` | Include `049` |
| `backend/src/main/kotlin/.../db/Connection.kt` | `aiReviewMode` column + adapter |
| `backend/src/main/kotlin/.../service/dto/Connection.kt` | Field on `DatasourceConnection` |
| `backend/src/main/kotlin/.../controller/ConnectionController.kt` | Request/response fields + key-required validation |
| `backend/src/main/kotlin/.../service/ConnectionService.kt` | Pass-through + validate mode |
| `backend/src/main/kotlin/.../security/MethodSecurityConfiguration.kt` | New permission enum value |
| `backend/src/main/kotlin/.../security/PermissionResolver.kt` | Include override permission in request permissions |
| `backend/src/main/kotlin/.../controller/ConfigController.kt` | `aiReviewConfigured` boolean |
| `backend/src/main/kotlin/.../controller/ExecutionRequestController.kt` | AI fields on detail + retry/override endpoints |
| `backend/src/main/kotlin/.../service/ExecutionRequestService.kt` | Publish revision event; call gate in execute paths |
| `backend/src/main/resources/application.yaml` / properties docs | Defaults for AI review props |
| `frontend/src/api/DatasourceApi.ts` | `aiReviewMode` |
| `frontend/src/api/ConfigApi.ts` | `aiReviewConfigured` |
| `frontend/src/api/ExecutionRequestApi.ts` | AI review schemas + retry/override API |
| `frontend/src/api/Permissions.ts` | New permission string |
| `frontend/src/routes/settings/connection/DatabaseConnectionForm.tsx` | Mode selector |
| `frontend/src/routes/settings/connection/details/UpdateDatasourceConnectionForm.tsx` | Mode selector |
| `frontend/src/routes/Review/RequestSidebar.tsx` | Badge |
| `frontend/src/routes/Review/index.tsx` | Mount panel |
| `frontend/src/hooks/request.ts` | Pending polling + retry/override helpers |
| `frontend/src/routes/Review/DatasourceRequestActions.tsx` | AI block messaging |
| `Readme.md` | Env vars for OpenRouter |

---

### Task 1: Schema, enums, and revision fingerprint

**Files:**
- Create: `backend/src/main/resources/changelog/049-ai-query-review.yaml`
- Modify: `backend/src/main/resources/changelog/000-changelog.yaml`
- Create: `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiReviewEnums.kt`
- Create: `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/RevisionFingerprint.kt`
- Test: `backend/src/test/kotlin/dev/kviklet/kviklet/service/aireview/RevisionFingerprintTest.kt`

**Interfaces:**
- Produces: `enum class AiReviewMode { DISABLED, OPTIONAL, MANDATORY }`
- Produces: `enum class AiReviewAttemptStatus { PENDING, APPROVED, APPROVED_WITH_NOTES, REJECTED, FAILED }`
- Produces: `enum class AiFindingSeverity { BLOCKER, WARNING, INFO }`
- Produces: `object RevisionFingerprint { fun compute(engine: DatasourceType, statement: String, title: String, description: String?, requestType: RequestType): String }`

- [ ] **Step 1: Write failing fingerprint tests**

```kotlin
class RevisionFingerprintTest {
    @Test
    fun `same inputs produce same hash`() {
        val a = RevisionFingerprint.compute(
            DatasourceType.POSTGRESQL, "SELECT 1", "t", "d", RequestType.SingleExecution,
        )
        val b = RevisionFingerprint.compute(
            DatasourceType.POSTGRESQL, "SELECT 1", "t", "d", RequestType.SingleExecution,
        )
        assertEquals(a, b)
        assertEquals(64, a.length) // sha-256 hex
    }

    @Test
    fun `sql change changes hash`() {
        val a = RevisionFingerprint.compute(
            DatasourceType.POSTGRESQL, "SELECT 1", "t", null, RequestType.SingleExecution,
        )
        val b = RevisionFingerprint.compute(
            DatasourceType.POSTGRESQL, "SELECT 2", "t", null, RequestType.SingleExecution,
        )
        assertNotEquals(a, b)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'dev.kviklet.kviklet.service.aireview.RevisionFingerprintTest'`
Expected: FAIL (class missing)

- [ ] **Step 3: Implement enums, fingerprint, and Liquibase**

`RevisionFingerprint.compute` must canonicalize with fixed field order and `\n` separators, then SHA-256 hex lowercase:

```text
engine=<DatasourceType.name>
type=<RequestType.name>
title=<title>
description=<description or empty>
statement=<statement>
```

Liquibase `049-ai-query-review.yaml`:

1. `connection.ai_review_mode` VARCHAR NOT NULL default `'DISABLED'`
2. Table `ai_query_review_attempt` with columns: `id`, `execution_request_id` FK, `revision_fingerprint`, `status`, `summary`, `findings` JSON, `suggested_sql`, `model`, `prompt_policy_version`, `error_category`, `created_at`, `completed_at`
3. Table `ai_query_review_override` with: `id`, `execution_request_id` FK, `revision_fingerprint`, `actor_id` FK to user, `reason`, `created_at`
4. Index on `(execution_request_id, revision_fingerprint, created_at)`

Include file from `000-changelog.yaml` after `048`.

- [ ] **Step 4: Run fingerprint tests — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/changelog/049-ai-query-review.yaml \
  backend/src/main/resources/changelog/000-changelog.yaml \
  backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiReviewEnums.kt \
  backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/RevisionFingerprint.kt \
  backend/src/test/kotlin/dev/kviklet/kviklet/service/aireview/RevisionFingerprintTest.kt
git commit -m "$(cat <<'EOF'
Add AI review schema and revision fingerprinting.

Introduce Liquibase tables/columns and a deterministic SHA-256 fingerprint for SingleExecution revisions.
EOF
)"
```

---

### Task 2: Persistence adapters for attempts and overrides

**Files:**
- Create: `backend/src/main/kotlin/dev/kviklet/kviklet/db/AiQueryReview.kt`
- Create: `backend/src/main/kotlin/dev/kviklet/kviklet/service/dto/AiQueryReview.kt` (DTO types for attempt, finding, override)
- Test: `backend/src/test/kotlin/dev/kviklet/kviklet/service/aireview/AiQueryReviewAdapterTest.kt` (or spring integration test using existing helpers)

**Interfaces:**
- Produces DTOs:
  - `data class AiFinding(val severity: AiFindingSeverity, val category: String, val explanation: String, val fix: String)`
  - `data class AiQueryReviewAttempt(...fields matching schema...)`
  - `data class AiQueryReviewOverride(...fields...)`
- Produces adapter methods:
  - `createPending(executionRequestId, fingerprint): AiQueryReviewAttempt`
  - `complete(attemptId, status, summary, findings, suggestedSql, model, promptPolicyVersion, errorCategory): AiQueryReviewAttempt`
  - `findLatestForRevision(executionRequestId, fingerprint): AiQueryReviewAttempt?`
  - `hasInFlightPending(executionRequestId, fingerprint): Boolean`
  - `createOverride(executionRequestId, fingerprint, actorId, reason): AiQueryReviewOverride`
  - `findLatestOverride(executionRequestId, fingerprint): AiQueryReviewOverride?`

- [ ] **Step 1: Write failing adapter test** — create pending, complete as APPROVED, fetch latest for fingerprint; create override and fetch.

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: Implement entities extending `BaseEntity`, JSON findings converter (reuse `PayloadConverter` pattern from `EventPayloadConverter`), repositories, and `AiQueryReviewAdapter`.**

Findings JSON shape:

```json
[{"severity":"BLOCKER","category":"...","explanation":"...","fix":"..."}]
```

- [ ] **Step 4: Run adapter test — expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Add AI query review persistence adapters.

Store append-only review attempts and revision-bound admin overrides.
EOF
)"
```

---

### Task 3: Verdict normalizer and prompt builder

**Files:**
- Create: `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiReviewVerdictNormalizer.kt`
- Create: `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiReviewPromptBuilder.kt`
- Test: `backend/src/test/kotlin/dev/kviklet/kviklet/service/aireview/AiReviewVerdictNormalizerTest.kt`
- Test: `backend/src/test/kotlin/dev/kviklet/kviklet/service/aireview/AiReviewPromptBuilderTest.kt`

**Interfaces:**
- Produces: `fun normalize(findings: List<AiFinding>, modelVerdict: AiReviewAttemptStatus): AiReviewAttemptStatus`
- Produces: `data class BuiltPrompt(val system: String, val user: String, val policyVersion: String)`
- Produces: `fun build(engine: DatasourceType, statement: String, title: String, description: String?): BuiltPrompt`
- Policy version constant: `PROMPT_POLICY_VERSION = "2026-08-21.1"`

Normalization rules (severity wins over model verdict):

| Findings | Status |
| --- | --- |
| Any BLOCKER | REJECTED |
| No blockers, any WARNING/INFO | APPROVED_WITH_NOTES |
| Empty / no substantive findings | APPROVED |

If model returns REJECTED without blockers, still treat as REJECTED only if findings non-empty with actionable text; otherwise force FAILED at service layer later. Prefer: severity rules always win; if severity says APPROVED but model said REJECTED with only WARNINGs → APPROVED_WITH_NOTES.

Prompt builder requirements:

- Wrap SQL in clear delimiters, e.g. `<<<SQL` / `SQL>>>`, labeled untrusted.
- Include common safety rubric + engine-specific module for POSTGRESQL/MYSQL/MARIADB/MSSQL.
- Instruct JSON Schema fields and no invented schema facts.

- [ ] **Step 1: Write failing normalizer + prompt tests**

```kotlin
@Test
fun `blocker forces rejected`() {
    val status = AiReviewVerdictNormalizer.normalize(
        listOf(AiFinding(AiFindingSeverity.BLOCKER, "safety", "unbounded delete", "add WHERE")),
        AiReviewAttemptStatus.APPROVED,
    )
    assertEquals(AiReviewAttemptStatus.REJECTED, status)
}

@Test
fun `postgres prompt includes engine name and sql delimiters`() {
    val built = AiReviewPromptBuilder.build(
        DatasourceType.POSTGRESQL, "DELETE FROM t", "cleanup", null,
    )
    assertTrue(built.user.contains("<<<SQL"))
    assertTrue(built.user.contains("DELETE FROM t"))
    assertTrue(built.system.contains("PostgreSQL") || built.system.contains("POSTGRESQL"))
    assertEquals("2026-08-21.1", built.policyVersion)
}
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement normalizer + prompt builder**

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Add AI review verdict normalization and engine prompts.

Severity rules override model verdicts; prompts are versioned and engine-specific.
EOF
)"
```

---

### Task 4: OpenRouter client and properties

**Files:**
- Create: `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiReviewProperties.kt`
- Create: `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/OpenRouterClient.kt`
- Modify: `backend/src/main/resources/application.yaml` (defaults)
- Test: `backend/src/test/kotlin/dev/kviklet/kviklet/service/aireview/OpenRouterClientTest.kt` (MockWebServer)

**Interfaces:**
- Produces properties prefix `kviklet.ai-review`:
  - `openrouter.apiKey: String?`
  - `openrouter.baseUrl: String = "https://openrouter.ai/api/v1"`
  - `primaryModel = "qwen/qwen3-coder-plus"`
  - `fallbackModel = "openai/gpt-4o"`
  - `timeout = Duration.ofSeconds(60)`
  - `maxPromptChars = 50000`
  - `fun isConfigured(): Boolean = !apiKey.isNullOrBlank()`
- Produces: `data class OpenRouterReviewResult(val model: String, val verdict: AiReviewAttemptStatus, val summary: String, val findings: List<AiFinding>, val suggestedSql: String?)`
- Produces: `fun review(system: String, user: String): OpenRouterReviewResult` throws typed failures mapped to error categories: `TIMEOUT`, `HTTP_ERROR`, `INVALID_RESPONSE`, `PROMPT_TOO_LARGE`, `NOT_CONFIGURED`

Request body must include:

```json
{
  "models": ["qwen/qwen3-coder-plus", "openai/gpt-4o"],
  "messages": [{"role":"system","content":"..."},{"role":"user","content":"..."}],
  "provider": {
    "zdr": true,
    "data_collection": "deny",
    "require_parameters": true
  },
  "response_format": {
    "type": "json_schema",
    "json_schema": { "name": "ai_query_review", "strict": true, "schema": { ... } }
  }
}
```

One bounded retry on 429/5xx honoring `Retry-After` when present. Never log request/response bodies or SQL.

- [ ] **Step 1: Write MockWebServer tests** asserting Authorization bearer, models order, zdr/data_collection, and successful JSON parse; plus invalid JSON → failure category.

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement properties + RestClient/WebClient-based client** (project already uses Spring Web; prefer `RestClient` or existing HTTP style if any — otherwise `java.net.http.HttpClient` with timeout).

Enable `@EnableConfigurationProperties(AiReviewProperties::class)` on a small `@Configuration` in the same package. Also `@EnableAsync` if not present globally (add to that config).

- [ ] **Step 4: Run OpenRouterClientTest — expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Add OpenRouter client for AI query review.

Enforce ZDR routing, model fallback list, and structured JSON Schema responses.
EOF
)"
```

---

### Task 5: Gate logic and review service (start / retry / override)

**Files:**
- Create: `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiQueryReviewGate.kt`
- Create: `backend/src/main/kotlin/dev/kviklet/kviklet/service/aireview/AiQueryReviewService.kt`
- Test: `backend/src/test/kotlin/dev/kviklet/kviklet/service/aireview/AiQueryReviewGateTest.kt`
- Test: `backend/src/test/kotlin/dev/kviklet/kviklet/service/aireview/AiQueryReviewServiceTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class AiGateDecision { ALLOWED, BLOCKED_PENDING, BLOCKED_REJECTED, BLOCKED_FAILED, BLOCKED_MISSING, NOT_APPLICABLE }

fun decide(
  mode: AiReviewMode,
  requestType: RequestType,
  engine: DatasourceType?,
  latestAttempt: AiQueryReviewAttempt?,
  override: AiQueryReviewOverride?,
  currentFingerprint: String,
): AiGateDecision
```

Gate rules:

- Non-`SingleExecution` OR non-relational OR `DISABLED` → `NOT_APPLICABLE` (treat as allowed by callers)
- `OPTIONAL` → always `ALLOWED` (still return attempt for UI elsewhere)
- `MANDATORY`:
  - matching override for current fingerprint + latest attempt FAILED → `ALLOWED`
  - latest attempt APPROVED / APPROVED_WITH_NOTES for current fingerprint → `ALLOWED`
  - PENDING → `BLOCKED_PENDING`
  - REJECTED → `BLOCKED_REJECTED`
  - FAILED without override → `BLOCKED_FAILED`
  - missing attempt → `BLOCKED_MISSING`

Service methods:

```kotlin
fun enqueueReview(details: ExecutionRequestDetails) // no-op if ineligible; dedupe pending
fun retry(id: ExecutionRequestId): AiQueryReviewAttempt
fun overrideFailed(id: ExecutionRequestId, actorId: String, reason: String): AiQueryReviewOverride
fun currentSnapshot(details: ExecutionRequestDetails): AiReviewSnapshot
```

`AiReviewSnapshot` includes mode, current fingerprint, latest attempt for fingerprint, override, `blocksExecution: Boolean`, `gate: AiGateDecision`.

`enqueueReview` / async worker:

1. If ineligible → return
2. If `hasInFlightPending` for fingerprint → return
3. `createPending`
4. Call OpenRouter; normalize; `complete`
5. On exception → `complete(..., FAILED, errorCategory=...)`
6. On timeout of PENDING → mark FAILED with `TIMEOUT` (worker timeout or scheduled sweeper; simplest: client timeout then complete FAILED)

Override rejects blank reason; rejects if latest status is not `FAILED`; binds to current fingerprint.

- [ ] **Step 1: Write gate matrix unit tests** covering DISABLED/OPTIONAL/MANDATORY × statuses × override

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement gate + service** (mock OpenRouter in service tests)

- [ ] **Step 4: Run gate + service tests — expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Add AI review gate and service orchestration.

Mandatory mode fails closed; overrides apply only to failed current revisions.
EOF
)"
```

---

### Task 6: Connection mode, config flag, and permission

**Files:**
- Modify: `backend/.../db/Connection.kt`, `service/dto/Connection.kt`, `ConnectionService.kt`, `ConnectionController.kt`
- Modify: `backend/.../security/MethodSecurityConfiguration.kt` — add `EXECUTION_REQUEST_OVERRIDE_AI_REVIEW(Resource.EXECUTION_REQUEST, "override_ai_review", EXECUTION_REQUEST_GET)`
- Modify: `backend/.../security/PermissionResolver.kt` — include new permission in request permission set when allowed
- Modify: `backend/.../controller/ConfigController.kt` + `PublicConfigResponse` / `ConfigResponse` — `aiReviewConfigured: Boolean`
- Modify: frontend `Permissions.ts`, `ConfigApi.ts`, `DatasourceApi.ts`
- Test: extend `ConnectionTest.kt` / add `AiReviewConnectionValidationTest.kt`

**Interfaces:**
- Connection create/update accept `aiReviewMode` (default DISABLED)
- Setting OPTIONAL/MANDATORY when `!aiReviewProperties.isConfigured()` throws `IllegalArgumentException("OpenRouter API key is not configured")`
- Config GET returns `aiReviewConfigured` without exposing the key

- [ ] **Step 1: Write failing test** — create connection with MANDATORY and no API key → error; with key → success; config exposes boolean

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Thread `aiReviewMode` through entity/DTO/controller/forms backend; update frontend Zod + forms later in Task 9 if splitting — for this task finish backend + `DatasourceApi.ts` / `ConfigApi.ts` / `Permissions.ts` type updates

- [ ] **Step 4: Run connection/config tests — expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Add per-connection AI review mode and override permission.

Require an OpenRouter API key before enabling Optional or Mandatory mode.
EOF
)"
```

---

### Task 7: Wire create/edit trigger and execution gate

**Files:**
- Create: `backend/.../service/RequestRevisionChangedEvent.kt`
- Create: `backend/.../service/aireview/AiQueryReviewCoordinator.kt`
- Modify: `ExecutionRequestService.kt` — publish event after successful create/update; call gate before execute/download (and dry-run when `dryRunRequiresApproval`)
- Test: `AiQueryReviewIntegrationTest.kt` using MockWebServer + existing request helpers

**Interfaces:**
- Event: `data class RequestRevisionChangedEvent(val executionRequestId: ExecutionRequestId)`
- Coordinator: `@TransactionalEventListener(phase = AFTER_COMMIT)` → `aiQueryReviewService.enqueueReviewAsync(...)`
- On execute: if gate is blocking → throw `InvalidReviewException` with distinct messages:
  - pending: `"AI query review is still pending"`
  - rejected: `"AI query review rejected this revision"`
  - failed: `"AI query review failed; retry or request an admin override"`
  - missing: `"AI query review has not completed"`

Do **not** alter `resolveReviewStatus()`.

Publish event from `create()` after `RequestCreatedEvent`, and from `update()` when statement/title/description change for datasource SingleExecution (always safe to publish on any datasource SingleExecution update).

- [ ] **Step 1: Write integration test** — Mandatory connection, create request, mock OpenRouter REJECTED with blocker → execute fails; edit SQL → new review APPROVED + human approve → execute succeeds. Second case: OpenRouter 500 → FAILED → execute fails → override → execute succeeds after human approval.

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement event, coordinator (`@Async`), and gate in `raiseIfNotExecutable` path / dry-run approval path

Helper:

```kotlin
fun ExecutionRequestDetails.raiseIfAiReviewBlocks(gate: AiQueryReviewGate, snapshotProvider: ...)
```

Keep changes localized; prefer injecting `AiQueryReviewService` into `ExecutionRequestService`.

- [ ] **Step 4: Run integration tests — expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Wire AI review into request lifecycle and execution gate.

Trigger async review after commit and block Mandatory executions until the current revision is allowed.
EOF
)"
```

---

### Task 8: HTTP API — detail fields, retry, override

**Files:**
- Modify: `ExecutionRequestController.kt` — extend detail responses; add endpoints
- Modify: `ExecutionRequestApi.ts` — Zod schemas + clients
- Test: controller/API tests or extend integration test with MockMvc if used in project

**Interfaces:**

Detail response additions (datasource detail primarily; kubernetes may be null):

```kotlin
data class AiReviewFindingResponse(val severity: String, val category: String, val explanation: String, val fix: String)
data class AiReviewAttemptResponse(
  val status: String,
  val summary: String?,
  val findings: List<AiReviewFindingResponse>,
  val suggestedSql: String?,
  val model: String?,
  val promptPolicyVersion: String?,
  val errorCategory: String?,
  val createdAt: LocalDateTime,
  val completedAt: LocalDateTime?,
)
data class AiReviewOverrideResponse(val reason: String, val createdAt: LocalDateTime, val actorName: String?)
// on detail:
val aiReviewMode: AiReviewMode?
val aiReview: AiReviewAttemptResponse?
val aiReviewOverride: AiReviewOverrideResponse?
val aiReviewBlocksExecution: Boolean
```

Endpoints:

```http
POST /execution-requests/{id}/ai-review/retry
POST /execution-requests/{id}/ai-review/override
Content-Type: application/json
{ "reason": "OpenRouter outage; manually verified" }
```

Auth:

- retry: `@Policy(Permission.EXECUTION_REQUEST_GET)`
- override: `@Policy(Permission.EXECUTION_REQUEST_OVERRIDE_AI_REVIEW)`

Rate-limit retry simply: if pending in-flight exists, return that attempt; if last FAILED completed < 3s ago, reject with 429-style error message.

- [ ] **Step 1: Write API tests for retry/override auth and payload**

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement controller + frontend API types/functions

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Expose AI review status, retry, and override APIs.

Include revision-scoped review fields on request detail responses.
EOF
)"
```

---

### Task 9: Frontend connection settings + review panel

**Files:**
- Modify: `DatabaseConnectionForm.tsx`, `UpdateDatasourceConnectionForm.tsx`
- Create: `AiQueryReviewPanel.tsx`, `AiReviewBadge.tsx`
- Modify: `RequestSidebar.tsx`, `Review/index.tsx`, `hooks/request.ts`, `DatasourceRequestActions.tsx`
- Test: `AiQueryReviewPanel.test.tsx`

**Interfaces:**
- Form: select `aiReviewMode` Off/Optional/Mandatory; disable Optional/Mandatory when `config.aiReviewConfigured === false` with helper text `"Set KVIKLET_AI_REVIEW_OPENROUTER_API_KEY (or kviklet.ai-review.openrouter.api-key) to enable"`
- Panel states: pending spinner; approved; approved-with-notes (list findings); rejected (findings + fix, CTA to edit); failed (Retry button); overridden banner
- `useRequest`: while `aiReview?.status === "PENDING"`, poll `getSingleRequest` every 2s; clear interval otherwise
- `retryAiReview()` / `overrideAiReview(reason)` helpers
- Sidebar badge shows compact status
- Execute button disabled reason distinguishes human vs AI block using `aiReviewBlocksExecution`

Rendering: use existing markdown/plain text patterns; do not `dangerouslySetInnerHTML` model text.

- [ ] **Step 1: Write Vitest tests for panel states** (pending/rejected/failed)

- [ ] **Step 2: Run `cd frontend && npm run test -- AiQueryReviewPanel` — expect FAIL**

- [ ] **Step 3: Implement UI components and wire forms/sidebar/actions/polling**

- [ ] **Step 4: Run frontend unit tests — expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Add AI query review UI for connections and request detail.

Show verdicts and findings, poll while pending, and support retry/override actions.
EOF
)"
```

---

### Task 10: Docs and end-to-end coverage

**Files:**
- Modify: `Readme.md` — document env vars and per-connection modes
- Create or extend: `e2e/tests/ai-query-review.spec.ts` (mock OpenRouter via backend test profile **or** document that e2e runs with WireMock sidecar). Prefer backend integration as primary CI; add Playwright smoke that asserts UI elements when AI fields are present in fixture if full OpenRouter mock in e2e is too heavy.

Practical CI approach for this task:

1. Ensure backend MockWebServer integration tests cover accept/reject/fail/override paths (already Task 7).
2. Add a focused Playwright test only if e2e harness can inject a stub — otherwise add a frontend e2e-free checklist in Readme under testing and rely on Vitest + backend tests.
3. Document:

```bash
KVIKLET_AI_REVIEW_OPENROUTER_API_KEY=...
# optional overrides:
KVIKLET_AI_REVIEW_PRIMARY_MODEL=qwen/qwen3-coder-plus
KVIKLET_AI_REVIEW_FALLBACK_MODEL=openai/gpt-4o
```

Map env vars to Spring relaxed binding for `kviklet.ai-review.*`.

- [ ] **Step 1: Update Readme with AI Query Review section**

- [ ] **Step 2: Add e2e or document CI reliance; if adding Playwright, assert badge/panel visible for a seeded request with mocked AI payload via backend stub**

- [ ] **Step 3: Run backend AI review tests + frontend unit tests**

```bash
cd backend && ./gradlew test --tests '*aireview*'
cd frontend && npm run test -- AiQueryReview
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
Document AI query review setup and lock CI coverage.

Describe OpenRouter env configuration and keep automated review tests in the suite.
EOF
)"
```

---

## Self-review (plan vs spec)

| Spec requirement | Task |
| --- | --- |
| Per-connection Off/Optional/Mandatory | 6, 9 |
| SingleExecution relational only | 5 gate, Global Constraints |
| Qwen + GPT-4o fallback, ZDR | 4 |
| Query metadata only | 3, 4 |
| Revision fingerprint + attempts | 1, 2 |
| Async after create/edit | 7 |
| Humans parallel; AI Mandatory gate | 5, 7 |
| Reject → edit → re-review | 5, 7 |
| Failed → retry / override (not for REJECTED) | 5, 8 |
| API fields + endpoints | 8 |
| UI panel/badge/polling | 9 |
| Security/logging constraints | 4, Global Constraints |
| Tests + acceptance | 1–10 |

No intentional TBD placeholders remain. Property names stay consistent: `AiReviewMode`, `AiReviewAttemptStatus`, `RevisionFingerprint.compute`, `AiQueryReviewService`, `aiReviewConfigured`.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-21-ai-query-review.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration  
2. **Inline Execution** — execute tasks in this session using executing-plans, with checkpoints  

Which approach?
