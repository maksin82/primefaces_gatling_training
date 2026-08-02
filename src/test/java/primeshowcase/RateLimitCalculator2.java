package primeshowcase;

public class RateLimitCalculator2 {
    public static void main(String[] args) {
        System.out.println("=== РАСЧЁТ РЕАЛЬНОГО РАСХОДА ТОКЕНОВ ===");
        System.out.println();

        int bucketCapacity = 1000;
        double refillRate = 0.5;  // токенов/сек
        int successfulLogins = 165;  // из вашего теста

        System.out.println("Вариант 1 — ошибка через 15 минут:");
        double time1 = 15 * 60;  // секунд
        double tokensAvailable1 = bucketCapacity + (refillRate * time1);
        double tokensPerLogin1 = tokensAvailable1 / successfulLogins;
        System.out.printf("  Доступно токенов: %.0f%n", tokensAvailable1);
        System.out.printf("  Расход на логин:  %.1f токенов%n", tokensPerLogin1);

        System.out.println();
        System.out.println("Вариант 2 — ошибка через 17 минут:");
        double time2 = 17 * 60;
        double tokensAvailable2 = bucketCapacity + (refillRate * time2);
        double tokensPerLogin2 = tokensAvailable2 / successfulLogins;
        System.out.printf("  Доступно токенов: %.0f%n", tokensAvailable2);
        System.out.printf("  Расход на логин:  %.1f токенов%n", tokensPerLogin2);

        System.out.println();
        System.out.println("Вариант 3 — ошибка через 20 минут:");
        double time3 = 20 * 60;
        double tokensAvailable3 = bucketCapacity + (refillRate * time3);
        double tokensPerLogin3 = tokensAvailable3 / successfulLogins;
        System.out.printf("  Доступно токенов: %.0f%n", tokensAvailable3);
        System.out.printf("  Расход на логин:  %.1f токенов%n", tokensPerLogin3);

        System.out.println();
        System.out.println("=== ВЫВОД ===");
        System.out.println("Реальный расход на логин: ~9-10 токенов");
        System.out.println("(а не 6, как предполагалось изначально)");
        System.out.println();
        System.out.println("Причины: калькулятор считает 9.7 токенов");
        System.out.println("Возможно, ваш сценарий делает больше запросов,");
        System.out.println("чем просто логин, или каждый запрос тратит");
        System.out.println("не 1 токен, а больше.");
    }
}
