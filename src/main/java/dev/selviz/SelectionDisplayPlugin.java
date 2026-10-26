package dev.selviz;

import com.google.inject.Inject;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.world.World;
import dev.selviz.geometry.Line;
import dev.selviz.geometry.LineStyle;
import dev.selviz.geometry.SelectionShapes;
import dev.selviz.render.ClientDisplays;
import dev.selviz.render.LineLook;
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
import java.util.HashMap;
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

    /** Явный выбор игрока командой /selviz. Кого здесь нет — тем действует enabled-by-default. */
    private final Map<UUID, Boolean> choices = new HashMap<>();
    /** Игроки, для которых уже записана ошибка отрисовки: не спамим лог каждые несколько тиков. */
    private final Set<UUID> failing = new HashSet<>();

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
        this.renderer = new SelectionRenderer(this.settings, this.parseLooks());
        if (!ClientDisplays.glowColorSupported()) {
            this.logger.warn("Glow color override is not available on this Minecraft version, glow outlines will be white");
        }
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

    // При выходе и смене мира клиент сам выбрасывает все сущности —
    // просто забываем, что отправляли, и при следующем обновлении шлём заново.

    @Listener
    public void onLeave(final ServerSideConnectionEvent.Leave event) {
        this.forget(event.player().uniqueId());
        this.failing.remove(event.player().uniqueId());
    }

    @Listener
    public void onChangeWorld(final ChangeEntityWorldEvent.Post event) {
        if (event.entity() instanceof ServerPlayer player) {
            this.forget(player.uniqueId());
        }
    }

    @Listener
    public void onRespawn(final RespawnPlayerEvent.Post event) {
        // При респауне в том же мире клиент сохраняет свои сущности. Если забыть их здесь,
        // следующее обновление нарисует копии, а старые линии останутся висеть навсегда.
        if (!event.originalWorld().key().equals(event.destinationWorld().key())) {
            this.forget(event.entity().uniqueId());
        }
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
            .interval(Ticks.of(this.settings.updateIntervalTicks))
            .execute(this::tick)
            .build());
    }

    private void tick() {
        for (final ServerPlayer player : Sponge.server().onlinePlayers()) {
            try {
                this.update(player);
                this.failing.remove(player.uniqueId());
            } catch (final Exception e) {
                // Одна ошибка не должна ронять задачу для всех.
                if (this.failing.add(player.uniqueId())) {
                    this.logger.warn("Failed to draw selection for {}", player.name(), e);
                }
                try {
                    this.renderer.hide(player);
                } catch (final Exception ignored) {
                    this.renderer.forget(player.uniqueId());
                }
            }
        }
    }

    private void update(final ServerPlayer player) {
        final List<Line> lines = this.isEnabledFor(player.uniqueId()) ? this.selectionLines(player) : List.of();
        if (lines.isEmpty()) {
            this.renderer.hide(player);
        } else {
            this.renderer.show(player, lines);
        }
    }

    /**
     * Рёбра выделения игрока в его текущем мире. Пустой список — рисовать нечего.
     *
     * <p>Достаточно worldedit-core: сессия ищется по UUID игрока, без SpongeAdapter.</p>
     */
    private List<Line> selectionLines(final ServerPlayer player) {
        final LocalSession session = WorldEdit.getInstance().getSessionManager()
            .getIfPresent(new SessionLookup(player.uniqueId(), player.name()));
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
        return this.choices.getOrDefault(player, this.settings.enabledByDefault);
    }

    // ------------------------------------------------------------------ настройки

    /** При ошибке чтения остаются прежние настройки (при старте — значения по умолчанию). */
    private void loadSettings() {
        try {
            this.configReference.load();
            Settings loaded = this.configReference.node().get(Settings.class);
            if (loaded == null) {
                loaded = new Settings();
            }
            final List<String> fixed = loaded.sanitize();
            if (!fixed.isEmpty()) {
                this.logger.warn("Config values out of range were adjusted: {}", String.join(", ", fixed));
            }
            this.settings = loaded;
            // Записываем обратно, чтобы в файле появились все ключи с комментариями.
            this.configReference.node().set(Settings.class, loaded);
            this.configReference.save();
        } catch (final ConfigurateException e) {
            this.logger.error("Could not load config, keeping previous settings", e);
        }
    }

    private Map<LineStyle, LineLook> parseLooks() {
        final Settings.Styles defaultBlocks = new Settings().blocks;
        final Settings.Styles defaultGlow = new Settings().glowColors;
        final Map<LineStyle, LineLook> result = new EnumMap<>(LineStyle.class);
        for (final LineStyle style : LineStyle.values()) {
            result.put(style, new LineLook(
                this.parseBlock(style, this.settings.blocks.get(style), defaultBlocks.get(style)),
                this.parseColor(style, this.settings.glowColors.get(style), defaultGlow.get(style))));
        }
        return result;
    }

    private BlockState parseBlock(final LineStyle style, final String id, final String fallback) {
        if (id != null) {
            try {
                return BlockState.fromString(id);
            } catch (final RuntimeException e) {
                this.logger.warn("Unknown block '{}' for {} lines, using {}", id, style, fallback);
            }
        }
        try {
            return BlockState.fromString(fallback);
        } catch (final RuntimeException e) {
            return BlockTypes.WHITE_CONCRETE.get().defaultState();
        }
    }

    private int parseColor(final LineStyle style, final String hex, final String fallback) {
        final String value = hex == null ? fallback : hex.trim();
        try {
            final String digits = value.startsWith("#") ? value.substring(1) : value;
            if (digits.length() == 6) {
                return Integer.parseInt(digits, 16);
            }
        } catch (final NumberFormatException ignored) {
            // ниже
        }
        this.logger.warn("Invalid glow color '{}' for {} lines, using {}", value, style, fallback);
        return Integer.parseInt(fallback, 16);
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
                    this.renderer.apply(this.settings, this.parseLooks());
                    this.restartTask();
                }
                ctx.cause().audience().sendMessage(Component.text("Конфиг перечитан.", NamedTextColor.GREEN));
                return CommandResult.success();
            })
            .build();

        event.register(this.container, Command.builder()
            .shortDescription(Component.text("Включить/выключить показ выделения WorldEdit"))
            .executor(ctx -> this.setFor(ctx, null))
            .addChild(on, "on")
            .addChild(off, "off")
            .addChild(reload, "reload")
            .build(), "selviz", "wesel");
    }

    /** @param enabled {@code null} — переключить */
    private CommandResult setFor(final CommandContext ctx, final Boolean enabled) {
        if (!(ctx.cause().root() instanceof ServerPlayer player)) {
            return CommandResult.error(Component.text("Только для игроков."));
        }
        final boolean value = enabled != null ? enabled : !this.isEnabledFor(player.uniqueId());
        this.choices.put(player.uniqueId(), value);
        if (!value && this.renderer != null) {
            this.renderer.hide(player);
        }
        player.sendMessage(Component.text(value ? "Показ выделения включён." : "Показ выделения выключен.",
            value ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        return CommandResult.success();
    }
}
