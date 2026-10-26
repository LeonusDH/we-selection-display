package dev.selviz;

import dev.selviz.geometry.LineStyle;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки плагина, файл config/we-selection-display/we-selection-display.conf.
 */
@ConfigSerializable
public class Settings {

    @Setting("update-interval-ticks")
    @Comment("Как часто (в тиках) проверять выделения игроков. 20 тиков = 1 секунда. От 1 до 200.")
    public int updateIntervalTicks = 4;

    @Setting("line-thickness")
    @Comment("Толщина линий в блоках. От 0.005 до 1.")
    public double lineThickness = 0.05;

    @Setting("render-radius")
    @Comment("Рисовать только куски линий не дальше этого расстояния от игрока (в блоках). "
        + "Автоматически урезается до дальности прорисовки игрока. От 8 до 512.")
    public double renderRadius = 96;

    @Setting("segment-length")
    @Comment("Длинные рёбра режутся на куски такой длины, каждый кусок — отдельная сущность. От 1 до 256.")
    public double segmentLength = 16;

    @Setting("max-entities-per-player")
    @Comment("Верхний предел сущностей на одного игрока. Сверх него ближние куски важнее дальних. От 1 до 4096.")
    public int maxEntitiesPerPlayer = 512;

    @Setting("curve-segments")
    @Comment("На сколько отрезков разбивать окружности цилиндра и эллипсоида. От 8 до 256.")
    public int curveSegments = 48;

    @Setting("glow")
    @Comment("Подсветка (glowing): контур виден сквозь блоки, как в WorldEditCUI.")
    public boolean glow = true;

    @Setting("full-bright")
    @Comment("Не затенять линии: всегда рисовать их с освещением 15.")
    public boolean fullBright = true;

    @Setting("view-range")
    @Comment("Множитель дальности прорисовки display-сущности (1.0 = 64 блока). От 0.1 до 16.")
    public double viewRange = 2.0;

    @Setting("enabled-by-default")
    @Comment("Показывать выделение игрокам, пока они сами не выключат его командой /selviz off.")
    public boolean enabledByDefault = true;

    @Setting("blocks")
    @Comment("Каким блоком рисовать каждый тип линий. Подойдёт любой блок, можно с состоянием, например minecraft:oak_log[axis=x].")
    public Styles blocks = Styles.defaultBlocks();

    @Setting("glow-colors")
    @Comment("Цвет подсветки для каждого типа линий, RRGGBB. Без него подсветка у всех линий белая.")
    public Styles glowColors = Styles.defaultGlowColors();

    /**
     * Приводит значения в допустимые пределы: кривой конфиг не должен ни ронять
     * сервер (нулевая длина куска — бесконечная нарезка), ни слать мусор клиенту.
     *
     * @return что пришлось исправить, для предупреждения в лог
     */
    public List<String> sanitize() {
        final List<String> fixed = new ArrayList<>();
        this.updateIntervalTicks = clamp("update-interval-ticks", this.updateIntervalTicks, 1, 200, fixed);
        this.lineThickness = clamp("line-thickness", this.lineThickness, 0.005, 1, fixed);
        this.renderRadius = clamp("render-radius", this.renderRadius, 8, 512, fixed);
        this.segmentLength = clamp("segment-length", this.segmentLength, 1, 256, fixed);
        this.maxEntitiesPerPlayer = clamp("max-entities-per-player", this.maxEntitiesPerPlayer, 1, 4096, fixed);
        this.curveSegments = clamp("curve-segments", this.curveSegments, 8, 256, fixed);
        this.viewRange = clamp("view-range", this.viewRange, 0.1, 16, fixed);
        if (this.blocks == null) {
            this.blocks = Styles.defaultBlocks();
            fixed.add("blocks");
        }
        if (this.glowColors == null) {
            this.glowColors = Styles.defaultGlowColors();
            fixed.add("glow-colors");
        }
        return fixed;
    }

    private static int clamp(final String name, final int value, final int min, final int max, final List<String> fixed) {
        final int result = Math.max(min, Math.min(max, value));
        if (result != value) {
            fixed.add(name);
        }
        return result;
    }

    private static double clamp(final String name, final double value, final double min, final double max, final List<String> fixed) {
        // NaN не проходит ни одно сравнение, поэтому проверяем его отдельно.
        final double result = Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
        if (Double.compare(result, value) != 0) {
            fixed.add(name);
        }
        return result;
    }

    /** По строковому значению на каждый тип линий. */
    @ConfigSerializable
    public static class Styles {
        public String primary;
        public String secondary;
        public String cuboid;
        public String polygon;
        public String cylinder;
        public String ellipsoid;
        public String convex;
        public String other;

        static Styles defaultBlocks() {
            return Styles.of("minecraft:red_concrete", "minecraft:blue_concrete", "minecraft:lime_concrete",
                "minecraft:yellow_concrete", "minecraft:orange_concrete", "minecraft:magenta_concrete",
                "minecraft:cyan_concrete", "minecraft:white_concrete");
        }

        /** Цвета красителей Minecraft — совпадают с бетоном выше. */
        static Styles defaultGlowColors() {
            return Styles.of("B02E26", "3C44AA", "80C71F", "FED83D", "F9801D", "C74EBD", "169C9C", "F9FFFE");
        }

        private static Styles of(final String primary, final String secondary, final String cuboid, final String polygon,
                                 final String cylinder, final String ellipsoid, final String convex, final String other) {
            final Styles s = new Styles();
            s.primary = primary;
            s.secondary = secondary;
            s.cuboid = cuboid;
            s.polygon = polygon;
            s.cylinder = cylinder;
            s.ellipsoid = ellipsoid;
            s.convex = convex;
            s.other = other;
            return s;
        }

        /** @return значение для типа или {@code null}, если в конфиге его нет */
        public String get(final LineStyle style) {
            return switch (style) {
                case PRIMARY -> this.primary;
                case SECONDARY -> this.secondary;
                case CUBOID -> this.cuboid;
                case POLYGON -> this.polygon;
                case CYLINDER -> this.cylinder;
                case ELLIPSOID -> this.ellipsoid;
                case CONVEX -> this.convex;
                case OTHER -> this.other;
            };
        }
    }
}
