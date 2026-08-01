Отлично, давайте разложу эти две темы максимально чётко.

## 1. Assertions — что это и зачем

**Assertions** — это **ожидания/критерии успешности всего теста**. Они НЕ влияют на выполнение сценария. Они смотрят на **итоговую статистику** и говорят: "Тест прошёл успешно" или "Тест провалился".

### Где это видно

В Gatling-отчёте:

```
================================================================================
---- Global Assertions ---------------------------------------------------------
> response time 95th percentile is less than 500 ms      SUCCESS (320 ms)
> max response time is less than 2000 ms                  SUCCESS (850 ms)
> failed requests percent is less than 1.0                FAILED  (3.5%)  ← ТЕСТ ПРОВАЛЕН!
================================================================================
```

И в консоли при завершении:
```
Assertion failed: global.failedRequests.percent.lt(1.0) 
  Actual value: 3.5%
```

### Что мы туда пишем

```java
{
    setUp(...)
    .protocols(httpProtocol)
    .assertions(
        // ===== ГЛОБАЛЬНЫЕ (по всем запросам) =====
        
        // 95% запросов должны быть быстрее 500ms
        global().responseTime().percentile95().lt(500),
        
        // Ни один запрос не должен выполняться дольше 2 секунд
        global().responseTime().max().lt(2000),
        
        // Процент успешных запросов должен быть > 99%
        global().successfulRequests().percent().gt(99.0),
        
        // Процент неуспешных запросов должен быть < 1%
        global().failedRequests().percent().lt(1.0),
        
        // ===== ПО КОНКРЕТНЫМ ЗАПРОСАМ =====
        
        // Для GET запроса — никаких ошибок
        details("GET Login Page").failedRequests().percent().lt(0.1),
        
        // Для POST запроса — допускаем до 5% ошибок (ожидаем rate limit)
        details("POST Login").failedRequests().percent().lt(5.0),
        
        // Среднее время POST запроса
        details("POST Login").responseTime().mean().lt(300)
    );
}
```

### Простой пример

```java
// Представьте: вы запустили тест на 10 минут
// Gatling собрал статистику за ВСЁ время теста:
//   - Всего запросов: 5000
//   - Успешных (200): 4850
//   - Неуспешных (429): 150
//   - Процент неуспешных: 150/5000 = 3%

// Assertion проверяет ИТОГОВУЮ цифру:
global().failedRequests().percent().lt(1.0)  // 3% < 1%? НЕТ → ASSERTION FAILED
```

### Важно понимать

| Что | Когда срабатывает |
|-----|-------------------|
| `check(status().is(200))` | **Во время теста**, для каждого запроса. Если не 200 → запрос помечается KO |
| `assertions(...)` | **После теста**, смотрит на итоговую статистику. Если условие не выполнено → тест FAILED |

---

## 2. Как правильно обрабатывать Rate Limit (429)

Проблема: если 429 помечается как **failed request (KO)**, то:
- В отчёте 429 смешиваются с реальными ошибками (500, timeout)
- Assertions будут падать, хотя 429 — это ожидаемое поведение
- Непонятно, когда именно начался rate limit

### Решение: делаем 429 "успехом с меткой"

В Gatling **нельзя** полностью исключить 429 из failed requests (это ограничение Gatling). Но можно:

1. **Пометить 429 как успех** через кастомную валидацию
2. **Сохранить информацию в сессию** для анализа
3. **Использовать кастомные метрики** для отслеживания

### Обновлённый PostLogin с правильной обработкой 429

```java
public static ChainBuilder postLogin() {
    return exec(
        http("POST Login")
            .post("/showcase/ui/misc/login.xhtml")
            .formParam("j_username", "#{username}")
            .formParam("j_password", "#{password}")
            .formParam("javax.faces.ViewState", "#{viewState}")
            .formParam("login", "Login")
            .check(
                // Кастомная проверка статуса:
                // 200/302 → SUCCESS
                // 429 → SUCCESS (но с меткой "rate_limited")
                // остальное → FAILURE
                status().validate(
                    (response, session) -> {
                        int code = response.status().code();
                        
                        if (code == 200 || code == 302) {
                            // Успешный логин
                            return session
                                .set("loginStatus", "SUCCESS")
                                .set("rateLimited", false);
                                
                        } else if (code == 429) {
                            // Rate Limit — это НЕ ошибка, а ожидаемое поведение!
                            return session
                                .set("loginStatus", "RATE_LIMITED")
                                .set("rateLimited", true);
                                
                        } else {
                            // Реальная ошибка (500, 503, 401)
                            throw new RuntimeException(
                                "Unexpected status: " + code + 
                                " for user: " + session.getString("username")
                            );
                        }
                    }
                )
            )
    );
}
```

### Добавляем кастомный счётчик rate limit'ов

```java
package primeshowcase.utils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Счётчик для отслеживания Rate Limit в реальном времени.
 */
public class RateLimitCounter {
    
    // Atomic для потокобезопасности (Gatling многопоточный)
    private static final AtomicInteger rateLimitCount = new AtomicInteger(0);
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicLong firstRateLimitTime = new AtomicLong(0);
    
    public static void recordSuccess() {
        successCount.incrementAndGet();
    }
    
    public static void recordRateLimit() {
        int count = rateLimitCount.incrementAndGet();
        // Запоминаем время первого rate limit
        firstRateLimitTime.compareAndSet(0, System.currentTimeMillis());
        
        // Каждые 10 срабатываний выводим в консоль
        if (count % 10 == 0) {
            long elapsed = (System.currentTimeMillis() - firstRateLimitTime.get()) / 1000;
            System.out.printf("[RATE LIMIT] Сработал %d раз (прошло %d сек с первого)%n", 
                count, elapsed);
        }
    }
    
    public static int getCount() {
        return rateLimitCount.get();
    }
    
    public static int getSuccessCount() {
        return successCount.get();
    }
    
    public static void reset() {
        rateLimitCount.set(0);
        successCount.set(0);
        firstRateLimitTime.set(0);
    }
    
    /**
     * Вывести итоговую статистику.
     */
    public static void printSummary() {
        int total = successCount.get() + rateLimitCount.get();
        if (total == 0) return;
        
        System.out.println();
        System.out.println("═══════════════════════════════════════");
        System.out.println("  СТАТИСТИКА RATE LIMIT");
        System.out.println("═══════════════════════════════════════");
        System.out.printf("  Успешных входов: %d%n", successCount.get());
        System.out.printf("  Rate Limit (429): %d%n", rateLimitCount.get());
        System.out.printf("  Всего попыток: %d%n", total);
        System.out.printf("  %% Rate Limit: %.1f%%%n", 
            (rateLimitCount.get() * 100.0) / total);
        System.out.println("═══════════════════════════════════════");
        System.out.println();
    }
}
```

### Обновлённый PostLogin с кастомным счётчиком

```java
public static ChainBuilder postLogin() {
    return exec(
        http("POST Login")
            .post("/showcase/ui/misc/login.xhtml")
            .formParam("j_username", "#{username}")
            .formParam("j_password", "#{password}")
            .formParam("javax.faces.ViewState", "#{viewState}")
            .formParam("login", "Login")
            .check(
                status().validate(
                    (response, session) -> {
                        int code = response.status().code();
                        
                        if (code == 200 || code == 302) {
                            RateLimitCounter.recordSuccess();
                            return session
                                .set("loginStatus", "SUCCESS")
                                .set("rateLimited", false);
                                
                        } else if (code == 429) {
                            RateLimitCounter.recordRateLimit();
                            return session
                                .set("loginStatus", "RATE_LIMITED")
                                .set("rateLimited", true);
                                
                        } else {
                            throw new RuntimeException(
                                "Unexpected status: " + code
                            );
                        }
                    }
                )
            )
    );
}
```

### В симуляции — сброс и вывод статистики

```java
// В MaxPerformanceSimulation
{
    // Сбрасываем счётчики перед тестом
    RateLimitCounter.reset();
    
    setUp(...)
    .protocols(httpProtocol)
    .assertions(
        global().responseTime().percentile95().lt(500),
        // Для POST разрешаем высокий процент "ошибок" (429 теперь успех)
        details("POST Login").failedRequests().percent().lt(1.0)
    )
    .after((simulation, result) -> {
        // После завершения теста выводим статистику
        RateLimitCounter.printSummary();
    });
}
```

### Что мы увидим в консоли во время теста

```
[RATE LIMIT] Сработал 10 раз (прошло 45 сек с первого)
[RATE LIMIT] Сработал 20 раз (прошло 52 сек с первого)
[RATE LIMIT] Сработал 30 раз (прошло 58 сек с первого)
...
═══════════════════════════════════════
  СТАТИСТИКА RATE LIMIT
═══════════════════════════════════════
  Успешных входов: 342
  Rate Limit (429): 158
  Всего попыток: 500
  % Rate Limit: 31.6%
═══════════════════════════════════════
```

### Как это выглядит в отчёте Gatling

После теста в отчёте:

```
---- Errors --------------------------------------------------------------------
> Unexpected status: 500                           2 ( 0.4%)  ← реальные ошибки
> Unexpected status: 503                           1 ( 0.2%)  ← реальные ошибки
```

**429 ошибок нет в этом списке!** Потому что мы пометили их как успех через `validate()`.

---

## Итоговая картина

### До теста (в коде):
```java
.assertions(
    // Если видим, что assertion FAILED — значит нашли предел
    global().failedRequests().percent().lt(1.0)  // реальные ошибки
)
```

### Во время теста (в консоли):
```
[RATE LIMIT] Сработал 10 раз ← первые признаки
[RATE LIMIT] Сработал 50 раз ← усиливается
```

### После теста (в консоли + отчёт):
```
СТАТИСТИКА RATE LIMIT:
  Успешных входов: 342
  Rate Limit: 158 (31.6%)  ← вот она, точка деградации!
```

Вы правы! Если rate limit срабатывает на **любом** запросе (не только на POST логина), то все методы должны быть готовы к 429. Давайте исправлю **весь класс** целиком.

```java
package primeshowcase.scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import primeshowcase.utils.GatlingLogger;
import primeshowcase.utils.RateLimitCounter;

/**
 * Переиспользуемый сценарий логина.
 * Все методы корректно обрабатывают Rate Limit (429).
 */
public final class LoginScenario {
    
    private LoginScenario() {}
    
    // ===== ЭЛЕМЕНТАРНЫЕ ДЕЙСТВИЯ =====
    
    /**
     * Шаг 1: GET страницы логина + извлечение ViewState.
     * 429 → сохраняем метку и продолжаем (ViewState не будет!)
     * 200 → извлекаем ViewState
     * Остальное → FAIL
     */
    public static ChainBuilder getLoginPage() {
        return exec(
            http("GET Login Page")
                .get("/showcase/ui/misc/login.xhtml")
                .check(
                    status().validate(
                        (response, session) -> {
                            int code = response.status().code();
                            
                            if (code == 200) {
                                // Успех — извлекаем ViewState из тела ответа
                                String body = response.body().string();
                                String viewState = extractViewState(body);
                                
                                if (viewState == null || viewState.isEmpty()) {
                                    throw new RuntimeException(
                                        "ViewState не найден в ответе 200! " +
                                        "Возможно, изменилась структура страницы."
                                    );
                                }
                                
                                RateLimitCounter.recordSuccess();
                                
                                return session
                                    .set("loginStatus", "SUCCESS")
                                    .set("viewState", viewState)
                                    .set("rateLimited", false);
                                    
                            } else if (code == 429) {
                                // Rate Limit на GET — ViewState НЕ БУДЕТ
                                RateLimitCounter.recordRateLimit();
                                
                                GatlingLogger.logRateLimit("GET Login Page", 
                                    session.getString("username"));
                                
                                return session
                                    .set("loginStatus", "RATE_LIMITED")
                                    .set("viewState", "")       // пустой ViewState
                                    .set("rateLimited", true);
                                    
                            } else {
                                // Реальная ошибка
                                throw new RuntimeException(
                                    "GET Login Page: неожиданный статус " + code +
                                    " для пользователя " + session.getString("username")
                                );
                            }
                        }
                    )
                )
        );
    }
    
    /**
     * Шаг 2: POST логина с креденшелами.
     * Выполняется ТОЛЬКО если предыдущий шаг был успешным (есть ViewState).
     * 200/302 → успешный вход
     * 429 → rate limit
     * Остальное → FAIL
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
                    status().validate(
                        (response, session) -> {
                            int code = response.status().code();
                            
                            if (code == 200 || code == 302) {
                                // Успешный логин
                                RateLimitCounter.recordSuccess();
                                
                                GatlingLogger.logSuccess("POST Login", code,
                                    session.getString("username"));
                                
                                return session
                                    .set("loginStatus", "SUCCESS")
                                    .set("rateLimited", false);
                                    
                            } else if (code == 429) {
                                // Rate Limit на POST
                                RateLimitCounter.recordRateLimit();
                                
                                GatlingLogger.logRateLimit("POST Login",
                                    session.getString("username"));
                                
                                return session
                                    .set("loginStatus", "RATE_LIMITED")
                                    .set("rateLimited", true);
                                    
                            } else if (code == 401 || code == 403) {
                                // Неверный логин/пароль — реальная ошибка
                                throw new RuntimeException(
                                    "POST Login: неверные креденшелы для " +
                                    session.getString("username") +
                                    " (статус " + code + ")"
                                );
                                
                            } else {
                                // Другие ошибки (500, 503)
                                throw new RuntimeException(
                                    "POST Login: неожиданный статус " + code +
                                    " для пользователя " + session.getString("username")
                                );
                            }
                        }
                    )
                )
        );
    }
    
    /**
     * Шаг 3: GET главной страницы для проверки успешного входа.
     * 200 + есть "Welcome" → точно залогинились
     * 429 → rate limit (но после успешного логина — странно)
     * 200 без "Welcome" → не залогинились
     */
    public static ChainBuilder verifyMainPage() {
        return exec(
            http("GET Main Page (Verify)")
                .get("/showcase/ui/misc/login.xhtml")
                .check(
                    status().validate(
                        (response, session) -> {
                            int code = response.status().code();
                            
                            if (code == 200) {
                                String body = response.body().string();
                                
                                if (body.contains("Welcome")) {
                                    // Точно залогинились
                                    RateLimitCounter.recordSuccess();
                                    
                                    return session
                                        .set("loginStatus", "VERIFIED")
                                        .set("rateLimited", false);
                                        
                                } else {
                                    // Страница без Welcome — возможно, не залогинились
                                    throw new RuntimeException(
                                        "GET Main Page: статус 200, но нет 'Welcome'. " +
                                        "Пользователь " + session.getString("username") +
                                        " не залогинился?"
                                    );
                                }
                                
                            } else if (code == 429) {
                                // Rate limit даже после логина
                                RateLimitCounter.recordRateLimit();
                                
                                GatlingLogger.logRateLimit("GET Main Page",
                                    session.getString("username"));
                                
                                return session
                                    .set("loginStatus", "RATE_LIMITED")
                                    .set("rateLimited", true);
                                    
                            } else {
                                throw new RuntimeException(
                                    "GET Main Page: неожиданный статус " + code +
                                    " для пользователя " + session.getString("username")
                                );
                            }
                        }
                    )
                )
        );
    }
    
    // ===== ВСПОМОГАТЕЛЬНЫЙ МЕТОД =====
    
    /**
     * Извлекает ViewState из HTML.
     * Выделено в отдельный метод для тестируемости.
     */
    private static String extractViewState(String html) {
        // Ищем <input name="javax.faces.ViewState" value="..." />
        String marker = "name=\"javax.faces.ViewState\"";
        int startIdx = html.indexOf(marker);
        
        if (startIdx == -1) {
            return null;
        }
        
        // Ищем value=" после marker
        int valueIdx = html.indexOf("value=\"", startIdx);
        if (valueIdx == -1) {
            return null;
        }
        
        valueIdx += 7;  // пропускаем 'value="'
        int endIdx = html.indexOf("\"", valueIdx);
        
        if (endIdx == -1) {
            return null;
        }
        
        return html.substring(valueIdx, endIdx);
    }
    
    // ===== СБОРКА СЦЕНАРИЕВ =====
    
    /**
     * Полный сценарий: GET → POST → VERIFY.
     * Если на любом шаге rate limit — пропускаем следующие шаги.
     */
    public static ChainBuilder fullLoginFlow() {
        return exec(getLoginPage())
            // Пауза ТОЛЬКО если не rate limit
            .doIf(session -> !session.getBoolean("rateLimited"))
            .then(pause(1))
            
            // POST логина — ТОЛЬКО если есть ViewState (не было rate limit на GET)
            .doIf(session -> !session.getBoolean("rateLimited"))
            .then(postLogin())
            
            // Пауза перед проверкой
            .doIf(session -> !session.getBoolean("rateLimited"))
            .then(pause(1))
            
            // Проверка главной — ТОЛЬКО если залогинились
            .doIf(session -> !session.getBoolean("rateLimited"))
            .then(verifyMainPage());
    }
    
    /**
     * Готовый ScenarioBuilder с фидером.
     */
    public static ScenarioBuilder loginScenario(String name) {
        return scenario(name)
            .feed(primeshowcase.feeders.UserFeeders.circular())
            .exec(fullLoginFlow());
    }
    
    /**
     * Готовый ScenarioBuilder без фидера (для хардкодных кредов).
     */
    public static ScenarioBuilder loginScenarioHardcoded(String name) {
        return scenario(name)
            .exec(fullLoginFlow());
    }
    
    /**
     * Сценарий с удержанием сессии (для теста лимита сессий).
     * После успешного логина держит сессию активной.
     */
    public static ScenarioBuilder sessionHolderScenario(String name, int holdMinutes) {
        return scenario(name)
            .feed(primeshowcase.feeders.UserFeeders.circular())
            .exec(fullLoginFlow())
            // ТОЛЬКО если успешно залогинились — удерживаем сессию
            .doIf(session -> !session.getBoolean("rateLimited"))
            .then(
                during(holdMinutes * 60)
                    .on(
                        exec(
                            http("Keep-Alive")
                                .get("/showcase/ui/misc/login.xhtml")
                                .check(
                                    status().validate(
                                        (response, session) -> {
                                            int code = response.status().code();
                                            if (code == 200 || code == 429) {
                                                // И 200, и 429 допустимы для keep-alive
                                                return session;
                                            }
                                            throw new RuntimeException(
                                                "Keep-Alive: неожиданный статус " + code
                                            );
                                        }
                                    )
                                )
                        )
                        .pause(30)  // пинг каждые 30 секунд
                    )
            );
    }
}
```

## Обновлённый RateLimitCounter

```java
package primeshowcase.utils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Потокобезопасный счётчик Rate Limit.
 */
public class RateLimitCounter {
    
    private static final AtomicInteger rateLimitCount = new AtomicInteger(0);
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicLong firstRateLimitTime = new AtomicLong(0);
    private static final AtomicLong startTime = new AtomicLong(0);
    
    public static void recordSuccess() {
        successCount.incrementAndGet();
    }
    
    public static void recordRateLimit() {
        int count = rateLimitCount.incrementAndGet();
        firstRateLimitTime.compareAndSet(0, System.currentTimeMillis());
        
        // Каждые 10 срабатываний — выводим статистику
        if (count % 10 == 0) {
            long elapsed;
            if (firstRateLimitTime.get() > 0) {
                elapsed = (System.currentTimeMillis() - firstRateLimitTime.get()) / 1000;
                System.out.printf("[RATE LIMIT] %d срабатываний за %d сек с первого%n", 
                    count, elapsed);
            } else {
                System.out.printf("[RATE LIMIT] %d срабатываний%n", count);
            }
        }
    }
    
    public static int getRateLimitCount() {
        return rateLimitCount.get();
    }
    
    public static int getSuccessCount() {
        return successCount.get();
    }
    
    public static int getTotalCount() {
        return successCount.get() + rateLimitCount.get();
    }
    
    public static void reset() {
        rateLimitCount.set(0);
        successCount.set(0);
        firstRateLimitTime.set(0);
        startTime.set(System.currentTimeMillis());
    }
    
    public static void printSummary() {
        int total = getTotalCount();
        if (total == 0) return;
        
        long elapsed = (System.currentTimeMillis() - startTime.get()) / 1000;
        double rateLimitPercent = (rateLimitCount.get() * 100.0) / total;
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  СТАТИСТИКА RATE LIMIT");
        System.out.println("═══════════════════════════════════════════");
        System.out.printf("  Длительность теста:        %d сек%n", elapsed);
        System.out.printf("  Успешных запросов:         %d%n", successCount.get());
        System.out.printf("  Rate Limit (429):          %d%n", rateLimitCount.get());
        System.out.printf("  Всего запросов:            %d%n", total);
        System.out.printf("  %% Rate Limit:              %.1f%%%n", rateLimitPercent);
        System.out.println("───────────────────────────────────────────");
        
        if (rateLimitPercent == 0) {
            System.out.println("  Статус: Rate Limit НЕ достигнут");
        } else if (rateLimitPercent < 10) {
            System.out.println("  Статус: Начало деградации");
        } else if (rateLimitPercent < 50) {
            System.out.println("  Статус: Активный Rate Limit");
        } else {
            System.out.println("  Статус: Жёсткий Rate Limit");
        }
        
        System.out.println("═══════════════════════════════════════════");
        System.out.println();
    }
}
```

## Обновлённый GatlingLogger

```java
package primeshowcase.utils;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Кастомный логгер для отладки.
 */
public final class GatlingLogger {
    
    private static final DateTimeFormatter TIME_FORMAT = 
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static boolean debugEnabled = false;
    
    private GatlingLogger() {}
    
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }
    
    public static void logSuccess(String step, int status, String username) {
        if (!debugEnabled) return;
        String time = LocalTime.now().format(TIME_FORMAT);
        System.out.printf("[%s] ✅ %s: статус=%d, user=%s%n", 
            time, step, status, username != null ? username : "anon");
    }
    
    public static void logRateLimit(String step, String username) {
        // Rate Limit ВСЕГДА логируем (это важно)
        String time = LocalTime.now().format(TIME_FORMAT);
        System.out.printf("[%s] ⚠️  RATE LIMIT на %s, user=%s%n", 
            time, step, username != null ? username : "anon");
    }
    
    public static void logFailure(String step, int status, String message) {
        String time = LocalTime.now().format(TIME_FORMAT);
        System.err.printf("[%s] ❌ %s: статус=%d, %s%n", 
            time, step, status, message);
    }
    
    public static void separator() {
        if (!debugEnabled) return;
        System.out.println("─".repeat(60));
    }
}
```

## Что изменилось

| Проблема | Было | Стало |
|----------|------|-------|
| GET при 429 | Ломался без ViewState | Сохраняет `rateLimited=true`, ViewState="" |
| POST при 429 | Отдельная ветка | Единая логика с RateLimitCounter |
| Verify при 429 | Не обрабатывался | Обрабатывается, пишет в счётчик |
| Счётчик | Не было | `RateLimitCounter` считает все 429 |
| После GET 429 | Шёл на POST без ViewState | `doIf(!rateLimited)` пропускает POST |

## Как это работает при rate limit на GET

```
Пользователь 1: GET /login → 200 → ViewState=ABC → POST → 200 → OK
Пользователь 2: GET /login → 429 → rateLimited=true → 
                пропускаем POST (нет ViewState) → 
                пропускаем Verify →
                идём на следующий цикл (если есть)
```

