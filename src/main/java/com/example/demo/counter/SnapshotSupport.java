package com.example.demo.counter;

import java.util.List;

/** Capability for exporting/restoring counter state for crash recovery. */
public interface SnapshotSupport {

    List<DrugSketchSnapshot> export();

    void restore(List<DrugSketchSnapshot> snapshots);
}
