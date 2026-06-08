package com.example.demo.api;

public record PatientCountResponse(String drugCode, long estimatedDistinctPatients) {
}
