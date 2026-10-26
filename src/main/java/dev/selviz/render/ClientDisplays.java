package dev.selviz.render;

import dev.selviz.Settings;
import dev.selviz.geometry.Line;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.entity.EntityTypes;
import org.spongepowered.api.entity.display.BlockDisplay;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.util.Transform;
import org.spongepowered.math.imaginary.Quaterniond;
import org.spongepowered.math.vector.Vector3d;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

/**
 * Клиентские (пакетные) block_display-сущности: существуют только у одного игрока.
 *
 * <p>Сущность настраивается обычным Sponge API ({@code ServerWorld#createEntity}),
 * но в мир не добавляется: из неё берутся id, UUID и синхронизируемые данные,
 * и всё это отправляется пакетами одному игроку. Остальные игроки ничего не видят,
 * на сервере ничего не тикает и не сохраняется.</p>
 *
 * <p>Единственное место в плагине, где используются классы Minecraft (Mojang-имена).
 * Sponge в рантайме работает на Mojang-маппингах, сущности и игроки Sponge — это
 * сами объекты Minecraft, поэтому достаточно приведения типов.</p>
 */
public final class ClientDisplays {

    /** Клиент принимает не больше 4096 пакетов в одной связке. */
    private static final int MAX_BUNDLE_SIZE = 4096;
    private static final double EPS = 1e-6;

    /**
     * Поле цвета подсветки. В Sponge API 12 для него нет ключа, а сеттер в Minecraft
     * приватный, поэтому берём само поле через рефлексию. Если его не нашлось
     * (другая версия игры), подсветка просто останется белой.
     */
    private static final EntityDataAccessor<Integer> GLOW_COLOR = findGlowColorAccessor();

    private ClientDisplays() {
    }

    /**
     * Добавляет в {@code out} пакеты, создающие у игрока брусок вдоль отрезка.
     *
     * @return id сущности на клиенте или {@code -1}, если отрезок вырожденный
     */
    public static int spawn(final ServerPlayer player, final Line line, final LineLook look, final Settings settings,
                            final List<Packet<? super ClientGamePacketListener>> out) {
        final double dx = line.x2() - line.x1();
        final double dy = line.y2() - line.y1();
        final double dz = line.z2() - line.z1();
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < EPS) {
            return -1;
        }

        final BlockDisplay display = player.world().createEntity(EntityTypes.BLOCK_DISPLAY,
            new Vector3d(line.x1(), line.y1(), line.z1()));
        display.offer(Keys.BLOCK_STATE, look.block());
        display.offer(Keys.TRANSFORM, transform(dx / length, dy / length, dz / length, length, settings.lineThickness));
        display.offer(Keys.VIEW_RANGE, settings.viewRange);
        if (settings.fullBright) {
            // Первый же ключ освещения выставляет оба канала: незаданный становится 15.
            display.offer(Keys.BLOCK_LIGHT, 15);
            display.offer(Keys.SKY_LIGHT, 15);
        }

        final Entity entity = (Entity) display;
        if (settings.glow) {
            display.offer(Keys.IS_GLOWING, true);
            if (GLOW_COLOR != null && look.glowColor() >= 0) {
                entity.getEntityData().set(GLOW_COLOR, look.glowColor());
            }
        }

        out.add(new ClientboundAddEntityPacket(
            entity.getId(), entity.getUUID(),
            line.x1(), line.y1(), line.z1(),
            0f, 0f,
            entity.getType(), 0, Vec3.ZERO, 0.0));
        final List<SynchedEntityData.DataValue<?>> data = entity.getEntityData().getNonDefaultValues();
        if (data != null) {
            out.add(new ClientboundSetEntityDataPacket(entity.getId(), data));
        }
        return entity.getId();
    }

    /** Добавляет в {@code out} пакет, убирающий у игрока сущности с этими id. */
    public static void remove(final Collection<Integer> ids, final List<Packet<? super ClientGamePacketListener>> out) {
        if (!ids.isEmpty()) {
            out.add(new ClientboundRemoveEntitiesPacket(ids.stream().mapToInt(Integer::intValue).toArray()));
        }
    }

    /**
     * Отправляет пакеты игроку связками (bundle): клиент применяет связку за один кадр,
     * а сервер не сбрасывает сокет на каждый пакет.
     */
    public static void send(final ServerPlayer player, final List<Packet<? super ClientGamePacketListener>> packets) {
        if (packets.isEmpty()) {
            return;
        }
        final var connection = ((net.minecraft.server.level.ServerPlayer) player).connection;
        if (packets.size() == 1) {
            connection.send(packets.get(0));
            return;
        }
        for (int from = 0; from < packets.size(); from += MAX_BUNDLE_SIZE) {
            final int to = Math.min(packets.size(), from + MAX_BUNDLE_SIZE);
            connection.send(new ClientboundBundlePacket(List.copyOf(packets.subList(from, to))));
        }
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Integer> findGlowColorAccessor() {
        try {
            final Field field = Display.class.getDeclaredField("DATA_GLOW_COLOR_OVERRIDE_ID");
            field.setAccessible(true);
            return (EntityDataAccessor<Integer>) field.get(null);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** @return удалось ли найти поле цвета подсветки (для сообщения в лог при старте) */
    public static boolean glowColorSupported() {
        return GLOW_COLOR != null;
    }

    // ------------------------------------------------------------------ трансформация

    /**
     * Модель block_display — единичный куб (0,0,0)–(1,1,1). Нужная матрица:
     *
     * <pre>M = R · T(-t/2, -t/2, -t/2) · S(L + t, t, t)</pre>
     *
     * <p>то есть сжать куб в брусок (длина × толщина × толщина), отцентровать по сечению
     * и повернуть ось +X в направление отрезка n. Брусок выходит за концы на t/2,
     * чтобы углы рамки смыкались.</p>
     *
     * <p>{@code Keys.MATRIX} появился только в Sponge API 12.1, а в 12.0 есть лишь
     * {@code Keys.TRANSFORM}. Sponge строит из него {@code T(position) · R · S(scale)},
     * а {@code R · T(c) = T(R·c) · R}, так что в position кладём {@code R·(-t/2, -t/2, -t/2)}.</p>
     *
     * <p>Поворот R = Ry(a) · Rz(e): сначала наклонить ось X вверх на угол e, потом
     * развернуть вокруг Y на угол a. Это порядок Эйлера Y·X·Z, который использует
     * Transform, с нулевым углом по X. Sponge при сборке инвертирует угол по Y
     * ({@code fromAxesAnglesDeg(x, -y, z)}), поэтому в rotation передаём −a.</p>
     */
    static Transform transform(final double nx, final double ny, final double nz, final double length, final double t) {
        final double e = Math.toDegrees(Math.atan2(ny, Math.sqrt(nx * nx + nz * nz)));
        final double a = Math.toDegrees(Math.atan2(-nz, nx));
        final Quaterniond rotation = Quaterniond.fromAngleDegAxis(a, Vector3d.UNIT_Y)
            .mul(Quaterniond.fromAngleDegAxis(e, Vector3d.UNIT_Z));
        final Vector3d offset = rotation.rotate(new Vector3d(-t / 2, -t / 2, -t / 2));
        return Transform.of(offset, new Vector3d(0, -a, e), new Vector3d(length + t, t, t));
    }
}
