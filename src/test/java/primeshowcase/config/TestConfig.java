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
