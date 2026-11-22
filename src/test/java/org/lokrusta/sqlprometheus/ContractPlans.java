package org.lokrusta.sqlprometheus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ожидаемый план кейса лежит в {@code src/test/resources/contract/<id>.plan.txt}.
 * Перезапись: {@code CONTRACT_PLANS_WRITE=true}.
 * Границы от {@code NOW()} в плане заменяются на {@code [time]}.
 * Пары в {@code filters=[{...}]} сортируются, их порядок в Calcite не фиксирован.
 */
final class ContractPlans {
    private static final Pattern FILTERS = Pattern.compile("filters=\\[\\{([^{}]*)}]");
    private static final Pattern BOUND = Pattern.compile("(start|end)=\\[(\\d+)]");
    private static final Set<String> FIXED_BOUNDS = Set.of(
            "1794960000001",
            "1794960900000",
            "1794963600000",
            "1794974400000");

    private ContractPlans() {
    }

    static void assertSame(String id, String actual) {
        String canonical = canonical(actual);
        if ("true".equals(System.getenv("CONTRACT_PLANS_WRITE"))) {
            write(id, canonical);
            return;
        }
        String expected = read(id);
        assertEquals(expected, canonical, id);
    }

    private static String canonical(String plan) {
        String sorted = FILTERS.matcher(plan).replaceAll(match -> {
            String[] parts = match.group(1).split(", ");
            Arrays.sort(parts);
            return "filters=[{" + String.join(", ", parts) + "}]";
        });
        String timed = BOUND.matcher(sorted).replaceAll(match ->
                FIXED_BOUNDS.contains(match.group(2))
                        ? match.group()
                        : match.group(1) + "=[time]");
        return timed.replace("\r\n", "\n").strip() + "\n";
    }

    private static String read(String id) {
        String name = "/contract/" + id + ".plan.txt";
        try (InputStream in = ContractPlans.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new AssertionError("Нет " + name + ". Запишите его командой CONTRACT_PLANS_WRITE=true");
            }
            return canonical(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new AssertionError(name + ": " + ex.getMessage(), ex);
        }
    }

    private static void write(String id, String canonical) {
        Path path = Path.of("src", "test", "resources", "contract", id + ".plan.txt");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, canonical, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError(path + ": " + ex.getMessage(), ex);
        }
    }
}
