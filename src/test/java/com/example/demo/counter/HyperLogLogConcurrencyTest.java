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
