package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;

import btools.util.CompactLongSet;
import btools.util.CompactLongMap;
import btools.util.FrozenLongSet;
import btools.util.FrozenLongMap;

/**
 * PosUnifier does 3 steps in map-processing:
 *
 * - unify positions
 * - add srtm elevation data
 * - make a bordernodes file containing net data
 *   from the bordernids-file just containing ids
 *
 * @author ab
 */
public class PosUnifier extends MapCreatorBase
{
  private DataOutputStream nodesOutStream;
  private DataOutputStream borderNodesOut;
  private File nodeTilesOut;
  private CompactLongSet positionSet;

  private HashMap<String,SrtmData> srtmmap ;
  private int lastStrmLonIdx;
  private int lastStrmLatIdx;
  private SrtmData lastSrtmData;
  private String srtmdir;

  private int totalLatSteps = 0;
  private int totalLonSteps = 0;

  private CompactLongSet borderNids;


  public static void main(String[] args) throws Exception
  {
    System.out.println("*** PosUnifier: Unify position values and enhance elevation");
    if (args.length != 5)
    {
      System.out.println("usage: java PosUnifier <node-tiles-in> <node-tiles-out> <bordernids-in> <bordernodes-out> <strm-data-dir>" );
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
      for(;;)
      {
        long nid = readId( dis );
        if ( !borderNids.contains( nid ) ) borderNids.fastAdd( nid );
      }
    }
    catch( EOFException eof )
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

    positionSet = new CompactLongSet();
  }

  @Override
  public void nextNode( NodeData n ) throws Exception
  {
    SrtmData srtm = srtmForNode( n.ilon, n.ilat );
    n.selev = srtm == null ? Short.MIN_VALUE : srtm.getElevation( n.ilon, n.ilat);

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

  private void findUniquePos( NodeData n )
  {
    // fix the position for uniqueness
    int lonmod = n.ilon % 1000000;
    int londelta = lonmod < 500000 ? 1 : -1;
    int latmod = n.ilat % 1000000;
    int latdelta = latmod < 500000 ? 1 : -1;
    for(int latsteps = 0; latsteps < 100; latsteps++)
    {
      for(int lonsteps = 0; lonsteps <= latsteps; lonsteps++)
      {
        int lon = n.ilon + lonsteps*londelta;
        int lat = n.ilat + latsteps*latdelta;
        long pid = ((long)lon)<<32 | lat; // id from position
        if ( !positionSet.contains( pid ) )
        {
          totalLonSteps += lonsteps;
          totalLatSteps += latsteps;
          positionSet.fastAdd( pid );
          n.ilon = lon;
          n.ilat = lat;
          return;
        }
      }
    }
    System.out.println( "*** WARNING: cannot unify position for: " + n.ilon + " " + n.ilat );
  }


  /**
   * get the srtm data set for a position
   * srtm coords are srtm_<srtmLon>_<srtmLat>
   * where srtmLon = 180 + lon, srtmLat = 60 - lat
   */
  private SrtmData srtmForNode( int ilon, int ilat ) throws Exception
  {
    int srtmLonIdx = (ilon+5000000)/5000000;
    int srtmLatIdx = (154999999-ilat)/5000000;

    if ( srtmLatIdx < 1 || srtmLatIdx > 24 || srtmLonIdx < 1 || srtmLonIdx > 72 )
    {
      return null;
    }
    if ( srtmLonIdx == lastStrmLonIdx && srtmLatIdx == lastStrmLatIdx )
    {
      return lastSrtmData;
    }
    lastStrmLonIdx = srtmLonIdx;
    lastStrmLatIdx = srtmLatIdx;

    StringBuilder sb = new StringBuilder( 16 );
    sb.append( "srtm_" );
    sb.append( (char)('0' + srtmLonIdx/10 ) ).append( (char)('0' + srtmLonIdx%10 ) ).append( '_' );
    sb.append( (char)('0' + srtmLatIdx/10 ) ).append( (char)('0' + srtmLatIdx%10 ) ).append( ".zip" );
    String filename = sb.toString();


    lastSrtmData = srtmmap.get( filename );
    if ( lastSrtmData == null && !srtmmap.containsKey( filename ) )
    {
      File f = new File( new File( srtmdir ), filename );
      System.out.println( "reading: " + f + " ilon=" + ilon + " ilat=" + ilat );
      if ( f.exists() )
      {
          try
          {
            lastSrtmData = new SrtmData( f );
          }
          catch( Exception e )
          {
              System.out.println( "**** ERROR reading " + f + " ****" );
          }
      }
      srtmmap.put( filename, lastSrtmData );
    }
    return lastSrtmData;
  }

  private void resetSrtm()
  {
    srtmmap = new HashMap<String,SrtmData>();
    lastStrmLonIdx = -1;
    lastStrmLatIdx = -1;
    lastSrtmData = null;
  }

}
