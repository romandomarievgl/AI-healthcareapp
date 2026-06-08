package com.example.demo.persistence;

import com.example.demo.counter.DrugSketchSnapshot;
import com.example.demo.counter.HyperLogLogDrugFrequencyCounter;
import com.example.demo.counter.SnapshotSupport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final HllSnapshotRepository repository;
    private final HyperLogLogDrugFrequencyCounter counter;

    public SnapshotService(HllSnapshotRepository repository, HyperLogLogDrugFrequencyCounter counter) {
        this.repository = repository;
        this.counter = counter;
    }

    @PostConstruct
    public void restoreOnStartup() {
        restoreInto(counter);
    }

    @PreDestroy
    public void snapshotOnShutdown() {
        save(counter);
    }

    @Scheduled(fixedDelayString = "${drugs.snapshot.interval-ms:30000}")
    public void scheduledSnapshot() {
        save(counter);
    }

    /** Persists all sketches. Failures are logged and swallowed — never block the hot path. */
    public void save(SnapshotSupport source) {
        try {
            List<HllSnapshot> rows = source.export().stream()
                    .map(s -> new HllSnapshot(s.drugCode(), s.drugName(), s.sketchBytes(), Instant.now()))
                    .toList();
            repository.saveAll(rows);
            log.info("Persisted {} drug sketches", rows.size());
        } catch (RuntimeException ex) {
            log.error("Snapshot save failed; will retry on next tick", ex);
        }
    }

    /** Loads all persisted sketches into the given counter. */
    public void restoreInto(SnapshotSupport target) {
        List<DrugSketchSnapshot> snapshots = repository.findAll().stream()
                .map(r -> new DrugSketchSnapshot(r.getDrugCode(), r.getDrugName(), r.getSketchBytes()))
                .toList();
        target.restore(snapshots);
        log.info("Restored {} drug sketches", snapshots.size());
    }
}
