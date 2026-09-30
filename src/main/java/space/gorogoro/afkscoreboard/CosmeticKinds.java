package space.gorogoro.afkscoreboard;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

final class CosmeticKinds {

    private CosmeticKinds() {
    }

    enum ParticleKind {
        SMOKE(Particle.CAMPFIRE_COSY_SMOKE),
        SPORE(Particle.SPORE_BLOSSOM_AIR),
        CHERRY(Particle.CHERRY_LEAVES),
        FIREFLY(Particle.FIREFLY),
        SNOW(Particle.SNOWFLAKE);

        final Particle particle;

        ParticleKind(Particle particle) {
            this.particle = particle;
        }

        static ParticleKind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return ParticleKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        static ParticleKind random() {
            ParticleKind[] values = values();
            return values[ThreadLocalRandom.current().nextInt(values.length)];
        }
    }

    enum BlockKind {
        COBWEB(Material.COBWEB),
        AZALEA(Material.FLOWERING_AZALEA_LEAVES),
        PETALS(Material.PINK_PETALS),
        MOSS(Material.MOSS_CARPET),
        MOSS_BLOCK(Material.MOSS_BLOCK),
        VINE(Material.VINE),
        BROWN_MUSHROOM(Material.BROWN_MUSHROOM),
        RED_MUSHROOM(Material.RED_MUSHROOM),
        DEAD_BUSH(Material.DEAD_BUSH),
        PALE_HANGING_MOSS(Material.PALE_HANGING_MOSS),
        PALE_OAK_LEAVES(Material.PALE_OAK_LEAVES);

        final Material material;

        BlockKind(Material material) {
            this.material = material;
        }

        static BlockKind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return BlockKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        static BlockKind random() {
            BlockKind[] values = values();
            return values[ThreadLocalRandom.current().nextInt(values.length)];
        }
    }

    enum MountKind {
        CAT(EntityType.CAT),
        FROG(EntityType.FROG),
        PUFFERFISH(EntityType.PUFFERFISH),
        PUFFERFISH_HALF(EntityType.PUFFERFISH),
        CHICKEN(EntityType.CHICKEN),
        RABBIT(EntityType.RABBIT),
        FOX(EntityType.FOX),
        BEE(EntityType.BEE),
        PARROT(EntityType.PARROT),
        COW(EntityType.COW),
        POLAR_BEAR(EntityType.POLAR_BEAR),
        VILLAGER(EntityType.VILLAGER),
        WOLF(EntityType.WOLF),
        TURTLE(EntityType.TURTLE),
        AXOLOTL(EntityType.AXOLOTL),
        COD(EntityType.COD),
        SALMON(EntityType.SALMON),
        MOOSHROOM(EntityType.MOOSHROOM),
        SQUID(EntityType.SQUID),
        GLOW_SQUID(EntityType.GLOW_SQUID),
        ARMADILLO(EntityType.ARMADILLO),
        NAUTILUS(EntityType.NAUTILUS),
        ZOMBIE_NAUTILUS(EntityType.ZOMBIE_NAUTILUS),
        SNIFFER(EntityType.SNIFFER),
        CAMEL(EntityType.CAMEL),
        GOAT(EntityType.GOAT),
        PANDA(EntityType.PANDA),
        SLIME(EntityType.SLIME);

        final EntityType entityType;

        MountKind(EntityType entityType) {
            this.entityType = entityType;
        }

        boolean baby() {
            return this == FOX || this == COW || this == POLAR_BEAR || this == VILLAGER
                    || this == MOOSHROOM || this == GOAT;
        }

        /** 頭に乗せたとき大きすぎるものだけ、バニラの scale 属性で縮める。1.0 はそのまま。 */
        double mountedScale() {
            return switch (this) {
                case SNIFFER, CAMEL -> 0.35;
                case PANDA -> 0.45;
                // 甲羅の幅 1.2 を、頭の幅くらい（約 0.6）まで
                case TURTLE -> 0.5;
                case SLIME -> 0.55;
                // 高さ 0.95・幅 0.875 を、約 0.6 まで。ゾンビも同じ
                case NAUTILUS, ZOMBIE_NAUTILUS -> 0.65;
                // 当たり 0.8 に触手が足るので、胴が頭に収まるくらい
                case SQUID, GLOW_SQUID -> 0.7;
                // 座っても胴が長い。高さ 0.85 を約 0.64 まで
                case WOLF -> 0.75;
                // 最大膨張はトゲ込みで頭より大きいので少しだけ縮める。半膨張は 1.0
                case PUFFERFISH -> 0.8;
                default -> 1.0;
            };
        }

        static MountKind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return MountKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        static MountKind random() {
            List<MountKind> pool = new ArrayList<>(List.of(values()));
            return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        }
    }
}
