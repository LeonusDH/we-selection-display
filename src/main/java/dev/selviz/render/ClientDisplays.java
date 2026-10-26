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
import org.spongepowered.math.imaginary.Quaterniond;
import org.spongepowered.math.matrix.Matrix4d;
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
        display.offer(Keys.MATRIX, matrix(dx / length, dy / length, dz / length, length, settings.lineThickness));
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

    // ------------------------------------------------------------------ матрица

    /**
     * Модель block_display — единичный куб (0,0,0)–(1,1,1). Сначала сжимаем его в брусок
     * (длина × толщина × толщина) и центрируем по сечению, потом поворачиваем ось +X
     * в направление отрезка. Брусок выходит за концы на t/2, чтобы углы смыкались.
     *
     * <pre>M = R(+X → n) · T(-t/2, -t/2, -t/2) · S(L + t, t, t)</pre>
     */
    static Matrix4d matrix(final double nx, final double ny, final double nz, final double length, final double t) {
        final Matrix4d scale = Matrix4d.createScaling(length + t, t, t, 1);
        final Matrix4d center = Matrix4d.createTranslation(-t / 2, -t / 2, -t / 2);
        return rotationFromX(nx, ny, nz).mul(center).mul(scale);
    }

    /** Поворот, переводящий ось +X в единичный вектор (nx, ny, nz). */
    static Matrix4d rotationFromX(final double nx, final double ny, final double nz) {
        if (nx > 1 - EPS) {
            return Matrix4d.IDENTITY;
        }
        if (nx < -1 + EPS) {
            return Matrix4d.createRotation(Quaterniond.fromAngleRadAxis(Math.PI, 0, 1, 0));
        }
        // Ось поворота = X × n = (0, -nz, ny), угол = acos(X · n) = acos(nx).
        final double axisLen = Math.sqrt(ny * ny + nz * nz);
        final Quaterniond q = Quaterniond.fromAngleRadAxis(Math.acos(nx), 0, -nz / axisLen, ny / axisLen);
        return Matrix4d.createRotation(q);
    }
}
