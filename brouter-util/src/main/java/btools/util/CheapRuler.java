package btools.util;

public final class CheapRuler {
  /**
   * Cheap-Ruler Java implementation
   * See
   * https://blog.mapbox.com/fast-geodesic-approximations-with-cheap-ruler-106f229ad016
   * for more details.
   * <p>
   * Original code is at https://github.com/mapbox/cheap-ruler under ISC license.
   * <p>
   * This is implemented as a Singleton to have a unique cache for the cosine
   * values across all the code.
   */
  // Conversion constants
  public final static double ILATLNG_TO_LATLNG = 1e-6; // From integer to degrees
  public final static int KILOMETERS_TO_METERS = 1000;
  public final static double DEG_TO_RAD = Math.PI / 180.;

  // Scale cache constants
  private final static int SCALE_CACHE_LENGTH = 1800;
  private final static int SCALE_CACHE_INCREMENT = 100000;
  // SCALE_CACHE_LENGTH cached values between 0 and COS_CACHE_MAX_DEGREES degrees.
  private final static double[][] SCALE_CACHE = new double[SCALE_CACHE_LENGTH][];

  /**
   * build the cache of cosine values.
   */
  static {
    for (int i = 0; i < SCALE_CACHE_LENGTH; i++) {
      SCALE_CACHE[i] = calcKxKyFromILat(i * SCALE_CACHE_INCREMENT + SCALE_CACHE_INCREMENT / 2);
    }
  }

  private static double[] calcKxKyFromILat(int ilat) {
    double lat = DEG_TO_RAD * (ilat * ILATLNG_TO_LATLNG - 90);
    double cos = Math.cos(lat);
    double cos2 = 2 * cos * cos - 1;
    double cos3 = 2 * cos * cos2 - cos;
    double cos4 = 2 * cos * cos3 - cos2;
    double cos5 = 2 * cos * cos4 - cos3;

    // Multipliers for converting integer longitude and latitude into distance
    // (http://1.usa.gov/1Wb1bv7)
    double[] kxky = new double[2];
    kxky[0] = (111.41513 * cos - 0.09455 * cos3 + 0.00012 * cos5) * ILATLNG_TO_LATLNG * KILOMETERS_TO_METERS;
    kxky[1] = (111.13209 - 0.56605 * cos2 + 0.0012 * cos4) * ILATLNG_TO_LATLNG * KILOMETERS_TO_METERS;
    return kxky;
  }

  /**
   * Calculate the degree-&gt;meter scale for given latitude
   *
   * @return [lon-&gt;meter,lat-&gt;meter]
   */
  public static double[] getLonLatToMeterScales(int ilat) {
    return SCALE_CACHE[ilat / SCALE_CACHE_INCREMENT];
  }

  /**
   * Compute the distance (in meters) between two points represented by their
   * (integer) latitude and longitude.
   *
   * @param ilon1 Integer longitude for the start point. this is (longitude in degrees + 180) * 1e6.
   * @param ilat1 Integer latitude for the start point, this is (latitude + 90) * 1e6.
   * @param ilon2 Integer longitude for the end point, this is (longitude + 180) * 1e6.
   * @param ilat2 Integer latitude for the end point, this is (latitude + 90) * 1e6.
   * @return The distance between the two points, in meters.
   * <p>
   * Note:
   * Integer longitude is ((longitude in degrees) + 180) * 1e6.
   * Integer latitude is ((latitude in degrees) + 90) * 1e6.
   */
  public static double distance(int ilon1, int ilat1, int ilon2, int ilat2) {
    double[] kxky = getLonLatToMeterScales((ilat1 + ilat2) >> 1);
    double dlon = (ilon1 - ilon2) * kxky[0];
    double dlat = (ilat1 - ilat2) * kxky[1];
    return Math.sqrt(dlat * dlat + dlon * dlon); // in m
  }

  public static int[] destination(int lon1, int lat1, double distance, double angle) {

    double[] lonlat2m = getLonLatToMeterScales(lat1);
    double lon2m = lonlat2m[0];
    double lat2m = lonlat2m[1];
    angle = 90. - angle;
    double st = Math.sin(angle * Math.PI / 180.);
    double ct = Math.cos(angle * Math.PI / 180.);

    int lon2 = (int) (0.5 + lon1 + ct * distance / lon2m);
    int lat2 = (int) (0.5 + lat1 + st * distance / lat2m);
    int[] ret = new int[2];
    ret[0] = lon2;
    ret[1] = lat2;
    return ret;
  }
}
