package dev.selviz.geometry;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector2;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.regions.ConvexPolyhedralRegion;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.CylinderRegion;
import com.sk89q.worldedit.regions.EllipsoidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.polyhedron.Triangle;
import com.sk89q.worldedit.regions.selector.ConvexPolyhedralRegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.regions.selector.CylinderRegionSelector;
import com.sk89q.worldedit.regions.selector.EllipsoidRegionSelector;
import com.sk89q.worldedit.regions.selector.Polygonal2DRegionSelector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Превращает выделение WorldEdit в набор отрезков.
 *
 * <p>Зависит только от worldedit-core, поэтому подходит для любой платформы
 * (Sponge, Bukkit/Paper, Fabric…). Координаты — мировые, в блоках; блок (x, y, z)
 * занимает куб от (x, y, z) до (x+1, y+1, z+1).</p>
 */
public final class SelectionShapes {

    /** Насколько рамка точки выступает за грани блока, чтобы не z-файтиться с ним. */
    private static final double MARKER_INFLATE = 0.02;

    private SelectionShapes() {
    }

    /**
     * @param selector      селектор игрока ({@code session.getRegionSelector(session.getSelectionWorld())})
     * @param curveSegments на сколько отрезков разбивать окружности/эллипсы
     */
    public static List<Line> of(final RegionSelector selector, final int curveSegments) {
        final List<Line> out = new ArrayList<>();

        // Первая точка есть почти у всех типов выделения, даже незавершённых.
        try {
            blockOutline(selector.getPrimaryPosition(), LineStyle.PRIMARY, out);
        } catch (final IncompleteRegionException ignored) {
            // Ещё ничего не выделено.
        }

        if (selector instanceof CuboidRegionSelector) {
            // Сюда же попадает ExtendingCuboidRegionSelector (//sel extend).
            if (selector.isDefined()) {
                final CuboidRegion region = (CuboidRegion) selector.getIncompleteRegion();
                box(region.getMinimumPoint(), region.getMaximumPoint(), LineStyle.CUBOID, out);
                blockOutline(region.getPos2(), LineStyle.SECONDARY, out);
            }
        } else if (selector instanceof Polygonal2DRegionSelector poly) {
            polygon(poly.getIncompleteRegion(), out);
        } else if (selector instanceof CylinderRegionSelector cyl) {
            if (selector.isDefined()) {
                cylinder(cyl.getIncompleteRegion(), curveSegments, out);
            }
        } else if (selector instanceof EllipsoidRegionSelector ell) {
            // Сюда же попадает SphereRegionSelector.
            if (selector.isDefined()) {
                ellipsoid(ell.getIncompleteRegion(), curveSegments, out);
            }
        } else if (selector instanceof ConvexPolyhedralRegionSelector) {
            if (selector.getIncompleteRegion() instanceof ConvexPolyhedralRegion convex) {
                convex(convex, out);
            }
        } else if (selector.isDefined()) {
            final Region region = selector.getIncompleteRegion();
            box(region.getMinimumPoint(), region.getMaximumPoint(), LineStyle.OTHER, out);
        }
        return out;
    }

    // ------------------------------------------------------------------ фигуры

    /** Рёбра параллелепипеда, охватывающего блоки от min до max включительно. */
    private static void box(final BlockVector3 min, final BlockVector3 max, final LineStyle style, final List<Line> out) {
        box(min.x(), min.y(), min.z(), max.x() + 1, max.y() + 1, max.z() + 1, style, out);
    }

    private static void blockOutline(final BlockVector3 pos, final LineStyle style, final List<Line> out) {
        final double e = MARKER_INFLATE;
        box(pos.x() - e, pos.y() - e, pos.z() - e, pos.x() + 1 + e, pos.y() + 1 + e, pos.z() + 1 + e, style, out);
    }

    private static void box(final double x1, final double y1, final double z1,
                            final double x2, final double y2, final double z2,
                            final LineStyle style, final List<Line> out) {
        // 4 ребра вдоль X
        out.add(new Line(x1, y1, z1, x2, y1, z1, style));
        out.add(new Line(x1, y2, z1, x2, y2, z1, style));
        out.add(new Line(x1, y1, z2, x2, y1, z2, style));
        out.add(new Line(x1, y2, z2, x2, y2, z2, style));
        // 4 ребра вдоль Y
        out.add(new Line(x1, y1, z1, x1, y2, z1, style));
        out.add(new Line(x2, y1, z1, x2, y2, z1, style));
        out.add(new Line(x1, y1, z2, x1, y2, z2, style));
        out.add(new Line(x2, y1, z2, x2, y2, z2, style));
        // 4 ребра вдоль Z
        out.add(new Line(x1, y1, z1, x1, y1, z2, style));
        out.add(new Line(x2, y1, z1, x2, y1, z2, style));
        out.add(new Line(x1, y2, z1, x1, y2, z2, style));
        out.add(new Line(x2, y2, z1, x2, y2, z2, style));
    }

    /** //sel poly: вертикальные стойки в каждой точке и контур сверху/снизу (по центрам блоков). */
    private static void polygon(final Polygonal2DRegion region, final List<Line> out) {
        final List<BlockVector2> points = region.getPoints();
        if (points.isEmpty()) {
            return;
        }
        final double bottom = region.getMinimumY();
        final double top = region.getMaximumY() + 1;
        for (final BlockVector2 p : points) {
            out.add(new Line(p.x() + 0.5, bottom, p.z() + 0.5, p.x() + 0.5, top, p.z() + 0.5, LineStyle.POLYGON));
        }
        if (points.size() < 2) {
            return;
        }
        final int edges = points.size() >= 3 ? points.size() : 1; // 2 точки — одно ребро, 3+ — замкнутый контур
        for (int i = 0; i < edges; i++) {
            final BlockVector2 a = points.get(i);
            final BlockVector2 b = points.get((i + 1) % points.size());
            out.add(new Line(a.x() + 0.5, bottom, a.z() + 0.5, b.x() + 0.5, bottom, b.z() + 0.5, LineStyle.POLYGON));
            out.add(new Line(a.x() + 0.5, top, a.z() + 0.5, b.x() + 0.5, top, b.z() + 0.5, LineStyle.POLYGON));
        }
    }

    /** //sel cyl: эллипсы сверху и снизу плюс 4 образующие. */
    private static void cylinder(final CylinderRegion region, final int segments, final List<Line> out) {
        final Vector3 center = region.getCenter();
        final Vector2 radius = region.getRadius();
        final double cx = center.x() + 0.5;
        final double cz = center.z() + 0.5;
        final double rx = radius.x() + 0.5;
        final double rz = radius.z() + 0.5;
        final double bottom = region.getMinimumY();
        final double top = region.getMaximumY() + 1;

        ellipse(cx, bottom, cz, rx, 0, 0, 0, 0, rz, segments, LineStyle.CYLINDER, out);
        ellipse(cx, top, cz, rx, 0, 0, 0, 0, rz, segments, LineStyle.CYLINDER, out);
        out.add(new Line(cx + rx, bottom, cz, cx + rx, top, cz, LineStyle.CYLINDER));
        out.add(new Line(cx - rx, bottom, cz, cx - rx, top, cz, LineStyle.CYLINDER));
        out.add(new Line(cx, bottom, cz + rz, cx, top, cz + rz, LineStyle.CYLINDER));
        out.add(new Line(cx, bottom, cz - rz, cx, top, cz - rz, LineStyle.CYLINDER));
    }

    /** //sel ellipsoid и //sel sphere: три «экватора» в плоскостях XZ, XY и YZ. */
    private static void ellipsoid(final EllipsoidRegion region, final int segments, final List<Line> out) {
        final Vector3 center = region.getCenter();
        final Vector3 radius = region.getRadius();
        final double cx = center.x() + 0.5;
        final double cy = center.y() + 0.5;
        final double cz = center.z() + 0.5;
        final double rx = radius.x() + 0.5;
        final double ry = radius.y() + 0.5;
        final double rz = radius.z() + 0.5;

        ellipse(cx, cy, cz, rx, 0, 0, 0, 0, rz, segments, LineStyle.ELLIPSOID, out);
        ellipse(cx, cy, cz, rx, 0, 0, 0, ry, 0, segments, LineStyle.ELLIPSOID, out);
        ellipse(cx, cy, cz, 0, 0, rz, 0, ry, 0, segments, LineStyle.ELLIPSOID, out);
    }

    /** //sel convex: рёбра треугольников оболочки, а пока оболочки нет — рамки вокруг вершин. */
    private static void convex(final ConvexPolyhedralRegion region, final List<Line> out) {
        final var triangles = region.getTriangles();
        if (triangles.isEmpty()) {
            for (final BlockVector3 v : region.getVertices()) {
                blockOutline(v, LineStyle.CONVEX, out);
            }
            return;
        }
        // У соседних треугольников общие рёбра — убираем дубликаты.
        final Set<String> seen = new HashSet<>();
        for (final Triangle triangle : triangles) {
            for (int i = 0; i < 3; i++) {
                final Vector3 a = triangle.getVertex(i);
                final Vector3 b = triangle.getVertex((i + 1) % 3);
                final String ka = a.x() + "," + a.y() + "," + a.z();
                final String kb = b.x() + "," + b.y() + "," + b.z();
                final String key = ka.compareTo(kb) < 0 ? ka + "|" + kb : kb + "|" + ka;
                if (seen.add(key)) {
                    out.add(new Line(a.x() + 0.5, a.y() + 0.5, a.z() + 0.5,
                        b.x() + 0.5, b.y() + 0.5, b.z() + 0.5, LineStyle.CONVEX));
                }
            }
        }
    }

    /**
     * Эллипс c центром (cx, cy, cz) и полуосями-векторами u и v:
     * {@code p(θ) = c + u·cos θ + v·sin θ}.
     */
    private static void ellipse(final double cx, final double cy, final double cz,
                                final double ux, final double uy, final double uz,
                                final double vx, final double vy, final double vz,
                                final int segments, final LineStyle style, final List<Line> out) {
        final int n = Math.max(8, segments);
        double px = cx + ux;
        double py = cy + uy;
        double pz = cz + uz;
        for (int i = 1; i <= n; i++) {
            final double a = 2 * Math.PI * i / n;
            final double cos = Math.cos(a);
            final double sin = Math.sin(a);
            final double nx = cx + ux * cos + vx * sin;
            final double ny = cy + uy * cos + vy * sin;
            final double nz = cz + uz * cos + vz * sin;
            out.add(new Line(px, py, pz, nx, ny, nz, style));
            px = nx;
            py = ny;
            pz = nz;
        }
    }
}
