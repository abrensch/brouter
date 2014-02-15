package btools.mapcreator;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import btools.util.DenseLongMap;
import btools.util.TinyDenseLongMap;

/**
 * NodeFilter does 1 step in map-processing:
 *
 * - filters out unused nodes according to the way file
 *
 * @author ab
 */
public class NodeFilter extends MapCreatorBase
{
  private DataOutputStream nodesOutStream;
  private File nodeTilesOut;
  protected DenseLongMap nodebitmap;

  public static void main(String[] args) throws Exception
  {
    System.out.println("*** NodeFilter: Filter way related nodes");
    if (args.length != 3)
    {
      System.out.println("usage: java NodeFilter <node-tiles-in> <way-file-in> <node-tiles-out>" );
      return;
    }

    new NodeFilter().process( new File( args[0] ), new File( args[1] ), new File( args[2] ) );
  }

  public void process( File nodeTilesIn, File wayFileIn, File nodeTilesOut ) throws Exception
  {
    this.nodeTilesOut = nodeTilesOut;

    // read the wayfile into a bitmap of used nodes
    nodebitmap = Boolean.getBoolean( "useDenseMaps" ) ? new DenseLongMap( 1 ) : new TinyDenseLongMap();
    new WayIterator( this, false ).processFile( wayFileIn );

    // finally filter all node files
    new NodeIterator( this, true ).processDir( nodeTilesIn, ".tls" );
  }

  @Override
  public void nextWay( WayData data ) throws Exception
  {
    int nnodes = data.nodes.size();
    for (int i=0; i<nnodes; i++ )
    {
      nodebitmap.put( data.nodes.get(i), 0 );
    }
  }

  @Override
  public void nodeFileStart( File nodefile ) throws Exception
  {
    String filename = nodefile.getName();
    filename = filename.substring( 0, filename.length() - 3 ) + "tlf"; 
    File outfile = new File( nodeTilesOut, filename );
    nodesOutStream = new DataOutputStream( new BufferedOutputStream ( new FileOutputStream( outfile ) ) );
  }

  @Override
  public void nextNode( NodeData n ) throws Exception
  {
    // check if node passes bitmap
    if ( nodebitmap.getInt( n.nid ) == 0 ) // 0 -> bit set, -1 -> unset
    {
      n.writeTo( nodesOutStream );
    }
  }

  @Override
  public void nodeFileEnd( File nodeFile ) throws Exception
  {
    nodesOutStream.close();
  }
}
