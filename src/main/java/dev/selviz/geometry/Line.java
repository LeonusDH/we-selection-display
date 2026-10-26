package dev.selviz.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Отрезок в мировых координатах. Не зависит ни от Sponge, ни от Bukkit —
 * геометрию можно переиспользовать на любой платформе.
 */
public record Line(double x1, double y1, double z1,
                   double x2, double y2, double z2,
                   LineStyle style) {

    public double length() {
        final double dx = this.x2 - this.x1;
        final double dy = this.y2 - this.y1;
        final double dz = this.z2 - this.z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Режет отрезок на куски не длиннее {@code maxLength}.
     *
     * <p>Каждый кусок станет отдельной сущностью. Это нужно, потому что клиент
     * получает сущность только когда её точка привязки в радиусе отслеживания:
     * одно ребро длиной в 500 блоков, привязанное к углу, игрок вдали просто не увидит.</p>
     */
    public List<Line> split(final double maxLength) {
        final List<Line> aligned = this.splitOnGrid(maxLength);
        if (aligned != null) {
            return aligned;
        }
        final double len = this.length();
        final int parts = Math.max(1, (int) Math.ceil(len / maxLength));
        if (parts == 1) {
            return List.of(this);
        }
        final List<Line> out = new ArrayList<>(parts);
        double px = this.x1, py = this.y1, pz = this.z1;
        for (int i = 1; i <= parts; i++) {
            final double t = (double) i / parts;
            final double nx = this.x1 + (this.x2 - this.x1) * t;
            final double ny = this.y1 + (this.y2 - this.y1) * t;
            final double nz = this.z1 + (this.z2 - this.z1) * t;
            out.add(new Line(px, py, pz, nx, ny, nz, this.style));
            px = nx;
            py = ny;
            pz = nz;
        }
        return out;
    }

    /**
     * Для отрезков вдоль оси режем по мировой сетке с шагом {@code step}.
     * Тогда при растягивании выделения куски, которые не изменились, остаются
     * теми же самыми, и их сущности не пересоздаются (нет мерцания).
     *
     * @return список кусков или {@code null}, если отрезок не параллелен оси
     */
    private List<Line> splitOnGrid(final double step) {
        final boolean alongX = this.y1 == this.y2 && this.z1 == this.z2;
        final boolean alongY = this.x1 == this.x2 && this.z1 == this.z2;
        final boolean alongZ = this.x1 == this.x2 && this.y1 == this.y2;
        final int axis = alongX ? 0 : alongY ? 1 : alongZ ? 2 : -1;
        if (axis < 0 || step <= 0) {
            return null;
        }
        final double a = axis == 0 ? this.x1 : axis == 1 ? this.y1 : this.z1;
        final double b = axis == 0 ? this.x2 : axis == 1 ? this.y2 : this.z2;
        final double from = Math.min(a, b);
        final double to = Math.max(a, b);
        final List<Line> out = new ArrayList<>();
        double cur = from;
        while (cur < to) {
            double next = (Math.floor(cur / step) + 1) * step;
            if (next > to) {
                next = to;
            }
            out.add(this.withAxisRange(axis, cur, next));
            cur = next;
        }
        if (out.isEmpty()) {
            out.add(this);
        }
        return out;
    }

    private Line withAxisRange(final int axis, final double from, final double to) {
        return switch (axis) {
            case 0 -> new Line(from, this.y1, this.z1, to, this.y1, this.z1, this.style);
            case 1 -> new Line(this.x1, from, this.z1, this.x1, to, this.z1, this.style);
            default -> new Line(this.x1, this.y1, from, this.x1, this.y1, to, this.style);
        };
    }

    /** Квадрат расстояния от точки до отрезка. */
    public double distanceSquared(final double px, final double py, final double pz) {
        final double dx = this.x2 - this.x1;
        final double dy = this.y2 - this.y1;
        final double dz = this.z2 - this.z1;
        final double lenSq = dx * dx + dy * dy + dz * dz;
        double t = 0;
        if (lenSq > 0) {
            t = ((px - this.x1) * dx + (py - this.y1) * dy + (pz - this.z1) * dz) / lenSq;
            t = Math.max(0, Math.min(1, t));
        }
        final double cx = this.x1 + dx * t - px;
        final double cy = this.y1 + dy * t - py;
        final double cz = this.z1 + dz * t - pz;
        return cx * cx + cy * cy + cz * cz;
    }
}
