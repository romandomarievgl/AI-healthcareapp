# Most-Frequent-Drugs Feature — Design

**Date:** 2026-06-08
**Status:** Approved (iteration 1)
**Author:** roman.domariev

## 1. Problem

The healthcare platform has a patient base, and patients use drugs. The first
feature must answer: **which drugs are used by the most patients?** — a top-N
ranking of drugs by the number of *distinct patients* taking them.

The client base is expected to grow fast, so the solution must be **resilient**
(correct under failure / duplicate delivery) and **elastic** (cost must not
scale with patient count).

## 2. Decisions (locked for iteration 1)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Frequency metric | **Distinct patients per drug** | "Population on this medication" |
| Freshness | **Near-real-time, slightly stale OK** | Enables precomputed/streamed aggregates |
| Data source | **External systems, mocked** | Focus is the counting implementation |
| Accuracy | **Approximate (HyperLogLog)** | Distinct-count at scale; elastic by nature |
| Deployment | **In-process (Approach A)** | Runnable as pure Spring Boot; upgradeable |

Iteration 2+ (not in scope now): physically-distributed state via Redis
`PFADD`/`PFCOUNT`, or Kafka Streams with HLL state stores.

## 3. Architecture

Single Spring Boot 4 / Java 21 service. Four layers, each behind an interface so
in-process pieces can be swapped for distributed ones without touching the domain.

```
MockMedicationFeed ──> MedicationIngestionService ──> DrugFrequencyCounter ──> DrugFrequencyController
   (mocked source)        (validate + forward)        (HyperLogLog state)        (GET top-N)
                                                              │
                                                       HllSnapshotStore (JPA) — crash recovery
```

The reusable core is `DrugFrequencyCounter`. Everything else feeds it or reads it.

## 4. Components

### Domain
- **`MedicationEvent`** (record): `patientId`, `drugCode`, `drugName`, `occurredAt`.
  The unit arriving from external systems.
- **`DrugFrequency`** (record): `drugCode`, `drugName`, `estimatedDistinctPatients`.

### Counting core (the seam for elasticity)
- **`DrugFrequencyCounter`** (interface):
  - `void record(MedicationEvent event)`
  - `List<DrugFrequency> topN(int n)`
  - `long distinctPatients(String drugCode)`
- **`HyperLogLogDrugFrequencyCounter`** (implementation):
  - State: `ConcurrentHashMap<String drugCode, HLL sketch>`.
  - `record`: add `hash(patientId)` to the drug's sketch; per-sketch lock for
    thread safety.
  - `topN`: estimate cardinality for every drug and rank. The number of distinct
    drugs (thousands) is far smaller than patients (millions+), so an on-demand
    scan is cheap. No separate maintained leaderboard needed in iteration 1.
  - HLL: stream-lib `HyperLogLogPlus` — mergeable, ~0.8% standard error,
    ~few KB per drug.

### Ingestion
- **`MedicationIngestionService`**: validates events (reject + log malformed —
  missing `patientId`/`drugCode`), forwards valid events to the counter.
- **`MockMedicationFeed`**: generates a stream of `MedicationEvent`s over a
  synthetic patient/drug catalog using a **Zipfian** drug distribution, so some
  drugs are clearly more popular and rankings are testable. Driven by a scheduler
  and/or a trigger endpoint. Swappable with a real external source later.

### API
- **`DrugFrequencyController`**:
  - `GET /api/v1/drugs/top?limit=10` → ranked `List<DrugFrequency>`.
  - `GET /api/v1/drugs/{drugCode}/patient-count` → estimated distinct patients.

### Persistence (resilience)
- **`HllSnapshotStore`**: JPA entity (`drugCode`, serialized sketch bytes,
  `updatedAt`) + repository. Periodic + on-shutdown snapshot of all sketches;
  reload on startup. Recovery can also merge snapshot + replay of recent events.

## 5. Why this is resilient & elastic (by design)

- **Idempotent ingestion for free:** adding the same `(drug, patient)` to an HLL
  twice does not change cardinality. At-least-once / duplicate delivery from
  external feeds is therefore *safe* with no event-dedup machinery. This is the
  key correctness-under-failure property.
- **Mergeable state → horizontal scale:** HLLs merge losslessly. Run N replicas,
  each consuming a slice of the feed; merge sketches for a global top-N. The
  `DrugFrequencyCounter` interface is unchanged. Redis `PFADD` is the drop-in
  distributed backing for iteration 2.
- **Bounded memory:** cost grows with the *number of drugs* (thousands), not the
  *number of patients* (millions+). Patient growth is essentially free.
- **Crash recovery:** snapshot/reload via JPA; never need to replay full history.

## 6. Error handling

- Malformed events are rejected and logged without killing the feed.
- API validates `limit` (e.g. 1..1000); out-of-range → 400.
- Snapshot failures are logged and retried on the next tick; they never block
  ingestion or reads.

## 7. Package layout (`com.example.demo`)

```
medication/    MedicationEvent
counter/       DrugFrequencyCounter, HyperLogLogDrugFrequencyCounter, DrugFrequency
ingestion/     MedicationIngestionService, MockMedicationFeed
api/           DrugFrequencyController
persistence/   HllSnapshotStore (entity + repository), snapshot scheduler
```

## 8. Dependencies

- `com.clearspring.analytics:stream` (HyperLogLogPlus) — added to `build.gradle`.
- Existing: spring-boot-starter-data-jpa, spring-boot-starter-webmvc.

## 9. Test strategy

See the dedicated **Test Strategy** section delivered alongside this spec; the
TDD-driven plan covers HLL accuracy bounds (validated against an exact reference
oracle), idempotency, top-N ordering on Zipfian data, snapshot round-trip, and a
MockMvc controller integration test.

## 10. Out of scope (iteration 1)

- Distributed/shared state (Redis, Kafka) — deferred to iteration 2.
- Real external ingestion adapters (HL7/FHIR) — feed is mocked.
- AuthN/Z, multi-tenancy, audit logging.
- A maintained real-time leaderboard structure (on-demand scan suffices now).
