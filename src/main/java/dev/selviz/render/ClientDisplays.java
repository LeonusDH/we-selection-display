package dev.selviz.render;

import dev.selviz.Settings;
import dev.selviz.geometry.Line;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.entity.EntityTypes;
import org.spongepowered.api.entity.display.BlockDisplay;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.imaginary.Quaterniond;
import org.spongepowered.math.matrix.Matrix4d;
import org.spongepowered.math.vector.Vector3d;

import java.util.Collection;
import java.util.List;

/**
 * Клиентские (пакетные) block_display-сущности: существуют только у одного игрока.
 *
 * <p>Сущность настраивается обычным Sponge API ({@link ServerWorld#createEntity}),
 * но в мир не добавляется: из неё берутся id, UUID и синхронизируемые данные,
 * и всё это отправляется пакетами одному игроку. Остальные игроки ничего не видят,
 * на сервере ничего не тикает и не сохраняется.</p>
 *
 * <p>Единственное место в плагине, где используются классы Minecraft (Mojang-имена).
 * Sponge в рантайме работает на Mojang-маппингах, сущности и игроки Sponge — это
 * сами объекты Minecraft, поэтому достаточно приведения типов.</p>
 */
public final class ClientDisplays {

    private static final double EPS = 1e-6;

    private ClientDisplays() {
    }

    /**
     * Показать игроку брусок вдоль отрезка.
     *
     * @return id сущности на клиенте или {@code -1}, если отрезок вырожденный
     */
    public static int show(final ServerPlayer player, final Line line, final BlockState block, final Settings settings) {
        final double dx = line.x2() - line.x1();
        final double dy = line.y2() - line.y1();
        final double dz = line.z2() - line.z1();
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < EPS) {
            return -1;
        }

        final ServerWorld world = player.world();
        final BlockDisplay display = world.createEntity(EntityTypes.BLOCK_DISPLAY,
            new Vector3d(line.x1(), line.y1(), line.z1()));
        display.offer(Keys.BLOCK_STATE, block);
        display.offer(Keys.MATRIX, matrix(dx / length, dy / length, dz / length, length, settings.lineThickness));
        display.offer(Keys.VIEW_RANGE, settings.viewRange);
        if (settings.glow) {
            display.offer(Keys.IS_GLOWING, true);
        }
        if (settings.fullBright) {
            display.offer(Keys.SKY_LIGHT, 15);
            display.offer(Keys.BLOCK_LIGHT, 15);
        }

        final Entity entity = (Entity) display;
        send(player, new ClientboundAddEntityPacket(
            entity.getId(), entity.getUUID(),
            line.x1(), line.y1(), line.z1(),
            0f, 0f,
            entity.getType(), 0, Vec3.ZERO, 0.0));
        final List<SynchedEntityData.DataValue<?>> data = entity.getEntityData().getNonDefaultValues();
        if (data != null) {
            send(player, new ClientboundSetEntityDataPacket(entity.getId(), data));
        }
        return entity.getId();
    }

    /** Убрать у игрока сущности с этими id. */
    public static void remove(final ServerPlayer player, final Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return;
        }
        final int[] array = ids.stream().mapToInt(Integer::intValue).toArray();
        send(player, new ClientboundRemoveEntitiesPacket(array));
    }

    private static void send(final ServerPlayer player, final Packet<?> packet) {
        ((net.minecraft.server.level.ServerPlayer) player).connection.send(packet);
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
