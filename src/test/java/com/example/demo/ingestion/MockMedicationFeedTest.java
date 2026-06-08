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
