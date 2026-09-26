package space.gorogoro.afkscoreboard;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class AFKScoreboard extends JavaPlugin implements Listener {

    private Scoreboard afkScoreboard;
    private Objective afkObjective;

    // 読み込んだ各ゾーンの座標範囲データを保持するマップ
    private final Map<String, ZoneArea> loadedZones = new HashMap<>();

    // プレイヤーの「現在の連続放置時間（秒）」を保持するマップ
    private final Map<UUID, Integer> currentSessionTimes = new HashMap<>();

    // ログアウトしたプレイヤーのデータを一時保存するマップ（UUID -> 放置秒数）
    private final Map<UUID, Integer> disconnectedSessionTimes = new HashMap<>();
    // ログアウトした時刻を保存するマップ（UUID -> エポックミリ秒）
    private final Map<UUID, Long> disconnectTimes = new HashMap<>();

    // ランキングから自分を非表示にしているプレイヤーのUUIDを保持するセット
    private final Set<UUID> hiddenPlayers = new HashSet<>();

    // 過去に一度でも放置ゾーンに入ったことがあるプレイヤーを記憶するセット
    private final Set<UUID> welcomedPlayers = new HashSet<>();

    // 救済猶予時間（5分 = 300,000ミリ秒）
    private static final long RECOVERY_GRACE_PERIOD_MS = 5 * 60 * 1000L;

    // ゾーン内だけの見た目。停止時に乗客を消す
    private CosmeticService cosmetics;

    // config.yml の書き込み専用スレッド（1本なので書き込みは必ず順番に行われる）
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "AFKScoreboard-Save"));
    // 書き込み待ちの config.yml の内容（null なら書き込み待ちなし）
    private final AtomicReference<String> pendingConfigYaml = new AtomicReference<>();

    @Override
    public void onEnable() {
        // config.ymlの保存・読み込み処理
        saveDefaultConfig();
        loadWelcomedPlayers();
        loadHiddenPlayers();

        // スコアボードの初期化
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        this.afkScoreboard = manager.getNewScoreboard();

        // タイトル (Paper推奨の形式に修正)
        this.afkObjective = afkScoreboard.registerNewObjective(
                "afk_top10",
                Criteria.DUMMY,
                LegacyComponentSerializer.legacySection().deserialize("§e§l放置時間ランキング"),
                RenderType.INTEGER
        );
        this.afkObjective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // スコアのフォーマットを「空白（Blank）」に設定することで、右側の数字を完全に非表示
        this.afkObjective.numberFormat(NumberFormat.blank());

        // AxAFKZone の zones フォルダから座標定義を自動読み込み
        reloadAxAFKZones();

        // スコアボードの更新頻度（5秒ごと = 100ティックス）
        Bukkit.getScheduler().runTaskTimer(this, this::updateLeaderboard, 0L, 100L);

        // 滞在時間のカウントタスク（1秒ごと = 20ティックス）
        Bukkit.getScheduler().runTaskTimer(this, this::incrementTimeEverySecond, 0L, 20L);

        // 見た目は別タスク。パーティクルは既定 10 秒、追従チェックは 1 秒。乗客なので座標の毎 tick 更新はしない
        this.cosmetics = new CosmeticService(this);
        this.cosmetics.load();
        this.cosmetics.removeStrayEntities();
        long particleInterval = getConfig().getLong("particle-interval-ticks");
        if (particleInterval < 20L) {
            particleInterval = 200L;
        }
        Bukkit.getScheduler().runTaskTimer(this, this.cosmetics::tickParticles, particleInterval, particleInterval);
        Bukkit.getScheduler().runTaskTimer(this, this.cosmetics::maintain, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this.cosmetics::requestSave, 1200L, 1200L);

        // プラグイン起動時に、既にエリア内にいるプレイヤーを検知してカウントを開始する
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            // afkhide（非表示モード）になっていないプレイヤーのみ対象
            if (!hiddenPlayers.contains(uuid) && isPlayerInAnyZone(player.getLocation())) {
                currentSessionTimes.put(uuid, 0);
                player.setScoreboard(afkScoreboard);
            }
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(this.cosmetics, this);
    }

    @Override
    public void onDisable() {
        // PlugManX の再読み込みでも、頭上のブロックと MOB を残さない
        if (cosmetics != null) {
            cosmetics.shutdown();
        }

        // サーバー終了時、既読プレイヤーデータをconfig.ymlに確実に保存
        saveWelcomedPlayers();
        saveHiddenPlayers();

        // PlugManX などでアンロードされたとき、更新されないランキングボードが残らないようメインボードに戻す
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getScoreboard().equals(afkScoreboard)) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }

        // 書き込み待ちがすべて終わるまで待つ（サーバー終了時のみ、メインスレッドで待機する）
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                getLogger().severe("config.yml の書き込みが時間内に終わりませんでした。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        getLogger().info("The Plugin Has Been Disabled!");
    }

    /**
     * 現在の config.yml の内容を専用スレッドで書き込む
     * getConfig() へのアクセスはメインスレッドで行い、ファイルの書き込みだけを専用スレッドに任せる
     * 書き込み待ちが残っている間に呼ばれた場合は、最新の内容で 1 回にまとめて書き込む
     */
    private void requestSaveConfig() {
        String yaml = getConfig().saveToString();
        if (pendingConfigYaml.getAndSet(yaml) != null) {
            // すでに書き込み待ちがあるので、その書き込みで最新の内容が使われる
            return;
        }
        File configFile = new File(getDataFolder(), "config.yml");
        saveExecutor.execute(() -> {
            String latestYaml = pendingConfigYaml.getAndSet(null);
            try {
                Files.writeString(configFile.toPath(), latestYaml, StandardCharsets.UTF_8);
            } catch (IOException e) {
                getLogger().severe("config.yml の書き込みに失敗しました: " + e.getMessage());
            }
        });
    }

    /**
     * config.yml からメッセージ既読プレイヤーのUUIDを読み込む
     */
    private void loadWelcomedPlayers() {
        welcomedPlayers.clear();
        List<String> uuidStrings = getConfig().getStringList("welcomed-players");
        for (String s : uuidStrings) {
            try {
                welcomedPlayers.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /**
     * メッセージ既読プレイヤーのUUIDを config.yml へ保存する
     */
    private void saveWelcomedPlayers() {
        List<String> uuidStrings = welcomedPlayers.stream()
                .map(UUID::toString)
                .collect(Collectors.toList());
        getConfig().set("welcomed-players", uuidStrings);
        requestSaveConfig();
    }

    /**
     * config.yml から非表示プレイヤーのUUIDを読み込む
     */
    private void loadHiddenPlayers() {
        hiddenPlayers.clear();
        List<String> uuidStrings = getConfig().getStringList("hidden-players");
        for (String s : uuidStrings) {
            try {
                hiddenPlayers.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /**
     * 非表示プレイヤーのUUIDを config.yml へ保存する
     */
    private void saveHiddenPlayers() {
        List<String> uuidStrings = hiddenPlayers.stream()
                .map(UUID::toString)
                .collect(Collectors.toList());
        getConfig().set("hidden-players", uuidStrings);
        requestSaveConfig();
    }

    /**
     * コマンドの処理ルーチン
     * /afkhide コマンドでランキングの表示/非表示を切り替えます
     */
    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます。");
            return true;
        }

        UUID uuid = player.getUniqueId();

        if (command.getName().equalsIgnoreCase("afkhide")) {
            // エリア内にいるかどうかの判定
            boolean isInZone = isPlayerInAnyZone(player.getLocation());

            if (hiddenPlayers.contains(uuid)) {
                // 非表示（除外）リストから削除 ＝ 通常モードに戻す
                hiddenPlayers.remove(uuid);

                // config.yml へ保存する（書き込みは専用スレッドで行う）
                saveHiddenPlayers();

                player.sendMessage("§f放置ランキングにあなたを§a表示§fするようにしました");

                // エリア内にいるなら、その場でカウントを開始しボードを表示
                if (isInZone) {
                    currentSessionTimes.put(uuid, 0);
                    player.setScoreboard(afkScoreboard);
                }
            } else {
                // 非表示（除外）リストに追加 ＝ 除外モードにする
                hiddenPlayers.add(uuid);

                // config.yml へ保存する（書き込みは専用スレッドで行う）
                saveHiddenPlayers();

                // 自身のカウントデータを破棄（ランキングから消す）
                currentSessionTimes.remove(uuid);
                disconnectedSessionTimes.remove(uuid);
                disconnectTimes.remove(uuid);

                player.sendMessage("§f放置ランキングからあなたを§a非表示§fにしました");

                // 除外モードになってもエリア内にいるならスコアボードを表示したままにする
                if (isInZone) {
                    player.setScoreboard(afkScoreboard);
                } else {
                    player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 指定されたロケーションがいずれかの放置ゾーン内にあるかを判定するヘルパー
     */
    boolean isPlayerInAnyZone(Location loc) {
        for (ZoneArea zone : loadedZones.values()) {
            if (zone.isInArea(loc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * AxAFKZone の zones フォルダ内にある全 .yml から座標情報をパースして読み込む
     */
    public void reloadAxAFKZones() {
        loadedZones.clear();

        Plugin axPlugin = Bukkit.getPluginManager().getPlugin("AxAFKZone");
        if (axPlugin == null) {
            getLogger().warning("AxAFKZone がサーバーに導入されていないか、有効化されていません。");
            return;
        }

        File afkZoneFolder = new File(axPlugin.getDataFolder(), "zones");
        if (!afkZoneFolder.exists() || afkZoneFolder.listFiles() == null) {
            getLogger().warning("AxAFKZoneのzonesフォルダが見つかりません。");
            return;
        }

        for (File file : Objects.requireNonNull(afkZoneFolder.listFiles())) {
            if (!file.getName().endsWith(".yml")) continue;

            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

                String locStr1 = config.getString("zone.location1");
                String locStr2 = config.getString("zone.location2");

                if (locStr1 == null || locStr2 == null) continue;

                String[] split1 = locStr1.split(";");
                String[] split2 = locStr2.split(";");

                String world = split1[0];

                double x1 = Double.parseDouble(split1[1]);
                double y1 = Double.parseDouble(split1[2]);
                double z1 = Double.parseDouble(split1[3]);

                double x2 = Double.parseDouble(split2[1]);
                double y2 = Double.parseDouble(split2[2]);
                double z2 = Double.parseDouble(split2[3]);

                // 2つの座標から「最小(min)」と「最大(max)」を計算して立体範囲を登録
                ZoneArea area = new ZoneArea(
                        world,
                        Math.min(x1, x2), Math.max(x1, x2),
                        Math.min(y1, y2), Math.max(y1, y2),
                        Math.min(z1, z2), Math.max(z1, z2)
                );

                String zoneName = file.getName().replace(".yml", "");
                loadedZones.put(zoneName, area);
                getLogger().info("放置ゾーンを自動登録しました: " + zoneName);

            } catch (Exception e) {
                getLogger().severe("ゾーンファイルの解析に失敗しました(書式違いなど): " + file.getName());
            }
        }
    }

    /**
     * ランキングを計算してスコアボードを更新
     */
    private void updateLeaderboard() {
        for (String entry : afkScoreboard.getEntries()) {
            afkScoreboard.resetScores(entry);
        }

        // 現在放置中の上位10人を取得
        List<Map.Entry<UUID, Integer>> sortedTop10 = currentSessionTimes.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .limit(10)
                .toList();

        // 初期値の動的計算: ヘッダー2行 ＋ ランクインしている人数
        // 誰もおらず「誰も放置していません」の1行を表示する場合は「2行 + 1行 = 3」になります
        int scoreValue = 2 + (sortedTop10.isEmpty() ? 1 : sortedTop10.size());

        // ヘッダー部分の設定
        afkObjective.getScore("§7位 プレイヤー §b連続放置時間").setScore(scoreValue--);
        afkObjective.getScore("§8----------------------").setScore(scoreValue--);

        if (sortedTop10.isEmpty()) {
            afkObjective.getScore("§7 現在、誰も放置していません").setScore(scoreValue--);
            return;
        }

        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : sortedTop10) {
            UUID uuid = entry.getKey();
            Player player = Bukkit.getPlayer(uuid);

            if (player == null || !player.isOnline()) {
                continue;
            }

            String playerName = player.getName();
            int sessionSeconds = entry.getValue();

            String currentStr = formatTimeCompact(sessionSeconds);
            String scoreLine = String.format("§7#%d §f%s §b%s", rank, playerName, currentStr);

            afkObjective.getScore(scoreLine).setScore(scoreValue--);
            rank++;
        }
    }

    /**
     * 1秒ごとに、ゾーンにいるプレイヤーの時間（連続）を加算
     */
    private void incrementTimeEverySecond() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // afkhide ユーザーは累積カウント処理のみをスキップ（ボードの有無とは分離）
            if (hiddenPlayers.contains(player.getUniqueId())) {
                continue;
            }
            if (player.getScoreboard().equals(afkScoreboard)) {
                UUID uuid = player.getUniqueId();
                currentSessionTimes.put(uuid, currentSessionTimes.getOrDefault(uuid, 0) + 1);
            }
        }
    }

    /**
     * プレイヤーの移動イベントから、リアルタイムに放置ゾーンの出入りを監視・処理
     */
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // ブロックの整数値の境界線を越えて移動したときだけ判定（負荷対策）
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
                event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 現在いずれかの放置ゾーン内にいるかチェック
        boolean isNowInAnyZone = isPlayerInAnyZone(player.getLocation());

        // --- 進入と退出の処理ロジック ---
        if (isNowInAnyZone) {
            // 変更点：afkhide中かどうかにかかわらず、エリア内に入ったらスコアボードを表示
            if (!player.getScoreboard().equals(afkScoreboard)) {
                player.setScoreboard(afkScoreboard);
            }

            // 初めていずれかの放置エリアに足を踏み入れたプレイヤーへの通知
            if (!welcomedPlayers.contains(uuid)) {
                welcomedPlayers.add(uuid);
                // メッセージを送信
                player.sendMessage("§b/afkhide §fで放置ランキングから自分を表示/非表示できます");
                // 既読情報を config.yml へ保存（書き込みは専用スレッドで行う）
                saveWelcomedPlayers();
            }

            // カウント用マップへの新規登録処理（通常モードのプレイヤーのみ）
            if (!hiddenPlayers.contains(uuid) && !currentSessionTimes.containsKey(uuid)) {
                int previousTime = 0;

                // 回線落ち救済データが存在し、かつ5分以内であれば時間を復元
                if (disconnectTimes.containsKey(uuid)) {
                    long quitTime = disconnectTimes.remove(uuid);
                    int savedTime = disconnectedSessionTimes.remove(uuid);

                    if ((System.currentTimeMillis() - quitTime) <= RECOVERY_GRACE_PERIOD_MS) {
                        previousTime = savedTime;
                        player.sendMessage("§f回線落ちから5分以内に復帰したため、放置時間を引き継ぎました！");
                    }
                }
                currentSessionTimes.put(uuid, previousTime);
            }
        } else {
            // 変更点：エリア外に出たら、通常・afkhideモードに関係なく一律メインボードに戻す
            if (player.getScoreboard().equals(afkScoreboard)) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }

            // 内部カウント対象だった場合はデータをリセット
            if (currentSessionTimes.containsKey(uuid)) {
                currentSessionTimes.remove(uuid);
                disconnectedSessionTimes.remove(uuid);
                disconnectTimes.remove(uuid);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (currentSessionTimes.containsKey(uuid)) {
            disconnectedSessionTimes.put(uuid, currentSessionTimes.remove(uuid));
            disconnectTimes.put(uuid, System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (disconnectTimes.containsKey(uuid)) {
            long quitTime = disconnectTimes.get(uuid);
            if ((System.currentTimeMillis() - quitTime) > RECOVERY_GRACE_PERIOD_MS) {
                disconnectedSessionTimes.remove(uuid);
                disconnectTimes.remove(uuid);
            }
        }

        // 変更点：ログイン時にすでにエリア内にいる場合の対策
        Player player = event.getPlayer();
        if (isPlayerInAnyZone(player.getLocation())) {
            player.setScoreboard(afkScoreboard);
        }
    }

    /**
     * コンパクトな時間フォーマット
     */
    private String formatTimeCompact(int totalSeconds) {
        if (totalSeconds < 60) return totalSeconds + "s";

        int totalMinutes = totalSeconds / 60;
        if (totalMinutes < 60) return totalMinutes + "m";

        int totalHours = totalMinutes / 60;
        int minutes = totalMinutes % 60;

        if (totalHours < 24) {
            if (minutes == 0) return totalHours + "h";
            return totalHours + "h" + minutes + "m";
        }

        int days = totalHours / 24;
        int hours = totalHours % 24;

        if (hours == 0 && minutes == 0) return days + "d";
        if (minutes == 0) return days + "d" + hours + "h";
        if (hours == 0) return days + "d" + minutes + "m";

        return days + "d" + hours + "h" + minutes + "m";
    }

    /**
     * ゾーンの立体範囲を表現・判定する内部データクラス
     */
    private static class ZoneArea {
        private final String world;
        private final double minX, maxX;
        private final double minY, maxY;
        private final double minZ, maxZ;

        public ZoneArea(String world, double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
            this.world = world;
            this.minX = minX - 0.5; this.maxX = maxX + 0.5;
            this.minY = minY - 0.5; this.maxY = maxY + 0.5;
            this.minZ = minZ - 0.5; this.maxZ = maxZ + 0.5;
        }

        public boolean isInArea(Location loc) {
            if (loc.getWorld() == null) {
                return false;
            }
            return loc.getWorld().getName().equalsIgnoreCase(world) &&
                    loc.getX() >= minX && loc.getX() <= maxX &&
                    loc.getY() >= minY && loc.getY() <= maxY &&
                    loc.getZ() >= minZ && loc.getZ() <= maxZ;
        }
    }
}
