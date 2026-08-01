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

Теперь понятно, как отделить мух от котлет — реальные ошибки от ожидаемого rate limit?