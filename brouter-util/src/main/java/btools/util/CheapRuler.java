package btools.util;

public final class CheapRuler {
  /**
   * Cheap-Ruler Java implementation
   * See
   * https://blog.mapbox.com/fast-geodesic-approximations-with-cheap-ruler-106f229ad016
   * for more details.
   *
   * Original code is at https://github.com/mapbox/cheap-ruler under ISC license.
   */
  static int KILOMETERS_TO_METERS = 1000;
  static double ILATLNG_TO_LATLNG = 1e-6;
  static double DEG_TO_RAD = Math.PI / 180.;

  /*
   * @param ilon1   Integer longitude for the start point. this is (longitude in degrees + 180) * 1e6.
   * @param ilat1   Integer latitude for the start point, this is (latitude + 90) * 1e6.
   * @param ilon2   Integer longitude for the end point, this is (longitude + 180) * 1e6.
   * @param ilat2   Integer latitude for the end point, this is (latitude + 90) * 1e6.
   *
   * @note          Integer longitude is ((longitude in degrees) + 180) * 1e6.
   *                Integer latitude is ((latitude in degrees) + 90) * 1e6.
   */
  static public double distance(int ilon1, int ilat1, int ilon2, int ilat2) {
    double lat1 = ilat1 * ILATLNG_TO_LATLNG - 90.; // Real latitude, in degrees
    double cos = Math.cos(lat1 * DEG_TO_RAD);
    double cos2 = 2 * cos * cos - 1;
    double cos3 = 2 * cos * cos2 - cos;
    double cos4 = 2 * cos * cos3 - cos2;
    double cos5 = 2 * cos * cos4 - cos3;

    // Multipliers for converting integer longitude and latitude into distance
    // (http://1.usa.gov/1Wb1bv7)
    double kx = (111.41513 * cos - 0.09455 * cos3 + 0.00012 * cos5) * ILATLNG_TO_LATLNG * KILOMETERS_TO_METERS;
    double ky = (111.13209 - 0.56605 * cos2 + 0.0012 * cos4) * ILATLNG_TO_LATLNG * KILOMETERS_TO_METERS;

    double dlat = (ilat1 - ilat2) * ky;
    double dlon = (ilon1 - ilon2) * kx;
    return Math.sqrt(dlat * dlat + dlon * dlon); // in m
  }
}
