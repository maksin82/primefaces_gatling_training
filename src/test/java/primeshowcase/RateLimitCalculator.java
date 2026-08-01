package primeshowcase;

/**
 * Калькулятор для расчёта нагрузок при тестировании token bucket rate limit.
 */
public class RateLimitCalculator {

    // Входные параметры
    private final int bucketCapacity;      // ёмкость bucket (токенов)
    private final double refillRate;       // скорость восстановления (токенов/сек)
    private final double tokensPerLogin;   // расход токенов на 1 логин

    public RateLimitCalculator(int bucketCapacity,
                               double refillTokens,
                               double refillSeconds,
                               double tokensPerLogin) {
        this.bucketCapacity = bucketCapacity;
        this.refillRate = refillTokens / refillSeconds;
        this.tokensPerLogin = tokensPerLogin;
    }

    /**
     * Устойчивый темп логинов — система может держать бесконечно долго.
     */
    public double getSustainableRate() {
        return refillRate / tokensPerLogin;
    }

    /**
     * Максимальный burst — сколько логинов можно сделать за один раз.
     */
    public int getMaxBurstLogins() {
        return (int)(bucketCapacity / tokensPerLogin);
    }

    /**
     * Время до истощения bucket при заданной нагрузке.
     * @param loginRate нагрузка в логинах/сек
     * @return время в секундах, или -1 если нагрузка ниже устойчивой
     */
    public double getTimeToExhaustion(double loginRate) {
        double consumptionRate = loginRate * tokensPerLogin;
        double netDrain = consumptionRate - refillRate;

        if (netDrain <= 0) {
            return -1;  // никогда не истощится
        }

        return bucketCapacity / netDrain;
    }

    /**
     * Нагрузка, при которой bucket истощится за указанное время.
     * @param targetSeconds желаемое время до истощения
     * @return необходимая нагрузка в логинах/сек
     */
    public double getRateForExhaustionTime(double targetSeconds) {
        double netDrain = bucketCapacity / targetSeconds;
        double consumptionRate = netDrain + refillRate;
        return consumptionRate / tokensPerLogin;
    }

    /**
     * Генерирует массив ступеней для MaxPerformance теста.
     */
    public double[] generateSteps(int numberOfSteps, double stepSize, double startFrom) {
        double[] steps = new double[numberOfSteps];
        for (int i = 0; i < numberOfSteps; i++) {
            steps[i] = Math.round((startFrom + (i * stepSize)) * 1000.0) / 1000.0;  // округляем до 3 знаков
        }
        return steps;
    }

    /**
     * Форматирует время в читаемый вид.
     */
    private String formatTime(double seconds) {
        if (seconds < 0) return "никогда не истощится";
        if (seconds < 60) return String.format("%.0f сек", seconds);
        if (seconds < 3600) return String.format("%.1f мин", seconds / 60);
        return String.format("%.1f час", seconds / 3600);
    }

    /**
     * Вывод всех расчётов в консоль.
     */
    public void printAnalysis() {
        double sustainable = getSustainableRate();
        int maxBurst = getMaxBurstLogins();

        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║     АНАЛИЗ TOKEN BUCKET ДЛЯ НАГРУЗОЧНОГО ТЕСТА  ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║                                                  ║");
        System.out.printf("║  Ёмкость bucket:           %4d токенов          ║%n", bucketCapacity);
        System.out.printf("║  Скорость восстановления:  %4.2f токенов/сек    ║%n", refillRate);
        System.out.printf("║  Расход на 1 логин:        %4.0f токенов          ║%n", tokensPerLogin);
        System.out.println("║                                                  ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║     КЛЮЧЕВЫЕ ПОКАЗАТЕЛИ                          ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.printf("║  Устойчивый темп:          %6.3f лог/сек       ║%n", sustainable);
        System.out.printf("║  Устойчивый темп:          %6.1f лог/мин       ║%n", sustainable * 60);
        System.out.printf("║  Максимальный burst:       %4d логинов         ║%n", maxBurst);
        System.out.printf("║  Burst с запасом 20%%:      %4d логинов         ║%n", (int)(maxBurst * 0.8));
        System.out.println("║                                                  ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║     ВРЕМЯ ИСТОЩЕНИЯ BUCKET ПРИ РАЗНЫХ НАГРУЗКАХ ║");
        System.out.println("╠══════════════════════════════════════════════════╣");

        double[] testRates = {
                sustainable * 0.6,   // 60% от устойчивого
                sustainable,          // ровно устойчивый
                sustainable * 1.2,   // чуть выше
                sustainable * 2,     // 2x
                sustainable * 3,     // 3x
                sustainable * 5,     // 5x
                sustainable * 10,    // 10x
                1.0,                 // 1 лог/сек
                5.0,                 // 5 лог/сек
                10.0                 // 10 лог/сек
        };

        for (double rate : testRates) {
            double time = getTimeToExhaustion(rate);
            String marker = "";

            if (rate == sustainable) {
                marker = " ← УСТОЙЧИВЫЙ ТЕМП";
            } else if (rate < sustainable) {
                marker = " ← ниже устойчивого";
            }

            System.out.printf("║  %6.3f лог/сек (%5.1f/мин): %-20s%s%n",
                    rate, rate * 60, formatTime(time), marker);
            if (marker.isEmpty()) System.out.println("║                                                  ║");
        }

        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║     РЕКОМЕНДУЕМЫЕ СТУПЕНИ MaxPerformance        ║");
        System.out.println("╠══════════════════════════════════════════════════╣");

        // Генерируем рекомендованные ступени
        double[] recommendedSteps = generateSteps(8, sustainable * 0.5, sustainable * 0.5);

        System.out.printf("║  Шаг ступени: %.3f лог/сек                       ║%n", sustainable * 0.5);
        System.out.println("║                                                  ║");

        for (int i = 0; i < recommendedSteps.length; i++) {
            double step = recommendedSteps[i];
            double time = getTimeToExhaustion(step);
            String label = "";

            if (Math.abs(step - sustainable) < 0.001) {
                label = " ← ЦЕЛЕВАЯ ТОЧКА";
            }

            System.out.printf("║  Ступень %d: %6.3f лог/сек → %-18s%s%n",
                    i + 1, step, formatTime(time), label);
            if (!label.isEmpty()) System.out.println("║                                                  ║");
        }

        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║     РЕКОМЕНДАЦИИ ПО ДЛИТЕЛЬНОСТИ СТУПЕНЕЙ       ║");
        System.out.println("╠══════════════════════════════════════════════════╣");

        // На какой ступени истощение наступает быстро
        for (double step : recommendedSteps) {
            double time = getTimeToExhaustion(step);
            if (time > 0 && time < 300) {  // меньше 5 минут
                System.out.printf("║  На %.3f лог/сек истощение через %.0f сек      ║%n", step, time);
                System.out.printf("║  → держать ступень минимум %.0f сек              ║%n", time * 1.5);
            }
        }

        System.out.println("║                                                  ║");
        System.out.println("║  Для Stability теста:                            ║");
        System.out.printf("║  Целевая нагрузка: %.3f лог/сек (80%% от устойч.)║%n", sustainable * 0.8);
        System.out.printf("║  Длительность: минимум 30 минут                  ║%n");

        System.out.println("║                                                  ║");
        System.out.println("║  Для Burst теста:                                ║");
        System.out.printf("║  Одновременный запуск %d пользователей           ║%n", (int)(maxBurst * 0.8));
        System.out.printf("║  (80%% от максимального burst)                    ║%n");

        System.out.println("╚══════════════════════════════════════════════════╝");
    }

    public static void main(String[] args) {
        // Ваши параметры стенда
        RateLimitCalculator calc = new RateLimitCalculator(
                1000,   // ёмкость bucket — 1000 токенов
                2,      // восстановление: 2 токена
                4,      // за 4 секунды
                6       // расход на логин: 6 токенов
        );

        calc.printAnalysis();

        System.out.println();
        System.out.println("=== ПРОВЕРКА ДЛЯ СЕССИЙ ===");
        System.out.printf("Максимум сессий: 500%n");
        System.out.printf("Для теста сессий: rampUsers(400) — 80%% от лимита%n");
        System.out.printf("Эксцесс-тест: rampUsers(100) поверх 500 активных%n");
    }
}