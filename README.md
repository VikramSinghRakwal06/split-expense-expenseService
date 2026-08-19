# SplitExpense expense-service

Records shared expenses and settlements for the SplitExpense platform, splits each expense
between its participants, and applies the resulting balance deltas to `group-service`'s debt
graph.

This service holds **no balances**. Who owes whom lives in `group-service`, as a pairwise debt
graph; what lives here is the record of an expense or a settlement and the attempt to apply it
— a saga driven from `expenses` / `settlements` tables and made safe to retry by a
client-supplied `Idempotency-Key`. See
[The expense and settlement saga](#the-expense-and-settlement-saga) for the state machine and
[Idempotency](#idempotency) for how a retried request is answered.

This service **validates** JWTs issued by `auth-service` using the shared signing secret. It
never mints a token, and it holds no user table — like `group-service`, it resolves "who is the
caller" entirely from the verified token. It additionally holds a pre-provisioned service
account so it can authenticate its own outbound calls onto `group-service`'s internal
`:apply` endpoint — see [Running it](#running-it).

---

## Contents

- [Running it](#running-it)
- [Environment variables](#environment-variables)
- [API](#api)
- [The expense and settlement saga](#the-expense-and-settlement-saga)
- [Idempotency](#idempotency)
- [Split calculation](#split-calculation)
- [Design notes](#design-notes)
- [Tests](#tests)

---

## Running it

Requires Java 21, PostgreSQL, Kafka, and running instances of `auth-service` and
`group-service` — every expense and settlement calls out to both.

```bash
# Dependencies
docker run -d --name splitexpense-pg-expense -p 5432:5432 \
  -e POSTGRES_USER=splitexpense -e POSTGRES_PASSWORD=splitexpense -e POSTGRES_DB=splitexpense_expense \
  postgres:16-alpine

# Register and promote the service account expense-service logs in as (see
# ServiceAccountProperties for why this exists): against a running auth-service,
curl -X POST http://localhost:8081/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"expense-service@splitexpense.internal","password":"dev-service-account-password-change-me-1","name":"expense-service"}'
# then, by hand, against auth-service's database:
#   UPDATE users SET role = 'ADMIN' WHERE email = 'expense-service@splitexpense.internal';

# The service, on the dev profile
./mvnw spring-boot:run
```

It listens on **8083**. Swagger UI is at <http://localhost:8083/swagger-ui.html>.

Kafka does not block start-up if it is unreachable — publishing an event is fire-and-forget
(see [Design notes](#design-notes)) — but a broker is needed for `notification-service` to
actually see anything this service records.

> The registration password above (`...-change-me-1`) is what the platform's own
> `docker-compose.yml` uses; it carries a trailing digit because auth-service's own
> `RegisterRequest` validation requires one, which the plain in-code default in
> `application.yml` (`dev-service-account-password-change-me`) does not satisfy. Use whichever
> password you register the account with as `SERVICE_ACCOUNT_PASSWORD`.

---

## Environment variables

Everything is read from the environment, with development fallbacks in `application.yml`. The
`prod` profile removes the fallbacks for every secret and every cross-service address, so a
production start-up fails fast rather than running against `localhost`.

| Variable | Default (dev) | Required in prod | Purpose |
|---|---|---|---|
| `SERVER_PORT` | `8083` | no | HTTP port |
| `SPRING_PROFILES_ACTIVE` | `dev` | — | `dev` or `prod` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/splitexpense_expense` | **yes** | JDBC URL |
| `DB_USERNAME` | `splitexpense` | **yes** | Database user |
| `DB_PASSWORD` | `splitexpense` | **yes** | Database password |
| `DB_POOL_MAX` | `10` (prod `20`) | no | Hikari maximum pool size — see [Design notes](#design-notes) for why it is sized this way |
| `DB_POOL_MIN` | `2` (prod `5`) | no | Hikari minimum idle |
| `JWT_SECRET` | `dev-secret-change-me-…` | **yes** | HMAC-SHA key, ≥32 bytes. **Must match `auth-service` and `group-service`.** |
| `JWT_ISSUER` | `splitexpense-auth-service` | no | Required value of the `iss` claim. **Must match `auth-service`.** |
| `AUTH_SERVICE_URL` | `http://localhost:8081` | **yes** | Where the service account logs in |
| `AUTH_CONNECT_TIMEOUT` | `2s` | no | Connect timeout to auth-service |
| `AUTH_READ_TIMEOUT` | `3s` | no | Read timeout to auth-service |
| `SERVICE_ACCOUNT_EMAIL` | `expense-service@splitexpense.internal` | **yes** | Pre-provisioned `ROLE_ADMIN` account used only to call group-service's internal `:apply` endpoint |
| `SERVICE_ACCOUNT_PASSWORD` | `dev-service-account-password-change-me` | **yes** | — |
| `GROUP_SERVICE_URL` | `http://localhost:8082` | **yes** | group-service's base URL |
| `GROUP_CONNECT_TIMEOUT` | `2s` | no | Deliberately short — see `application.yml` |
| `GROUP_READ_TIMEOUT` | `5s` | no | Deliberately asymmetric to the connect timeout — allows for group-service's own optimistic-locking retries on a contended group |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | **yes** | Where `expense.created`, `expense.voided` and `settlement.recorded` are published, for notification-service |
| `SWAGGER_UI_ENABLED` | `true` (prod `false`) | no | Serve the interactive UI |
| `LOG_LEVEL` | `DEBUG` (prod `INFO`) | no | Level for `com.splitexpense.expense` |
| `JPA_SHOW_SQL` | `true` (dev only) | no | Log generated SQL |

> **`JWT_SECRET` and `JWT_ISSUER` must be identical across `auth-service`, `group-service` and
> this service.** Signature verification fails otherwise, and this service never signs a token
> of its own — it only ever forwards or verifies one.

---

## API

Every endpoint requires `Authorization: Bearer <access token>`; only `/actuator/health` and the
OpenAPI paths are public.

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@splitexpense.io","password":"correct-horse-9"}' | jq -r .accessToken)
```

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/expenses` | Record a new expense and split it between participants — requires `Idempotency-Key` |
| `GET` | `/api/v1/expenses/{expenseId}` | Read one expense and its splits |
| `GET` | `/api/v1/expenses/me` | Paginated expenses the caller paid, newest first, 20 per page |
| `GET` | `/api/v1/expenses/groups/{groupId}` | Paginated expenses for a group, newest first, 20 per page |
| `POST` | `/api/v1/expenses/{expenseId}/void` | Void a completed expense — requires `Idempotency-Key` |
| `POST` | `/api/v1/settlements` | Record a settlement between two group members — requires `Idempotency-Key` |
| `GET` | `/api/v1/settlements/{settlementId}` | Read one settlement |
| `GET` | `/api/v1/settlements/groups/{groupId}` | Paginated settlements for a group, newest first, 20 per page |

Expenses are mapped under `/api/v1/expenses`, not nested beneath `/api/v1/groups/**`: the
gateway routes that whole prefix to group-service, so an expense-owned resource needs its own
top-level path. Every endpoint that names a group or an expense authorises through
group-service, using the caller's own forwarded bearer token — never a role check in this
service.

### Record an expense

```bash
curl -X POST http://localhost:8083/api/v1/expenses \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "groupId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "payerUserId": "a0c4f912-7d36-4b81-93ea-5f28c6d40719",
    "amount": 1350.00,
    "description": "Dinner at Toit",
    "splitType": "EQUAL",
    "participants": [
      {"userId": "a0c4f912-7d36-4b81-93ea-5f28c6d40719"},
      {"userId": "b1e7d3c2-4a58-4f19-9d63-2e8a7c015f4b"},
      {"userId": "c2f8e4d3-5b69-4a2a-8e74-3f9b8d026a5c"}
    ]
  }'
```

```json
{
  "id": "6f0ebc8d-b9ff-410a-8d4a-7e0da0689d02",
  "groupId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "payerUserId": "a0c4f912-7d36-4b81-93ea-5f28c6d40719",
  "amount": 1350.0000,
  "currency": "INR",
  "description": "Dinner at Toit",
  "splitType": "EQUAL",
  "status": "COMPLETED",
  "failureReason": null,
  "splits": [
    {"userId": "a0c4f912-7d36-4b81-93ea-5f28c6d40719", "shareAmount": 450.0000},
    {"userId": "b1e7d3c2-4a58-4f19-9d63-2e8a7c015f4b", "shareAmount": 450.0000},
    {"userId": "c2f8e4d3-5b69-4a2a-8e74-3f9b8d026a5c", "shareAmount": 450.0000}
  ],
  "createdAt": "2026-08-13T08:34:25.798722Z",
  "updatedAt": "2026-08-13T08:34:25.812114Z"
}
```

`payerUserId` is deliberately a field rather than always the caller — one member frequently
records an expense on somebody else's behalf ("Priya paid for the cab, put it through"). What
the caller cannot do is record an expense in a group they do not belong to, or name a payer or
participant who is not a current member.

`value` on each participant means something different per `splitType`: ignored for `EQUAL`
(shown above); the exact share for `EXACT`; the percentage (0–100) for `PERCENTAGE`; the
integer weight for `SHARES`. See [Split calculation](#split-calculation).

`Idempotency-Key` is required and client-generated. Retrying the same key with the same body is
always safe and returns the original outcome — see [Idempotency](#idempotency). Retrying with a
*different* body under the same key is a `422`.

| Status | Meaning |
|---|---|
| `200` | Expense recorded |
| `400` | Validation failed, or the split does not add up |
| `404` | No such group, or the caller is not a member |
| `409` | A request with this key is already in progress |
| `422` | The expense was refused (payer or a participant is not a group member), or the key was reused with a different request |
| `503` | group-service is unavailable |

### Read / list expenses

```bash
curl http://localhost:8083/api/v1/expenses/6f0ebc8d-b9ff-410a-8d4a-7e0da0689d02 \
  -H "Authorization: Bearer $TOKEN"

curl "http://localhost:8083/api/v1/expenses/me?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"

curl "http://localhost:8083/api/v1/expenses/groups/3fa85f64-5717-4562-b3fc-2c963f66afa6?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

A `404` for a single expense covers both "no such expense" and "the caller is not a member of
its group" — deliberately indistinguishable, the same way group-service treats a group it does
not want to confirm exists to a non-member.

### Void an expense

```bash
curl -X POST http://localhost:8083/api/v1/expenses/6f0ebc8d-b9ff-410a-8d4a-7e0da0689d02/void \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)"
```

Returns the expense with `"status": "VOIDED"`. Only the payer or a participant may void an
expense, and only while it is `COMPLETED` — see
[The expense and settlement saga](#the-expense-and-settlement-saga).

| Status | Meaning |
|---|---|
| `200` | Expense voided |
| `403` | Caller is neither the payer nor a participant |
| `404` | No such expense, or the caller is not a member of its group |
| `409` | A request with this key is already in progress, or the expense is not `COMPLETED` |

### Record a settlement

```bash
curl -X POST http://localhost:8083/api/v1/settlements \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "groupId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "fromUserId": "b1e7d3c2-4a58-4f19-9d63-2e8a7c015f4b",
    "toUserId": "a0c4f912-7d36-4b81-93ea-5f28c6d40719",
    "amount": 450.00,
    "note": "Paid back via UPI"
  }'
```

```json
{
  "id": "7a1fcd9e-c0aa-421b-9e5b-8f1eb790a013",
  "groupId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "fromUserId": "b1e7d3c2-4a58-4f19-9d63-2e8a7c015f4b",
  "toUserId": "a0c4f912-7d36-4b81-93ea-5f28c6d40719",
  "amount": 450.0000,
  "currency": "INR",
  "status": "COMPLETED",
  "failureReason": null,
  "note": "Paid back via UPI",
  "createdAt": "2026-08-13T09:02:11.114003Z",
  "updatedAt": "2026-08-13T09:02:11.129511Z"
}
```

A settlement carries no field naming the caller: either party to the debt may record it —
`fromUserId` confirming a payment went out, or `toUserId` confirming it came in — but not an
uninvolved third group member. A settlement can only ever confirm a debt that already exists:
`fromUserId`/`toUserId`/`amount` is checked against a fresh read of group-service's current
pairwise balances before anything is written, and refused if it exceeds what is actually owed.

| Status | Meaning |
|---|---|
| `200` | Settlement recorded |
| `400` | Validation failed, or the two parties are the same user |
| `403` | Caller is neither party to the settlement |
| `404` | No such group, or the caller is not a member |
| `409` | A request with this key is already in progress |
| `422` | The settlement was refused — including "more than is owed" or "no debt to settle" — or the key was reused with a different request |
| `503` | group-service is unavailable |

### Read / list settlements

```bash
curl http://localhost:8083/api/v1/settlements/7a1fcd9e-c0aa-421b-9e5b-8f1eb790a013 \
  -H "Authorization: Bearer $TOKEN"

curl "http://localhost:8083/api/v1/settlements/groups/3fa85f64-5717-4562-b3fc-2c963f66afa6?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

---

## The expense and settlement saga

```
Expense:     INITIATED -> COMPLETED -> VOIDED
                       \-> FAILED

Settlement:  INITIATED -> COMPLETED
                       \-> FAILED
```

Both sagas run the same shape: insert a local row, compute the deltas, apply them to
group-service in one call, then mark the row terminal. Each local write is its own short,
already-committed transaction; the group-service call happens entirely outside any of them —
never one long `@Transactional` spanning the network round trip. Every step is recorded to
`audit_logs` in its own `REQUIRES_NEW` transaction (`AuditService`), so the trail of a *failed*
expense or settlement survives that operation's own rollback.

### Why there is no compensation logic

The transfer saga this service's predecessor ran needed a compensating credit because a
transfer was two independent remote calls — debit, then credit — with a window between them in
which money had moved on one side but not the other. An expense or settlement applies **every**
one of its deltas in a single call to group-service's `:apply` endpoint, which is atomic (every
delta commits together or none do) and idempotent by the expense's or settlement's own id. So an
interrupted apply is never partially applied, and a retry always converges on the correct state
whether or not the original attempt landed. There is no intermediate state to be stranded in,
and therefore nothing to compensate for — notably absent from `ExpenseStatus` is anything
resembling the old `Transaction`'s `DEBITED` state.

What happens on an apply call depends entirely on which exception `GroupClient` translates the
failure into:

- **`GroupOperationException`** — group-service reached a decision and reported it (an archived
  group, a participant who is not a member, a malformed payload). No balance changed, so the
  row is simply marked `FAILED`. Never retried, never counted against the circuit breaker — see
  [Design notes](#design-notes).
- **`GroupServiceUnavailableException`** — group-service could not be reached, or answered in a
  way that means "unwell" (timeout, connection refused, 5xx). The row is left `INITIATED`: since
  the apply is idempotent by reference id, a later retry (of the same request, under the same
  key) converges regardless of whether the original call actually landed. Retried automatically
  by resilience4j, and counted against the `groupService` circuit breaker.

### Voiding an expense

Voiding applies the exact inverse of an expense's original deltas — same pairs, debtor and
creditor swapped — under its own reference id (`{expenseId}-void`), so the group's balances end
up exactly as if the expense had never been recorded. Only `COMPLETED` expenses may be voided;
the expense and its splits are never deleted, only marked `VOIDED`, remaining as a record of
what was originally charged. A settlement is never voided at all — a settlement recorded in
error is corrected by recording a second settlement in the opposite direction, exactly as
group-service's append-only ledger treats any other correction.

---

## Idempotency

Every expense and settlement is initiated under a client-supplied `Idempotency-Key`. The flow:

1. Reserve the key by inserting a row into `idempotency_records`. A concurrent request for the
   same key loses this race and is served a replay instead of a duplicate attempt — arbitrated
   by a database unique constraint, not application logic, because a check-then-insert in code
   cannot close the race window a concurrent `INSERT` can.
2. Insert the `expenses` or `settlements` row itself, guarded by its own unique constraint on
   `idempotency_key` as a second backstop.
3. Run the saga described above.
4. On completion, cache the serialised response on the `idempotency_records` row, so a later
   retry is replayed byte for byte without re-touching group-service at all.

A retry under a key that has not yet reached a terminal state (still `INITIATED`, or no cached
response yet) is answered `OperationInProgressException` (`409`), never re-attempted. A retry
under a key whose request body does not match what the key was first used with is rejected
outright (`422`, `IdempotencyKeyConflictException`) rather than replayed, since replaying would
answer a question the caller did not ask. The match is decided by `RequestHasher`: a SHA-256
fingerprint of a canonical string built from the caller, group, amounts and participants — the
hash, not the raw request, is what's stored, so this table does not accumulate the full detail
of every expense ever attempted.

Voiding an expense reuses the identical mechanism, with one load-bearing ordering difference:
the replay check runs **before** the "is this expense `COMPLETED`?" check. Voiding an expense
under a given key flips its status to `VOIDED`; a genuine retry of that same call (a dropped
connection, a timeout) must be answered from the cached response, not re-evaluated against the
"already voided" status the first call itself produced. Checking status first would make a
successful void's own retry indistinguishable from someone else trying to void an
already-voided expense under a fresh key, and reject both alike with `409`.

---

## Split calculation

`SplitCalculator` is a pure function — no repository, no service, nothing but `BigDecimal`
arithmetic — that turns one expense's amount and its participants' inputs into exact per-person
shares, called by `ExpenseService` before anything is persisted. Four split types
(`SplitType`), all producing shares that sum to the total **exactly**:

| Type | Participant input | Rule |
|---|---|---|
| `EQUAL` | none | Divided as evenly as `NUMERIC(19,4)` allows |
| `EXACT` | exact share amount | Must sum to the total precisely — no rounding tolerated, since the caller already did the arithmetic |
| `PERCENTAGE` | percentage (0–100) | Percentages must sum to exactly 100 |
| `SHARES` | positive integer weight | Proportional to each participant's weight (e.g. two housemates on 2 shares, a third on 1) |

### The rounding problem

Splitting 100.0000 three ways gives 33.3333 each, summing to 99.9999 — one ten-thousandth
(`0.0001`, the smallest unit `NUMERIC(19,4)` can represent) is unaccounted for. Left unhandled,
every uneven split would leak a residual, and a group's balances would never quite sum to zero.
`SplitCalculator` closes that gap the same way for every split type:

1. Compute each participant's raw share, floored to 4 decimal places (`RoundingMode.DOWN`) — no
   share is ever rounded up past what was actually allocated.
2. The residual (`total − Σshares`) is, by construction, a non-negative whole number of
   `0.0001` units, strictly fewer than the participant count.
3. Hand out one `0.0001` unit at a time to participants ordered by **user id ascending**, until
   the residual reaches zero.

For example, 100.0000 split `EQUAL` three ways: each gets 33.3333, leaving 0.0001 over, which
goes to whichever of the three participants has the lowest UUID — that participant ends up with
33.3334, the other two with 33.3333.

Ordering by user id rather than input order matters for a specific reason: the same expense
submitted twice — even with its participant list shuffled — must produce byte-identical splits,
because the idempotent replay in `ExpenseService` depends on two attempts of the same logical
request computing the same shares. It does mean the same low-numbered participant absorbs the
extra fraction on every expense that rounds their way; at `0.0001` per expense that's
immaterial.

Once shares are computed, `ExpenseService` turns them into the deltas sent to group-service:
every non-payer participant with a non-zero share owes the payer that share (a
`BalanceDeltaPayload(debtorId, creditorId, amount)` per participant). The payer's own row is
excluded — nobody owes themselves — and so is any zero share, since group-service rejects a
delta of exactly zero.

---

## Design notes

**Money is `BigDecimal`, everywhere** — never `double` or `float`. Amounts are compared with
`compareTo`, never `equals`; `equals` also compares scale, so `100.00` would not equal
`100.0000`. Columns are `NUMERIC(19,4)`, matching group-service digit for digit, and an amount
with more than four decimal places is rejected by validation rather than silently rounded.

**Flyway owns the schema**; Hibernate runs with `ddl-auto: validate` and fails at start-up if
the mapped entities and the migrated tables disagree. A schema recording expenses and
settlements must not change itself.

**No group id is ever a foreign key here.** Groups live in group-service, behind a different
database this service cannot read — every fact about a group's existence or membership is
learned exclusively through an HTTP call, using the caller's own forwarded bearer token, never
the service account's, for reads the caller is entitled to make for themselves.

**No `@Transactional` spans a saga.** An expense or settlement is several short local writes
separated by remote calls to group-service; wrapping the whole thing in one transaction would
hold a database connection for the entire round trip. The Hikari pool comment in
`application.yml` explains why the pool is sized for the number of concurrent operations, not
the number of writes each one makes.

**The circuit breaker only trips on "group-service is unwell."** `GroupOperationException` and
`NoSuchGroupException` are healthy, correct answers from a healthy service and are explicitly
excluded from both retry and breaker configuration in `application.yml` — otherwise a run of
expenses touching an archived group would trip the breaker and take expense recording down for
everyone. Only `GroupServiceUnavailableException` (timeouts, connection failures, 5xx) counts as
a failure.

**The service-account login pattern.** `group-service`'s internal `:apply` endpoint requires
`ROLE_ADMIN`, and the platform has no dedicated machine-identity mechanism, so expense-service
authenticates the same way a person would: it holds the credentials of one pre-provisioned
`ADMIN` account (`ServiceAccountProperties`) and logs in through the ordinary
`/api/v1/auth/login` flow. `AdminTokenProvider` caches the resulting token — good for roughly
fifteen minutes — and refreshes it a safety margin ahead of its stated expiry; a `401` from
group-service forces one out-of-cycle refresh-and-retry before giving up. See
[Running it](#running-it) for provisioning the account.

**Events are fire-and-forget.** By the time `ExpenseEventPublisher` is called, the expense or
settlement has already reached a terminal, persisted outcome — a notification that never
arrives is a degraded experience, not a lost expense. Failing the request over a Kafka send
failure would make notification-service's availability a dependency of the recording path. Kafka
messages are keyed by `groupId`, not by expense or settlement id: a group's activity feed and
unread-notification count are both read as a sequence, so every event for one group needs
in-order delivery relative to the others; ordering across different groups never matters.

**group-service's DTOs are declared in full**, not just the fields this service reads (e.g.
`GroupView`, `GroupBalancesView`). This module's Jackson 3 (`tools.jackson`) `ObjectMapper` has
no separate annotations artifact to opt out of strict binding with, unlike classic Jackson 2, so
matching the full response shape avoids relying on unknown-property leniency.

**resilience4j on Spring Boot 4**: the `spring-boot3` starter artifact (there is no
`spring-boot4` line yet) works correctly against Boot 4.1 — the `@CircuitBreaker` and `@Retry`
aspects and the `/actuator/circuitbreakers` endpoint all function — except that its
health-indicator autoconfiguration is conditional on classes Boot 4 relocated, so breaker state
does not appear in `/actuator/health`. Read it from `/actuator/circuitbreakers` or the
`resilience4j_*` Prometheus meters instead.

---

## Tests

```bash
./mvnw test
```

The integration test needs a Docker daemon for Testcontainers.

| Test | What it covers |
|---|---|
| `domain.SplitCalculatorTest` | Every split type's arithmetic, including the property-based check that shares always sum to the total exactly across many random totals and participant counts, and precisely which participant absorbs the residual fraction |
| `service.ExpenseServiceTest` | The expense saga with every collaborator mocked except the real `ExpenseMapper` and `SplitCalculator`: membership validation, idempotency reservation and its lost-race path, every replay outcome (cached, in-progress, failed, non-terminal), the apply call's outcomes (refused, ambiguous), voiding (including the replay-before-status-check ordering), and response caching |
| `service.SettlementServiceTest` | The settlement saga: same-user rejection, caller-must-be-a-party enforcement, the insufficient-debt check against a fresh balances read, and — the test this class exists to pin down — that a settlement's delta runs in the *reverse* direction of the debt it is settling |
| `ExpenseServiceApplicationTests` | Boots against a real PostgreSQL: Flyway creates all five tables, the idempotency unique constraint on `expenses.idempotency_key` exists, all five actuator endpoints are exposed, and the resilience4j configuration in YAML actually binds to the registries |
