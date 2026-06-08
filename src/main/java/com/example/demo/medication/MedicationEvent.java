package com.example.demo.medication;

import java.time.Instant;

public record MedicationEvent(
        String patientId,
        String drugCode,
        String drugName,
        Instant occurredAt) {
}
