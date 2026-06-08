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
