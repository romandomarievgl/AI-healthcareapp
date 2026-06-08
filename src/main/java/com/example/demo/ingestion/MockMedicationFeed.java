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
