package com.example.demo.counter;

public record DrugFrequency(
        String drugCode,
        String drugName,
        long estimatedDistinctPatients) {
}
