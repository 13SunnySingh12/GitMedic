package dev.gitmedic;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ApplicationSmokeTest {

  @Autowired MockMvc mvc;

  @Test
  void migratesAndServesHealth() throws Exception {
    mvc.perform(get("/actuator/health/db")).andExpect(status().isOk());
  }

  @Test
  void apiRequiresSession() throws Exception {
    mvc.perform(get("/api/v1/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());
  }
}
