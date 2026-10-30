package wevioo.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import wevioo.backend.service.AirflowClient;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:context;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
class BackendApplicationTests {
    @MockitoBean
    AirflowClient airflowClient;

    @Test
    void contextLoads() {
    }
}
