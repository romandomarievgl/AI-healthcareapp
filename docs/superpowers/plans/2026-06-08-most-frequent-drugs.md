# Most-Frequent-Drugs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot feature that reports the top-N drugs ranked by the number of *distinct patients* taking them, fed by a mocked external medication stream, using an in-process HyperLogLog counter with JPA snapshot recovery.

**Architecture:** A `DrugFrequencyCounter` interface backed by a HyperLogLog implementation (one mergeable sketch per drug) is the reusable core. A mock feed validates and pushes `MedicationEvent`s through an ingestion service into the counter; a REST controller reads top-N out. Sketches are periodically snapshotted to a JPA table for crash recovery. Approximate counting keeps memory bounded by drug count, not patient count.

**Tech Stack:** Java 21, Spring Boot 4 (Web MVC + Data JPA), stream-lib `HyperLogLogPlus` (`com.clearspring.analytics:stream`), H2 (embedded DB), JUnit 5 + AssertJ, Gradle.

**Spec:** `docs/superpowers/specs/2026-06-08-most-frequent-drugs-design.md`

---

## File Structure

| File | Responsibility |
|------|----------------|
| `build.gradle` | Add stream-lib + H2 dependencies |
| `src/main/resources/application.properties` | JPA/H2 config |
| `medication/MedicationEvent.java` | Incoming event record |
| `counter/DrugFrequency.java` | Ranked-result record |
| `counter/DrugFrequencyCounter.java` | Core counting interface |
| `counter/DrugSketchSnapshot.java` | Serialized-sketch transfer record |
| `counter/SnapshotSupport.java` | Export/restore capability interface |
| `counter/HyperLogLogDrugFrequencyCounter.java` | HLL-backed implementation |
| `persistence/HllSnapshot.java` | JPA entity for a stored sketch |
| `persistence/HllSnapshotRepository.java` | Spring Data repository |
| `persistence/SnapshotService.java` | Save/load + scheduled snapshot + startup restore |
| `ingestion/MedicationIngestionService.java` | Validate + forward events |
| `ingestion/MockMedicationFeed.java` | Zipfian synthetic event generator |
| `api/PatientCountResponse.java` | Response DTO for per-drug count |
| `api/DrugFrequencyController.java` | REST endpoints |

Test files mirror these under `src/test/java/...`. The **exact reference counter** (`ExactDrugFrequencyCounter`) lives only in test sources.

---

## Task 0: Project setup — dependencies, DB config, scaffolding cleanup

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.properties`
- Delete: `src/main/java/com/example/demo/Demo.java`

- [ ] **Step 1: Add dependencies to `build.gradle`**

Replace the `dependencies { ... }` block with:

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'com.clearspring.analytics:stream:2.9.8'
    runtimeOnly 'com.h2database:h2'
    testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

- [ ] **Step 2: Configure H2 + JPA in `application.properties`**

```properties
spring.application.name=demo
spring.datasource.url=jdbc:h2:mem:drugs;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
```

- [ ] **Step 3: Delete the empty scaffold class**

```bash
git rm src/main/java/com/example/demo/Demo.java
```

- [ ] **Step 4: Verify the project still builds and the context loads**

Run: `./gradlew test --tests "com.example.demo.DemoApplicationTests"`
Expected: `BUILD SUCCESSFUL`, `contextLoads()` passes.

- [ ] **Step 5: Commit**

```bash
git add build.gradle src/main/resources/application.properties
git commit -m "chore: add stream-lib + H2 deps and JPA config"
```

---

## Task 1: Domain records — `MedicationEvent` and `DrugFrequency`

**Files:**
- Create: `src/main/java/com/example/demo/medication/MedicationEvent.java`
- Create: `src/main/java/com/example/demo/counter/DrugFrequency.java`

These are simple value records with no behavior to test in isolation; they are exercised by every later task. No dedicated test.

- [ ] **Step 1: Create `MedicationEvent`**

```java
package com.example.demo.medication;

import java.time.Instant;

public record MedicationEvent(
        String patientId,
        String drugCode,
        String drugName,
        Instant occurredAt) {
}
```

- [ ] **Step 2: Create `DrugFrequency`**

```java
package com.example.demo.counter;

public record DrugFrequency(
        String drugCode,
        String drugName,
        long estimatedDistinctPatients) {
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/demo/medication/MedicationEvent.java src/main/java/com/example/demo/counter/DrugFrequency.java
git commit -m "feat: add MedicationEvent and DrugFrequency domain records"
```

---

## Task 2: `DrugFrequencyCounter` interface + exact reference oracle (test-only)

The exact counter is the test oracle the HLL implementation is validated against. Defining it first locks the contract.

**Files:**
- Create: `src/main/java/com/example/demo/counter/DrugFrequencyCounter.java`
- Create: `src/test/java/com/example/demo/counter/ExactDrugFrequencyCounter.java`
- Test: `src/test/java/com/example/demo/counter/ExactDrugFrequencyCounterTest.java`

- [ ] **Step 1: Create the interface**

```java
package com.example.demo.counter;

import com.example.demo.medication.MedicationEvent;
import java.util.List;

public interface DrugFrequencyCounter {

    void record(MedicationEvent event);

    List<DrugFrequency> topN(int n);

    long distinctPatients(String drugCode);
}
```

- [ ] **Step 2: Write the failing test for the exact oracle**

```java
package com.example.demo.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.medication.MedicationEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExactDrugFrequencyCounterTest {

    private MedicationEvent event(String patient, String drugCode, String drugName) {
        return new MedicationEvent(patient, drugCode, drugName, Instant.EPOCH);
    }

    @Test
    void countsDistinctPatientsPerDrug() {
        DrugFrequencyCounter counter = new ExactDrugFrequencyCounter();
        counter.record(event("p1", "D1", "Aspirin"));
        counter.record(event("p2", "D1", "Aspirin"));
        counter.record(event("p1", "D2", "Ibuprofen"));

        assertThat(counter.distinctPatients("D1")).isEqualTo(2);
        assertThat(counter.distinctPatients("D2")).isEqualTo(1);
    }

    @Test
    void duplicatePatientDrugPairDoesNotInflateCount() {
        DrugFrequencyCounter counter = new ExactDrugFrequencyCounter();
        counter.record(event("p1", "D1", "Aspirin"));
        counter.record(event("p1", "D1", "Aspirin"));

        assertThat(counter.distinctPatients("D1")).isEqualTo(1);
    }

    @Test
    void topNReturnsDrugsOrderedByDistinctPatientsDescending() {
        DrugFrequencyCounter counter = new ExactDrugFrequencyCounter();
        counter.record(event("p1", "D1", "Aspirin"));
        counter.record(event("p2", "D1", "Aspirin"));
        counter.record(event("p3", "D1", "Aspirin"));
        counter.record(event("p1", "D2", "Ibuprofen"));
        counter.record(event("p2", "D2", "Ibuprofen"));
        counter.record(event("p1", "D3", "Paracetamol"));

        assertThat(counter.topN(2))
                .extracting(DrugFrequency::drugCode)
                .containsExactly("D1", "D2");
    }

    @Test
    void topNHandlesEmptyAndOversizedRequests() {
        DrugFrequencyCounter counter = new ExactDrugFrequencyCounter();
        assertThat(counter.topN(5)).isEmpty();

        counter.record(event("p1", "D1", "Aspirin"));
        assertThat(counter.topN(0)).isEmpty();
        assertThat(counter.topN(10)).hasSize(1);
    }

    @Test
    void distinctPatientsForUnknownDrugIsZero() {
        DrugFrequencyCounter counter = new ExactDrugFrequencyCounter();
        assertThat(counter.distinctPatients("nope")).isZero();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.example.demo.counter.ExactDrugFrequencyCounterTest"`
Expected: FAIL — `ExactDrugFrequencyCounter` does not exist (compilation error).

- [ ] **Step 4: Implement the exact oracle**

```java
package com.example.demo.counter;

import com.example.demo.medication.MedicationEvent;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact, in-memory reference counter used as a test oracle. Not for production. */
public class ExactDrugFrequencyCounter implements DrugFrequencyCounter {

    private record DrugState(String drugName, Set<String> patients) {}

    private final Map<String, DrugState> states = new HashMap<>();

    @Override
    public void record(MedicationEvent event) {
        states.computeIfAbsent(event.drugCode(),
                        k -> new DrugState(event.drugName(), new HashSet<>()))
                .patients().add(event.patientId());
    }

    @Override
    public List<DrugFrequency> topN(int n) {
        if (n <= 0) {
            return List.of();
        }
        return states.entrySet().stream()
                .map(e -> new DrugFrequency(
                        e.getKey(), e.getValue().drugName(), e.getValue().patients().size()))
                .sorted(Comparator.comparingLong(DrugFrequency::estimatedDistinctPatients).reversed()
                        .thenComparing(DrugFrequency::drugCode))
                .limit(n)
                .toList();
    }

    @Override
    public long distinctPatients(String drugCode) {
        DrugState state = states.get(drugCode);
        return state == null ? 0 : state.patients().size();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.example.demo.counter.ExactDrugFrequencyCounterTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/demo/counter/DrugFrequencyCounter.java src/test/java/com/example/demo/counter/ExactDrugFrequencyCounter.java src/test/java/com/example/demo/counter/ExactDrugFrequencyCounterTest.java
git commit -m "feat: add DrugFrequencyCounter interface and exact test oracle"
```

---

## Task 3: `HyperLogLogDrugFrequencyCounter` — accuracy, idempotency, ordering

**Files:**
- Create: `src/main/java/com/example/demo/counter/HyperLogLogDrugFrequencyCounter.java`
- Test: `src/test/java/com/example/demo/counter/HyperLogLogDrugFrequencyCounterTest.java`

- [ ] **Step 1: Write the failing tests (incl. accuracy vs. exact oracle)**

```java
package com.example.demo.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.medication.MedicationEvent;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HyperLogLogDrugFrequencyCounterTest {

    private MedicationEvent event(String patient, String drugCode, String drugName) {
        return new MedicationEvent(patient, drugCode, drugName, Instant.EPOCH);
    }

    @Test
    void estimatesDistinctPatientsWithinTwoPercentOfExact() {
        DrugFrequencyCounter hll = new HyperLogLogDrugFrequencyCounter();
        DrugFrequencyCounter exact = new ExactDrugFrequencyCounter();
        Random random = new Random(42);

        for (int i = 0; i < 100_000; i++) {
            String patient = "patient-" + i;
            String drugCode = "D" + random.nextInt(50);
            MedicationEvent e = event(patient, drugCode, "drug-" + drugCode);
            hll.record(e);
            exact.record(e);
        }

        for (int d = 0; d < 50; d++) {
            String drugCode = "D" + d;
            long exactCount = exact.distinctPatients(drugCode);
            long hllCount = hll.distinctPatients(drugCode);
            double error = Math.abs(hllCount - exactCount) / (double) exactCount;
            assertThat(error)
                    .as("relative error for %s (exact=%d, hll=%d)", drugCode, exactCount, hllCount)
                    .isLessThanOrEqualTo(0.02);
        }
    }

    @Test
    void duplicatePatientDrugPairDoesNotChangeEstimate() {
        DrugFrequencyCounter hll = new HyperLogLogDrugFrequencyCounter();
        hll.record(event("p1", "D1", "Aspirin"));
        long once = hll.distinctPatients("D1");

        for (int i = 0; i < 1000; i++) {
            hll.record(event("p1", "D1", "Aspirin"));
        }

        assertThat(hll.distinctPatients("D1")).isEqualTo(once);
    }

    @Test
    void topNReturnsDrugsOrderedByDistinctPatientsDescending() {
        DrugFrequencyCounter hll = new HyperLogLogDrugFrequencyCounter();
        // D1: 300 patients, D2: 200, D3: 100 — clearly separable for HLL.
        record(hll, "D1", "Aspirin", 300);
        record(hll, "D2", "Ibuprofen", 200);
        record(hll, "D3", "Paracetamol", 100);

        List<DrugFrequency> top = hll.topN(2);
        assertThat(top).extracting(DrugFrequency::drugCode).containsExactly("D1", "D2");
    }

    @Test
    void emptyCounterAndUnknownDrugBehaveSafely() {
        DrugFrequencyCounter hll = new HyperLogLogDrugFrequencyCounter();
        assertThat(hll.topN(5)).isEmpty();
        assertThat(hll.distinctPatients("nope")).isZero();
        assertThat(hll.topN(0)).isEmpty();
    }

    private void record(DrugFrequencyCounter counter, String drugCode, String name, int patients) {
        for (int i = 0; i < patients; i++) {
            counter.record(event(drugCode + "-patient-" + i, drugCode, name));
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.example.demo.counter.HyperLogLogDrugFrequencyCounterTest"`
Expected: FAIL — `HyperLogLogDrugFrequencyCounter` does not exist.

- [ ] **Step 3: Implement the HLL counter**

```java
package com.example.demo.counter;

import com.clearspring.analytics.stream.cardinality.HyperLogLogPlus;
import com.example.demo.medication.MedicationEvent;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-process HyperLogLog counter: one mergeable sketch per drug.
 * Memory grows with the number of drugs, not the number of patients.
 */
@Component
public class HyperLogLogDrugFrequencyCounter implements DrugFrequencyCounter, SnapshotSupport {

    /** p=14 -> ~16k registers, ~0.8% standard error. sp=25 sparse precision. */
    private static final int PRECISION = 14;
    private static final int SPARSE_PRECISION = 25;

    private static final class DrugState {
        final String drugName;
        final HyperLogLogPlus sketch;

        DrugState(String drugName, HyperLogLogPlus sketch) {
            this.drugName = drugName;
            this.sketch = sketch;
        }
    }

    private final Map<String, DrugState> states = new ConcurrentHashMap<>();

    @Override
    public void record(MedicationEvent event) {
        DrugState state = states.computeIfAbsent(event.drugCode(),
                k -> new DrugState(event.drugName(),
                        new HyperLogLogPlus(PRECISION, SPARSE_PRECISION)));
        synchronized (state.sketch) {
            state.sketch.offer(event.patientId());
        }
    }

    @Override
    public List<DrugFrequency> topN(int n) {
        if (n <= 0) {
            return List.of();
        }
        return states.entrySet().stream()
                .map(e -> new DrugFrequency(e.getKey(), e.getValue().drugName, cardinality(e.getValue())))
                .sorted(Comparator.comparingLong(DrugFrequency::estimatedDistinctPatients).reversed()
                        .thenComparing(DrugFrequency::drugCode))
                .limit(n)
                .toList();
    }

    @Override
    public long distinctPatients(String drugCode) {
        DrugState state = states.get(drugCode);
        return state == null ? 0 : cardinality(state);
    }

    private long cardinality(DrugState state) {
        synchronized (state.sketch) {
            return state.sketch.cardinality();
        }
    }

    // --- SnapshotSupport ---

    @Override
    public List<DrugSketchSnapshot> export() {
        return states.entrySet().stream()
                .map(e -> {
                    synchronized (e.getValue().sketch) {
                        try {
                            return new DrugSketchSnapshot(
                                    e.getKey(), e.getValue().drugName, e.getValue().sketch.getBytes());
                        } catch (java.io.IOException ex) {
                            throw new IllegalStateException("Failed to serialize sketch for " + e.getKey(), ex);
                        }
                    }
                })
                .toList();
    }

    @Override
    public void restore(List<DrugSketchSnapshot> snapshots) {
        for (DrugSketchSnapshot snapshot : snapshots) {
            try {
                HyperLogLogPlus sketch =
                        (HyperLogLogPlus) HyperLogLogPlus.Builder.build(snapshot.sketchBytes());
                states.put(snapshot.drugCode(), new DrugState(snapshot.drugName(), sketch));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("Failed to deserialize sketch for " + snapshot.drugCode(), ex);
            }
        }
    }
}
```

> Note: `SnapshotSupport` and `DrugSketchSnapshot` are created in the next step so this class compiles.

- [ ] **Step 4: Create `SnapshotSupport` and `DrugSketchSnapshot`**

`src/main/java/com/example/demo/counter/DrugSketchSnapshot.java`:

```java
package com.example.demo.counter;

public record DrugSketchSnapshot(String drugCode, String drugName, byte[] sketchBytes) {
}
```

`src/main/java/com/example/demo/counter/SnapshotSupport.java`:

```java
package com.example.demo.counter;

import java.util.List;

/** Capability for exporting/restoring counter state for crash recovery. */
public interface SnapshotSupport {

    List<DrugSketchSnapshot> export();

    void restore(List<DrugSketchSnapshot> snapshots);
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.example.demo.counter.HyperLogLogDrugFrequencyCounterTest"`
Expected: PASS (4 tests). The accuracy test runs 100k inserts; allow a few seconds.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/demo/counter/
git add src/test/java/com/example/demo/counter/HyperLogLogDrugFrequencyCounterTest.java
git commit -m "feat: add HyperLogLog drug frequency counter with snapshot support"
```

---

## Task 4: Concurrency safety test for the HLL counter

**Files:**
- Test: `src/test/java/com/example/demo/counter/HyperLogLogConcurrencyTest.java`

- [ ] **Step 1: Write the failing/again-passing concurrency test**

```java
package com.example.demo.counter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.medication.MedicationEvent;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HyperLogLogConcurrencyTest {

    @Test
    void concurrentRecordingProducesAccurateEstimateWithoutErrors() throws InterruptedException {
        DrugFrequencyCounter counter = new HyperLogLogDrugFrequencyCounter();
        int threads = 8;
        int patientsPerThread = 12_500; // 100k distinct patients total on D1
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            int base = t * patientsPerThread;
            pool.submit(() -> {
                for (int i = 0; i < patientsPerThread; i++) {
                    counter.record(new MedicationEvent(
                            "patient-" + (base + i), "D1", "Aspirin", Instant.EPOCH));
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        long estimate = counter.distinctPatients("D1");
        double error = Math.abs(estimate - 100_000) / 100_000.0;
        assertThat(error).as("estimate=%d", estimate).isLessThanOrEqualTo(0.02);
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests "com.example.demo.counter.HyperLogLogConcurrencyTest"`
Expected: PASS. (Validates the per-sketch `synchronized` guard — no lost updates, no exceptions.)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/example/demo/counter/HyperLogLogConcurrencyTest.java
git commit -m "test: verify HLL counter is thread-safe under concurrent recording"
```

---

## Task 5: JPA entity + repository for sketch snapshots

**Files:**
- Create: `src/main/java/com/example/demo/persistence/HllSnapshot.java`
- Create: `src/main/java/com/example/demo/persistence/HllSnapshotRepository.java`

No standalone test; exercised by Task 6's `@DataJpaTest`.

- [ ] **Step 1: Create the entity**

```java
package com.example.demo.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "hll_snapshot")
public class HllSnapshot {

    @Id
    @Column(name = "drug_code", nullable = false)
    private String drugCode;

    @Column(name = "drug_name", nullable = false)
    private String drugName;

    @Lob
    @Column(name = "sketch_bytes", nullable = false)
    private byte[] sketchBytes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HllSnapshot() {
        // for JPA
    }

    public HllSnapshot(String drugCode, String drugName, byte[] sketchBytes, Instant updatedAt) {
        this.drugCode = drugCode;
        this.drugName = drugName;
        this.sketchBytes = sketchBytes;
        this.updatedAt = updatedAt;
    }

    public String getDrugCode() {
        return drugCode;
    }

    public String getDrugName() {
        return drugName;
    }

    public byte[] getSketchBytes() {
        return sketchBytes;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
```

- [ ] **Step 2: Create the repository**

```java
package com.example.demo.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HllSnapshotRepository extends JpaRepository<HllSnapshot, String> {
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/demo/persistence/HllSnapshot.java src/main/java/com/example/demo/persistence/HllSnapshotRepository.java
git commit -m "feat: add HllSnapshot JPA entity and repository"
```

---

## Task 6: `SnapshotService` — save/load round-trip + crash recovery

**Files:**
- Create: `src/main/java/com/example/demo/persistence/SnapshotService.java`
- Test: `src/test/java/com/example/demo/persistence/SnapshotServiceTest.java`

- [ ] **Step 1: Write the failing test (round-trip + recovery-with-replay)**

```java
package com.example.demo.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.counter.DrugFrequency;
import com.example.demo.counter.HyperLogLogDrugFrequencyCounter;
import com.example.demo.medication.MedicationEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({SnapshotService.class, HyperLogLogDrugFrequencyCounter.class})
class SnapshotServiceTest {

    @Autowired
    private HllSnapshotRepository repository;

    @Autowired
    private SnapshotService snapshotService;

    private MedicationEvent event(String patient, String drugCode, String name) {
        return new MedicationEvent(patient, drugCode, name, Instant.EPOCH);
    }

    @Test
    void snapshotRoundTripPreservesEstimates() {
        HyperLogLogDrugFrequencyCounter source = new HyperLogLogDrugFrequencyCounter();
        for (int i = 0; i < 500; i++) {
            source.record(event("p" + i, "D1", "Aspirin"));
        }
        for (int i = 0; i < 200; i++) {
            source.record(event("p" + i, "D2", "Ibuprofen"));
        }

        snapshotService.save(source);

        HyperLogLogDrugFrequencyCounter restored = new HyperLogLogDrugFrequencyCounter();
        snapshotService.restoreInto(restored);

        assertThat(restored.distinctPatients("D1")).isEqualTo(source.distinctPatients("D1"));
        assertThat(restored.distinctPatients("D2")).isEqualTo(source.distinctPatients("D2"));
        assertThat(restored.topN(2)).extracting(DrugFrequency::drugCode).containsExactly("D1", "D2");
    }

    @Test
    void recoveryThenReplayAccumulatesOnTopOfSnapshot() {
        HyperLogLogDrugFrequencyCounter source = new HyperLogLogDrugFrequencyCounter();
        for (int i = 0; i < 300; i++) {
            source.record(event("p" + i, "D1", "Aspirin"));
        }
        snapshotService.save(source);

        HyperLogLogDrugFrequencyCounter restored = new HyperLogLogDrugFrequencyCounter();
        snapshotService.restoreInto(restored);
        // Replay 100 brand-new patients after recovery.
        for (int i = 300; i < 400; i++) {
            restored.record(event("p" + i, "D1", "Aspirin"));
        }

        double error = Math.abs(restored.distinctPatients("D1") - 400) / 400.0;
        assertThat(error).isLessThanOrEqualTo(0.02);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.example.demo.persistence.SnapshotServiceTest"`
Expected: FAIL — `SnapshotService` does not exist.

- [ ] **Step 3: Implement `SnapshotService`**

```java
package com.example.demo.persistence;

import com.example.demo.counter.DrugSketchSnapshot;
import com.example.demo.counter.SnapshotSupport;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final HllSnapshotRepository repository;

    public SnapshotService(HllSnapshotRepository repository) {
        this.repository = repository;
    }

    /** Persists all sketches. Failures are logged and swallowed — never block the hot path. */
    public void save(SnapshotSupport counter) {
        try {
            List<HllSnapshot> rows = counter.export().stream()
                    .map(s -> new HllSnapshot(s.drugCode(), s.drugName(), s.sketchBytes(), Instant.EPOCH))
                    .toList();
            repository.saveAll(rows);
            log.info("Persisted {} drug sketches", rows.size());
        } catch (RuntimeException ex) {
            log.error("Snapshot save failed; will retry on next tick", ex);
        }
    }

    /** Loads all persisted sketches into the given counter. */
    public void restoreInto(SnapshotSupport counter) {
        List<DrugSketchSnapshot> snapshots = repository.findAll().stream()
                .map(r -> new DrugSketchSnapshot(r.getDrugCode(), r.getDrugName(), r.getSketchBytes()))
                .toList();
        counter.restore(snapshots);
        log.info("Restored {} drug sketches", snapshots.size());
    }
}
```

> `Instant.EPOCH` is used for `updatedAt` to keep tests deterministic; the scheduled wiring in Task 7 stamps the real time.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.example.demo.persistence.SnapshotServiceTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/persistence/SnapshotService.java src/test/java/com/example/demo/persistence/SnapshotServiceTest.java
git commit -m "feat: add SnapshotService with save/restore round-trip"
```

---

## Task 7: Scheduled snapshot + startup restore + failure isolation

**Files:**
- Modify: `src/main/java/com/example/demo/persistence/SnapshotService.java`
- Modify: `src/main/java/com/example/demo/DemoApplication.java`
- Test: `src/test/java/com/example/demo/persistence/SnapshotFailureIsolationTest.java`

- [ ] **Step 1: Write the failing failure-isolation test**

```java
package com.example.demo.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.demo.counter.HyperLogLogDrugFrequencyCounter;
import com.example.demo.medication.MedicationEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SnapshotFailureIsolationTest {

    @Test
    void saveFailureIsSwallowedAndDoesNotPropagate() {
        HllSnapshotRepository failing = mock(HllSnapshotRepository.class);
        when(failing.saveAll(anyList())).thenThrow(new RuntimeException("db down"));
        SnapshotService service = new SnapshotService(failing);

        HyperLogLogDrugFrequencyCounter counter = new HyperLogLogDrugFrequencyCounter();
        counter.record(new MedicationEvent("p1", "D1", "Aspirin", Instant.EPOCH));

        assertThatCode(() -> service.save(counter)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run test to verify it passes (save already guards failures)**

Run: `./gradlew test --tests "com.example.demo.persistence.SnapshotFailureIsolationTest"`
Expected: PASS. (Confirms the try/catch added in Task 6 isolates failures.)

- [ ] **Step 3: Add scheduled snapshot + lifecycle hooks to `SnapshotService`**

Add the counter dependency and lifecycle methods. Replace the class body's constructor/fields region with:

```java
package com.example.demo.persistence;

import com.example.demo.counter.DrugSketchSnapshot;
import com.example.demo.counter.HyperLogLogDrugFrequencyCounter;
import com.example.demo.counter.SnapshotSupport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final HllSnapshotRepository repository;
    private final HyperLogLogDrugFrequencyCounter counter;

    public SnapshotService(HllSnapshotRepository repository, HyperLogLogDrugFrequencyCounter counter) {
        this.repository = repository;
        this.counter = counter;
    }

    @PostConstruct
    public void restoreOnStartup() {
        restoreInto(counter);
    }

    @PreDestroy
    public void snapshotOnShutdown() {
        save(counter);
    }

    @Scheduled(fixedDelayString = "${drugs.snapshot.interval-ms:30000}")
    public void scheduledSnapshot() {
        save(counter);
    }

    /** Persists all sketches. Failures are logged and swallowed — never block the hot path. */
    public void save(SnapshotSupport source) {
        try {
            List<HllSnapshot> rows = source.export().stream()
                    .map(s -> new HllSnapshot(s.drugCode(), s.drugName(), s.sketchBytes(), Instant.now()))
                    .toList();
            repository.saveAll(rows);
            log.info("Persisted {} drug sketches", rows.size());
        } catch (RuntimeException ex) {
            log.error("Snapshot save failed; will retry on next tick", ex);
        }
    }

    /** Loads all persisted sketches into the given counter. */
    public void restoreInto(SnapshotSupport target) {
        List<DrugSketchSnapshot> snapshots = repository.findAll().stream()
                .map(r -> new DrugSketchSnapshot(r.getDrugCode(), r.getDrugName(), r.getSketchBytes()))
                .toList();
        target.restore(snapshots);
        log.info("Restored {} drug sketches", snapshots.size());
    }
}
```

> `SnapshotServiceTest` (`@DataJpaTest`) constructs the service via `@Import` and Spring injects both beans; its `save(source)`/`restoreInto(target)` calls still compile because those methods are unchanged. The `@PostConstruct` restore runs against an empty table in that test (harmless).

- [ ] **Step 4: Enable scheduling on the application**

```java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

}
```

- [ ] **Step 5: Run the persistence tests + context load**

Run: `./gradlew test --tests "com.example.demo.persistence.*" --tests "com.example.demo.DemoApplicationTests"`
Expected: PASS. `SnapshotServiceTest` already imports `HyperLogLogDrugFrequencyCounter` (Task 6), so the new constructor dependency resolves; `@Scheduled` does not fire in a `@DataJpaTest` slice (no `@EnableScheduling`), and the `@PostConstruct` restore runs harmlessly against the empty table.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/demo/persistence/SnapshotService.java src/main/java/com/example/demo/DemoApplication.java src/test/java/com/example/demo/persistence/SnapshotFailureIsolationTest.java
git commit -m "feat: scheduled snapshot, startup restore, failure isolation"
```

---

## Task 8: `MedicationIngestionService` — validate + forward

**Files:**
- Create: `src/main/java/com/example/demo/ingestion/MedicationIngestionService.java`
- Test: `src/test/java/com/example/demo/ingestion/MedicationIngestionServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.example.demo.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.counter.ExactDrugFrequencyCounter;
import com.example.demo.medication.MedicationEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MedicationIngestionServiceTest {

    private final ExactDrugFrequencyCounter counter = new ExactDrugFrequencyCounter();
    private final MedicationIngestionService service = new MedicationIngestionService(counter);

    @Test
    void validEventIsForwardedToCounter() {
        service.ingest(new MedicationEvent("p1", "D1", "Aspirin", Instant.EPOCH));
        assertThat(counter.distinctPatients("D1")).isEqualTo(1);
    }

    @Test
    void eventWithBlankPatientIdIsRejected() {
        service.ingest(new MedicationEvent("  ", "D1", "Aspirin", Instant.EPOCH));
        assertThat(counter.distinctPatients("D1")).isZero();
    }

    @Test
    void eventWithNullDrugCodeIsRejected() {
        service.ingest(new MedicationEvent("p1", null, "Aspirin", Instant.EPOCH));
        assertThat(counter.topN(10)).isEmpty();
    }

    @Test
    void nullEventIsRejectedWithoutThrowing() {
        service.ingest(null);
        assertThat(counter.topN(10)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.example.demo.ingestion.MedicationIngestionServiceTest"`
Expected: FAIL — `MedicationIngestionService` does not exist.

- [ ] **Step 3: Implement the service**

```java
package com.example.demo.ingestion;

import com.example.demo.counter.DrugFrequencyCounter;
import com.example.demo.medication.MedicationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MedicationIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MedicationIngestionService.class);

    private final DrugFrequencyCounter counter;

    public MedicationIngestionService(DrugFrequencyCounter counter) {
        this.counter = counter;
    }

    public void ingest(MedicationEvent event) {
        if (!isValid(event)) {
            log.warn("Rejected malformed medication event: {}", event);
            return;
        }
        counter.record(event);
    }

    private boolean isValid(MedicationEvent event) {
        return event != null
                && isPresent(event.patientId())
                && isPresent(event.drugCode())
                && isPresent(event.drugName());
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.example.demo.ingestion.MedicationIngestionServiceTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/ingestion/MedicationIngestionService.java src/test/java/com/example/demo/ingestion/MedicationIngestionServiceTest.java
git commit -m "feat: add medication ingestion service with validation"
```

---

## Task 9: `MockMedicationFeed` — Zipfian synthetic stream

**Files:**
- Create: `src/main/java/com/example/demo/ingestion/MockMedicationFeed.java`
- Test: `src/test/java/com/example/demo/ingestion/MockMedicationFeedTest.java`

- [ ] **Step 1: Write the failing test (skew is observable)**

```java
package com.example.demo.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.counter.DrugFrequency;
import com.example.demo.counter.HyperLogLogDrugFrequencyCounter;
import java.util.List;
import org.junit.jupiter.api.Test;

class MockMedicationFeedTest {

    @Test
    void emitsZipfianSkewedEventsSoOneDrugClearlyLeads() {
        HyperLogLogDrugFrequencyCounter counter = new HyperLogLogDrugFrequencyCounter();
        MedicationIngestionService ingestion = new MedicationIngestionService(counter);
        MockMedicationFeed feed = new MockMedicationFeed(ingestion, 1234L);

        feed.emitBatch(50_000);

        List<DrugFrequency> top = counter.topN(3);
        assertThat(top).hasSize(3);
        // Rank-1 drug should have materially more distinct patients than rank-3.
        assertThat(top.get(0).estimatedDistinctPatients())
                .isGreaterThan(top.get(2).estimatedDistinctPatients());
    }

    @Test
    void emitsRequestedNumberOfDistinctPatientsAcrossCatalog() {
        HyperLogLogDrugFrequencyCounter counter = new HyperLogLogDrugFrequencyCounter();
        MedicationIngestionService ingestion = new MedicationIngestionService(counter);
        MockMedicationFeed feed = new MockMedicationFeed(ingestion, 1234L);

        feed.emitBatch(1_000);

        assertThat(counter.topN(100)).isNotEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.example.demo.ingestion.MockMedicationFeedTest"`
Expected: FAIL — `MockMedicationFeed` does not exist.

- [ ] **Step 3: Implement the feed**

```java
package com.example.demo.ingestion;

import com.example.demo.medication.MedicationEvent;
import java.time.Instant;
import java.util.Random;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stand-in for an external medication feed. Emits events whose drug is chosen
 * from a Zipfian distribution, so a few drugs dominate (realistic and testable).
 */
@Component
public class MockMedicationFeed {

    private static final int DRUG_CATALOG_SIZE = 30;

    private final MedicationIngestionService ingestion;
    private final Random random;
    private final double[] cumulativeWeights;
    private int patientSequence = 0;

    public MockMedicationFeed(MedicationIngestionService ingestion,
                              @Value("${drugs.feed.seed:42}") long seed) {
        this.ingestion = ingestion;
        this.random = new Random(seed);
        this.cumulativeWeights = buildZipfWeights(DRUG_CATALOG_SIZE);
    }

    /** Emits {@code count} events with distinct patient ids and Zipfian drug selection. */
    public void emitBatch(int count) {
        for (int i = 0; i < count; i++) {
            int drugIndex = sampleDrugIndex();
            String patientId = "patient-" + (patientSequence++);
            String drugCode = "D" + drugIndex;
            String drugName = "drug-" + drugIndex;
            ingestion.ingest(new MedicationEvent(patientId, drugCode, drugName, Instant.now()));
        }
    }

    @Scheduled(fixedDelayString = "${drugs.feed.interval-ms:5000}")
    public void emitScheduledBatch() {
        emitBatch(1_000);
    }

    private int sampleDrugIndex() {
        double r = random.nextDouble() * cumulativeWeights[cumulativeWeights.length - 1];
        for (int i = 0; i < cumulativeWeights.length; i++) {
            if (r <= cumulativeWeights[i]) {
                return i;
            }
        }
        return cumulativeWeights.length - 1;
    }

    private static double[] buildZipfWeights(int size) {
        double[] cumulative = new double[size];
        double running = 0;
        for (int i = 0; i < size; i++) {
            running += 1.0 / (i + 1); // weight of rank i is 1/(i+1)
            cumulative[i] = running;
        }
        return cumulative;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.example.demo.ingestion.MockMedicationFeedTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/ingestion/MockMedicationFeed.java src/test/java/com/example/demo/ingestion/MockMedicationFeedTest.java
git commit -m "feat: add Zipfian mock medication feed"
```

---

## Task 10: REST API — `DrugFrequencyController`

**Files:**
- Create: `src/main/java/com/example/demo/api/PatientCountResponse.java`
- Create: `src/main/java/com/example/demo/api/DrugFrequencyController.java`
- Test: `src/test/java/com/example/demo/api/DrugFrequencyControllerTest.java`

- [ ] **Step 1: Write the failing `@WebMvcTest`**

```java
package com.example.demo.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.counter.DrugFrequency;
import com.example.demo.counter.DrugFrequencyCounter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DrugFrequencyController.class)
class DrugFrequencyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DrugFrequencyCounter counter;

    @Test
    void topReturnsRankedDrugsAsJson() throws Exception {
        when(counter.topN(2)).thenReturn(List.of(
                new DrugFrequency("D1", "Aspirin", 300),
                new DrugFrequency("D2", "Ibuprofen", 200)));

        mockMvc.perform(get("/api/v1/drugs/top?limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].drugCode").value("D1"))
                .andExpect(jsonPath("$[0].estimatedDistinctPatients").value(300))
                .andExpect(jsonPath("$[1].drugCode").value("D2"));
    }

    @Test
    void topUsesDefaultLimitWhenAbsent() throws Exception {
        when(counter.topN(10)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/drugs/top")).andExpect(status().isOk());
    }

    @Test
    void topRejectsOutOfRangeLimit() throws Exception {
        mockMvc.perform(get("/api/v1/drugs/top?limit=0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/drugs/top?limit=1001")).andExpect(status().isBadRequest());
    }

    @Test
    void patientCountReturnsEstimate() throws Exception {
        when(counter.distinctPatients(eq("D1"))).thenReturn(123L);

        mockMvc.perform(get("/api/v1/drugs/D1/patient-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drugCode").value("D1"))
                .andExpect(jsonPath("$.estimatedDistinctPatients").value(123));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.example.demo.api.DrugFrequencyControllerTest"`
Expected: FAIL — controller and DTO do not exist.

- [ ] **Step 3: Create the response DTO**

```java
package com.example.demo.api;

public record PatientCountResponse(String drugCode, long estimatedDistinctPatients) {
}
```

- [ ] **Step 4: Implement the controller**

```java
package com.example.demo.api;

import com.example.demo.counter.DrugFrequency;
import com.example.demo.counter.DrugFrequencyCounter;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/drugs")
public class DrugFrequencyController {

    private static final int MAX_LIMIT = 1000;

    private final DrugFrequencyCounter counter;

    public DrugFrequencyController(DrugFrequencyCounter counter) {
        this.counter = counter;
    }

    @GetMapping("/top")
    public List<DrugFrequency> top(@RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        return counter.topN(limit);
    }

    @GetMapping("/{drugCode}/patient-count")
    public PatientCountResponse patientCount(@PathVariable String drugCode) {
        return new PatientCountResponse(drugCode, counter.distinctPatients(drugCode));
    }
}
```

> Multiple `DrugFrequencyCounter` beans exist at runtime? No — only `HyperLogLogDrugFrequencyCounter` is `@Component`; the exact counter is test-only. So injection is unambiguous.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.example.demo.api.DrugFrequencyControllerTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/demo/api/ src/test/java/com/example/demo/api/DrugFrequencyControllerTest.java
git commit -m "feat: add drug frequency REST controller"
```

---

## Task 11: End-to-end wiring test

**Files:**
- Test: `src/test/java/com/example/demo/EndToEndDrugFrequencyTest.java`

- [ ] **Step 1: Write the end-to-end test**

```java
package com.example.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.ingestion.MockMedicationFeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "drugs.feed.interval-ms=3600000",      // disable auto feed during the test
        "drugs.snapshot.interval-ms=3600000"   // disable auto snapshot during the test
})
class EndToEndDrugFrequencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockMedicationFeed feed;

    @Test
    void feedThenQueryTopReturnsPopulatedRanking() throws Exception {
        feed.emitBatch(20_000);

        mockMvc.perform(get("/api/v1/drugs/top?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].drugCode").exists())
                .andExpect(jsonPath("$[0].estimatedDistinctPatients").isNumber());
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests "com.example.demo.EndToEndDrugFrequencyTest"`
Expected: PASS. (Proves feed → ingestion → counter → controller wiring across all layers.)

- [ ] **Step 3: Run the full suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/example/demo/EndToEndDrugFrequencyTest.java
git commit -m "test: end-to-end feed-to-API drug frequency ranking"
```

---

## Done criteria

- `./gradlew test` is green.
- `./gradlew bootRun` starts; the mock feed populates counts; `GET /api/v1/drugs/top?limit=10` returns a ranked JSON list and `GET /api/v1/drugs/{drugCode}/patient-count` returns an estimate.
- Counts survive a restart (snapshot table repopulated on shutdown, restored on startup).
- Memory footprint is governed by drug count (~30 in the mock), not patient count.

## Iteration-2 hooks (out of scope, noted for reviewers)

- Swap `HyperLogLogDrugFrequencyCounter` for a Redis-backed `DrugFrequencyCounter` (`PFADD`/`PFCOUNT`) — interface unchanged.
- Replace `MockMedicationFeed` with a real HL7/FHIR ingestion adapter feeding `MedicationIngestionService`.
- Distributed merge of per-replica sketches for a global top-N.
