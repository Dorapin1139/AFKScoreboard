package space.gorogoro.afkscoreboard;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.HangingMoss;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Bee;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Frog;
import org.bukkit.entity.Goat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Panda;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.PolarBear;
import org.bukkit.entity.PufferFish;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Salmon;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Sniffer;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.ZombieNautilus;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreeperPowerEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * ゾーン内だけの見た目。パーティクルは間隔を空け、頭上のブロックと MOB は乗客なので毎 tick 動かさない。
 * エンダードラゴンは入れない。
 */
final class CosmeticService implements Listener {

    private final AFKScoreboard plugin;
    private final CosmeticStore store;
    private final NamespacedKey tagKey;
    private final NamespacedKey ownerKey;
    // 座っている間、足元のブロックを上げる量(腰の高さ)。床に直接座ると少し浮くかもしれないので実機で調整する
    private static final float SEATED_FLOOR_LIFT = 0.75f;
    // 名前を出す高さ(頭の上からのずらし)。バニラのネームタグとほぼ同じ位置。実機で調整する
    private static final float NAME_TAG_LIFT = 0.25f;

    private final Map<UUID, Active> active = new HashMap<>();
    private int weekClock;
    private boolean allowDismount;

    CosmeticService(AFKScoreboard plugin) {
        this.plugin = plugin;
        this.store = new CosmeticStore(plugin);
        this.tagKey = new NamespacedKey(plugin, "cosmetic");
        this.ownerKey = new NamespacedKey(plugin, "owner");
    }

    void load() {
        store.load();
    }

    void requestSave() {
        store.requestSave();
    }

    void shutdown() {
        removeAll();
        store.shutdown();
    }

    void removeStrayEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isOurs(entity)) {
                    entity.remove();
                }
            }
        }
    }

    void removeAll() {
        allowDismount = true;
        try {
            for (UUID uuid : new ArrayList<>(active.keySet())) {
                clear(Bukkit.getPlayer(uuid), uuid);
            }
            removeStrayEntities();
        } finally {
            allowDismount = false;
        }
    }

    /**
     * 1 秒に 1 回。ゾーン内の秒数を足し、見た目を合わせる。ゾーン外とログアウトでは消す。
     * /afkhide で非表示中の人は、秒数だけ数えて見た目は付けない。
     */
    void maintain() {
        if (++weekClock >= 60) {
            weekClock = 0;
            if (store.rolloverIfNeeded()) {
                removeAll();
            }
        }
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            if (plugin.isPlayerInAnyZone(player.getLocation())) {
                store.addSecond(player.getUniqueId());
                if (plugin.isHidden(player.getUniqueId())) {
                    clear(player);
                } else {
                    sync(player);
                }
            } else {
                clear(player);
            }
        }
        for (UUID uuid : new ArrayList<>(active.keySet())) {
            if (!online.contains(uuid)) {
                clear(null, uuid);
            }
        }
    }

    /**
     * デバッグ付与。放置秒数は増やさない。未抽選の枠だけその場で決める。
     * ゾーン内ならすぐに出し、ゾーン外では入ったときに出す。
     */
    DebugGrant debugGrant(Player player, boolean particle, boolean block, boolean mount) {
        CosmeticStore.Record record = store.record(player.getUniqueId());
        DebugGrant grant = new DebugGrant();
        boolean changed = false;
        if (particle) {
            grant.particleNew = CosmeticKinds.ParticleKind.parse(record.particle) == null;
            if (grant.particleNew) {
                record.particle = CosmeticKinds.ParticleKind.random().name();
                changed = true;
            }
            grant.particle = record.particle;
        }
        if (block) {
            grant.blockNew = CosmeticKinds.BlockKind.parse(record.block) == null;
            if (grant.blockNew) {
                record.block = CosmeticKinds.BlockKind.random().name();
                changed = true;
            }
            grant.block = record.block;
        }
        if (mount) {
            grant.mountNew = CosmeticKinds.MountKind.parse(record.mount) == null;
            if (grant.mountNew) {
                record.mount = CosmeticKinds.MountKind.random().name();
                record.mountVariant = null;
                changed = true;
            }
            grant.mount = record.mount;
        }
        if (changed) {
            store.markDirty();
            store.requestSave();
        }
        grant.inZone = plugin.isPlayerInAnyZone(player.getLocation());
        grant.hidden = plugin.isHidden(player.getUniqueId());
        if (grant.inZone && !grant.hidden) {
            sync(player);
            Active state = active.get(player.getUniqueId());
            if (state != null && state.particle != null) {
                spawnParticle(player, state.particle);
            }
        }
        return grant;
    }

    /**
     * /afklook reset。見た目をすべて外す。今週の放置秒数は残すので、条件を満たしている枠は次の 1 秒で引き直される。
     */
    boolean debugClear(Player player) {
        CosmeticStore.Record record = store.record(player.getUniqueId());
        boolean had = CosmeticKinds.ParticleKind.parse(record.particle) != null
                || CosmeticKinds.BlockKind.parse(record.block) != null
                || CosmeticKinds.MountKind.parse(record.mount) != null;
        record.particle = null;
        record.block = null;
        record.mount = null;
        record.mountVariant = null;
        if (had) {
            store.markDirty();
            store.requestSave();
        }
        clear(player);
        return had;
    }

    /**
     * /afklook。指定した種類の表示と非表示を切り替え、その場で見た目を合わせる。
     * 複数指定(all)のときは、1 つでも表示中なら全部を非表示に、全部非表示なら全部を表示にする。
     * @return 切り替え後に非表示なら true
     */
    boolean toggleLook(Player player, List<CosmeticStore.Slot> slots) {
        UUID uuid = player.getUniqueId();
        boolean anyOn = false;
        for (CosmeticStore.Slot slot : slots) {
            if (!store.isOff(uuid, slot)) {
                anyOn = true;
                break;
            }
        }
        for (CosmeticStore.Slot slot : slots) {
            store.setOff(uuid, slot, anyOn);
        }
        refresh(player);
        return anyOn;
    }

    /**
     * /afkhide・/afklook の切り替え直後に、次の 1 秒を待たずに見た目を合わせる。秒数は足さない。
     */
    void refresh(Player player) {
        if (plugin.isPlayerInAnyZone(player.getLocation()) && !plugin.isHidden(player.getUniqueId())) {
            sync(player);
        } else {
            clear(player);
        }
    }

    /**
     * 頭上の MOB の向き(左右・上下)をプレイヤーに合わせる。位置は動かさない。
     * PlayerMoveEvent から、向きが変わったときだけ呼ばれる。MOB がいない人は Map を引いて抜ける。
     */
    void syncRotation(Player player, Location to) {
        Active state = active.get(player.getUniqueId());
        if (state == null || state.rider == null || !state.rider.isValid()) {
            return;
        }
        state.rider.setRotation(to.getYaw(), to.getPitch());
    }

    /**
     * 3 tick に 1 回。何かに乗っている(GSit で座っているなど)人について、
     * 足元のブロック(花びら、キノコと枯れ木の地面側)を座面の高さに上げ下げし、頭上の MOB の向きを合わせる。
     * 乗っている間は PlayerMoveEvent が来ないため。見るのは足元ブロックか頭上 MOB がある人だけ。
     */
    void tickSeated() {
        for (Map.Entry<UUID, Active> entry : active.entrySet()) {
            Active state = entry.getValue();
            boolean hasFloor = !state.floorDisplays.isEmpty();
            boolean hasRider = state.rider != null && state.rider.isValid();
            if (!hasFloor && !hasRider) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            boolean seated = player.isInsideVehicle();
            // 座った・立ったときだけ高さを切り替える
            if (hasFloor && seated != state.floorLifted) {
                liftDisplays(state, seated ? SEATED_FLOOR_LIFT : -SEATED_FLOOR_LIFT);
                state.floorLifted = seated;
            }
            if (!hasRider || !seated) {
                continue;
            }
            Location look = player.getLocation();
            Location current = state.rider.getLocation();
            if (look.getYaw() == current.getYaw() && look.getPitch() == current.getPitch()) {
                continue;
            }
            state.rider.setRotation(look.getYaw(), look.getPitch());
        }
    }

    void tickParticles() {
        for (Map.Entry<UUID, Active> entry : active.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Active state = entry.getValue();
            if (player == null || !player.isOnline() || state.particle == null) {
                continue;
            }
            if (!plugin.isPlayerInAnyZone(player.getLocation())) {
                continue;
            }
            spawnParticle(player, state.particle);
        }
    }

    private void sync(Player player) {
        CosmeticStore.Record record = store.record(player.getUniqueId());
        if (unlockIfNeeded(record)) {
            store.markDirty();
            store.requestSave();
        }
        Active state = active.computeIfAbsent(player.getUniqueId(), ignored -> new Active());
        UUID uuid = player.getUniqueId();
        // /afklook で非表示にしている種類は、抽選結果は残したまま付けない
        CosmeticKinds.ParticleKind particle = store.isOff(uuid, CosmeticStore.Slot.PARTICLE)
                ? null : CosmeticKinds.ParticleKind.parse(record.particle);
        state.particle = particle;

        CosmeticKinds.BlockKind block = store.isOff(uuid, CosmeticStore.Slot.BLOCK)
                ? null : CosmeticKinds.BlockKind.parse(record.block);
        if (block == null) {
            removeDisplays(state);
            state.block = null;
        } else if (state.block != block || !hasLiveDisplay(state)) {
            removeDisplays(state);
            spawnDisplays(player, block, state);
            state.block = block;
        } else {
            keepMounted(player, state.displays);
        }

        CosmeticKinds.MountKind mount = store.isOff(uuid, CosmeticStore.Slot.MOUNT)
                ? null : CosmeticKinds.MountKind.parse(record.mount);
        if (mount == null) {
            removeRider(state);
            state.mount = null;
        } else if (state.mount != mount || state.rider == null || !state.rider.isValid() || !player.getPassengers().contains(state.rider)) {
            removeRider(state);
            state.rider = spawnMount(player, mount, record.mountVariant);
            state.mount = mount;
            if (state.rider != null) {
                String variant = readVariant(state.rider);
                if (variant != null && !variant.equals(record.mountVariant)) {
                    record.mountVariant = variant;
                    store.markDirty();
                    store.requestSave();
                }
            }
        }

        syncNameTag(player, state);
    }

    private boolean unlockIfNeeded(CosmeticStore.Record record) {
        boolean changed = false;
        if (record.seconds >= threshold("thresholds.particle-seconds", 1800)
                && CosmeticKinds.ParticleKind.parse(record.particle) == null) {
            record.particle = CosmeticKinds.ParticleKind.random().name();
            changed = true;
        }
        if (record.seconds >= threshold("thresholds.block-seconds", 3600)
                && CosmeticKinds.BlockKind.parse(record.block) == null) {
            record.block = CosmeticKinds.BlockKind.random().name();
            changed = true;
        }
        if (record.seconds >= threshold("thresholds.mount-seconds", 10800)
                && CosmeticKinds.MountKind.parse(record.mount) == null) {
            record.mount = CosmeticKinds.MountKind.random().name();
            record.mountVariant = null;
            changed = true;
        }
        return changed;
    }

    private int threshold(String path, int fallback) {
        int value = plugin.getConfig().getInt(path);
        return value > 0 ? value : fallback;
    }

    private void clear(Player player) {
        if (player == null) {
            return;
        }
        clear(player, player.getUniqueId());
    }

    private void clear(Player player, UUID uuid) {
        boolean outer = allowDismount;
        allowDismount = true;
        try {
            Active state = active.remove(uuid);
            if (state != null) {
                removeDisplays(state);
                removeRider(state);
                removeNameTag(state);
            }
            if (player != null) {
                stripPassengers(player);
            }
        } finally {
            allowDismount = outer;
        }
    }

    private void spawnParticle(Player player, CosmeticKinds.ParticleKind kind) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        switch (kind) {
            case SMOKE -> world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc.clone().add(0.0, 0.35, 0.0), 1, 0.12, 0.02, 0.12, 0.0);
            case SPORE -> world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, loc.clone().add(0.0, 1.1, 0.0), 2, 0.35, 0.35, 0.35, 0.0);
            case CHERRY -> world.spawnParticle(Particle.CHERRY_LEAVES, loc.clone().add(0.0, 2.0, 0.0), 1, 0.2, 0.08, 0.2, 0.0);
            case FIREFLY -> world.spawnParticle(Particle.FIREFLY, loc.clone().add(0.0, 1.05, 0.0), 1, 0.4, 0.3, 0.4, 0.0);
            case SNOW -> world.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0.0, 2.05, 0.0), 1, 0.22, 0.08, 0.22, 0.0);
        }
    }

    private void spawnDisplays(Player player, CosmeticKinds.BlockKind kind, Active state) {
        // 位置はブロックの中心ではなく角が基準
        Offset[] offsets = switch (kind) {
            case COBWEB -> new Offset[] {
                    new Offset(-0.25f, -0.15f, 0.05f, 0.42f),
                    new Offset(0.2f, 0.05f, -0.15f, 0.38f)
            };
            // 肩と背中、脇から葉が生えている。上端は目より下
            case AZALEA -> new Offset[] {
                    new Offset(-0.34f, -0.82f, -0.02f, 0.36f),
                    new Offset(0.06f, -0.78f, 0.12f, 0.32f),
                    new Offset(-0.12f, -0.98f, 0.20f, 0.34f),
                    new Offset(-0.30f, -1.22f, -0.10f, 0.28f),
                    new Offset(0.22f, -1.05f, -0.16f, 0.26f)
            };
            // 散らばり方はそのまま、全体の範囲(x -0.495〜0.495、z -0.515〜0.515)の中心をプレイヤーの真下にする
            case PETALS -> new Offset[] {
                    ground(-0.495f, -1.76f, -0.155f, 0.5f),
                    ground(0.045f, -1.76f, -0.515f, 0.45f),
                    ground(-0.155f, -1.76f, 0.065f, 0.45f)
            };
            // 肩・背中・脚に薄く生えた苔。カーペットは底面だけなので、角の高さが見た目の高さ
            case MOSS -> new Offset[] {
                    sheet(-0.22f, -0.50f, -0.06f, 0.40f, 1.0f, 0.34f, 0f, 0f, 0f),
                    sheet(0.02f, -0.52f, 0.04f, 0.34f, 1.0f, 0.30f, 0f, 0f, 0f),
                    sheet(-0.10f, -0.78f, 0.18f, 0.36f, 1.0f, 0.28f, 0f, 0f, 0f),
                    sheet(-0.08f, -1.28f, -0.05f, 0.32f, 1.0f, 0.28f, 0f, 0f, 0f),
                    sheet(0.12f, -1.18f, 0.08f, 0.28f, 1.0f, 0.24f, 0f, 12f, 0f)
            };
            // 肩と背中に盛り上がった苔の塊。厚みは低くして、貼り付いて生えたようにする
            case MOSS_BLOCK -> new Offset[] {
                    sheet(-0.30f, -0.66f, -0.04f, 0.26f, 0.14f, 0.22f, 0f, 0f, 0f),
                    sheet(0.08f, -0.60f, 0.02f, 0.22f, 0.12f, 0.20f, 0f, 0f, 0f),
                    sheet(-0.08f, -0.90f, 0.18f, 0.28f, 0.14f, 0.18f, 0f, 0f, 0f),
                    sheet(0.22f, -1.14f, -0.08f, 0.16f, 0.16f, 0.20f, 0f, 0f, 0f)
            };
            // 面だけの蔓。胴の横、背中、腹、肩、脚に沿わせる。目の正面は空けておく
            case VINE -> new Offset[] {
                    sheet(-0.42f, -1.35f, -0.18f, 0.08f, 0.85f, 0.40f, 0f, 0f, 0f, BlockFace.WEST, BlockFace.EAST),
                    sheet(0.32f, -1.25f, -0.05f, 0.07f, 0.70f, 0.32f, 0f, 0f, 0f, BlockFace.EAST, BlockFace.WEST),
                    sheet(-0.20f, -0.98f, 0.28f, 0.45f, 0.50f, 0.06f, 0f, 18f, 0f, BlockFace.SOUTH, BlockFace.NORTH),
                    sheet(-0.10f, -1.18f, -0.38f, 0.32f, 0.40f, 0.06f, 0f, -12f, 0f, BlockFace.NORTH, BlockFace.SOUTH),
                    sheet(-0.34f, -0.68f, 0.02f, 0.10f, 0.35f, 0.28f, 0f, 0f, 25f, BlockFace.WEST, BlockFace.EAST),
                    sheet(0.18f, -1.62f, 0.05f, 0.06f, 0.45f, 0.18f, 0f, 0f, 0f, BlockFace.EAST, BlockFace.WEST)
            };
            // ポーション材料の小さい茶キノコ。体からと、足元に複数本
            case BROWN_MUSHROOM -> new Offset[] {
                    new Offset(-0.30f, -0.82f, -0.02f, 0.32f),
                    new Offset(-0.06f, -0.96f, 0.18f, 0.34f),
                    new Offset(0.16f, -1.08f, -0.12f, 0.28f),
                    ground(-0.42f, -1.76f, -0.22f, 0.42f),
                    ground(0.08f, -1.76f, -0.48f, 0.38f),
                    ground(-0.18f, -1.76f, 0.12f, 0.46f),
                    ground(0.32f, -1.76f, 0.02f, 0.36f),
                    ground(-0.05f, -1.76f, -0.08f, 0.32f)
            };
            // ポーション材料の小さい赤キノコ。配置は茶とずらす
            case RED_MUSHROOM -> new Offset[] {
                    new Offset(0.08f, -0.76f, 0.04f, 0.30f),
                    new Offset(-0.22f, -0.90f, 0.16f, 0.32f),
                    new Offset(-0.28f, -1.14f, -0.08f, 0.28f),
                    ground(-0.28f, -1.76f, -0.40f, 0.40f),
                    ground(0.18f, -1.76f, -0.12f, 0.44f),
                    ground(-0.48f, -1.76f, 0.08f, 0.36f),
                    ground(0.05f, -1.76f, 0.28f, 0.42f),
                    ground(0.30f, -1.76f, 0.32f, 0.34f)
            };
            // 枯れ木。肩・背中・脇と、足元に数本。上端は目より下
            case DEAD_BUSH -> new Offset[] {
                    new Offset(-0.32f, -0.90f, -0.02f, 0.42f),
                    new Offset(-0.08f, -1.02f, 0.18f, 0.44f),
                    new Offset(0.16f, -1.16f, -0.08f, 0.36f),
                    ground(-0.36f, -1.76f, -0.16f, 0.48f),
                    ground(0.10f, -1.76f, -0.40f, 0.44f),
                    ground(-0.08f, -1.76f, 0.14f, 0.50f)
            };
            // ペールの垂れ苔。先端の十字を、胴の横・背中・肩・脚に垂らす
            case PALE_HANGING_MOSS -> new Offset[] {
                    sheet(-0.40f, -1.28f, -0.06f, 0.32f, 0.74f, 0.32f, 0f, 0f, 0f),
                    sheet(0.16f, -1.18f, 0.02f, 0.28f, 0.64f, 0.28f, 0f, 0f, 0f),
                    sheet(-0.12f, -1.08f, 0.20f, 0.36f, 0.58f, 0.28f, 0f, 0f, 0f),
                    sheet(-0.30f, -0.80f, 0.00f, 0.26f, 0.34f, 0.26f, 0f, 0f, 0f),
                    sheet(0.12f, -1.62f, 0.04f, 0.24f, 0.46f, 0.22f, 0f, 0f, 0f)
            };
            // ペールオークの葉。開花したツツジの葉と同じ位置
            case PALE_OAK_LEAVES -> new Offset[] {
                    new Offset(-0.34f, -0.82f, -0.02f, 0.36f),
                    new Offset(0.06f, -0.78f, 0.12f, 0.32f),
                    new Offset(-0.12f, -0.98f, 0.20f, 0.34f),
                    new Offset(-0.30f, -1.22f, -0.10f, 0.28f),
                    new Offset(0.22f, -1.05f, -0.16f, 0.26f)
            };
        };
        World world = player.getWorld();
        // 向きを 0 にして出す。プレイヤーの向きを引き継ぐと、ずらし(translation)ごと回転して足元から外れる
        Location spawnAt = player.getLocation();
        spawnAt.setYaw(0.0f);
        spawnAt.setPitch(0.0f);
        for (Offset offset : offsets) {
            BlockDisplay display = world.spawn(spawnAt, BlockDisplay.class, entity -> {
                tag(entity, player.getUniqueId());
                entity.setBlock(blockData(kind, offset.faces()));
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setViewRange(32.0f);
                entity.setShadowRadius(0.0f);
                entity.setShadowStrength(0.0f);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setInterpolationDuration(0);
                Quaternionf rotation = new Quaternionf()
                        .rotateX((float) Math.toRadians(offset.pitch()))
                        .rotateY((float) Math.toRadians(offset.yaw()))
                        .rotateZ((float) Math.toRadians(offset.roll()));
                entity.setTransformation(new Transformation(
                        new Vector3f(offset.x(), offset.y(), offset.z()),
                        rotation,
                        new Vector3f(offset.sx(), offset.sy(), offset.sz()),
                        new Quaternionf()));
            });
            player.addPassenger(display);
            state.displays.add(display);
            if (offset.floor()) {
                state.floorDisplays.add(display);
            }
        }
    }

    private static Offset ground(float x, float y, float z, float scale) {
        return new Offset(x, y, z, scale, scale, scale, 0f, 0f, 0f, null, true);
    }

    private static Offset sheet(float x, float y, float z, float sx, float sy, float sz,
            float pitch, float yaw, float roll, BlockFace... faces) {
        BlockFace[] stored = faces == null || faces.length == 0 ? null : faces;
        return new Offset(x, y, z, sx, sy, sz, pitch, yaw, roll, stored, false);
    }

    private static BlockData blockData(CosmeticKinds.BlockKind kind, BlockFace[] faces) {
        BlockData data = kind.material.createBlockData();
        if (faces != null && data instanceof MultipleFacing facing) {
            for (BlockFace face : faces) {
                facing.setFace(face, true);
            }
        }
        if (data instanceof HangingMoss moss) {
            // 途中の節ではなく先端の房。垂れた見た目になる
            moss.setTip(true);
        }
        return data;
    }

    private LivingEntity spawnMount(Player player, CosmeticKinds.MountKind kind, String variant) {
        Entity spawned = player.getWorld().spawnEntity(
                player.getLocation(),
                kind.entityType,
                CreatureSpawnEvent.SpawnReason.CUSTOM,
                entity -> prepareMount(entity, player, kind, variant));
        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            return null;
        }
        living.setSilent(true);
        player.addPassenger(living);
        return living;
    }

    private void prepareMount(Entity entity, Player player, CosmeticKinds.MountKind kind, String variant) {
        tag(entity, player.getUniqueId());
        entity.setPersistent(false);
        entity.setSilent(true);
        entity.setGravity(false);
        entity.setInvulnerable(true);
        // 頭上の MOB でプレイヤーのネームタグが隠れるので、MOB の上に名前(色付き)を出す
        entity.customName(player.displayName());
        entity.setCustomNameVisible(true);
        if (entity instanceof LivingEntity living) {
            living.setCollidable(false);
            living.setRemoveWhenFarAway(false);
            living.setCanPickupItems(false);
            living.setAI(false);
            living.setSilent(true);
        }
        if (entity instanceof Mob mob) {
            mob.setAware(false);
            mob.setSilent(true);
        }
        if (entity instanceof Sittable sittable) {
            sittable.setSitting(true);
        }
        if (entity instanceof Ageable ageable && kind.baby()) {
            ageable.setBaby();
            ageable.setAgeLock(true);
        }
        if (entity instanceof PufferFish puffer) {
            // 0 が通常、1 が半膨張、2 が最大。半膨張は別枠で、サイズはそのまま
            puffer.setPuffState(kind == CosmeticKinds.MountKind.PUFFERFISH_HALF ? 1 : 2);
        }
        if (entity instanceof Slime slime) {
            // サイズ 2 を縮めて、頭に乗るくらいの中くらいにする
            slime.setSize(2);
        }
        if (entity instanceof Pig pig) {
            pig.setSaddle(false);
        }
        if (entity instanceof Creeper creeper) {
            // 右クリックとダメージは別で止めている。半径 0 と着火解除で、漏れても壊さない
            creeper.setPowered(false);
            creeper.setIgnited(false);
            creeper.setExplosionRadius(0);
        }
        if (entity instanceof LivingEntity livingScaled) {
            double scale = kind.mountedScale();
            if (scale != 1.0) {
                AttributeInstance attribute = livingScaled.getAttribute(Attribute.SCALE);
                if (attribute != null) {
                    attribute.setBaseValue(scale);
                }
            }
        }
        if (entity instanceof Sniffer sniffer) {
            sniffer.setState(Sniffer.State.IDLING);
        }
        if (entity instanceof Wolf wolf) {
            wolf.setAngry(false);
        }
        if (entity instanceof Camel camel) {
            camel.setDashing(false);
        }
        if (entity instanceof Bee bee) {
            bee.setAnger(0);
            bee.setHasStung(false);
        }
        if (entity instanceof Chicken chicken) {
            chicken.setEggLayTime(Integer.MAX_VALUE);
        }
        if (entity instanceof Fox fox) {
            fox.setSleeping(false);
            fox.setDefending(false);
        }
        if (entity instanceof Villager villager) {
            villager.setProfession(Villager.Profession.NONE);
            villager.setVillagerExperience(0);
            villager.setAware(false);
        }
        if (entity instanceof PolarBear bear) {
            bear.setAI(false);
        }
        applyVariant(entity, kind, variant);
    }

    private void applyVariant(Entity entity, CosmeticKinds.MountKind kind, String stored) {
        switch (kind) {
            case CAT -> applyRegistry(Cat.Type.class, stored, type -> {
                if (entity instanceof Cat cat) {
                    cat.setCatType(type);
                }
            });
            case FROG -> applyRegistry(Frog.Variant.class, stored, variant -> {
                if (entity instanceof Frog frog) {
                    frog.setVariant(variant);
                }
            });
            case FOX -> {
                if (entity instanceof Fox fox) {
                    fox.setFoxType(pickEnum(Fox.Type.class, stored));
                }
            }
            case RABBIT -> {
                if (entity instanceof Rabbit rabbit) {
                    rabbit.setRabbitType(pickEnum(Rabbit.Type.class, stored));
                }
            }
            case PARROT -> {
                if (entity instanceof Parrot parrot) {
                    parrot.setVariant(pickEnum(Parrot.Variant.class, stored));
                }
            }
            case CHICKEN -> applyRegistry(Chicken.Variant.class, stored, variant -> {
                if (entity instanceof Chicken chicken) {
                    chicken.setVariant(variant);
                }
            });
            case COW -> applyRegistry(Cow.Variant.class, stored, variant -> {
                if (entity instanceof Cow cow) {
                    cow.setVariant(variant);
                }
            });
            case VILLAGER -> applyRegistry(Villager.Type.class, stored, type -> {
                if (entity instanceof Villager villager) {
                    villager.setVillagerType(type);
                }
            });
            case WOLF -> applyRegistry(Wolf.Variant.class, stored, variant -> {
                if (entity instanceof Wolf wolf) {
                    wolf.setVariant(variant);
                }
            });
            case AXOLOTL -> {
                if (entity instanceof Axolotl axolotl) {
                    axolotl.setVariant(pickEnum(Axolotl.Variant.class, stored));
                }
            }
            case SALMON -> {
                if (entity instanceof Salmon salmon) {
                    salmon.setVariant(pickEnum(Salmon.Variant.class, stored));
                }
            }
            case MOOSHROOM -> {
                if (entity instanceof MushroomCow cow) {
                    cow.setVariant(pickEnum(MushroomCow.Variant.class, stored));
                }
            }
            case ZOMBIE_NAUTILUS -> applyRegistry(ZombieNautilus.Variant.class, stored, variant -> {
                if (entity instanceof ZombieNautilus nautilus) {
                    nautilus.setVariant(variant);
                }
            });
            case PIG -> applyRegistry(Pig.Variant.class, stored, variant -> {
                if (entity instanceof Pig pig) {
                    pig.setVariant(variant);
                }
            });
            case SHEEP -> {
                if (entity instanceof Sheep sheep) {
                    sheep.setColor(pickEnum(DyeColor.class, stored));
                    sheep.setSheared(false);
                }
            }
            case GOAT -> {
                if (entity instanceof Goat goat) {
                    boolean screaming = stored == null || stored.isBlank()
                            ? ThreadLocalRandom.current().nextBoolean()
                            : "SCREAMING".equalsIgnoreCase(stored.trim());
                    goat.setScreaming(screaming);
                }
            }
            case PANDA -> {
                if (entity instanceof Panda panda) {
                    String mainRaw = stored;
                    String hiddenRaw = null;
                    if (stored != null && stored.contains("/")) {
                        String[] parts = stored.split("/", 2);
                        mainRaw = parts[0];
                        hiddenRaw = parts[1];
                    }
                    panda.setMainGene(pickEnum(Panda.Gene.class, mainRaw));
                    panda.setHiddenGene(pickEnum(Panda.Gene.class, hiddenRaw));
                    panda.setRolling(false);
                    panda.setOnBack(false);
                }
            }
            case PUFFERFISH, PUFFERFISH_HALF, BEE, POLAR_BEAR, TURTLE, COD, SQUID, GLOW_SQUID, ARMADILLO, NAUTILUS, SNIFFER, CAMEL, SLIME, CREEPER -> {
            }
        }
    }

    private String readVariant(LivingEntity entity) {
        if (entity instanceof Cat cat) {
            return keyOf(cat.getCatType());
        }
        if (entity instanceof Frog frog) {
            return keyOf(frog.getVariant());
        }
        if (entity instanceof Fox fox && fox.getFoxType() != null) {
            return fox.getFoxType().name();
        }
        if (entity instanceof Rabbit rabbit && rabbit.getRabbitType() != null) {
            return rabbit.getRabbitType().name();
        }
        if (entity instanceof Parrot parrot && parrot.getVariant() != null) {
            return parrot.getVariant().name();
        }
        if (entity instanceof Chicken chicken) {
            return keyOf(chicken.getVariant());
        }
        if (entity instanceof Cow cow) {
            return keyOf(cow.getVariant());
        }
        if (entity instanceof Villager villager) {
            return keyOf(villager.getVillagerType());
        }
        if (entity instanceof PufferFish puffer) {
            return Integer.toString(puffer.getPuffState());
        }
        if (entity instanceof Wolf wolf) {
            return keyOf(wolf.getVariant());
        }
        if (entity instanceof Axolotl axolotl && axolotl.getVariant() != null) {
            return axolotl.getVariant().name();
        }
        if (entity instanceof Salmon salmon && salmon.getVariant() != null) {
            return salmon.getVariant().name();
        }
        if (entity instanceof MushroomCow cow && cow.getVariant() != null) {
            return cow.getVariant().name();
        }
        if (entity instanceof ZombieNautilus nautilus) {
            return keyOf(nautilus.getVariant());
        }
        if (entity instanceof Goat goat) {
            return goat.isScreaming() ? "SCREAMING" : "NORMAL";
        }
        if (entity instanceof Pig pig) {
            return keyOf(pig.getVariant());
        }
        if (entity instanceof Sheep sheep && sheep.getColor() != null) {
            return sheep.getColor().name();
        }
        if (entity instanceof Panda panda && panda.getMainGene() != null && panda.getHiddenGene() != null) {
            return panda.getMainGene().name() + "/" + panda.getHiddenGene().name();
        }
        if (entity instanceof Slime slime) {
            return Integer.toString(slime.getSize());
        }
        return null;
    }

    private <T extends Enum<T>> T pickEnum(Class<T> type, String stored) {
        T[] values = type.getEnumConstants();
        if (stored != null && !stored.isBlank() && !"THE_KILLER_BUNNY".equalsIgnoreCase(stored.trim())) {
            try {
                return Enum.valueOf(type, stored.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 保存値が壊れていれば引き直す
            }
        }
        List<T> options = new ArrayList<>();
        for (T value : values) {
            if (!"THE_KILLER_BUNNY".equals(value.name())) {
                options.add(value);
            }
        }
        if (options.isEmpty()) {
            return values[0];
        }
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }

    private <T extends Keyed> void applyRegistry(Class<T> type, String stored, Consumer<T> setter) {
        Registry<T> registry = Bukkit.getRegistry(type);
        if (registry == null) {
            return;
        }
        if (stored != null && !stored.isBlank()) {
            T value = registry.get(NamespacedKey.minecraft(stored));
            if (value != null) {
                setter.accept(value);
                return;
            }
        }
        List<T> all = new ArrayList<>();
        for (T value : registry) {
            all.add(value);
        }
        if (!all.isEmpty()) {
            setter.accept(all.get(ThreadLocalRandom.current().nextInt(all.size())));
        }
    }

    private static String keyOf(Keyed keyed) {
        if (keyed == null || keyed.getKey() == null) {
            return null;
        }
        return keyed.getKey().getKey();
    }

    private boolean hasLiveDisplay(Active state) {
        if (state.displays.isEmpty()) {
            return false;
        }
        for (BlockDisplay display : state.displays) {
            if (display == null || !display.isValid()) {
                return false;
            }
        }
        return true;
    }

    private void keepMounted(Player player, List<BlockDisplay> displays) {
        for (BlockDisplay display : displays) {
            if (display == null || !display.isValid() || player.getPassengers().contains(display)) {
                continue;
            }
            player.addPassenger(display);
        }
    }

    private void removeDisplays(Active state) {
        // 自分で消すときは降車を止めない。止めると消えたまま乗り続け、毎 tick 降車処理が走る
        boolean outer = allowDismount;
        allowDismount = true;
        try {
            for (BlockDisplay display : state.displays) {
                if (display != null && display.isValid()) {
                    display.remove();
                }
            }
        } finally {
            allowDismount = outer;
        }
        state.displays.clear();
        state.floorDisplays.clear();
        // 出し直すと元の高さに戻るので、座っていれば次の 3 tick で上げ直す
        state.floorLifted = false;
    }

    private void liftDisplays(Active state, float dy) {
        for (BlockDisplay display : state.floorDisplays) {
            if (display == null || !display.isValid()) {
                continue;
            }
            Transformation current = display.getTransformation();
            Vector3f translation = new Vector3f(current.getTranslation()).add(0.0f, dy, 0.0f);
            display.setTransformation(new Transformation(
                    translation, current.getLeftRotation(), current.getScale(), current.getRightRotation()));
        }
    }

    private void removeRider(Active state) {
        // 自分で消すときは降車を止めない(removeDisplays と同じ理由)
        boolean outer = allowDismount;
        allowDismount = true;
        try {
            if (state.rider != null && state.rider.isValid()) {
                state.rider.remove();
            }
        } finally {
            allowDismount = outer;
        }
        state.rider = null;
    }

    /**
     * 何かが乗っているプレイヤーのネームタグは、クライアントが表示しない。
     * ブロックだけが乗っている間は、代わりに名前の TextDisplay を頭の上に乗せる(頭上 MOB がいるときは MOB の名前で足りる)。
     */
    private void syncNameTag(Player player, Active state) {
        boolean needed = !state.displays.isEmpty() && state.rider == null;
        if (!needed) {
            removeNameTag(state);
            return;
        }
        if (state.nameTag != null && state.nameTag.isValid()) {
            if (!player.getPassengers().contains(state.nameTag)) {
                player.addPassenger(state.nameTag);
            }
            return;
        }
        removeNameTag(state);
        Location spawnAt = player.getLocation();
        spawnAt.setYaw(0.0f);
        spawnAt.setPitch(0.0f);
        TextDisplay nameTag = player.getWorld().spawn(spawnAt, TextDisplay.class, entity -> {
            tag(entity, player.getUniqueId());
            entity.text(player.displayName());
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            // バニラのネームタグと同じく、常にこちらを向き、64 ブロックまで見える
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setViewRange(1.0f);
            entity.setShadowRadius(0.0f);
            entity.setShadowStrength(0.0f);
            entity.setInterpolationDuration(0);
            entity.setTransformation(new Transformation(
                    new Vector3f(0.0f, NAME_TAG_LIFT, 0.0f),
                    new Quaternionf(),
                    new Vector3f(1.0f, 1.0f, 1.0f),
                    new Quaternionf()));
        });
        // バニラと同じく、自分の名前は自分には見せない
        player.hideEntity(plugin, nameTag);
        player.addPassenger(nameTag);
        state.nameTag = nameTag;
    }

    private void removeNameTag(Active state) {
        // 自分で消すときは降車を止めない(removeDisplays と同じ理由)
        boolean outer = allowDismount;
        allowDismount = true;
        try {
            if (state.nameTag != null && state.nameTag.isValid()) {
                state.nameTag.remove();
            }
        } finally {
            allowDismount = outer;
        }
        state.nameTag = null;
    }

    private void stripPassengers(Player player) {
        for (Entity passenger : new ArrayList<>(player.getPassengers())) {
            if (isOurs(passenger)) {
                passenger.remove();
            }
        }
    }

    private void tag(Entity entity, UUID owner) {
        entity.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
    }

    private boolean isOurs(Entity entity) {
        Byte mark = entity.getPersistentDataContainer().get(tagKey, PersistentDataType.BYTE);
        return mark != null && mark == (byte) 1;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (isOurs(event.getEntity()) || (event.getTarget() != null && isOurs(event.getTarget()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (isOurs(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEntityEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (event.getEntity() != null && isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreeperPower(CreeperPowerEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRide(EntityMountEvent event) {
        // ラクダやオウムガイに他の人が乗るのは止める。自分の頭へ乗せるときは乗り物がプレイヤーなのでここには来ない
        if (isOurs(event.getMount())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        // 安い判定を先に行い、PDC の読み取り(isOurs)は最後にする
        if (allowDismount || !(event.getDismounted() instanceof Player player)) {
            return;
        }
        // remove 済みの乗客は必ず降ろす。止めると消えたまま乗り続け、毎 tick 降車処理が走る
        if (!event.getEntity().isValid()) {
            return;
        }
        if (isOurs(event.getEntity()) && plugin.isPlayerInAnyZone(player.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        clear(event.getEntity());
    }

    private static final class Active {
        private CosmeticKinds.ParticleKind particle;
        private CosmeticKinds.BlockKind block;
        private CosmeticKinds.MountKind mount;
        private final List<BlockDisplay> displays = new ArrayList<>();
        // 座っている間だけ上げる足元側。体から生えている分は含めない
        private final List<BlockDisplay> floorDisplays = new ArrayList<>();
        private LivingEntity rider;
        // ブロックだけが乗っている間に出す、ネームタグの代わりの名前
        private TextDisplay nameTag;
        // 足元のブロックを座面の高さに上げているか
        private boolean floorLifted;
    }

    private record Offset(float x, float y, float z, float sx, float sy, float sz,
            float pitch, float yaw, float roll, BlockFace[] faces, boolean floor) {
        private Offset(float x, float y, float z, float scale) {
            this(x, y, z, scale, scale, scale, 0f, 0f, 0f, null, false);
        }
    }

    static final class DebugGrant {
        String particle;
        String block;
        String mount;
        boolean particleNew;
        boolean blockNew;
        boolean mountNew;
        boolean inZone;
        boolean hidden;
    }
}
