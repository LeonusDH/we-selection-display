package dev.selviz;

import com.google.inject.Inject;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.world.World;
import dev.selviz.geometry.Line;
import dev.selviz.geometry.LineStyle;
import dev.selviz.geometry.SelectionShapes;
import dev.selviz.render.SelectionRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.CommandContext;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.entity.ChangeEntityWorldEvent;
import org.spongepowered.api.event.entity.living.player.RespawnPlayerEvent;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.scheduler.ScheduledTask;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.util.Ticks;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.reference.ConfigurationReference;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Plugin("we-selection-display")
public final class SelectionDisplayPlugin {

    private final PluginContainer container;
    private final Logger logger;
    private final ConfigurationReference<CommentedConfigurationNode> configReference;

    private Settings settings = new Settings();
    private SelectionRenderer renderer;
    private ScheduledTask task;

    /** Игроки, переключившие показ в состояние, обратное enabled-by-default. */
    private final Set<UUID> toggled = new HashSet<>();

    @Inject
    SelectionDisplayPlugin(final PluginContainer container, final Logger logger,
                           @DefaultConfig(sharedRoot = false) final ConfigurationReference<CommentedConfigurationNode> configReference) {
        this.container = container;
        this.logger = logger;
        this.configReference = configReference;
    }

    // ------------------------------------------------------------------ жизненный цикл

    @Listener
    public void onConstruct(final ConstructPluginEvent event) {
        this.loadSettings();
    }

    @Listener
    public void onServerStarted(final StartedEngineEvent<Server> event) {
        // Блоки разбираем здесь: на этапе конструирования реестры ещё не готовы.
        this.renderer = new SelectionRenderer(this.settings, this.parseBlocks());
        this.restartTask();
    }

    @Listener
    public void onServerStopping(final StoppingEngineEvent<Server> event) {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        if (this.renderer != null) {
            this.renderer.hideAll();
        }
    }

    // При выходе, смене мира и респауне клиент сам выбрасывает все сущности —
    // просто забываем, что отправляли, и при следующем обновлении шлём заново.

    @Listener
    public void onLeave(final ServerSideConnectionEvent.Leave event) {
        this.forget(event.player().uniqueId());
    }

    @Listener
    public void onChangeWorld(final ChangeEntityWorldEvent.Post event) {
        if (event.entity() instanceof ServerPlayer player) {
            this.forget(player.uniqueId());
        }
    }

    @Listener
    public void onRespawn(final RespawnPlayerEvent.Post event) {
        this.forget(event.entity().uniqueId());
    }

    private void forget(final UUID player) {
        if (this.renderer != null) {
            this.renderer.forget(player);
        }
    }

    // ------------------------------------------------------------------ основной цикл

    private void restartTask() {
        if (this.task != null) {
            this.task.cancel();
        }
        this.task = Sponge.server().scheduler().submit(Task.builder()
            .plugin(this.container)
            .interval(Ticks.of(Math.max(1, this.settings.updateIntervalTicks)))
            .execute(this::tick)
            .build());
    }

    private void tick() {
        for (final ServerPlayer player : Sponge.server().onlinePlayers()) {
            try {
                this.update(player);
            } catch (final Exception e) {
                // Одна ошибка не должна ронять задачу для всех.
                this.logger.warn("Failed to draw selection for {}", player.name(), e);
                this.renderer.hide(player);
            }
        }
    }

    private void update(final ServerPlayer player) {
        if (!this.isEnabledFor(player.uniqueId())) {
            this.renderer.hide(player);
            return;
        }

        final List<Line> lines = this.selectionLines(player);
        if (lines.isEmpty()) {
            this.renderer.hide(player);
        } else {
            this.renderer.show(player, lines);
        }
    }

    /**
     * Рёбра выделения игрока в его текущем мире. Пустой список — рисовать нечего.
     *
     * <p>Сессию ищем по имени игрока: так не нужна зависимость от worldedit-sponge
     * (SpongeAdapter), достаточно worldedit-core.</p>
     */
    private List<Line> selectionLines(final ServerPlayer player) {
        final LocalSession session = WorldEdit.getInstance().getSessionManager().findByName(player.name());
        if (session == null) {
            return List.of();
        }
        final World selectionWorld = session.getSelectionWorld();
        // В WorldEdit для Sponge имя мира — его ключ, например "minecraft:overworld".
        if (selectionWorld == null || !selectionWorld.getName().equals(player.world().key().asString())) {
            return List.of();
        }
        // Передаём тот же мир, что у селектора: иначе getRegionSelector() сбросит выделение.
        final RegionSelector selector = session.getRegionSelector(selectionWorld);
        return SelectionShapes.of(selector, this.settings.curveSegments);
    }

    private boolean isEnabledFor(final UUID player) {
        return this.settings.enabledByDefault != this.toggled.contains(player);
    }

    private void setEnabled(final UUID player, final boolean enabled) {
        if (enabled == this.settings.enabledByDefault) {
            this.toggled.remove(player);
        } else {
            this.toggled.add(player);
        }
    }

    // ------------------------------------------------------------------ настройки

    private void loadSettings() {
        try {
            this.configReference.load();
            Settings loaded = this.configReference.node().get(Settings.class);
            if (loaded == null) {
                loaded = new Settings();
            }
            this.settings = loaded;
            // Записываем обратно, чтобы в файле появились все ключи с комментариями.
            this.configReference.node().set(Settings.class, loaded);
            this.configReference.save();
        } catch (final ConfigurateException e) {
            this.logger.error("Could not load config, using defaults", e);
            this.settings = new Settings();
        }
    }

    private Map<LineStyle, BlockState> parseBlocks() {
        final Map<LineStyle, BlockState> result = new EnumMap<>(LineStyle.class);
        for (final LineStyle style : LineStyle.values()) {
            final String id = this.settings.blocks.get(style);
            BlockState state;
            try {
                state = BlockState.fromString(id);
            } catch (final Exception e) {
                this.logger.warn("Unknown block '{}' for {} lines, falling back to white concrete", id, style);
                state = BlockTypes.WHITE_CONCRETE.get().defaultState();
            }
            result.put(style, state);
        }
        return result;
    }

    // ------------------------------------------------------------------ команды

    @Listener
    public void onRegisterCommands(final RegisterCommandEvent<Command.Parameterized> event) {
        final Command.Parameterized on = Command.builder()
            .shortDescription(Component.text("Показывать выделение WorldEdit"))
            .executor(ctx -> this.setFor(ctx, true))
            .build();
        final Command.Parameterized off = Command.builder()
            .shortDescription(Component.text("Скрыть выделение WorldEdit"))
            .executor(ctx -> this.setFor(ctx, false))
            .build();
        final Command.Parameterized reload = Command.builder()
            .shortDescription(Component.text("Перечитать конфиг"))
            .permission("we-selection-display.reload")
            .executor(ctx -> {
                this.loadSettings();
                if (this.renderer != null) {
                    this.renderer.apply(this.settings, this.parseBlocks());
                    this.restartTask();
                }
                ctx.cause().audience().sendMessage(Component.text("Конфиг перечитан.", NamedTextColor.GREEN));
                return CommandResult.success();
            })
            .build();

        event.register(this.container, Command.builder()
            .shortDescription(Component.text("Включить/выключить показ выделения WorldEdit"))
            .executor(ctx -> {
                final ServerPlayer player = this.requirePlayer(ctx);
                if (player == null) {
                    return CommandResult.error(Component.text("Только для игроков."));
                }
                return this.setFor(ctx, !this.isEnabledFor(player.uniqueId()));
            })
            .addChild(on, "on")
            .addChild(off, "off")
            .addChild(reload, "reload")
            .build(), "selviz", "wesel");
    }

    private CommandResult setFor(final CommandContext ctx, final boolean enabled) {
        final ServerPlayer player = this.requirePlayer(ctx);
        if (player == null) {
            return CommandResult.error(Component.text("Только для игроков."));
        }
        this.setEnabled(player.uniqueId(), enabled);
        if (!enabled && this.renderer != null) {
            this.renderer.hide(player);
        }
        player.sendMessage(Component.text(enabled ? "Показ выделения включён." : "Показ выделения выключен.",
            enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        return CommandResult.success();
    }

    private ServerPlayer requirePlayer(final CommandContext ctx) {
        return ctx.cause().root() instanceof ServerPlayer player ? player : null;
    }
}
