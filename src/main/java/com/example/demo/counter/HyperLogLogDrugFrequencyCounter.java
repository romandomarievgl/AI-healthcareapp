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
