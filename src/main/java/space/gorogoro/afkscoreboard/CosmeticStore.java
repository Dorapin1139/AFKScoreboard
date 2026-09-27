package space.gorogoro.afkscoreboard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 見た目ボーナス用の今週の秒数と抽選結果。cosmetics.yml への書き込みだけを専用スレッドで行う。
 * 週間累計ランキングの data.yml とは別ファイル。
 */
final class CosmeticStore {

    private final AFKScoreboard plugin;
    private final File file;
    private final Map<UUID, Record> records = new HashMap<>();
    // /afklook で非表示にしている種類。週が変わっても消さない
    private final Map<UUID, EnumSet<Slot>> lookOff = new HashMap<>();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AFKScoreboard-Cosmetic");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<String> pendingYaml = new AtomicReference<>();

    private String weekId = "";
    private boolean dirty;

    CosmeticStore(AFKScoreboard plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cosmetics.yml");
    }

    void load() {
        records.clear();
        lookOff.clear();
        if (!file.exists()) {
            weekId = currentWeekId();
            dirty = true;
            requestSave();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String storedWeek = yaml.getString("week-id");
        weekId = storedWeek == null ? "" : storedWeek;
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    ConfigurationSection row = section.getConfigurationSection(key);
                    if (row == null) {
                        continue;
                    }
                    Record record = new Record();
                    record.seconds = Math.max(0, row.getInt("seconds"));
                    record.particle = emptyToNull(row.getString("particle"));
                    record.block = emptyToNull(row.getString("block"));
                    record.mount = emptyToNull(row.getString("mount"));
                    record.mountVariant = emptyToNull(row.getString("mount-variant"));
                    records.put(UUID.fromString(key), record);
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("cosmetics.yml の不正なUUIDをスキップしました: " + key);
                }
            }
        }
        ConfigurationSection offSection = yaml.getConfigurationSection("look-off");
        if (offSection != null) {
            for (String key : offSection.getKeys(false)) {
                try {
                    EnumSet<Slot> slots = EnumSet.noneOf(Slot.class);
                    for (String name : offSection.getStringList(key)) {
                        Slot slot = Slot.parse(name);
                        if (slot != null) {
                            slots.add(slot);
                        }
                    }
                    if (!slots.isEmpty()) {
                        lookOff.put(UUID.fromString(key), slots);
                    }
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("cosmetics.yml の不正なUUIDをスキップしました: " + key);
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

    Record record(UUID uuid) {
        return records.computeIfAbsent(uuid, ignored -> new Record());
    }

    boolean isOff(UUID uuid, Slot slot) {
        EnumSet<Slot> slots = lookOff.get(uuid);
        return slots != null && slots.contains(slot);
    }

    void setOff(UUID uuid, Slot slot, boolean off) {
        EnumSet<Slot> slots = lookOff.computeIfAbsent(uuid, ignored -> EnumSet.noneOf(Slot.class));
        boolean changed = off ? slots.add(slot) : slots.remove(slot);
        if (slots.isEmpty()) {
            lookOff.remove(uuid);
        }
        if (changed) {
            dirty = true;
            requestSave();
        }
    }

    void addSecond(UUID uuid) {
        Record record = record(uuid);
        if (record.seconds < Integer.MAX_VALUE) {
            record.seconds++;
            dirty = true;
        }
    }

    void markDirty() {
        dirty = true;
    }

    boolean rolloverIfNeeded() {
        String current = currentWeekId();
        if (current.equals(weekId)) {
            return false;
        }
        plugin.getLogger().info("見た目ボーナスを週次リセットしました: " + weekId + " -> " + current);
        weekId = current;
        records.clear();
        dirty = true;
        requestSave();
        return true;
    }

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
                plugin.getLogger().severe("cosmetics.yml の書き込みに失敗しました: " + e.getMessage());
            }
        });
    }

    void shutdown() {
        requestSave();
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("cosmetics.yml の書き込みが時間内に終わりませんでした。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String toYaml() {
        StringBuilder builder = new StringBuilder();
        builder.append("week-id: ").append(yamlScalar(weekId)).append('\n');
        builder.append("players:\n");
        for (Map.Entry<UUID, Record> entry : records.entrySet()) {
            Record record = entry.getValue();
            if (record.seconds <= 0 && record.particle == null && record.block == null && record.mount == null) {
                continue;
            }
            builder.append("  ").append(entry.getKey()).append(":\n");
            builder.append("    seconds: ").append(record.seconds).append('\n');
            append(builder, "particle", record.particle);
            append(builder, "block", record.block);
            append(builder, "mount", record.mount);
            append(builder, "mount-variant", record.mountVariant);
        }
        // /afklook の非表示設定。週のリセットでは消さない
        builder.append("look-off:\n");
        for (Map.Entry<UUID, EnumSet<Slot>> entry : lookOff.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            builder.append("  ").append(entry.getKey()).append(": [");
            boolean first = true;
            for (Slot slot : entry.getValue()) {
                if (!first) {
                    builder.append(", ");
                }
                builder.append(slot.key);
                first = false;
            }
            builder.append("]\n");
        }
        return builder.toString();
    }

    private static void append(StringBuilder builder, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append("    ").append(key).append(": ").append(yamlScalar(value)).append('\n');
    }

    private static String yamlScalar(String value) {
        if (value.matches("[A-Za-z0-9_.-]+")) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private String currentWeekId() {
        ZoneId zone;
        try {
            String configured = plugin.getConfig().getString("timezone");
            zone = ZoneId.of(configured == null || configured.isBlank() ? "Asia/Tokyo" : configured.trim());
        } catch (DateTimeException ex) {
            zone = ZoneId.of("Asia/Tokyo");
        }
        DayOfWeek startDay = DayOfWeek.MONDAY;
        String raw = plugin.getConfig().getString("week-start-day");
        if (raw != null && !raw.isBlank()) {
            try {
                startDay = DayOfWeek.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                startDay = DayOfWeek.MONDAY;
            }
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        WeekFields fields = WeekFields.of(startDay, 4);
        int year = now.get(fields.weekBasedYear());
        int week = now.get(fields.weekOfWeekBasedYear());
        return year + "-W" + String.format(Locale.ROOT, "%02d", week);
    }

    private static String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    /**
     * /afklook で切り替える見た目の種類
     */
    enum Slot {
        PARTICLE("particle"),
        BLOCK("block"),
        MOUNT("mount");

        final String key;

        Slot(String key) {
            this.key = key;
        }

        static Slot parse(String raw) {
            if (raw == null) {
                return null;
            }
            for (Slot slot : values()) {
                if (slot.key.equalsIgnoreCase(raw.trim())) {
                    return slot;
                }
            }
            return null;
        }
    }

    static final class Record {
        int seconds;
        String particle;
        String block;
        String mount;
        String mountVariant;
    }
}
