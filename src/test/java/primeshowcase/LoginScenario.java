package primeshowcase.scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import primeshowcase.utils.GatlingLogger;

public final class LoginScenario {

    private LoginScenario() {}

    /**
     * Шаг 1: GET страницы логина.
     * Теперь обрабатывает не только 429, но и таймауты.
     */
    public static ChainBuilder getLoginPage() {
        return exec(
                http("GET Login Page")
                        .get("/showcase/ui/misc/login.xhtml")
                        // Запрос может упасть не только по статусу, но и по таймауту
                        .check(
                                status().validate(
                                        (response, session) -> {
                                            int code = response.status().code();

                                            if (code == 200) {
                                                String body = response.body().string();
                                                String viewState = extractViewState(body);

                                                if (viewState == null || viewState.isEmpty()) {
                                                    throw new RuntimeException("ViewState не найден");
                                                }

                                                RateLimitCounter.recordSuccess();
                                                return session
                                                        .set("loginStatus", "SUCCESS")
                                                        .set("viewState", viewState)
                                                        .set("rateLimited", false);

                                            } else if (code == 429) {
                                                RateLimitCounter.recordRateLimit();
                                                return session
                                                        .set("loginStatus", "RATE_LIMITED")
                                                        .set("viewState", "")
                                                        .set("rateLimited", true);

                                            } else {
                                                throw new RuntimeException(
                                                        "Неожиданный статус: " + code
                                                );
                                            }
                                        }
                                )
                        )
        )
                // ===== ОБРАБОТКА ИСКЛЮЧЕНИЙ (таймаутов) =====
                .exec(session -> {
                    // Если предыдущий exec упал с исключением,
                    // Gatling помечает сессию как failed.
                    // Проверяем статус сессии:
                    if (session.isFailed()) {
                        String error = session.getAs("errorMessage");
                        System.err.printf("[TIMEOUT] GET Login Page: %s%n", error);

                        RateLimitCounter.recordTimeout();  // отдельный счётчик таймаутов

                        // Сбрасываем failed-статус, чтобы сценарий продолжился
                        // (пользователь завершится, но не "зависнет")
                        return session
                                .set("loginStatus", "TIMEOUT")
                                .set("viewState", "")
                                .set("rateLimited", true)   // помечаем как "неуспех"
                                .markAsSucceeded();          // СБРАСЫВАЕМ FAILED!
                    }
                    return session;
                });
    }






    // ===== Ключевые проверки =====

    /**
     * Шаг 1: GET страницы логина + извлечение ViewState.
     * FAIL если: не 200, нет ViewState на странице.
     */
    public static ChainBuilder getLoginPage() {
        return exec(
                http("GET Login Page")
                        .get("/showcase/ui/misc/login.xhtml")
                        .check(
                                // Проверка 1: статус должен быть 200
                                status().is(200)
                                        .saveAs("getLoginStatus"),  // сохраняем для логов

                                // Проверка 2: ViewState ОБЯЗАТЕЛЬНО должен быть
                                css("input[name='javax.faces.ViewState']", "value")
                                        .saveAs("viewState")
                                        .exists()  // если нет → FAIL
                        )
        )
                .exec(session -> {
                    // Логируем, что прошло успешно
                    GatlingLogger.logSuccess("GET Login Page",
                            session.getInt("getLoginStatus"),
                            session.getString("viewState"));
                    return session;
                });
    }

    /**
     * Шаг 2: POST логина.
     * FAIL если: статус не 200/302 (например, 429 Rate Limit, 401 плохой пароль).
     */
    public static ChainBuilder postLogin() {
        return exec(
                http("POST Login")
                        .post("/showcase/ui/misc/login.xhtml")
                        .formParam("j_username", "#{username}")
                        .formParam("j_password", "#{password}")
                        .formParam("javax.faces.ViewState", "#{viewState}")
                        .formParam("login", "Login")
                        .check(
                                // Проверка 1: статус успешного логина
                                // 200 = OK, 302 = редирект после логина
                                status().in(200, 302)
                                        .saveAs("loginStatus"),

                                // Проверка 2: если статус 429 — это Rate Limit!
                                // Сохраняем для анализа, но не фейлим здесь
                                status().saveAs("exactStatus")
                        )
        )
                .exec(session -> {
                    int status = session.getInt("exactStatus");

                    if (status == 429) {
                        // Rate Limit сработал — логируем отдельно
                        GatlingLogger.logRateLimit(session.getString("username"));
                        // Помечаем в сессии, что был rate limit
                        session = session.set("rateLimited", true);
                    } else if (status == 200 || status == 302) {
                        GatlingLogger.logSuccess("POST Login", status, null);
                        session = session.set("rateLimited", false);
                    } else {
                        GatlingLogger.logFailure("POST Login", status,
                                "Неожиданный статус: " + status);
                    }

                    return session;
                });
    }

    /**
     * Шаг 3: GET главной страницы для проверки входа.
     * FAIL если: не 200, нет "Welcome" (значит не залогинились).
     */
    public static ChainBuilder verifyMainPage() {
        return exec(
                http("GET Main Page (Verify)")
                        .get("/showcase/ui/misc/login.xhtml")
                        .check(
                                // Проверка 1: статус 200
                                status().is(200),

                                // Проверка 2: на странице есть признак успешного входа
                                // Если rate limit — этой проверки не будет
                                substring("Welcome").exists()
                        )
        );
    }

    // ===== Сборка полного сценария =====

    /**
     * Полный сценарий с ВСЕМИ проверками.
     */
    public static ScenarioBuilder fullLoginScenario(String name) {
        return scenario(name)
                .exec(getLoginPage())
                .pause(1)
                .exec(postLogin())
                // Проверяем, не сработал ли rate limit
                .doIf(session -> !session.getBoolean("rateLimited"))
                .then(
                        exec(verifyMainPage())
                );
    }
}