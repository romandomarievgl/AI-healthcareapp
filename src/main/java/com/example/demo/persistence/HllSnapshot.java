package com.example.demo.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "hll_snapshot")
public class HllSnapshot {

    @Id
    @Column(name = "drug_code", nullable = false)
    private String drugCode;

    @Column(name = "drug_name", nullable = false)
    private String drugName;

    @Lob
    @Column(name = "sketch_bytes", nullable = false)
    private byte[] sketchBytes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HllSnapshot() {
        // for JPA
    }

    public HllSnapshot(String drugCode, String drugName, byte[] sketchBytes, Instant updatedAt) {
        this.drugCode = drugCode;
        this.drugName = drugName;
        this.sketchBytes = sketchBytes;
        this.updatedAt = updatedAt;
    }

    public String getDrugCode() {
        return drugCode;
    }

    public String getDrugName() {
        return drugName;
    }

    public byte[] getSketchBytes() {
        return sketchBytes;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
