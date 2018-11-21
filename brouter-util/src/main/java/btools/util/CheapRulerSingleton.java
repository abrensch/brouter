package btools.util;

public final class CheapRulerSingleton {
  /**
   * Cheap-Ruler Java implementation
   * See
   * https://blog.mapbox.com/fast-geodesic-approximations-with-cheap-ruler-106f229ad016
   * for more details.
   *
   * Original code is at https://github.com/mapbox/cheap-ruler under ISC license.
   *
   * This is implemented as a Singleton to have a unique cache for the cosine
   * values across all the code.
   */
  private static volatile CheapRulerSingleton instance = null;

  // Conversion constants
  private final static double ILATLNG_TO_LATLNG = 1e-6; // From integer to degrees
  private final static int KILOMETERS_TO_METERS = 1000;
  private final static double DEG_TO_RAD = Math.PI / 180.;

  // Cosine cache constants
  private final static int COS_CACHE_LENGTH = 8192;
  private final static double COS_CACHE_MAX_DEGREES = 90.0;
  // COS_CACHE_LENGTH cached values between 0 and COS_CACHE_MAX_DEGREES degrees.
  double[] COS_CACHE = new double[COS_CACHE_LENGTH];

  /**
   * Helper to build the cache of cosine values.
   */
  private void buildCosCache() {
    double increment = DEG_TO_RAD * COS_CACHE_MAX_DEGREES / COS_CACHE_LENGTH;
    for (int i = 0; i < COS_CACHE_LENGTH; i++) {
      COS_CACHE[i] = Math.cos(i * increment);
    }
  }

  private CheapRulerSingleton() {
    super();
    // Build the cache of cosine values.
    buildCosCache();
  }

  /**
   * Get an instance of this singleton class.
   */
  public final static CheapRulerSingleton getInstance() {
    if (CheapRulerSingleton.instance == null) {
      synchronized(CheapRulerSingleton.class) {
        if (CheapRulerSingleton.instance == null) {
          CheapRulerSingleton.instance = new CheapRulerSingleton();
        }
      }
    }
    return CheapRulerSingleton.instance;
  }

  /**
   * Helper to compute the cosine of an integer latitude.
   */
  private double cosLat(int ilat) {
    double latDegrees = ilat * ILATLNG_TO_LATLNG;
    if (ilat > 90000000) {
        // Use the symmetry of the cosine.
        latDegrees -= 90;
    }
    return COS_CACHE[(int) (latDegrees * COS_CACHE_LENGTH / COS_CACHE_MAX_DEGREES)];
  }

  /**
   * Compute the distance (in meters) between two points represented by their
   * (integer) latitude and longitude.
   *
   * @param ilon1   Integer longitude for the start point. this is (longitude in degrees + 180) * 1e6.
   * @param ilat1   Integer latitude for the start point, this is (latitude + 90) * 1e6.
   * @param ilon2   Integer longitude for the end point, this is (longitude + 180) * 1e6.
   * @param ilat2   Integer latitude for the end point, this is (latitude + 90) * 1e6.
   *
   * @note          Integer longitude is ((longitude in degrees) + 180) * 1e6.
   *                Integer latitude is ((latitude in degrees) + 90) * 1e6.
   */
  public double distance(int ilon1, int ilat1, int ilon2, int ilat2) {
    double cos = cosLat(ilat1);
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
