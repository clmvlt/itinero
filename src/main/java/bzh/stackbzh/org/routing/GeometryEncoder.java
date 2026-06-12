package bzh.stackbzh.org.routing;

public final class GeometryEncoder {

    public static final int DEFAULT_PRECISION = 5;

    private GeometryEncoder() {
    }

    public static String encodePolyline(double[][] points) {
        return encodePolyline(points, DEFAULT_PRECISION);
    }

    public static String encodePolyline(double[][] points, int precision) {
        if (points == null || points.length == 0) {
            return "";
        }
        long factor = (long) Math.pow(10, precision);
        StringBuilder sb = new StringBuilder();
        long lastLat = 0;
        long lastLon = 0;
        for (double[] p : points) {
            long lat = Math.round(p[0] * factor);
            long lon = Math.round(p[1] * factor);
            encodeValue(lat - lastLat, sb);
            encodeValue(lon - lastLon, sb);
            lastLat = lat;
            lastLon = lon;
        }
        return sb.toString();
    }

    private static void encodeValue(long value, StringBuilder sb) {
        long v = value < 0 ? ~(value << 1) : (value << 1);
        while (v >= 0x20) {
            sb.append((char) ((0x20 | (int) (v & 0x1f)) + 63));
            v >>= 5;
        }
        sb.append((char) ((int) v + 63));
    }
}
