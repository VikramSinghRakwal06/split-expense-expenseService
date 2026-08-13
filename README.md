# PayFlow payment-service

Orchestrates idempotent transfers between wallets for the PayFlow platform.

This service holds **no balances**. Money lives in `wallet-service`; what lives here is
the record of an attempt to move it — a saga of a debit, a credit, and (when the credit
fails) a compensating credit back to the sender, all driven from a `transactions` table
and made safe to retry by a client-supplied `Idempotency-Key`. See
[The transfer saga](#the-transfer-saga) for the state machine and
[Idempotency](#idempotency) for how a retried request is answered.

This service **validates** JWTs issued by `auth-service` using the shared signing secret.
It never mints a token, and it holds no user table — like `wallet-service`, it resolves
"who is the caller" entirely from the verified token.

---

## Contents

- [Running it](#running-it)
- [Environment variables](#environment-variables)
- [API](#api)
- [The transfer saga](#the-transfer-saga)
- [Idempotency](#idempotency)
- [Design notes](#design-notes)
- [Tests](#tests)

---

## Running it

Requires Java 21, PostgreSQL, and running instances of `auth-service` and `wallet-service`
— every transfer calls out to both.

```bash
# Dependencies
docker run -d --name payflow-pg-payment -p 5432:5432 \
  -e POSTGRES_USER=payflow -e POSTGRES_PASSWORD=payflow -e POSTGRES_DB=payflow_payment \
  postgres:16-alpine

# Register and promote the service account payment-service logs in as (see
# ServiceAccountProperties for why this exists): against a running auth-service,
curl -X POST http://localhost:8081/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"payment-service@payflow.internal","password":"dev-service-account-password-change-me","name":"payment-service"}'
# then, by hand, against auth-service's database:
#   UPDATE users SET role = 'ADMIN' WHERE email = 'payment-service@payflow.internal';

# The service, on the dev profile
./mvnw spring-boot:run
```

It listens on **8083**. Swagger UI is at <http://localhost:8083/swagger-ui.html>.

---

## Environment variables

Everything is read from the environment, with development fallbacks in
`application.yml`. The `prod` profile removes the fallbacks for every secret and every
cross-service address, so a production start-up fails fast rather than running against
`localhost`.

| Variable | Default (dev) | Required in prod | Purpose |
|---|---|---|---|
| `SERVER_PORT` | `8083` | no | HTTP port |
| `SPRING_PROFILES_ACTIVE` | `dev` | — | `dev` or `prod` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/payflow_payment` | **yes** | JDBC URL |
| `DB_USERNAME` | `payflow` | **yes** | Database user |
| `DB_PASSWORD` | `payflow` | **yes** | Database password |
| `DB_POOL_MAX` | `10` (prod `20`) | no | Hikari maximum pool size — see [Design notes](#design-notes) for why it is sized this way |
| `DB_POOL_MIN` | `2` (prod `5`) | no | Hikari minimum idle |
| `JWT_SECRET` | `dev-secret-change-me-…` | **yes** | HMAC-SHA key, ≥32 bytes. **Must match `auth-service` and `wallet-service`.** |
| `JWT_ISSUER` | `payflow-auth-service` | no | Required value of the `iss` claim. **Must match `auth-service`.** |
| `AUTH_SERVICE_URL` | `http://localhost:8081` | **yes** | Where the service account logs in |
| `AUTH_CONNECT_TIMEOUT` | `2s` | no | Connect timeout to auth-service |
| `AUTH_READ_TIMEOUT` | `3s` | no | Read timeout to auth-service |
| `SERVICE_ACCOUNT_EMAIL` | `payment-service@payflow.internal` | **yes** | Pre-provisioned `ROLE_ADMIN` account used only to call wallet-service's internal endpoints |
| `SERVICE_ACCOUNT_PASSWORD` | `dev-service-account-password-change-me` | **yes** | — |
| `WALLET_SERVICE_URL` | `http://localhost:8082` | **yes** | wallet-service's base URL |
| `WALLET_CONNECT_TIMEOUT` | `2s` | no | Deliberately short — see `application.yml` |
| `WALLET_READ_TIMEOUT` | `5s` | no | Deliberately asymmetric to the connect timeout — see `application.yml` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | **yes** | Not yet published to; reserved for a future `TRANSFER_COMPLETED` event |
| `SWAGGER_UI_ENABLED` | `true` (prod `false`) | no | Serve the interactive UI |
| `LOG_LEVEL` | `DEBUG` (prod `INFO`) | no | Level for `com.payflow.payment` |

> **`JWT_SECRET` and `JWT_ISSUER` must be identical across all three services.** Signature
> verification fails otherwise, and this service never signs a token of its own — it only
> ever forwards or verifies one.

---

## API

Base path `/api/v1/payments`. Every endpoint requires
`Authorization: Bearer <access token>`; only `/actuator/health` and the OpenAPI paths are
public.

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@payflow.io","password":"correct-horse-9"}' | jq -r .accessToken)
```

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/payments/transfer` | Transfer money from the caller's wallet to another — requires `Idempotency-Key` |
| `GET` | `/api/v1/payments/me` | Paginated transfer history, sent and received, newest first, 20 per page |

Neither endpoint accepts a wallet id for the caller's own side: the sender (or the wallet
whose history is being read) is always resolved from the verified JWT via wallet-service's
`/me`, so there is no parameter through which one user could move or read another user's
money.

### Transfer

```bash
curl -X POST http://localhost:8083/api/v1/payments/transfer \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"receiverWalletId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "amount": 250.00, "description": "Dinner split"}'
```

```json
{
  "id": "6f0ebc8d-b9ff-410a-8d4a-7e0da0689d02",
  "senderWalletId": "d8f17020-92be-4080-808c-4c3e99a55033",
  "receiverWalletId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "amount": 250.0000,
  "currency": "INR",
  "status": "COMPLETED",
  "failureReason": null,
  "description": "Dinner split",
  "createdAt": "2026-08-13T08:34:25.798722Z",
  "updatedAt": "2026-08-13T08:34:25.812114Z"
}
```

`Idempotency-Key` is required and client-generated. Retrying the same key with the same
body is always safe and returns the original outcome — see [Idempotency](#idempotency).
Retrying with a *different* body under the same key is a `422`.

| Status | Meaning |
|---|---|
| `200` | Transfer completed |
| `400` | Validation failed, or the receiver is the caller's own wallet |
| `404` | The caller has no wallet yet |
| `409` | A request with this key is already in progress, or the transfer failed and was safely reversed |
| `422` | The transfer was refused (e.g. insufficient funds), or the key was reused with a different request |
| `500` | The transfer could not be resolved automatically — see [Design notes](#design-notes) — **do not retry** |
| `503` | wallet-service is unavailable |

### Transfer history

```bash
curl "http://localhost:8083/api/v1/payments/me?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

Returns transfers where the caller's wallet is either the sender or the receiver.

---

## The transfer saga

```
INITIATED -> DEBITED -> COMPLETED
           \-> FAILED           (debit refused; nothing moved)
  DEBITED  -> REVERSED          (credit ambiguous or refused; compensating credit issued)
```

A transfer is several short, already-committed local writes separated by two remote calls
to wallet-service — never one long database transaction spanning the network round trip.
Each step is recorded to `audit_logs` in its own `REQUIRES_NEW` transaction, so the trail
of a *failed* payment survives that payment's own rollback.

The two remote calls are not symmetric:

- **The credit leg fails safely.** If crediting the receiver is refused or ambiguous, this
  service issues a compensating credit back to the sender. If that also succeeds, the
  transfer ends `REVERSED` — the net effect on both wallets is zero, as if nothing had been
  attempted. If the compensation *itself* fails, the transfer ends in the one state a
  client should never simply retry: `TransferUnresolvedException`, HTTP `500`, "do not
  retry; contact support with this transaction id." The row is left `DEBITED` for manual
  reconciliation against wallet-service's ledger.

- **The debit leg cannot be compensated the same way.** wallet-service's movement
  endpoints do not deduplicate by the `reference` this service sends them, so retrying an
  ambiguous debit risks debiting twice, and there is no "credit back" that is provably safe
  without first knowing whether the debit actually applied. An ambiguous debit is therefore
  left `INITIATED` rather than `FAILED`, is not retried, and a retry under the same
  idempotency key reports `TransferInProgressException` (`409`) rather than guessing.

What counts as "ambiguous" versus "refused" is decided entirely by which exception
`WalletClient` translates a wallet-service failure into — see its Javadoc and the
`resilience4j.*` block in `application.yml`, which is what decides whether a given failure
is retried and whether it counts against the shared `walletService` circuit breaker.

---

## Idempotency

Every transfer is initiated under a client-supplied `Idempotency-Key`. The flow:

1. Reserve the key by inserting a row into `idempotency_records`. A concurrent request for
   the same key loses this race and is served a replay instead of a duplicate attempt —
   arbitrated by a database unique constraint, not application logic, because a
   check-then-insert in code cannot close the race window a concurrent INSERT can.
2. Insert the `transactions` row itself, guarded by its own unique constraint on
   `idempotency_key` as a second backstop.
3. Run the saga above.
4. On completion, cache the serialised response on the `idempotency_records` row, so a
   later retry is replayed byte for byte without re-touching wallet-service at all.

A retry under a key that has not yet reached a terminal state (still `INITIATED` or
`DEBITED`, or no cached response yet) is answered `TransferInProgressException`, never
re-attempted. A retry under a key whose request body does not match what the key was first
used with is rejected outright (`422`) rather than replayed, since replaying would answer a
question the caller did not ask.

---

## Design notes

**Money is `BigDecimal`, everywhere** — never `double` or `float`. Amounts are compared
with `compareTo`, never `equals`; `equals` also compares scale, so `100.00` would not equal
`100.0000`. Columns are `NUMERIC(19,4)`, matching wallet-service digit for digit, and an
amount with more than four decimal places is rejected by validation rather than silently
rounded.

**Flyway owns the schema**; Hibernate runs with `ddl-auto: validate` and fails at start-up
if the mapped entities and the migrated tables disagree. A schema recording money movements
must not change itself.

**No wallet id is ever a foreign key here.** Wallets live in `wallet-service`, behind a
different database this service cannot read — every fact about a wallet's existence or
balance is learned exclusively through an HTTP call.

**No `@Transactional` spans a transfer.** Wrapping the whole saga in one transaction would
hold a database connection for the entire round trip to wallet-service; the Hikari pool
comment in `application.yml` explains why that is specifically what the pool is sized to
avoid. Each local write is its own short, already-committed transaction, and the
wallet-service calls happen entirely outside any of them.

**The circuit breaker only trips on "wallet-service is unwell."** `InsufficientFundsException`,
`WalletOperationException` and `NoWalletException` are healthy, correct answers from a
healthy service and are explicitly excluded from both the retry and breaker configuration
in `application.yml` — otherwise a run of customers with empty wallets would trip the
breaker and take payments down for everyone. Only `WalletServiceUnavailableException`
(timeouts, connection failures, 5xx) counts as a failure.

**resilience4j on Spring Boot 4**: the `spring-boot3` starter artifact (there is no
`spring-boot4` line yet) works correctly against Boot 4.1 — the `@CircuitBreaker` and
`@Retry` aspects and the `/actuator/circuitbreakers` endpoint all function — except that its
health-indicator autoconfiguration is conditional on classes Boot 4 relocated, so breaker
state does not appear in `/actuator/health`. Read it from `/actuator/circuitbreakers` or the
`resilience4j_*` Prometheus meters instead.

---

## Tests

```bash
./mvnw test
```

The integration test needs a Docker daemon for Testcontainers.

| Test | What it covers |
|---|---|
| `PaymentServiceTest` | The saga with every collaborator mocked: same-wallet rejection, idempotency reservation and its lost-race path, every replay outcome (cached, in-progress, failed, reversed, non-terminal), the debit leg's three outcomes (refused, insufficient funds, ambiguous), the credit leg's compensation (reversed, ambiguous-also-reversed, double-failure-unresolved), and response caching |
| `PaymentControllerTest` | `@WebMvcTest` slice: header requirements (`Idempotency-Key`, `Authorization`), request validation, and the full exception-to-status-code mapping from `GlobalExceptionHandler` |
| `PaymentServiceApplicationTests` | Boots against a real PostgreSQL: Flyway creates all three tables, the idempotency unique constraint exists, all five actuator endpoints are exposed, and the resilience4j configuration in YAML actually binds to the registries |

# payment-service