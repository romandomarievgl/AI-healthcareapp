# Most-Frequent-Drugs Service

A healthcare-platform feature that answers one question at scale:

> **Which drugs are used by the most patients?**

It consumes a stream of medication events (mocked here, from external systems in
production) and serves a near-real-time **top-N ranking of drugs by the number of
distinct patients** taking them.

The design target is a fast-growing client base, so the solution is built to be
**elastic** (cost grows with the number of *drugs*, not *patients*) and
**resilient** (correct under duplicate delivery; survives restarts).

---

## What it does

- Ingests `MedicationEvent`s: `(patientId, drugCode, drugName, occurredAt)`.
- Counts **distinct patients per drug** using a HyperLogLog sketch per drug.
- Serves a top-N ranking and per-drug patient-count estimate over REST.
- Periodically snapshots its state to a database and restores it on startup.

### Why HyperLogLog?

Counting *distinct* patients exactly would cost memory/storage that grows with
`patients × drugs`. HyperLogLog estimates set cardinality in a fixed ~few KB per
drug regardless of how many patients exist, with ~0.8% standard error. This gives
three properties the platform needs:

| Property | How HLL provides it |
|----------|---------------------|
| **Elastic** | Memory scales with drug count (thousands), not patient count (millions+). |
| **Resilient to duplicates** | Adding the same `(drug, patient)` twice doesn't change the estimate, so at-least-once / duplicate delivery from external feeds is safe — no dedup machinery needed. |
| **Horizontally scalable** | HLL sketches merge losslessly, so per-replica counts can be combined into a global top-N. |

---

## Architecture

```
MockMedicationFeed ──> MedicationIngestionService ──> DrugFrequencyCounter ──> DrugFrequencyController
   (mocked source)        (validate + forward)        (HyperLogLog state)        (GET top-N / count)
                                                              │
                                                       SnapshotService ──> HllSnapshotRepository (JPA / H2)
                                                       (scheduled + on shutdown / startup restore)
```

The reusable core is the `DrugFrequencyCounter` interface — everything else
feeds it or reads from it. The interface is the seam for future scaling: the
in-process HyperLogLog implementation can be swapped for a Redis- or
Kafka-backed one without touching the surrounding code.

### Packages (`com.example.demo`)

| Package | Responsibility |
|---------|----------------|
| `medication` | `MedicationEvent` — the incoming event record. |
| `counter` | `DrugFrequencyCounter` (interface), `HyperLogLogDrugFrequencyCounter` (one mergeable HLL sketch per drug, thread-safe), `DrugFrequency` result record, `SnapshotSupport` + `DrugSketchSnapshot` for export/restore. |
| `ingestion` | `MedicationIngestionService` (validates events, drops malformed ones) and `MockMedicationFeed` (generates a Zipf-distributed synthetic stream). |
| `api` | `DrugFrequencyController` REST endpoints + `PatientCountResponse` DTO. |
| `persistence` | `HllSnapshot` JPA entity, `HllSnapshotRepository`, and `SnapshotService` (scheduled snapshot, startup restore, shutdown flush, failure isolation). |

### Key design choices

- **`MockMedicationFeed`** stands in for a real external feed (HL7/FHIR, pharmacy
  systems, file drops). It emits events with a **Zipfian** drug distribution so a
  few drugs dominate — realistic, and it makes rankings observable. Replace it
  with a real adapter that calls `MedicationIngestionService.ingest(...)`.
- **Snapshotting** serializes each drug's HLL sketch to the `hll_snapshot` table
  on a schedule and on shutdown, and reloads on startup — so counts survive a
  restart without replaying full history. Snapshot failures are logged and
  retried on the next tick; they never block ingestion or reads.
- **Approximate, by design.** Counts are estimates (~0.8% error). This is the
  deliberate trade-off that makes the service elastic.

---

## Tech stack

- Java 21, Spring Boot 4 (Web MVC + Data JPA)
- [stream-lib](https://github.com/addthis/stream-lib) `HyperLogLogPlus` for cardinality estimation
- H2 (embedded, in-memory) for snapshot storage
- JUnit 5 + AssertJ + Mockito for tests
- Gradle (wrapper included)

---

## Build & run

> On Windows use `gradlew.bat`; on macOS/Linux use `./gradlew`.

```bash
# Run the test suite (25 tests)
./gradlew test

# Start the service (defaults to http://localhost:8080)
./gradlew bootRun
```

On startup the `MockMedicationFeed` begins emitting ~1,000 events every 5 seconds,
so counts populate automatically. Within a few seconds the ranking endpoint
returns data.

---

## Using the API

### Top-N drugs by distinct patients

```
GET /api/v1/drugs/top?limit=10
```

`limit` is optional (default `10`, must be `1..1000`; out of range → `400`).

```bash
curl "http://localhost:8080/api/v1/drugs/top?limit=5"
```

```json
[
  { "drugCode": "D0", "drugName": "drug-0", "estimatedDistinctPatients": 8123 },
  { "drugCode": "D1", "drugName": "drug-1", "estimatedDistinctPatients": 4061 },
  { "drugCode": "D2", "drugName": "drug-2", "estimatedDistinctPatients": 2701 },
  { "drugCode": "D3", "drugName": "drug-3", "estimatedDistinctPatients": 2032 },
  { "drugCode": "D4", "drugName": "drug-4", "estimatedDistinctPatients": 1625 }
]
```

### Distinct-patient estimate for one drug

```
GET /api/v1/drugs/{drugCode}/patient-count
```

```bash
curl "http://localhost:8080/api/v1/drugs/D0/patient-count"
```

```json
{ "drugCode": "D0", "estimatedDistinctPatients": 8123 }
```

An unknown drug returns an estimate of `0`.

---

## Configuration

Set these in `src/main/resources/application.properties` or as
`--property=value` flags on `bootRun`:

| Property | Default | Purpose |
|----------|---------|---------|
| `drugs.feed.interval-ms` | `5000` | How often the mock feed emits a batch. |
| `drugs.feed.seed` | `42` | Seed for the mock feed's RNG (deterministic output). |
| `drugs.snapshot.interval-ms` | `30000` | How often state is snapshotted to the DB. |

By default snapshots are stored in an in-memory H2 database, so they survive a
JVM restart only while the process group's H2 instance lives. Point
`spring.datasource.*` at a persistent database to retain counts across full
restarts.

---

## Testing

The suite is organized by layer (TDD-driven):

- **Counter** — HLL accuracy validated against an exact reference oracle within
  2%; idempotency; top-N ordering; thread safety under concurrent recording.
- **Persistence** — snapshot round-trip, crash-recovery-then-replay, and snapshot
  failure isolation.
- **Ingestion** — event validation; Zipfian skew of the mock feed.
- **API** — `@WebMvcTest` controller slice; full `@SpringBootTest` end-to-end
  (feed → ingestion → counter → controller).

```bash
./gradlew test
```

---

## Roadmap (future iterations)

This is iteration 1 — an in-process implementation that is elastic *by design*.
The `DrugFrequencyCounter` interface makes the following drop-in:

- **Distributed state:** back the counter with Redis `PFADD`/`PFCOUNT` so many
  instances share one set of sketches.
- **Streaming at scale:** Kafka Streams with HLL state stores for partitioned,
  replayable, fault-tolerant aggregation.
- **Real ingestion:** replace `MockMedicationFeed` with an HL7/FHIR adapter.
- **Cross-replica merge:** combine per-replica sketches into a global top-N.

See `docs/superpowers/specs/2026-06-08-most-frequent-drugs-design.md` for the
full design and `docs/superpowers/plans/2026-06-08-most-frequent-drugs.md` for
the implementation plan.
