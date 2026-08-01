## Структура проекта

```
primefaces-gatling-training/
├── pom.xml
├── .mvn/
│   └── jvm.config                    # JVM аргументы
├── src/
│   └── test/
│       ├── java/
│       │   └── primeshowcase/
│       │       ├── config/
│       │       │   └── TestConfig.java           # Все константы и настройки
│       │       ├── scenarios/
│       │       │   └── LoginScenario.java        # Переиспользуемый сценарий
│       │       ├── protocols/
│       │       │   └── HttpProtocols.java        # HTTP-протоколы (обычный + debug)
│       │       ├── simulations/
│       │       │   ├── DebugSimulation.java       # Отладка (1 пользователь)
│       │       │   ├── MaxPerformanceSimulation.java  # Ступенчатая нагрузка
│       │       │   └── StabilitySimulation.java   # Длительная стабильная
│       │       ├── feeders/
│       │       │   └── UserFeeders.java           # Фидеры с данными
│       │       └── utils/
│       │           ├── RateLimitCalculator.java   # Наш калькулятор
│       │           └── GatlingLogger.java         # Кастомный логгер
│       └── resources/
│           ├── logback.xml                        # Основной конфиг логирования
│           ├── logback-debug.xml                  # Конфиг для DEBUG режима
│           ├── gatling.conf                       # Конфигурация Gatling
│           └── data/
│               └── users.csv                      # Тестовые данные
```

## 1. TestConfig.java — все настройки в одном месте

```java
package primeshowcase.config;

/**
 * Централизованная конфигурация всех тестов.
 * Никаких магических чисел в симуляциях!
 */
public final class TestConfig {
    
    private TestConfig() {} // утилитный класс
    
    // ===== Окружение =====
    public static final String BASE_URL = "https://www.primefaces.org";
    
    // ===== Token Bucket параметры (можно вынести в properties-файл) =====
    public static final int BUCKET_CAPACITY = 1000;
    public static final double REFILL_TOKENS = 2;
    public static final double REFILL_SECONDS = 4;
    public static final double TOKENS_PER_LOGIN = 6;
    
    // ===== Лимит сессий =====
    public static final int MAX_SESSIONS = 500;
    public static final double SESSION_TEST_RATIO = 0.8;  // 80% от лимита
    
    // ===== Временные настройки =====
    public static final class Timing {
        public static final int DEBUG_PAUSE_SEC = 2;
        
        // MaxPerformance
        public static final int STEP_DURATION_MIN = 3;      // длительность ступени
        public static final int STEP_COUNT = 15;            // количество ступеней
        public static final double STEP_SIZE_LOGINS_PER_SEC = 0.05;  // шаг ступени
        public static final double START_RATE = 0.05;       // начальная нагрузка
        
        // Stability
        public static final int RAMP_UP_MIN = 5;            // разгон
        public static final int STABLE_MIN = 30;            // стабильная нагрузка
        public static final int RAMP_DOWN_MIN = 2;          // завершение
        public static final double STABILITY_RATIO = 0.8;   // 80% от устойчивого темпа
    }
    
    // ===== Пороги для assertions =====
    public static final class Thresholds {
        public static final int RESPONSE_TIME_95_MS = 500;
        public static final int RESPONSE_TIME_MAX_MS = 2000;
        public static final double FAILED_REQUESTS_PERCENT = 1.0;
        public static final double SUCCESS_REQUESTS_PERCENT = 99.0;
    }
    
    // ===== Режимы запуска =====
    public enum RunMode {
        DEBUG,
        MAX_PERFORMANCE,
        STABILITY
    }
}
```

## 2. HttpProtocols.java — протоколы для разных режимов

```java
package primeshowcase.protocols;

import io.gatling.javaapi.http.HttpProtocolBuilder;
import static io.gatling.javaapi.http.HttpDsl.http;
import primeshowcase.config.TestConfig;

/**
 * Фабрика HTTP-протоколов.
 * Отдельный протокол для debug с дополнительными проверками.
 */
public final class HttpProtocols {
    
    private HttpProtocols() {}
    
    /**
     * Стандартный протокол для нагрузочных тестов.
     * Минимум лишних проверок ради производительности.
     */
    public static HttpProtocolBuilder standard() {
        return http
            .baseUrl(TestConfig.BASE_URL)
            .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .acceptLanguageHeader("en-US,en;q=0.5")
            .acceptEncodingHeader("gzip, deflate")
            .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Firefox/89.0")
            // Не сохраняем тело ответа для ускорения
            .disableAutoReferer()
            // Автоматически следовать редиректам
            .followRedirect(true);
    }
    
    /**
     * Debug протокол с расширенным логированием.
     * Сохраняет ВСЁ для отладки сценария.
     */
    public static HttpProtocolBuilder debug() {
        return standard()
            // Сохраняем тела ответов для анализа
            .silentResources()  // не логировать статику
            // Проверяем ВСЕ статусы
            .check(
                io.gatling.javaapi.http.HttpDsl.status().saveAs("responseStatus")
            );
    }
}
```

## 3. LoginScenario.java — переиспользуемый сценарий

```java
package primeshowcase.scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import primeshowcase.config.TestConfig;

/**
 * Переиспользуемый сценарий логина.
 * Можно использовать в любых симуляциях.
 */
public final class LoginScenario {
    
    private LoginScenario() {}
    
    // ===== Элементарные действия (ChainBuilder'ы) =====
    
    /**
     * Шаг 1: GET страницы логина, извлечение ViewState.
     */
    public static ChainBuilder getLoginPage() {
        return exec(
            http("GET Login Page")
                .get("/showcase/ui/misc/login.xhtml")
                .check(
                    status().is(200),
                    css("input[name='javax.faces.ViewState']", "value")
                        .saveAs("viewState")
                )
        )
        .exec(session -> {
            // Дополнительное логирование в debug режиме
            String viewState = session.getString("viewState");
            System.out.printf("[DEBUG] Извлечён ViewState: %s...%n", 
                viewState != null ? viewState.substring(0, Math.min(20, viewState.length())) : "null");
            return session;
        });
    }
    
    /**
     * Шаг 2: POST логина с креденшелами.
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
                    status().in(200, 302)
                )
        )
        .exec(session -> {
            System.out.printf("[DEBUG] Логин: пользователь=%s, статус=%s%n",
                session.getString("username"),
                session.getInt("responseStatus"));
            return session;
        });
    }
    
    /**
     * Шаг 3: GET главной страницы для проверки успешного входа.
     */
    public static ChainBuilder verifyMainPage() {
        return exec(
            http("GET Main Page (Verify)")
                .get("/showcase/ui/misc/login.xhtml")
                .check(
                    status().is(200),
                    substring("Welcome").exists()
                )
        );
    }
    
    // ===== Составные сценарии =====
    
    /**
     * Полный сценарий логина (без фидера — для хардкодных кредов).
     */
    public static ScenarioBuilder loginScenarioHardcoded(String name) {
        return scenario(name)
            .exec(getLoginPage())
            .pause(1)
            .exec(postLogin())
            .pause(1)
            .exec(verifyMainPage());
    }
    
    /**
     * Полный сценарий логина с фидером.
     */
    public static ScenarioBuilder loginScenarioWithFeeder(String name) {
        return scenario(name)
            .feed(UserFeeders.circular())      // подкидываем данные
            .exec(getLoginPage())
            .pause(1)
            .exec(postLogin())
            .pause(1)
            .exec(verifyMainPage());
    }
    
    /**
     * Только действия без пауз (для массовых тестов).
     */
    public static ChainBuilder loginActions() {
        return exec(getLoginPage())
            .exec(postLogin())
            .exec(verifyMainPage());
    }
    
    /**
     * Сценарий с удержанием сессии (для теста лимита сессий).
     */
    public static ScenarioBuilder sessionHolderScenario(int holdMinutes) {
        return scenario("Session Holder")
            .feed(UserFeeders.circular())
            .exec(loginActions())
            .during(holdMinutes * 60)
            .on(
                exec(
                    http("Keep-Alive")
                        .get("/showcase/ui/misc/login.xhtml")
                        .check(status().is(200))
                )
                .pause(30)  // пинг каждые 30 секунд
            );
    }
}
```

## 4. UserFeeders.java — фидеры с данными

```java
package primeshowcase.feeders;

import io.gatling.javaapi.core.FeederBuilder;
import static io.gatling.javaapi.core.CoreDsl.csv;

/**
 * Централизованное управление тестовыми данными.
 */
public final class UserFeeders {
    
    private UserFeeders() {}
    
    public static FeederBuilder<String> circular() {
        return csv("data/users.csv").circular();
    }
    
    public static FeederBuilder<String> random() {
        return csv("data/users.csv").random();
    }
    
    public static FeederBuilder<String> queue() {
        return csv("data/users.csv").queue();
    }
}
```

## 5. GatlingLogger.java — кастомный логгер

```java
package primeshowcase.utils;

import io.gatling.javaapi.core.Session;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Кастомный логгер для отладки сценариев.
 */
public final class GatlingLogger {
    
    private static final DateTimeFormatter TIME_FORMAT = 
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static boolean debugEnabled = true;
    
    private GatlingLogger() {}
    
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }
    
    public static void logRequest(String step, Session session) {
        if (!debugEnabled) return;
        
        String time = LocalTime.now().format(TIME_FORMAT);
        Long userId = session.getLong("userId", -1L);
        String username = session.getString("username");
        
        System.out.printf("[%s] [USER:%d] %s | user=%s%n", 
            time, userId, step, username != null ? username : "anon");
    }
    
    public static void logResponse(String step, int status, Session session) {
        if (!debugEnabled) return;
        
        String time = LocalTime.now().format(TIME_FORMAT);
        System.out.printf("[%s] [%s] ← status=%d | session=%s%n", 
            time, step, status, session.userId());
    }
    
    public static void logError(String step, String error, Session session) {
        String time = LocalTime.now().format(TIME_FORMAT);
        System.err.printf("[%s] [ERROR] %s: %s | user=%s%n", 
            time, step, error, session.userId());
    }
    
    public static void separator() {
        if (!debugEnabled) return;
        System.out.println("─".repeat(60));
    }
}
```

## 6. DebugSimulation.java — отладка

```java
package primeshowcase.simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import primeshowcase.config.TestConfig;
import primeshowcase.protocols.HttpProtocols;
import primeshowcase.scenarios.LoginScenario;
import primeshowcase.utils.GatlingLogger;

/**
 * Debug симуляция — 1 пользователь, максимум логов.
 * Для проверки работоспособности сценария.
 */
public class DebugSimulation extends Simulation {

    {
        // Включаем расширенное логирование
        GatlingLogger.setDebugEnabled(true);
        
        System.out.println("═══════════════════════════════════════");
        System.out.println("  DEBUG MODE — Проверка сценария");
        System.out.println("═══════════════════════════════════════");
        System.out.println();
        System.out.println("Настройки:");
        System.out.println("  Пользователей: 1");
        System.out.println("  Режим: последовательное выполнение");
        System.out.println("  Логирование: расширенное");
        System.out.println();
        
        ScenarioBuilder debugScenario = scenario("Debug: Login Flow")
            .exec(session -> {
                System.out.println(">>> Начало сценария <<<");
                return session;
            })
            .exec(LoginScenario.getLoginPage())
            .exec(session -> {
                GatlingLogger.separator();
                System.out.println(">>> ViewState получен, делаем паузу... <<<");
                return session;
            })
            .pause(TestConfig.Timing.DEBUG_PAUSE_SEC)
            .exec(LoginScenario.postLogin())
            .exec(session -> {
                GatlingLogger.separator();
                System.out.println(">>> Логин выполнен, проверяем главную... <<<");
                return session;
            })
            .pause(TestConfig.Timing.DEBUG_PAUSE_SEC)
            .exec(LoginScenario.verifyMainPage())
            .exec(session -> {
                GatlingLogger.separator();
                System.out.println(">>> Сценарий завершён успешно! <<<");
                return session;
            });
        
        setUp(
            debugScenario.injectOpen(atOnceUsers(1))
        )
        .protocols(HttpProtocols.debug())
        .maxDuration(60);  // максимум 60 секунд на всё
    }
}
```

## 7. MaxPerformanceSimulation.java

```java
package primeshowcase.simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import primeshowcase.config.TestConfig;
import primeshowcase.protocols.HttpProtocols;
import primeshowcase.scenarios.LoginScenario;
import primeshowcase.utils.GatlingLogger;
import primeshowcase.utils.RateLimitCalculator;
import java.time.Duration;

/**
 * MaxPerformance — ступенчатая нагрузка для поиска предела.
 */
public class MaxPerformanceSimulation extends Simulation {

    private static final RateLimitCalculator CALC = new RateLimitCalculator(
        TestConfig.BUCKET_CAPACITY,
        TestConfig.REFILL_TOKENS,
        TestConfig.REFILL_SECONDS,
        TestConfig.TOKENS_PER_LOGIN
    );
    
    private static final double SUSTAINABLE_RATE = CALC.getSustainableRate();
    
    {
        GatlingLogger.setDebugEnabled(false);  // минимум логов в нагрузочном тесте
        
        System.out.println("═══════════════════════════════════════");
        System.out.println("  MAX PERFORMANCE TEST");
        System.out.println("═══════════════════════════════════════");
        System.out.printf("  Устойчивый темп: %.3f лог/сек%n", SUSTAINABLE_RATE);
        System.out.printf("  Начальная нагрузка: %.3f лог/сек%n", TestConfig.Timing.START_RATE);
        System.out.printf("  Шаг ступени: %.3f лог/сек%n", TestConfig.Timing.STEP_SIZE_LOGINS_PER_SEC);
        System.out.printf("  Количество ступеней: %d%n", TestConfig.Timing.STEP_COUNT);
        System.out.printf("  Длительность ступени: %d мин%n", TestConfig.Timing.STEP_DURATION_MIN);
        System.out.println();
        
        ScenarioBuilder loginScn = LoginScenario.loginScenarioWithFeeder("MaxPerf: Login");
        
        setUp(
            loginScn.injectOpen(
                incrementUsersPerSec(TestConfig.Timing.STEP_SIZE_LOGINS_PER_SEC)
                    .times(TestConfig.Timing.STEP_COUNT)
                    .eachLevelLasting(Duration.ofMinutes(TestConfig.Timing.STEP_DURATION_MIN))
                    .startingFrom(TestConfig.Timing.START_RATE)
            )
        )
        .protocols(HttpProtocols.standard())
        .assertions(
            global().responseTime().percentile95().lt(TestConfig.Thresholds.RESPONSE_TIME_95_MS),
            global().responseTime().max().lt(TestConfig.Thresholds.RESPONSE_TIME_MAX_MS),
            global().failedRequests().percent().lt(TestConfig.Thresholds.FAILED_REQUESTS_PERCENT)
        );
    }
}
```

## 8. StabilitySimulation.java

```java
package primeshowcase.simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import primeshowcase.config.TestConfig;
import primeshowcase.protocols.HttpProtocols;
import primeshowcase.scenarios.LoginScenario;
import primeshowcase.utils.GatlingLogger;
import primeshowcase.utils.RateLimitCalculator;
import java.time.Duration;

/**
 * Stability — длительная стабильная нагрузка.
 */
public class StabilitySimulation extends Simulation {

    private static final RateLimitCalculator CALC = new RateLimitCalculator(
        TestConfig.BUCKET_CAPACITY,
        TestConfig.REFILL_TOKENS,
        TestConfig.REFILL_SECONDS,
        TestConfig.TOKENS_PER_LOGIN
    );
    
    private static final double SUSTAINABLE_RATE = CALC.getSustainableRate();
    private static final double TARGET_RATE = SUSTAINABLE_RATE * TestConfig.Timing.STABILITY_RATIO;
    
    {
        GatlingLogger.setDebugEnabled(false);
        
        System.out.println("═══════════════════════════════════════");
        System.out.println("  STABILITY TEST");
        System.out.println("═══════════════════════════════════════");
        System.out.printf("  Устойчивый темп: %.3f лог/сек%n", SUSTAINABLE_RATE);
        System.out.printf("  Целевая нагрузка: %.3f лог/сек (%.0f%% от устойчивого)%n", 
            TARGET_RATE, TestConfig.Timing.STABILITY_RATIO * 100);
        System.out.printf("  Разгон: %d мин%n", TestConfig.Timing.RAMP_UP_MIN);
        System.out.printf("  Стабильная нагрузка: %d мин%n", TestConfig.Timing.STABLE_MIN);
        System.out.printf("  Завершение: %d мин%n", TestConfig.Timing.RAMP_DOWN_MIN);
        System.out.println();
        
        ScenarioBuilder loginScn = LoginScenario.loginScenarioWithFeeder("Stability: Login");
        
        setUp(
            loginScn.injectOpen(
                // Разгон
                rampUsersPerSec(0.1).to(TARGET_RATE)
                    .during(Duration.ofMinutes(TestConfig.Timing.RAMP_UP_MIN)),
                // Стабильная нагрузка
                constantUsersPerSec(TARGET_RATE)
                    .during(Duration.ofMinutes(TestConfig.Timing.STABLE_MIN)),
                // Завершение
                rampUsersPerSec(TARGET_RATE).to(0)
                    .during(Duration.ofMinutes(TestConfig.Timing.RAMP_DOWN_MIN))
            )
        )
        .protocols(HttpProtocols.standard())
        .assertions(
            global().responseTime().percentile95().lt(TestConfig.Thresholds.RESPONSE_TIME_95_MS),
            global().responseTime().max().lt(TestConfig.Thresholds.RESPONSE_TIME_MAX_MS),
            global().failedRequests().percent().lt(TestConfig.Thresholds.FAILED_REQUESTS_PERCENT)
        );
    }
}
```

## 9. logback.xml и logback-debug.xml

### logback.xml (для нагрузочных тестов):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%-5level] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- Минимум логов от Gatling -->
    <logger name="io.gatling.http.engine.response" level="WARN" />
    <logger name="io.gatling.http.client" level="WARN" />
    <logger name="io.netty" level="WARN" />

    <root level="INFO">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

### logback-debug.xml (для DebugSimulation):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%-5level] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- В debug режиме показываем ВСЁ -->
    <logger name="io.gatling.http.engine.response" level="DEBUG" />
    <logger name="io.gatling.http.client" level="DEBUG" />

    <root level="DEBUG">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

## 10. Запуск

```bash
# Debug — проверка сценария
mvn gatling:test -Dgatling.simulationClass=primeshowcase.simulations.DebugSimulation

# MaxPerformance — поиск предела
mvn gatling:test -Dgatling.simulationClass=primeshowcase.simulations.MaxPerformanceSimulation

# Stability — проверка стабильности
mvn gatling:test -Dgatling.simulationClass=primeshowcase.simulations.StabilitySimulation
```

## Преимущества такой архитектуры

| Принцип | Реализация |
|---------|------------|
| **Переиспользование** | `LoginScenario` используется во всех трёх симуляциях |
| **Централизация** | Все настройки в `TestConfig`, меняются в одном месте |
| **Разделение ответственности** | Протоколы отдельно, сценарии отдельно, симуляции отдельно |
| **Debug vs Prod** | Разные протоколы и logback-конфиги |
| **Читаемость** | Каждая симуляция — 20-30 строк понятного кода |
| **Расширяемость** | Добавить новый сценарий — создать класс в `simulations/` |