//import static io.gatling.javaapi.core.CoreDsl.*;
//import static io.gatling.javaapi.http.HttpDsl.*;
//
//import io.gatling.javaapi.core.*;
//import io.gatling.javaapi.http.*;
//import java.time.Duration;
//
//public class BasicSimulation extends Simulation {
//
//    // 1. Define the HTTP Protocol configuration
//    HttpProtocolBuilder httpProtocol = http
//            .baseUrl("https://example.com") // Target API base URL
//            .acceptHeader("application/json")
//            .contentTypeHeader("application/json");
//
//    // 2. Define the Scenario (What the virtual user will do)
//    ScenarioBuilder scn = scenario("Public API Load Test Scenario")
//            .exec(http("Get All Posts")
//                    .get("/")
//                    .check(status().is(200))
//                    .check(regex("Example").find().exists())) // Assert HTTP status is 200
//            .pause(2); // Pause for 2 seconds (think time)
//
//    // 3. Define the Load Injection Profile
//    {
//        setUp(
//                scn.injectOpen(
//                        nothingFor(Duration.ofSeconds(2)),               // Wait 2 seconds
//                        atOnceUsers(5),                                  // Inject 5 users immediately
//                        rampUsers(10).during(Duration.ofSeconds(10))     // Ramp up 10 users over 10 seconds
//                )
//        ).protocols(httpProtocol);
//    }
//}
