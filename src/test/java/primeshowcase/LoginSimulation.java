package primeshowcase;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import io.gatling.javaapi.core.Simulation;

public class LoginSimulation extends Simulation {

    // HTTP протокол
    HttpProtocolBuilder httpProtocol = http
            .baseUrl("https://www.primefaces.org");

    // ChainBuilder - отдельный кусок, можно переиспользовать
    ChainBuilder login = exec(
            http("GET Login Page")
                    .get("/showcase/ui/misc/login.xhtml")
                    .check(
                            css("input[name='javax.faces.ViewState']", "value")
                                    .saveAs("viewState")
                    )
    );


    // ScenarioBuilder - полный сценарий, состоит из ChainBuilder'ов
    ScenarioBuilder loginScenario = scenario("Basic Login Test")  // ← scenario(), не scenarioBuilder()
            .exec(login)
            .exec(
                    http("POST Login")
                            .post("/showcase/ui/misc/login.xhtml")
                            .formParam("j_username", "primefaces")
                            .formParam("j_password", "primefaces")
                            .formParam("javax.faces.ViewState", "#{viewState}")
                            .formParam("login", "Login")
            );

    // Настройка инжекции
    {
        setUp(
                loginScenario.injectOpen(atOnceUsers(1))
        ).protocols(httpProtocol);
    }
}