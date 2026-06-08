package com.example.demo.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.counter.DrugFrequency;
import com.example.demo.counter.DrugFrequencyCounter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DrugFrequencyController.class)
class DrugFrequencyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DrugFrequencyCounter counter;

    @Test
    void topReturnsRankedDrugsAsJson() throws Exception {
        when(counter.topN(2)).thenReturn(List.of(
                new DrugFrequency("D1", "Aspirin", 300),
                new DrugFrequency("D2", "Ibuprofen", 200)));

        mockMvc.perform(get("/api/v1/drugs/top?limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].drugCode").value("D1"))
                .andExpect(jsonPath("$[0].estimatedDistinctPatients").value(300))
                .andExpect(jsonPath("$[1].drugCode").value("D2"));
    }

    @Test
    void topUsesDefaultLimitWhenAbsent() throws Exception {
        when(counter.topN(10)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/drugs/top")).andExpect(status().isOk());
    }

    @Test
    void topRejectsOutOfRangeLimit() throws Exception {
        mockMvc.perform(get("/api/v1/drugs/top?limit=0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/drugs/top?limit=1001")).andExpect(status().isBadRequest());
    }

    @Test
    void patientCountReturnsEstimate() throws Exception {
        when(counter.distinctPatients(eq("D1"))).thenReturn(123L);

        mockMvc.perform(get("/api/v1/drugs/D1/patient-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drugCode").value("D1"))
                .andExpect(jsonPath("$.estimatedDistinctPatients").value(123));
    }
}
