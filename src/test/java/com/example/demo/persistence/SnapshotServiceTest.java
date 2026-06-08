package com.example.demo.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.counter.DrugFrequency;
import com.example.demo.counter.HyperLogLogDrugFrequencyCounter;
import com.example.demo.medication.MedicationEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
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
