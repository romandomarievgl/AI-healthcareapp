package com.example.demo.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HllSnapshotRepository extends JpaRepository<HllSnapshot, String> {
}
