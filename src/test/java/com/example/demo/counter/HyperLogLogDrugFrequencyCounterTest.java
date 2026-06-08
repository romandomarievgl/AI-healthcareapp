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
