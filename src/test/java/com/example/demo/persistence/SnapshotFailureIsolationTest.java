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
        HyperLogLogDrugFrequencyCounter counter = new HyperLogLogDrugFrequencyCounter();
        SnapshotService service = new SnapshotService(failing, counter);

        counter.record(new MedicationEvent("p1", "D1", "Aspirin", Instant.EPOCH));

        assertThatCode(() -> service.save(counter)).doesNotThrowAnyException();
    }
}
