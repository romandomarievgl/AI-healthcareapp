package com.example.demo.counter;

import com.example.demo.medication.MedicationEvent;
import java.util.List;

public interface DrugFrequencyCounter {

    void record(MedicationEvent event);

    List<DrugFrequency> topN(int n);

    long distinctPatients(String drugCode);
}
