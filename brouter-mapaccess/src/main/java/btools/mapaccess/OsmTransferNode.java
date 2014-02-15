/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess;



public final class OsmTransferNode
{
 /**
   * The description bitmap is mainly the way description
   * used to calculate the costfactor
   */
  public long descriptionBitmap;

  public OsmTransferNode next;

  public int ilon;
  public int ilat;
  public short selev;

  private static final int BIT_DESC = 1;
  private static final int BIT_ILONHIGH = 2;
  private static final int BIT_ILATHIGH = 4;
  private static final int BIT_STOP = 8;

  // encode this transfer-node into a byte array
  public static byte[] encode( OsmTransferNode tn )
  {
      long currentDesc = 0;
      int currentILonHigh = 0;
      int currentILatHigh = 0;
      OsmTransferNode n = tn;
      
      // first loop to calc size
      int size = 1; // stop-bit
      
      while( n != null )
      {
        if( n.descriptionBitmap != currentDesc )
        {
          size += 8;
          currentDesc = n.descriptionBitmap;
        }
        if( ( n.ilon >> 16 ) != currentILonHigh )
        {
          size += 2;
          currentILonHigh = n.ilon >> 16;
        }
        if( (n.ilat >> 16) != currentILatHigh )
        {
          size += 2;
          currentILatHigh = n.ilat >> 16;
        }
        size += 7;
        n = n.next;
      }
      
      byte[] ab = new byte[size];
      ByteDataWriter os = new ByteDataWriter( ab );
      
      currentDesc = 0;
      currentILonHigh = 0;
      currentILatHigh = 0;
      n = tn;
      while( n != null )
      {
        int mode = 0;
        if( n.descriptionBitmap != currentDesc )
        {
          mode |= BIT_DESC;
          currentDesc = n.descriptionBitmap;
        }
        if( ( n.ilon >> 16 ) != currentILonHigh )
        {
          mode |= BIT_ILONHIGH;
          currentILonHigh = n.ilon >> 16;
        }
        if( (n.ilat >> 16) != currentILatHigh )
        {
          mode |= BIT_ILATHIGH;
          currentILatHigh = n.ilat >> 16;
        }
        os.writeByte( mode);
        if ( (mode & BIT_DESC) != 0 ) os.writeLong( currentDesc );
        if ( (mode & BIT_ILONHIGH) != 0 ) os.writeShort( currentILonHigh );
        if ( (mode & BIT_ILATHIGH) != 0 ) os.writeShort( currentILatHigh );
        os.writeShort( n.ilon );
        os.writeShort( n.ilat );
        os.writeShort( n.selev );
        n = n.next;
      }
      os.writeByte( BIT_STOP );
      return ab;
  }

  // decode a transfer-node from a byte array
  public static OsmTransferNode decode( byte[] ab )
  {
      ByteDataReader is = new ByteDataReader( ab );

      OsmTransferNode firstNode = null;
      OsmTransferNode lastNode = null;
      long currentDesc = 0;
      int currentILonHigh = 0;
      int currentILatHigh = 0;
      for(;;)
      {
        byte mode = is.readByte();
        if ( (mode & BIT_STOP ) != 0 ) break;

        OsmTransferNode n = new OsmTransferNode();
        if ( (mode & BIT_DESC) != 0 ) currentDesc = is.readLong();
        if ( (mode & BIT_ILONHIGH) != 0 ) currentILonHigh =  is.readShort();
        if ( (mode & BIT_ILATHIGH) != 0 ) currentILatHigh =  is.readShort();
        n.descriptionBitmap = currentDesc;
        int ilon = is.readShort() & 0xffff; ilon |= currentILonHigh << 16;
        int ilat = is.readShort() & 0xffff; ilat |= currentILatHigh << 16;
        n.ilon = ilon;
        n.ilat = ilat;
        n.selev = is.readShort();

        if ( ilon != n.ilon ) System.out.println( "ilon=" + ilon + " n.ilon=" + n.ilon );
        if ( ilat != n.ilat ) System.out.println( "ilat=" + ilat + " n.ilat=" + n.ilat );

        if ( lastNode != null )
        {
          lastNode.next = n;
        }
        else
        {
          firstNode = n;
        }
        lastNode = n;
      }
      return firstNode;
  }

}
