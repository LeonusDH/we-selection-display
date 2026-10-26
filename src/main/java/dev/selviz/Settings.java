package dev.selviz;

import dev.selviz.geometry.LineStyle;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/**
 * Настройки плагина, файл config/we-selection-display.conf.
 */
@ConfigSerializable
public class Settings {

    @Setting("update-interval-ticks")
    @Comment("Как часто (в тиках) проверять выделения игроков. 20 тиков = 1 секунда.")
    public int updateIntervalTicks = 4;

    @Setting("line-thickness")
    @Comment("Толщина линий в блоках.")
    public double lineThickness = 0.05;

    @Setting("render-radius")
    @Comment("Рисовать только куски линий не дальше этого расстояния от игрока (в блоках). Автоматически урезается до дальности прорисовки клиента.")
    public double renderRadius = 96;

    @Setting("segment-length")
    @Comment("Длинные рёбра режутся на куски такой длины, каждый кусок — отдельная сущность.")
    public double segmentLength = 16;

    @Setting("max-entities-per-player")
    @Comment("Верхний предел сущностей на одного игрока. Сверх него ближние куски важнее дальних.")
    public int maxEntitiesPerPlayer = 512;

    @Setting("curve-segments")
    @Comment("На сколько отрезков разбивать окружности цилиндра и эллипсоида.")
    public int curveSegments = 48;

    @Setting("glow")
    @Comment("Подсветка (glowing): контур виден сквозь блоки, как в WorldEditCUI.")
    public boolean glow = true;

    @Setting("full-bright")
    @Comment("Не затенять линии: всегда рисовать их с освещением 15.")
    public boolean fullBright = true;

    @Setting("view-range")
    @Comment("Множитель дальности прорисовки display-сущности (1.0 = 64 блока).")
    public double viewRange = 2.0;

    @Setting("enabled-by-default")
    @Comment("Показывать выделение игрокам, пока они сами не выключат его командой /selviz off.")
    public boolean enabledByDefault = true;

    @Setting("blocks")
    @Comment("Каким блоком рисовать каждый тип линий. Подойдёт любой блок, можно с состоянием, например minecraft:oak_log[axis=x].")
    public Blocks blocks = new Blocks();

    @ConfigSerializable
    public static class Blocks {
        public String primary = "minecraft:red_concrete";
        public String secondary = "minecraft:blue_concrete";
        public String cuboid = "minecraft:lime_concrete";
        public String polygon = "minecraft:yellow_concrete";
        public String cylinder = "minecraft:orange_concrete";
        public String ellipsoid = "minecraft:magenta_concrete";
        public String convex = "minecraft:cyan_concrete";
        public String other = "minecraft:white_concrete";

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
