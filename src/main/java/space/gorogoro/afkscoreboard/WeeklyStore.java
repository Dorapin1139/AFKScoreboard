package space.gorogoro.afkscoreboard;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 今週の放置秒数。判定はメモリ上で行い、data.yml への書き込みだけを専用スレッドで行う。
 */
final class WeeklyStore {

    private final AFKScoreboard plugin;
    private final File file;
    private final Map<UUID, Integer> seconds = new HashMap<>();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AFKScoreboard-Weekly");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<String> pendingYaml = new AtomicReference<>();

    private String weekId = "";
    private boolean dirty;

    WeeklyStore(AFKScoreboard plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    void load() {
        seconds.clear();
        if (!file.exists()) {
            weekId = currentWeekId();
            dirty = true;
            requestSave();
            return;
        }
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        weekId = yaml.getString("week-id");
        if (weekId == null) {
            weekId = "";
        }
        org.bukkit.configuration.ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int value = section.getInt(key + ".seconds");
                    if (value > 0) {
                        seconds.put(UUID.fromString(key), value);
                    }
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("data.yml の不正なUUIDをスキップしました: " + key);
                }
            }
        }
        if (rolloverIfNeeded()) {
            return;
        }
        if (weekId.isEmpty()) {
            weekId = currentWeekId();
            dirty = true;
            requestSave();
        }
    }

    int getSeconds(UUID uuid) {
        return seconds.getOrDefault(uuid, 0);
    }

    void addSecond(UUID uuid) {
        int next = seconds.getOrDefault(uuid, 0);
        if (next < Integer.MAX_VALUE) {
            seconds.put(uuid, next + 1);
            dirty = true;
        }
    }

    /**
     * 週が変わっていれば累計を捨てる。変わったときだけ true。
     */
    boolean rolloverIfNeeded() {
        String current = currentWeekId();
        if (current.equals(weekId)) {
            return false;
        }
        plugin.getLogger().info("放置ランキングを週次リセットしました: " + weekId + " -> " + current);
        weekId = current;
        seconds.clear();
        dirty = true;
        requestSave();
        return true;
    }

    /**
     * メインスレッドで YAML を作り、ファイルへの書き込みだけを専用スレッドへ渡す。
     * 書き込み待ちがある間の変更は、次の保存で最新の内容にまとめる。
     */
    void requestSave() {
        if (!dirty) {
            return;
        }
        dirty = false;
        String yaml = toYaml();
        if (pendingYaml.getAndSet(yaml) != null) {
            return;
        }
        saveExecutor.execute(() -> {
            String latest = pendingYaml.getAndSet(null);
            if (latest == null) {
                return;
            }
            try {
                file.getParentFile().mkdirs();
                Files.writeString(file.toPath(), latest, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().severe("data.yml の書き込みに失敗しました: " + e.getMessage());
            }
        });
    }

    void shutdown() {
        requestSave();
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("data.yml の書き込みが時間内に終わりませんでした。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String toYaml() {
        StringBuilder builder = new StringBuilder();
        builder.append("week-id: ").append(weekId).append('\n');
        builder.append("players:\n");
        for (Map.Entry<UUID, Integer> entry : seconds.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            builder.append("  ").append(entry.getKey()).append(":\n");
            builder.append("    seconds: ").append(entry.getValue()).append('\n');
        }
        return builder.toString();
    }

    private String currentWeekId() {
        ZoneId zone;
        try {
            String configured = plugin.getConfig().getString("timezone");
            zone = ZoneId.of(configured == null || configured.isBlank() ? "Asia/Tokyo" : configured.trim());
        } catch (DateTimeException ex) {
            zone = ZoneId.of("Asia/Tokyo");
        }
        DayOfWeek startDay = parseDay(plugin.getConfig().getString("week-start-day"));
        ZonedDateTime now = ZonedDateTime.now(zone);
        WeekFields fields = WeekFields.of(startDay, 4);
        int year = now.get(fields.weekBasedYear());
        int week = now.get(fields.weekOfWeekBasedYear());
        return year + "-W" + String.format(Locale.ROOT, "%02d", week);
    }

    private static DayOfWeek parseDay(String raw) {
        if (raw == null || raw.isBlank()) {
            return DayOfWeek.MONDAY;
        }
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DayOfWeek.MONDAY;
        }
    }
}
