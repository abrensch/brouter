/**
 * Efficient cache or osmnodes
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import btools.codec.DataBuffers;
import btools.codec.MicroCache;
import btools.codec.WaypointMatcher;
import btools.expressions.BExpressionContextWay;

public final class NodesCache
{
  private File segmentDir;
  private File secondarySegmentsDir = null;

  private OsmNodesMap nodesMap;
  private BExpressionContextWay expCtxWay;
  private int lookupVersion;
  private int lookupMinorVersion;
  private boolean carMode;
  private boolean forceSecondaryData;
  private String currentFileName;

  private HashMap<String, PhysicalFile> fileCache;
  private DataBuffers dataBuffers;

  private OsmFile[][] fileRows;
  private ArrayList<MicroCache> segmentList = new ArrayList<MicroCache>();

  public DistanceChecker distanceChecker;

  public WaypointMatcher waypointMatcher;

  public boolean first_file_access_failed = false;
  public String first_file_access_name;

  private long cacheSum = 0;
  private boolean garbageCollectionEnabled = false;

  public NodesCache( String segmentDir, OsmNodesMap nodesMap, BExpressionContextWay ctxWay, boolean carMode, boolean forceSecondaryData,
      NodesCache oldCache )
  {
    this.segmentDir = new File( segmentDir );
    this.nodesMap = nodesMap;
    this.expCtxWay = ctxWay;
    this.lookupVersion = ctxWay.meta.lookupVersion;
    this.lookupMinorVersion = ctxWay.meta.lookupMinorVersion;
    this.carMode = carMode;
    this.forceSecondaryData = forceSecondaryData;

    first_file_access_failed = false;
    first_file_access_name = null;

    if ( !this.segmentDir.isDirectory() )
      throw new RuntimeException( "segment directory " + segmentDir + " does not exist" );

    if ( oldCache != null )
    {
      fileCache = oldCache.fileCache;
      dataBuffers = oldCache.dataBuffers;
      secondarySegmentsDir = oldCache.secondarySegmentsDir;

      // re-use old, virgin caches
      fileRows = oldCache.fileRows;
      for ( OsmFile[] fileRow : fileRows )
      {
        if ( fileRow == null )
          continue;
        for ( OsmFile osmf : fileRow )
        {
          cacheSum += osmf.setGhostState();
        }
      }
    }
    else
    {
      fileCache = new HashMap<String, PhysicalFile>( 4 );
      fileRows = new OsmFile[180][];
      dataBuffers = new DataBuffers();
      secondarySegmentsDir = StorageConfigHelper.getSecondarySegmentDir( segmentDir );
    }
  }

  // if the cache sum exceeded a threshold,
  // clean all ghosts and enable garbage collection
  private void checkEnableCacheCleaning()
  {
    if ( cacheSum < 500000 || garbageCollectionEnabled )
      return;

    for ( int i = 0; i < fileRows.length; i++ )
    {
      OsmFile[] fileRow = fileRows[i];
      if ( fileRow == null )
        continue;
      int nghosts = 0;
      for ( OsmFile osmf : fileRow )
      {
        if ( osmf.ghost )
          nghosts++;
        else
          osmf.cleanAll();
      }
      if ( nghosts == 0 )
        continue;
      int j = 0;
      OsmFile[] frow = new OsmFile[fileRow.length - nghosts];
      for ( OsmFile osmf : fileRow )
      {
        if ( osmf.ghost )
          continue;
        frow[j++] = osmf;
      }
      fileRows[i] = frow;
    }
    garbageCollectionEnabled = true;
  }

  public int loadSegmentFor( int ilon, int ilat )
  {
    MicroCache mc = getSegmentFor( ilon, ilat );
    return mc == null ? 0 : mc.getSize();
  }

  public MicroCache getSegmentFor( int ilon, int ilat )
  {
    try
    {
      int lonDegree = ilon / 1000000;
      int latDegree = ilat / 1000000;
      OsmFile osmf = null;
      OsmFile[] fileRow = fileRows[latDegree];
      int ndegrees = fileRow == null ? 0 : fileRow.length;
      for ( int i = 0; i < ndegrees; i++ )
      {
        if ( fileRow[i].lonDegree == lonDegree )
        {
          osmf = fileRow[i];
          break;
        }
      }
      if ( osmf == null )
      {
        osmf = fileForSegment( lonDegree, latDegree );
        OsmFile[] newFileRow = new OsmFile[ndegrees + 1];
        for ( int i = 0; i < ndegrees; i++ )
        {
          newFileRow[i] = fileRow[i];
        }
        newFileRow[ndegrees] = osmf;
        fileRows[latDegree] = newFileRow;
      }
      osmf.ghost = false;
      currentFileName = osmf.filename;

      if ( !osmf.hasData() )
      {
        return null;
      }

      MicroCache segment = osmf.getMicroCache( ilon, ilat );
      if ( segment == null )
      {
        checkEnableCacheCleaning();
        segment = osmf.createMicroCache( ilon, ilat, dataBuffers, expCtxWay, waypointMatcher );

        cacheSum += segment.getDataSize();
        if ( segment.getSize() > 0 )
        {
          segmentList.add( segment );
        }
      }
      else if ( segment.ghost )
      {
        segment.unGhost();
        if ( segment.getSize() > 0 )
        {
          segmentList.add( segment );
        }
      }
      return segment;
    }
    catch (RuntimeException re)
    {
      throw re;
    }
    catch (Exception e)
    {
      throw new RuntimeException( "error reading datafile " + currentFileName + ": ", e );
    }
  }

  public boolean obtainNonHollowNode( OsmNode node )
  {
    if ( !node.isHollow() )
      return true;

    MicroCache segment = getSegmentFor( node.ilon, node.ilat );
    if ( segment == null )
    {
      return false;
    }

    long id = node.getIdFromPos();
    if ( segment.getAndClear( id ) )
    {
      node.parseNodeBody( segment, nodesMap, distanceChecker );
    }

    if ( garbageCollectionEnabled ) // garbage collection
    {
      segment.collect( segment.getSize() >> 1 );
    }

    return !node.isHollow();
  }

  private OsmFile fileForSegment( int lonDegree, int latDegree ) throws Exception
  {
    int lonMod5 = lonDegree % 5;
    int latMod5 = latDegree % 5;

    int lon = lonDegree - 180 - lonMod5;
    String slon = lon < 0 ? "W" + ( -lon ) : "E" + lon;
    int lat = latDegree - 90 - latMod5;

    String slat = lat < 0 ? "S" + ( -lat ) : "N" + lat;
    String filenameBase = slon + "_" + slat;

    currentFileName = filenameBase + ".rd5/cd5";

    PhysicalFile ra = null;
    if ( !fileCache.containsKey( filenameBase ) )
    {
      File f = null;
      if ( !forceSecondaryData )
      {
        File primary = new File( segmentDir, filenameBase + ".rd5" );
        if ( primary .exists() )
        {
          f = primary;
        }
      }
      if ( f == null )
      {
        if ( carMode ) // look for carsubset-files only in secondary (primaries are now good for car-mode)
        {
          File carFile = new File( secondarySegmentsDir, "carsubset/" + filenameBase + ".cd5" );
          if ( carFile.exists() )
          {
            f = carFile;
          }
        }
        if ( f == null )
        {
          File secondary = new File( secondarySegmentsDir, filenameBase + ".rd5" );
          if ( secondary.exists() )
          {
            f = secondary;
          }
        }
      }
      if ( f != null )
      {
        currentFileName = f.getName();
        ra = new PhysicalFile( f, dataBuffers, lookupVersion, lookupMinorVersion );
      }
      fileCache.put( filenameBase, ra );
    }
    ra = fileCache.get( filenameBase );
    OsmFile osmf = new OsmFile( ra, lonDegree, latDegree, dataBuffers );

    if ( first_file_access_name == null )
    {
      first_file_access_name = currentFileName;
      first_file_access_failed = osmf.filename == null;
    }

    return osmf;
  }

  public List<OsmNode> getAllNodes()
  {
    List<OsmNode> all = new ArrayList<OsmNode>();
    for ( MicroCache segment : segmentList )
    {
      ArrayList<OsmNode> positions = new ArrayList<OsmNode>();
      int size = segment.getSize();

      for ( int i = 0; i < size; i++ )
      {
        long id = segment.getIdForIndex( i );
        OsmNode n = new OsmNode( id );
        n.setHollow();
        nodesMap.put( n );
        positions.add( n );
      }
      all.addAll( positions );
    }
    return all;
  }

  public void close()
  {
    for ( PhysicalFile f : fileCache.values() )
    {
      try
      {
        if ( f != null )
          f.ra.close();
      }
      catch (IOException ioe)
      {
        // ignore
      }
    }
  }
}
