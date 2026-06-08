package com.example.demo.ingestion;

import com.example.demo.counter.DrugFrequencyCounter;
import com.example.demo.medication.MedicationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MedicationIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MedicationIngestionService.class);

    private final DrugFrequencyCounter counter;

    public MedicationIngestionService(DrugFrequencyCounter counter) {
        this.counter = counter;
    }

    public void ingest(MedicationEvent event) {
        if (!isValid(event)) {
            log.warn("Rejected malformed medication event: {}", event);
            return;
        }
        counter.record(event);
    }

    private boolean isValid(MedicationEvent event) {
        return event != null
                && isPresent(event.patientId())
                && isPresent(event.drugCode())
                && isPresent(event.drugName());
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
