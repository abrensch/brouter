package btools.mapdecoder;

public class OsmNode extends OsmObject
{
  public int ilon;
  public int ilat;

  public boolean inBBox( int z, int x, int y )
  {
    int shift = 28-z;
    int x0 = x << shift;
    int x1 = (x+1) << shift;
    int y0 = y << shift;
    int y1 = (y+1) << shift;
    boolean outofbox = x1 < ilon || x0 >= ilon  || y1 < ilat || y0 >= ilat;
    return !outofbox;
  }

  public static double gudermannian(double y)
  {
    return Math.atan(Math.sinh(y)) * (180. / Math.PI);
  }
 
  public double getLon()
  {
    return (((double)ilon)/( 1L << 27 ) - 1.)*180.;
  }
  
  public double getLat()
  {
    double y = (1. - ((double)ilat)/( 1L << 27 ))*Math.PI;
    return gudermannian(y);
  }
}
