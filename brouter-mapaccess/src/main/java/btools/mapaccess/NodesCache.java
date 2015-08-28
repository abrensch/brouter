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

public final class NodesCache
{
  private File segmentDir;
  private File secondarySegmentsDir = null;

  private OsmNodesMap nodesMap;
  private int lookupVersion;
  private int lookupMinorVersion;
  private boolean carMode;
  private boolean forceSecondaryData;
  private String currentFileName;

  private HashMap<String,PhysicalFile> fileCache;
  private byte[] iobuffer;
  
  private OsmFile[][] fileRows;
  private ArrayList<MicroCache> segmentList = new ArrayList<MicroCache>();

  public DistanceChecker distanceChecker;
  
  public boolean oom_carsubset_hint = false;
  public boolean first_file_access_failed = false;
  public String first_file_access_name;

  private long cacheSum = 0;
  private boolean garbageCollectionEnabled = false;
  

  public NodesCache( String segmentDir, OsmNodesMap nodesMap, int lookupVersion, int minorVersion, boolean carMode, boolean forceSecondaryData, NodesCache oldCache )
  {
    this.segmentDir = new File( segmentDir );
    this.nodesMap = nodesMap;
    this.lookupVersion = lookupVersion;
    this.lookupMinorVersion = minorVersion;
    this.carMode = carMode;
    this.forceSecondaryData = forceSecondaryData;

    first_file_access_failed = false;
    first_file_access_name = null;

    if ( !this.segmentDir.isDirectory() ) throw new RuntimeException( "segment directory " + segmentDir + " does not exist" );

    if ( oldCache != null )
    {
      fileCache = oldCache.fileCache;
      iobuffer = oldCache.iobuffer;
      oom_carsubset_hint = oldCache.oom_carsubset_hint;
      secondarySegmentsDir = oldCache.secondarySegmentsDir;

      // re-use old, virgin caches
      fileRows = oldCache.fileRows;
      for( OsmFile[] fileRow : fileRows )
      {
        if ( fileRow == null ) continue;
        for( OsmFile osmf : fileRow )
        {
          cacheSum += osmf.setGhostState();
        }
      }
    }
    else
    {
      fileCache = new HashMap<String,PhysicalFile>(4);
      fileRows = new OsmFile[180][];
      iobuffer = new byte[65636];
      secondarySegmentsDir = StorageConfigHelper.getSecondarySegmentDir( segmentDir );
    }
  }
  
  private File getFileFromSegmentDir( String filename )
  {
    if ( forceSecondaryData )
    {
      return new File( secondarySegmentsDir, filename );
    }
  	
    File f = new File( segmentDir, filename );
    if ( secondarySegmentsDir != null && !f.exists() )
    {
      File f2 = new File( secondarySegmentsDir, filename );
      if ( f2.exists() ) return f2;
    }
    return f;
  }

  // if the cache sum exceeded a threshold,
  // clean all ghosts and enable garbage collection
  private void checkEnableCacheCleaning()
  {
    if ( cacheSum < 200000 || garbageCollectionEnabled ) return;

    for( int i=0; i<fileRows.length; i++ )
    {
      OsmFile[] fileRow = fileRows[i];
      if ( fileRow == null ) continue;
      int nghosts = 0;
      for( OsmFile osmf :  fileRow )
      {
        if ( osmf.ghost ) nghosts++;
        else osmf.cleanAll();
      }
      if ( nghosts == 0 ) continue;
      int j=0;
      OsmFile[] frow = new OsmFile[fileRow.length-nghosts];
      for( OsmFile osmf : fileRow )
      {
        if ( osmf.ghost ) continue;
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
        int lonIdx80 = ilon/12500;
        int latIdx80 = ilat/12500;
        int lonDegree = lonIdx80/80;
        int latDegree = latIdx80/80;
        OsmFile osmf = null;
        OsmFile[] fileRow = fileRows[latDegree];
        int ndegrees = fileRow == null ? 0 : fileRow.length;
        for( int i=0; i<ndegrees; i++ )
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
          OsmFile[] newFileRow = new OsmFile[ndegrees+1];
          for( int i=0; i<ndegrees; i++ )
          {
            newFileRow[i] = fileRow[i];
          }
          newFileRow[ndegrees] = osmf;
          fileRows[latDegree] = newFileRow;
        }
        osmf.ghost = false;
        currentFileName = osmf.filename;
        if ( osmf.microCaches == null )
        {
          return null;
        }
        int subIdx = (latIdx80-80*latDegree)*80 + (lonIdx80-80*lonDegree);
        MicroCache segment = osmf.microCaches[subIdx];
        if ( segment == null )
        {
          // nodesMap.removeCompleteNodes();

          checkEnableCacheCleaning();

          segment = new MicroCache( osmf, lonIdx80, latIdx80, iobuffer );
          cacheSum += segment.getDataSize();
          osmf.microCaches[subIdx] = segment;
          segmentList.add( segment );
        }
        else if ( segment.ghost )
        {
          segment.unGhost();
          segmentList.add( segment );
        }
        return segment;
    }
    catch( RuntimeException re )
    {
      throw re;
    }
    catch( Exception e )
    {
      throw new RuntimeException( "error reading datafile " + currentFileName + ": " + e );
    }
  }


  public boolean obtainNonHollowNode( OsmNode node )
  {
    if ( !node.isHollow() ) return true;

    MicroCache segment = getSegmentFor( node.ilon, node.ilat );
    if ( segment == null )
    {
      return false;
    }
    segment.fillNode( node, nodesMap, distanceChecker, garbageCollectionEnabled );
    return !node.isHollow();
  }

  private OsmFile fileForSegment( int lonDegree, int latDegree ) throws Exception
  {
    int lonMod5 = lonDegree % 5;
    int latMod5 = latDegree % 5;
    int tileIndex = lonMod5 * 5 + latMod5;

    int lon = lonDegree - 180 - lonMod5;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    int lat = latDegree - 90 - latMod5;

    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    String filenameBase = slon + "_" + slat;

    currentFileName = filenameBase + ".rd5/cd5";

    PhysicalFile ra = null;
    if ( !fileCache.containsKey( filenameBase ) )
    {
      File f = null;
      if ( carMode )
      {
        File carFile = getFileFromSegmentDir( "carsubset/" + filenameBase + ".cd5" );
    	if ( carFile.exists() ) f = carFile;
      }
      if ( f == null )
      {
        File fullFile = getFileFromSegmentDir( filenameBase + ".rd5" );
        if ( fullFile.exists() ) f = fullFile;
        if ( carMode && f != null ) oom_carsubset_hint = true;
      }
      if ( f != null )
      {
        currentFileName = f.getName();
        ra = new PhysicalFile( f, iobuffer, lookupVersion, lookupMinorVersion );
      }
      fileCache.put( filenameBase, ra );
    }
    ra = fileCache.get( filenameBase );
    OsmFile osmf = new OsmFile( ra, tileIndex, iobuffer );
    osmf.lonDegree = lonDegree;
    osmf.latDegree = latDegree;

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
    for( MicroCache segment :  segmentList )
    {
      List<OsmNode> positions = segment.getPositions( nodesMap );
      all.addAll( positions );
    }
    return all;
  }


  public void close()
  {
    for( PhysicalFile f: fileCache.values() )
    {
      try
      {
        if ( f != null ) f.ra.close();
      }
      catch( IOException ioe )
      {
        // ignore
      }
    }
  }
}
