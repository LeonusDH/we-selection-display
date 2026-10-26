package dev.selviz.geometry;

import java.util.List;

/**
 * Отрезок в мировых координатах. Не зависит ни от Sponge, ни от Bukkit —
 * геометрию можно переиспользовать на любой платформе.
 */
public record Line(double x1, double y1, double z1,
                   double x2, double y2, double z2,
                   LineStyle style) {

    /**
     * Добавляет в {@code out} куски отрезка не длиннее {@code step}, которые проходят
     * ближе {@code radius} к точке (px, py, pz). Каждый кусок станет отдельной сущностью.
     *
     * <p>Режется только та часть отрезка, что рядом с точкой, поэтому ребро длиной
     * в миллион блоков обходится так же дёшево, как короткое.</p>
     *
     * <p>Границы кусков не зависят от положения точки: отрезок вдоль оси режется
     * по мировой сетке с шагом {@code step}, наклонный — на равные доли. Поэтому при
     * движении игрока или растягивании выделения куски, которые не изменились, остаются
     * теми же самыми (равными по {@code equals}), и их сущности не пересоздаются.</p>
     */
    public void splitNear(final double step, final double px, final double py, final double pz,
                          final double radius, final List<Line> out) {
        if (!(step > 0) || !(radius >= 0)) {
            return;
        }
        final double dx = this.x2 - this.x1;
        final double dy = this.y2 - this.y1;
        final double dz = this.z2 - this.z1;
        final double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq == 0) {
            return;
        }

        // Параметры t ∈ [0, 1], при которых точка отрезка ближе radius:
        // |P1 + t·d − p|² ≤ r²  →  a·t² + b·t + c ≤ 0.
        final double ox = this.x1 - px;
        final double oy = this.y1 - py;
        final double oz = this.z1 - pz;
        final double b = 2 * (dx * ox + dy * oy + dz * oz);
        final double c = ox * ox + oy * oy + oz * oz - radius * radius;
        final double disc = b * b - 4 * lenSq * c;
        if (disc < 0) {
            return;
        }
        final double sqrt = Math.sqrt(disc);
        final double t0 = Math.max(0, (-b - sqrt) / (2 * lenSq));
        final double t1 = Math.min(1, (-b + sqrt) / (2 * lenSq));
        if (t0 > t1) {
            return;
        }

        final int axis = this.axis();
        if (axis >= 0) {
            this.splitOnGrid(axis, step, t0, t1, out);
        } else {
            this.splitEvenly(Math.sqrt(lenSq), step, t0, t1, out);
        }
    }

    /** Ось, вдоль которой идёт отрезок: 0 — X, 1 — Y, 2 — Z, −1 — наклонный. */
    private int axis() {
        if (this.y1 == this.y2 && this.z1 == this.z2) {
            return 0;
        }
        if (this.x1 == this.x2 && this.z1 == this.z2) {
            return 1;
        }
        if (this.x1 == this.x2 && this.y1 == this.y2) {
            return 2;
        }
        return -1;
    }

    private void splitOnGrid(final int axis, final double step, final double t0, final double t1, final List<Line> out) {
        final double a = this.coord(axis, true);
        final double b = this.coord(axis, false);
        final double from = Math.min(a, b);
        final double to = Math.max(a, b);
        // Участок отрезка рядом с точкой, в координатах вдоль оси.
        final double nearA = a + (b - a) * t0;
        final double nearB = a + (b - a) * t1;
        final long first = (long) Math.floor(Math.min(nearA, nearB) / step);
        final long last = (long) Math.floor(Math.max(nearA, nearB) / step);
        for (long k = first; k <= last; k++) {
            final double lo = Math.max(from, k * step);
            final double hi = Math.min(to, (k + 1) * step);
            if (lo < hi) {
                out.add(this.withAxisRange(axis, lo, hi));
            }
        }
    }

    private void splitEvenly(final double length, final double step, final double t0, final double t1, final List<Line> out) {
        final long parts = Math.max(1, (long) Math.ceil(length / step));
        final long first = Math.min(parts - 1, (long) Math.floor(t0 * parts));
        final long last = Math.min(parts - 1, (long) Math.floor(t1 * parts));
        for (long i = first; i <= last; i++) {
            out.add(this.between((double) i / parts, (double) (i + 1) / parts));
        }
    }

    private double coord(final int axis, final boolean start) {
        return switch (axis) {
            case 0 -> start ? this.x1 : this.x2;
            case 1 -> start ? this.y1 : this.y2;
            default -> start ? this.z1 : this.z2;
        };
    }

    private Line withAxisRange(final int axis, final double from, final double to) {
        return switch (axis) {
            case 0 -> new Line(from, this.y1, this.z1, to, this.y1, this.z1, this.style);
            case 1 -> new Line(this.x1, from, this.z1, this.x1, to, this.z1, this.style);
            default -> new Line(this.x1, this.y1, from, this.x1, this.y1, to, this.style);
        };
    }

    private Line between(final double ta, final double tb) {
        final double dx = this.x2 - this.x1;
        final double dy = this.y2 - this.y1;
        final double dz = this.z2 - this.z1;
        return new Line(
            this.x1 + dx * ta, this.y1 + dy * ta, this.z1 + dz * ta,
            this.x1 + dx * tb, this.y1 + dy * tb, this.z1 + dz * tb,
            this.style);
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
