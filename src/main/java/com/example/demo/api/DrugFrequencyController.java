package com.example.demo.api;

import com.example.demo.counter.DrugFrequency;
import com.example.demo.counter.DrugFrequencyCounter;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/drugs")
public class DrugFrequencyController {

    private static final int MAX_LIMIT = 1000;

    private final DrugFrequencyCounter counter;

    public DrugFrequencyController(DrugFrequencyCounter counter) {
        this.counter = counter;
    }

    @GetMapping("/top")
    public List<DrugFrequency> top(@RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        return counter.topN(limit);
    }

    @GetMapping("/{drugCode}/patient-count")
    public PatientCountResponse patientCount(@PathVariable String drugCode) {
        return new PatientCountResponse(drugCode, counter.distinctPatients(drugCode));
    }
}
