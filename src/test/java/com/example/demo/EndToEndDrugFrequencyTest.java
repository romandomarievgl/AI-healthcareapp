package com.example.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.ingestion.MockMedicationFeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "drugs.feed.interval-ms=3600000",      // disable auto feed during the test
        "drugs.snapshot.interval-ms=3600000"   // disable auto snapshot during the test
})
class EndToEndDrugFrequencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockMedicationFeed feed;

    @Test
    void feedThenQueryTopReturnsPopulatedRanking() throws Exception {
        feed.emitBatch(20_000);

        mockMvc.perform(get("/api/v1/drugs/top?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].drugCode").exists())
                .andExpect(jsonPath("$[0].estimatedDistinctPatients").isNumber());
    }
}
