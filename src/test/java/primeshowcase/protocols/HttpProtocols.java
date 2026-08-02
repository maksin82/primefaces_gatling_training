package primeshowcase.protocols;

import primeshowcase.config.TestConfig;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

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
                // Полезно для прохождения базовых фильтров безопасности сервера:
                .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .acceptLanguageHeader("en-US,en;q=0.5")
                .acceptEncodingHeader("gzip, deflate")
                .userAgentHeader("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Firefox/120.0")

                // ===== РЕАЛЬНО ПОЛЕЗНАЯ ОПТИМИЗАЦИЯ =====
                // Отключает логирование картинок/css/шрифтов (если они скачиваются внутри сценария),
                // чтобы они не засоряли вам финальный отчет Gatling ошибками и лишними графиками.
                .silentResources();
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
                        status().is(200)
                );
    }
}