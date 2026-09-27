package space.gorogoro.afkscoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Bee;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Frog;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.entity.PolarBear;
import org.bukkit.entity.PufferFish;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
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
                sync(player);
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
        if (grant.inZone) {
            sync(player);
            Active state = active.get(player.getUniqueId());
            if (state != null && state.particle != null) {
                spawnParticle(player, state.particle);
            }
        }
        return grant;
    }

    /**
     * デバッグ付与を外す。今週の放置秒数は残す。
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
        CosmeticKinds.ParticleKind particle = CosmeticKinds.ParticleKind.parse(record.particle);
        state.particle = particle;

        CosmeticKinds.BlockKind block = CosmeticKinds.BlockKind.parse(record.block);
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

        CosmeticKinds.MountKind mount = CosmeticKinds.MountKind.parse(record.mount);
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
        Offset[] offsets = switch (kind) {
            case MOSS -> new Offset[] {
                    new Offset(-0.35f, -1.78f, -0.35f, 0.55f),
                    new Offset(0.15f, -1.78f, -0.35f, 0.55f),
                    new Offset(-0.35f, -1.78f, 0.15f, 0.55f),
                    new Offset(0.15f, -1.78f, 0.15f, 0.55f)
            };
            case COBWEB -> new Offset[] {
                    new Offset(-0.25f, -0.15f, 0.05f, 0.42f),
                    new Offset(0.2f, 0.05f, -0.15f, 0.38f)
            };
            case AZALEA -> new Offset[] {
                    new Offset(-0.45f, -1.15f, 0.05f, 0.48f),
                    new Offset(0.4f, -1.05f, -0.1f, 0.48f)
            };
            case PETALS -> new Offset[] {
                    new Offset(-0.32f, -1.76f, 0.08f, 0.5f),
                    new Offset(0.22f, -1.76f, -0.28f, 0.45f),
                    new Offset(0.02f, -1.76f, 0.3f, 0.45f)
            };
            case LITTER -> new Offset[] {
                    new Offset(-0.28f, -1.76f, -0.22f, 0.52f),
                    new Offset(0.24f, -1.76f, 0.18f, 0.48f),
                    new Offset(-0.08f, -1.76f, 0.32f, 0.42f)
            };
        };
        World world = player.getWorld();
        for (Offset offset : offsets) {
            BlockDisplay display = world.spawn(player.getLocation(), BlockDisplay.class, entity -> {
                tag(entity, player.getUniqueId());
                entity.setBlock(kind.material.createBlockData());
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setViewRange(32.0f);
                entity.setShadowRadius(0.0f);
                entity.setShadowStrength(0.0f);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setInterpolationDuration(0);
                entity.setTransformation(new Transformation(
                        new Vector3f(offset.x, offset.y, offset.z),
                        new Quaternionf(),
                        new Vector3f(offset.scale, offset.scale, offset.scale),
                        new Quaternionf()));
            });
            player.addPassenger(display);
            state.displays.add(display);
        }
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
        entity.setCustomNameVisible(false);
        entity.customName(null);
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
            puffer.setPuffState(1);
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
            case PUFFERFISH, BEE, POLAR_BEAR -> {
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
        for (BlockDisplay display : state.displays) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        state.displays.clear();
    }

    private void removeRider(Active state) {
        if (state.rider != null && state.rider.isValid()) {
            state.rider.remove();
        }
        state.rider = null;
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
    public void onDismount(EntityDismountEvent event) {
        if (allowDismount || !isOurs(event.getEntity())) {
            return;
        }
        if (event.getDismounted() instanceof Player player && plugin.isPlayerInAnyZone(player.getLocation())) {
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
        private LivingEntity rider;
    }

    private record Offset(float x, float y, float z, float scale) {
    }

    static final class DebugGrant {
        String particle;
        String block;
        String mount;
        boolean particleNew;
        boolean blockNew;
        boolean mountNew;
        boolean inZone;
    }
}
