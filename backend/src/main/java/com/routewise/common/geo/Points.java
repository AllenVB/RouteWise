package com.routewise.common.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * Builds WGS84 points.
 *
 * <p>JTS takes coordinates as (x, y) — that is (longitude, latitude). Humans and
 * map UIs say "lat, lon", so the argument order is the single most common place
 * to silently put a Bandırma address in the Indian Ocean. This helper exists so
 * the swap happens in exactly one place.
 */
public final class Points {

    public static final int SRID_WGS84 = 4326;

    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    private Points() {}

    /** @param lat enlem, @param lon boylam — insan sırası */
    public static Point of(double lat, double lon) {
        return FACTORY.createPoint(new Coordinate(lon, lat));
    }

    public static double latOf(Point p) {
        return p.getY();
    }

    public static double lonOf(Point p) {
        return p.getX();
    }
}
