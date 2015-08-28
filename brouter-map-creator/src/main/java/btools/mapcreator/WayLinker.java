package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.List;

import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import btools.util.ByteArrayUnifier;
import btools.util.ByteDataWriter;
import btools.util.CompactLongMap;
import btools.util.CompactLongSet;
import btools.util.Crc32;
import btools.util.FrozenLongMap;
import btools.util.FrozenLongSet;
import btools.util.LazyArrayOfLists;

/**
 * WayLinker finally puts the pieces together
 * to create the rd5 files. For each 5*5 tile,
 * the corresponding nodefile and wayfile is read,
 * plus the (global) bordernodes file, and an rd5
 * is written
 *
 * @author ab
 */
public class WayLinker extends MapCreatorBase
{
  private File nodeTilesIn;
  private File trafficTilesIn;
  private File dataTilesOut;
  private File borderFileIn;

  private String dataTilesSuffix;

  private boolean readingBorder;

  private CompactLongMap<OsmNodeP> nodesMap;
  private OsmTrafficMap trafficMap;
  private List<OsmNodeP> nodesList;
  private CompactLongSet borderSet;
  private short lookupVersion;
  private short lookupMinorVersion;

  private long creationTimeStamp;
  
  private BExpressionContextWay expctxWay;
  private BExpressionContextNode expctxNode;

  private ByteArrayUnifier abUnifier;
  
  private int minLon;
  private int minLat;
  
  private void reset()
  {
    minLon = -1;
    minLat = -1;
    nodesMap = new CompactLongMap<OsmNodeP>();
    borderSet = new CompactLongSet();
  }
  
  public static void main(String[] args) throws Exception
  {
    System.out.println("*** WayLinker: Format a region of an OSM map for routing");
    if (args.length != 7)
    {
      System.out.println("usage: java WayLinker <node-tiles-in> <way-tiles-in> <bordernodes> <lookup-file> <profile-file> <data-tiles-out> <data-tiles-suffix> ");
      return;
    }
    new WayLinker().process( new File( args[0] ), new File( args[1] ), new File( args[2] ), new File( args[3] ), new File( args[4] ), new File( args[5] ), args[6] );
  }

  public void process( File nodeTilesIn, File wayTilesIn, File borderFileIn, File lookupFile, File profileFile, File dataTilesOut, String dataTilesSuffix ) throws Exception
  {
    this.nodeTilesIn = nodeTilesIn;
    this.trafficTilesIn = new File( "traffic" );
    this.dataTilesOut = dataTilesOut;
    this.borderFileIn = borderFileIn;
    this.dataTilesSuffix = dataTilesSuffix;
    
    BExpressionMetaData meta = new BExpressionMetaData();
    
    // read lookup + profile for lookup-version + access-filter
    expctxWay = new BExpressionContextWay( meta);
    expctxNode = new BExpressionContextNode( meta);
    meta.readMetaData( lookupFile );

    lookupVersion = meta.lookupVersion;
    lookupMinorVersion = meta.lookupMinorVersion;

    expctxWay.parseFile( profileFile, "global" );
    expctxNode.parseFile( profileFile, "global" );
    
    creationTimeStamp = System.currentTimeMillis();

    abUnifier = new ByteArrayUnifier( 16384, false );
    
    // then process all segments    
    new WayIterator( this, true ).processDir( wayTilesIn, ".wt5" );
  }

  @Override
  public void wayFileStart( File wayfile ) throws Exception
  {
    // process corresponding node-file, if any
    File nodeFile = fileFromTemplate( wayfile, nodeTilesIn, "u5d" );
    if ( nodeFile.exists() )
    {
      reset();

      // read the border file
      readingBorder = true;
      new NodeIterator( this, false ).processFile( borderFileIn );
      borderSet = new FrozenLongSet( borderSet );

      // read this tile's nodes
      readingBorder = false;
      new NodeIterator( this, true ).processFile( nodeFile );
    
      // freeze the nodes-map
      FrozenLongMap<OsmNodeP> nodesMapFrozen = new FrozenLongMap<OsmNodeP>( nodesMap );
      nodesMap = nodesMapFrozen;
      nodesList = nodesMapFrozen.getValueList();
    }

    // read a traffic-file, if any
    File trafficFile = fileFromTemplate( wayfile, trafficTilesIn, "trf" );
    if ( trafficFile.exists() )
    {
      trafficMap = new OsmTrafficMap();
      trafficMap.load( trafficFile, minLon, minLat, minLon + 5000000, minLat + 5000000, false );
    }
  }

  @Override
  public void nextNode( NodeData data ) throws Exception
  {
    OsmNodeP n = data.description == null ? new OsmNodeP() : new OsmNodePT(data.description);
    n.ilon = data.ilon;
    n.ilat = data.ilat;
    n.selev = data.selev;

    if ( readingBorder || (!borderSet.contains( data.nid )) )
    {
      nodesMap.fastPut( data.nid, n );
    }

    if ( readingBorder )
    {
      n.bits |= OsmNodeP.BORDER_BIT;
      borderSet.fastAdd( data.nid );
      return;
    }

    // remember the segment coords
    int min_lon = (n.ilon / 5000000 ) * 5000000;
    int min_lat = (n.ilat / 5000000 ) * 5000000;
    if ( minLon == -1 ) minLon = min_lon;
    if ( minLat == -1 ) minLat = min_lat;
    if ( minLat != min_lat || minLon != min_lon )
      throw new IllegalArgumentException( "inconsistent node: " + n.ilon + " " + n.ilat );
  }

  @Override
  public void nextWay( WayData way ) throws Exception
  {
    byte[] description = abUnifier.unify( way.description );
    int lastTraffic = 0;

    // filter according to profile
    expctxWay.evaluate( false, description, null );
    boolean ok = expctxWay.getCostfactor() < 10000.; 
    expctxWay.evaluate( true, description, null );
    ok |= expctxWay.getCostfactor() < 10000.;
    if ( !ok ) return;

    byte wayBits = 0;
    expctxWay.decode( description );
    if ( !expctxWay.getBooleanLookupValue( "bridge" ) ) wayBits |= OsmNodeP.NO_BRIDGE_BIT;
    if ( !expctxWay.getBooleanLookupValue( "tunnel" ) ) wayBits |= OsmNodeP.NO_TUNNEL_BIT;
   
    OsmNodeP n1 = null;
    OsmNodeP n2 = null;
    for (int i=0; i<way.nodes.size(); i++)
    {
      long nid = way.nodes.get(i);
      n1 = n2;
      n2 = nodesMap.get( nid );
      if ( n1 != null && n2 != null && n1 != n2 )
      {
        OsmLinkP link = n2.createLink( n1 );

        int traffic = trafficMap == null ? 0 : trafficMap.getTrafficClass( n1.getIdFromPos(), n2.getIdFromPos() );
        if ( traffic != lastTraffic )
        {
          expctxWay.decode( description );
          expctxWay.addLookupValue( "estimated_traffic_class", traffic == 0 ? 0 : traffic + 1 );
          description = abUnifier.unify( expctxWay.encode() );
          lastTraffic = traffic;
        }
        link.descriptionBitmap = description;
      }
      if ( n2 != null )
      {
        n2.bits |= wayBits;
      }
    }
  }

  @Override
  public void wayFileEnd( File wayfile ) throws Exception
  {
    nodesMap = null;
    borderSet = null;
    trafficMap = null;
    
    byte[] abBuf = new byte[1024*1024];
    byte[] abBuf2 = new byte[10*1024*1024];

    int maxLon = minLon + 5000000;    
    int maxLat = minLat + 5000000;

    // write segment data to individual files
    {
      int nLonSegs = (maxLon - minLon)/1000000;
      int nLatSegs = (maxLat - minLat)/1000000;
      
      // sort the nodes into segments
      LazyArrayOfLists<OsmNodeP> seglists = new LazyArrayOfLists<OsmNodeP>(nLonSegs*nLatSegs);
      for( OsmNodeP n : nodesList )
      {
        if ( n == null || n.getFirstLink() == null || n.isTransferNode() ) continue;
        if ( n.ilon < minLon || n.ilon >= maxLon
          || n.ilat < minLat || n.ilat >= maxLat ) continue;
        int lonIdx = (n.ilon-minLon)/1000000;
        int latIdx = (n.ilat-minLat)/1000000;
        
        int tileIndex = lonIdx * nLatSegs + latIdx;
        seglists.getList(tileIndex).add( n );
      }
      nodesList = null;
      seglists.trimAll();

      // open the output file
      File outfile = fileFromTemplate( wayfile, dataTilesOut, dataTilesSuffix );
      DataOutputStream os = createOutStream( outfile );

      long[] fileIndex = new long[25];
      int[] fileHeaderCrcs = new int[25];

      // write 5*5 index dummy
      for( int i55=0; i55<25; i55++)
      {
        os.writeLong( 0 );
      }
      long filepos = 200L;
      
      // sort further in 1/80-degree squares
      for( int lonIdx = 0; lonIdx < nLonSegs; lonIdx++ )
      {
        for( int latIdx = 0; latIdx < nLatSegs; latIdx++ )
        {
          int tileIndex = lonIdx * nLatSegs + latIdx;
          if ( seglists.getSize(tileIndex) > 0 )
          {
            List<OsmNodeP> nlist = seglists.getList(tileIndex);
            
            LazyArrayOfLists<OsmNodeP> subs = new LazyArrayOfLists<OsmNodeP>(6400);
            byte[][] subByteArrays = new byte[6400][];
            for( int ni=0; ni<nlist.size(); ni++ )
            {
              OsmNodeP n = nlist.get(ni);
              int subLonIdx = (n.ilon - minLon) / 12500 - 80*lonIdx;
              int subLatIdx = (n.ilat - minLat) / 12500 - 80*latIdx;
              int si = subLatIdx*80 + subLonIdx;
              subs.getList(si).add( n );
            }
            subs.trimAll();
            int[] posIdx = new int[6400];
            int pos = 25600;
            for( int si=0; si<6400; si++)
            {
              List<OsmNodeP> subList = subs.getList(si);
              if ( subList.size() > 0 )
              {
                Collections.sort( subList );

                ByteDataWriter dos = new ByteDataWriter( abBuf2 );
                
                dos.writeInt( subList.size() );
                for( int ni=0; ni<subList.size(); ni++ )
                {
                  OsmNodeP n = subList.get(ni);
                  n.writeNodeData( dos, abBuf );
                }
                byte[] subBytes = dos.toByteArray();
                pos += subBytes.length + 4; // reserve 4 bytes for crc
                subByteArrays[si] = subBytes;
              }
              posIdx[si] = pos;
            }

            byte[] abSubIndex = compileSubFileIndex( posIdx );
            fileHeaderCrcs[tileIndex] = Crc32.crc( abSubIndex, 0, abSubIndex.length );
            os.write( abSubIndex, 0, abSubIndex.length );
            for( int si=0; si<6400; si++)
            {
              byte[] ab = subByteArrays[si];
              if ( ab != null )
              {
                os.write( ab );
                os.writeInt( Crc32.crc( ab, 0 , ab.length ) );
              }
            }
            filepos += pos;
          }
          fileIndex[ tileIndex ] = filepos;
        }
      }
      
      byte[] abFileIndex = compileFileIndex( fileIndex, lookupVersion, lookupMinorVersion );
      
      // write extra data: timestamp + index-checksums
      os.writeLong( creationTimeStamp );
      os.writeInt( Crc32.crc( abFileIndex, 0, abFileIndex.length ) );
      for( int i55=0; i55<25; i55++)
      {
        os.writeInt( fileHeaderCrcs[i55] );
      }
      
      os.close();
      
      // re-open random-access to write file-index
      RandomAccessFile ra = new RandomAccessFile( outfile, "rw" );
      ra.write( abFileIndex, 0, abFileIndex.length );
      ra.close();
    }
  }
  
  private byte[] compileFileIndex( long[] fileIndex, short lookupVersion, short lookupMinorVersion ) throws Exception
  {
    ByteArrayOutputStream bos = new ByteArrayOutputStream( );
    DataOutputStream dos = new DataOutputStream( bos );
    for( int i55=0; i55<25; i55++)
    {
      long versionPrefix = i55 == 1 ? lookupMinorVersion : lookupVersion;
      versionPrefix <<= 48;
      dos.writeLong( fileIndex[i55] | versionPrefix );
    }
    dos.close();
    return bos.toByteArray();
  }

  private byte[] compileSubFileIndex( int[] posIdx ) throws Exception
  {
    ByteArrayOutputStream bos = new ByteArrayOutputStream( );
    DataOutputStream dos = new DataOutputStream( bos );
    for( int si=0; si<6400; si++)
    {
      dos.writeInt( posIdx[si] );
    }
    dos.close();
    return bos.toByteArray();
  }
}
