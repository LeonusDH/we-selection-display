package dev.selviz.render;

import dev.selviz.Settings;
import dev.selviz.geometry.Line;
import dev.selviz.geometry.LineStyle;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.math.vector.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Помнит, какие клиентские сущности отправлены каждому игроку, и приводит их
 * к нужному набору отрезков.
 *
 * <p>Обновление дифференциальное: пакеты уходят только для появившихся и исчезнувших
 * кусков, одной связкой на игрока. Поэтому при движении игрока или растягивании
 * выделения перерисовывается лишь то, что изменилось.</p>
 *
 * <p>Все методы вызываются из главного потока сервера.</p>
 */
public final class SelectionRenderer {

    private final Map<UUID, View> views = new HashMap<>();
    private Settings settings;
    private Map<LineStyle, LineLook> looks = new EnumMap<>(LineStyle.class);

    public SelectionRenderer(final Settings settings, final Map<LineStyle, LineLook> looks) {
        this.apply(settings, looks);
    }

    /** Новые настройки: всё нарисованное убирается и при следующем обновлении рисуется заново. */
    public void apply(final Settings settings, final Map<LineStyle, LineLook> looks) {
        this.hideAll();
        this.settings = settings;
        this.looks = new EnumMap<>(looks);
    }

    /**
     * Показать игроку эти отрезки.
     *
     * @param lines логические рёбра выделения; здесь они режутся на куски и отсекаются по расстоянию
     */
    public void show(final ServerPlayer player, final List<Line> lines) {
        final View view = this.views.computeIfAbsent(player.uniqueId(), id -> new View());
        final ResourceKey world = player.world().key();
        if (view.world != null && !view.world.equals(world)) {
            // Клиент сам выбросил все сущности при смене мира — слать удаление не нужно.
            view.sent.clear();
        }
        view.world = world;

        final Set<Line> wanted = this.visibleSegments(player, lines);
        final List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();

        final List<Integer> gone = new ArrayList<>();
        final Iterator<Map.Entry<Line, Integer>> it = view.sent.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Line, Integer> entry = it.next();
            if (!wanted.contains(entry.getKey())) {
                gone.add(entry.getValue());
                it.remove();
            }
        }
        ClientDisplays.remove(gone, packets);

        for (final Line line : wanted) {
            if (view.sent.containsKey(line)) {
                continue;
            }
            final LineLook look = this.looks.get(line.style());
            if (look == null) {
                continue;
            }
            final int id = ClientDisplays.spawn(player, line, look, this.settings, packets);
            if (id >= 0) {
                view.sent.put(line, id);
            }
        }

        ClientDisplays.send(player, packets);
    }

    /** Убрать у игрока всё нарисованное. */
    public void hide(final ServerPlayer player) {
        final View view = this.views.remove(player.uniqueId());
        if (view != null) {
            hide(player, view);
        }
    }

    /**
     * Забыть, что игроку что-то отправлялось, не посылая пакетов. Для случаев,
     * когда клиент уже сам выбросил сущности: выход, смена мира.
     */
    public void forget(final UUID player) {
        this.views.remove(player);
    }

    public void hideAll() {
        for (final Map.Entry<UUID, View> entry : this.views.entrySet()) {
            Sponge.server().player(entry.getKey()).ifPresent(player -> hide(player, entry.getValue()));
        }
        this.views.clear();
    }

    private static void hide(final ServerPlayer player, final View view) {
        final List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(1);
        ClientDisplays.remove(view.sent.values(), packets);
        ClientDisplays.send(player, packets);
    }

    /** Куски отрезков в радиусе отрисовки, не больше лимита, ближние в приоритете. */
    private Set<Line> visibleSegments(final ServerPlayer player, final List<Line> lines) {
        final Vector3d eye = player.position();
        final double radius = this.effectiveRadius(player);
        final List<Line> pieces = new ArrayList<>();
        for (final Line line : lines) {
            line.splitNear(this.settings.segmentLength, eye.x(), eye.y(), eye.z(), radius, pieces);
        }
        if (pieces.size() > this.settings.maxEntitiesPerPlayer) {
            pieces.sort(Comparator.comparingDouble(l -> l.distanceSquared(eye.x(), eye.y(), eye.z())));
            return new HashSet<>(pieces.subList(0, this.settings.maxEntitiesPerPlayer));
        }
        return new HashSet<>(pieces);
    }

    /** Радиус не больше дальности прорисовки игрока: дальше линии всё равно не видны. */
    private double effectiveRadius(final ServerPlayer player) {
        int chunks = player.world().properties().viewDistance();
        final int client = player.viewDistance().get();
        if (client > 0) {
            chunks = Math.min(chunks, client);
        }
        return Math.min(this.settings.renderRadius, Math.max(1, chunks) * 16.0);
    }

    private static final class View {
        ResourceKey world;
        /** Отрезок → id клиентской сущности. */
        final Map<Line, Integer> sent = new HashMap<>();
    }
}
