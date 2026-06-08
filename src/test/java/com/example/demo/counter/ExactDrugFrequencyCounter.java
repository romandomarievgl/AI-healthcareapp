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
