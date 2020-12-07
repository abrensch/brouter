package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;

import btools.util.CompactLongSet;
import btools.util.DiffCoderDataOutputStream;
import btools.util.FrozenLongSet;

/**
 * PosUnifier does 3 steps in map-processing:
 *
 * - unify positions - add srtm elevation data - make a bordernodes file
 * containing net data from the bordernids-file just containing ids
 *
 * @author ab
 */
public class PosUnifier extends MapCreatorBase
{
  private DiffCoderDataOutputStream nodesOutStream;
  private DiffCoderDataOutputStream borderNodesOut;
  private File nodeTilesOut;
  private CompactLongSet[] positionSets;

  private HashMap<String, SrtmRaster> srtmmap;
  private int lastSrtmLonIdx;
  private int lastSrtmLatIdx;
  private SrtmRaster lastSrtmRaster;
  private String srtmdir;

  private CompactLongSet borderNids;

  public static void main( String[] args ) throws Exception
  {
    System.out.println( "*** PosUnifier: Unify position values and enhance elevation" );
    if ( args.length != 5 )
    {
      System.out.println( "usage: java PosUnifier <node-tiles-in> <node-tiles-out> <bordernids-in> <bordernodes-out> <srtm-data-dir>" );
      return;
    }
    new PosUnifier().process( new File( args[0] ), new File( args[1] ), new File( args[2] ), new File( args[3] ), args[4] );
  }

  public void process( File nodeTilesIn, File nodeTilesOut, File bordernidsinfile, File bordernodesoutfile, String srtmdir ) throws Exception
  {
    this.nodeTilesOut = nodeTilesOut;
    this.srtmdir = srtmdir;

    // read border nids set
    DataInputStream dis = createInStream( bordernidsinfile );
    borderNids = new CompactLongSet();
    try
    {
      for ( ;; )
      {
        long nid = readId( dis );
        if ( !borderNids.contains( nid ) )
          borderNids.fastAdd( nid );
      }
    }
    catch (EOFException eof)
    {
      dis.close();
    }
    borderNids = new FrozenLongSet( borderNids );

    // process all files
    borderNodesOut = createOutStream( bordernodesoutfile );
    new NodeIterator( this, true ).processDir( nodeTilesIn, ".n5d" );
    borderNodesOut.close();
  }

  @Override
  public void nodeFileStart( File nodefile ) throws Exception
  {
    resetSrtm();

    nodesOutStream = createOutStream( fileFromTemplate( nodefile, nodeTilesOut, "u5d" ) );

    positionSets = new CompactLongSet[2500];
  }

  @Override
  public void nextNode( NodeData n ) throws Exception
  {
    SrtmRaster srtm = srtmForNode( n.ilon, n.ilat );
    n.selev = srtm == null ? Short.MIN_VALUE : srtm.getElevation( n.ilon, n.ilat );

    findUniquePos( n );

    n.writeTo( nodesOutStream );
    if ( borderNids.contains( n.nid ) )
    {
      n.writeTo( borderNodesOut );
    }
  }

  @Override
  public void nodeFileEnd( File nodeFile ) throws Exception
  {
    nodesOutStream.close();
  }

  private boolean checkAdd( int lon, int lat )
  {
    int slot = ((lon%5000000)/100000)*50 + ((lat%5000000)/100000);    
    long id = ( (long) lon ) << 32 | lat;
    CompactLongSet set = positionSets[slot];
    if ( set == null )
    {
      positionSets[slot] = set = new CompactLongSet();
    }
    if ( !set.contains( id ) )
    {
      set.fastAdd( id );
      return true;
    }
    return false;
  }
      



  private void findUniquePos( NodeData n )
  {
    if ( !checkAdd( n.ilon, n.ilat ) )
    {
      _findUniquePos( n );
    }
  }

  private void _findUniquePos( NodeData n )
  {
    // fix the position for uniqueness
    int lonmod = n.ilon % 1000000;
    int londelta = lonmod < 500000 ? 1 : -1;
    int latmod = n.ilat % 1000000;
    int latdelta = latmod < 500000 ? 1 : -1;
    for ( int latsteps = 0; latsteps < 100; latsteps++ )
    {
      for ( int lonsteps = 0; lonsteps <= latsteps; lonsteps++ )
      {
        int lon = n.ilon + lonsteps * londelta;
        int lat = n.ilat + latsteps * latdelta;
        if ( checkAdd( lon, lat ) )
        {
          n.ilon = lon;
          n.ilat = lat;
          return;
        }
      }
    }
    System.out.println( "*** WARNING: cannot unify position for: " + n.ilon + " " + n.ilat );
  }

  /*
    ilon, ilat are produced like this: see btools.mapcreator.NodeData
    ilat = (int)( ( lat + 90. )*1000000. + 0.5);
    ilon = (int)( ( lon + 180. )*1000000. + 0.5);
  */

  public double doublelon(int ilon){
    return (ilon / 1000000.0) - 180.0;
  }

  public double doublelat(int ilat){
    return (ilat / 1000000.0) - 90.0;
  }

  public int lonIndexHgt(int ilon){
    return indexHgt(doublelon(ilon));
  }

  public int latIndexHgt(int ilat){
    return indexHgt(doublelat(ilat));
  }

  public int indexHgt(double coord){
    if (coord >= 180.0) coord = 179.99999; // north pole 90.0 ignored...
    // adjust for south and west hemispheres
    if(coord < 0.0 && coord% 1.0 != 0.0) coord -= 1.0;
    return (int) coord;
  }

  /**
   * @param srtmLonIdx west bound
   * @param srtmLatIdx south bound
   * @return name of the tile in ".hgt" convention, no suffix
   */
  public String hgtFileName(int srtmLonIdx, int srtmLatIdx){
    String hemiLon = srtmLonIdx >= 0 ? "E": "W";
    String hemilat = srtmLatIdx >= 0 ? "N": "S";
    String lonS = "00" + Math.abs(srtmLonIdx);
    String latS = "0" + Math.abs(srtmLatIdx);
    return hemilat + latS.substring(latS.length() - 2) + hemiLon + lonS.substring(lonS.length() -3);
  }

  // integration tests
  public void setSrtmdir(String dir){
    this.srtmdir = dir;
  }

  public SrtmRaster srtmForNode( int ilon, int ilat ) throws Exception
  {

    int srtmLonIdx = lonIndexHgt(ilon);
    int srtmLatIdx = latIndexHgt(ilat);

    if ( srtmLonIdx == lastSrtmLonIdx && srtmLatIdx == lastSrtmLatIdx )
    {
      return lastSrtmRaster;
    }
    lastSrtmLonIdx = srtmLonIdx;
    lastSrtmLatIdx = srtmLatIdx;

    /*
    filename represents: .zip archive named by the convention of .hgt files, containing arbitrarily named .asc file
    and any number of associated files, e.g. N48E012.zip contains N48E012.asc, N48E012.prj, N48E012.asc.aux.xml
    */
    String filename = hgtFileName(srtmLonIdx, srtmLatIdx);

    lastSrtmRaster = srtmmap.get( filename );
    if ( lastSrtmRaster == null && !srtmmap.containsKey( filename ) )
    {
      File f = new File( new File( srtmdir ), filename + ".bef" );
      System.out.println( "checking: " + f + " ilon=" + ilon + " ilat=" + ilat );
      if ( f.exists() )
      {
        System.out.println( "*** reading: " + f );
        try
        {
          InputStream isc = new BufferedInputStream( new FileInputStream( f ) );
          lastSrtmRaster = new RasterCoder().decodeRaster( isc );
          isc.close();
        }
        catch (Exception e)
        {
          System.out.println( "**** ERROR reading " + f + " ****" );
        }
        srtmmap.put( filename, lastSrtmRaster );
        return lastSrtmRaster;
      }

      f = new File( new File( srtmdir ), filename + ".zip" );
      System.out.println( "reading: " + f + " ilon=" + ilon + " ilat=" + ilat );
      if ( f.exists() )
      {
        try
        {
          lastSrtmRaster = new SrtmData( f ).getRaster();
        }
        catch (Exception e)
        {
          System.out.println( "**** ERROR reading " + f + " ****" );
        }
      }
      srtmmap.put( filename, lastSrtmRaster );
    }
    return lastSrtmRaster;
  }

  public void resetSrtm()
  {
    srtmmap = new HashMap<String, SrtmRaster>();
    lastSrtmLonIdx = -9999;
    lastSrtmLatIdx = -9999;
    lastSrtmRaster = null;
  }

}
