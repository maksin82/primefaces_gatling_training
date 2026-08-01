package primeshowcase;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import java.time.Duration;

public class MaxPerformanceSimulation extends Simulation {

    HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://www.primefaces.org");

    // Наш сценарий логина (тот же)
    ScenarioBuilder loginScenario = scenario("Login Test")
            .exec(
                    http("GET Login Page")
                            .get("/showcase/ui/misc/login.xhtml")
                            .check(
                                    css("input[name='javax.faces.ViewState']", "value")
                                            .saveAs("viewState")
                            )
            )
            .exec(
                    http("POST Login")
                            .post("/showcase/ui/misc/login.xhtml")
                            .formParam("j_username", "primefaces")
                            .formParam("j_password", "primefaces")
                            .formParam("javax.faces.ViewState", "#{viewState}")
                            .check(status().in(200, 302))
            );

    {
        setUp(
                loginScenario.injectOpen(
                        // СТУПЕНИ НАГРУЗКИ
                        incrementUsersPerSec(1)     // шаг увеличения: +1 польз/сек
                                .times(5)               // 5 ступенек
                                .eachLevelLasting(Duration.ofMinutes(2))  // каждая 2 минуты
                                .startingFrom(1)        // начинаем с 1 польз/сек
                )
        )
                .protocols(httpProtocol)
                .assertions(
                        // Здесь определим точку макс. производительности
                        global().responseTime().max().lt(2000),            // максимум < 2 сек
                        global().failedRequests().percent().lt(5.0),       // ошибок < 5%
                        global().successfulRequests().percent().gt(95.0)
                );
    }
}