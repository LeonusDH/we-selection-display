package dev.selviz.render;

import dev.selviz.Settings;
import dev.selviz.geometry.Line;
import dev.selviz.geometry.LineStyle;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockState;
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
 * кусков. Поэтому при движении игрока или растягивании выделения перерисовывается
 * лишь то, что изменилось.</p>
 *
 * <p>Все методы вызываются из главного потока сервера.</p>
 */
public final class SelectionRenderer {

    private final Map<UUID, View> views = new HashMap<>();
    private Settings settings;
    private Map<LineStyle, BlockState> blocks = new EnumMap<>(LineStyle.class);

    public SelectionRenderer(final Settings settings, final Map<LineStyle, BlockState> blocks) {
        this.apply(settings, blocks);
    }

    /** Новые настройки: всё нарисованное убирается и при следующем обновлении рисуется заново. */
    public void apply(final Settings settings, final Map<LineStyle, BlockState> blocks) {
        this.hideAll();
        this.settings = settings;
        this.blocks = new EnumMap<>(blocks);
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

        final List<Integer> toRemove = new ArrayList<>();
        final Iterator<Map.Entry<Line, Integer>> it = view.sent.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Line, Integer> entry = it.next();
            if (!wanted.contains(entry.getKey())) {
                toRemove.add(entry.getValue());
                it.remove();
            }
        }
        ClientDisplays.remove(player, toRemove);

        for (final Line line : wanted) {
            if (view.sent.containsKey(line)) {
                continue;
            }
            final BlockState block = this.blocks.get(line.style());
            if (block == null) {
                continue;
            }
            final int id = ClientDisplays.show(player, line, block, this.settings);
            if (id >= 0) {
                view.sent.put(line, id);
            }
        }
    }

    /** Убрать у игрока всё нарисованное. */
    public void hide(final ServerPlayer player) {
        final View view = this.views.remove(player.uniqueId());
        if (view != null) {
            ClientDisplays.remove(player, view.sent.values());
        }
    }

    /**
     * Забыть, что игроку что-то отправлялось, не посылая пакетов. Для случаев,
     * когда клиент уже сам выбросил сущности: выход, смена мира, респаун.
     */
    public void forget(final UUID player) {
        this.views.remove(player);
    }

    public void hideAll() {
        for (final Map.Entry<UUID, View> entry : this.views.entrySet()) {
            Sponge.server().player(entry.getKey())
                .ifPresent(player -> ClientDisplays.remove(player, entry.getValue().sent.values()));
        }
        this.views.clear();
    }

    /** Куски отрезков в радиусе отрисовки, не больше лимита, ближние в приоритете. */
    private Set<Line> visibleSegments(final ServerPlayer player, final List<Line> lines) {
        final Vector3d eye = player.position();
        final double radius = this.effectiveRadius(player);
        final double radiusSq = radius * radius;
        final List<Line> candidates = new ArrayList<>();
        for (final Line line : lines) {
            if (line.distanceSquared(eye.x(), eye.y(), eye.z()) > radiusSq) {
                continue;
            }
            for (final Line piece : line.split(this.settings.segmentLength)) {
                if (piece.distanceSquared(eye.x(), eye.y(), eye.z()) <= radiusSq) {
                    candidates.add(piece);
                }
            }
        }
        if (candidates.size() > this.settings.maxEntitiesPerPlayer) {
            candidates.sort(Comparator.comparingDouble(l -> l.distanceSquared(eye.x(), eye.y(), eye.z())));
            return new HashSet<>(candidates.subList(0, Math.max(0, this.settings.maxEntitiesPerPlayer)));
        }
        return new HashSet<>(candidates);
    }

    /**
     * Радиус не больше, чем видит клиент: сущность в выгруженном у клиента чанке он
     * удаляет сам, а мы бы считали её показанной.
     */
    private double effectiveRadius(final ServerPlayer player) {
        int chunks = player.world().properties().viewDistance();
        final int client = player.viewDistance().get();
        if (client > 0) {
            chunks = Math.min(chunks, client);
        }
        final double byView = Math.max(1, chunks - 1) * 16.0;
        return Math.min(this.settings.renderRadius, byView);
    }

    private static final class View {
        ResourceKey world;
        /** Отрезок → id клиентской сущности. */
        final Map<Line, Integer> sent = new HashMap<>();
    }
}
