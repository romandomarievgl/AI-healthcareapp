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
