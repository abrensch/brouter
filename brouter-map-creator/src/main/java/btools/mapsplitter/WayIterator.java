package btools.mapsplitter;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;

/**
 * Iterate over a singe wayfile or a directory
 * of waytiles and feed the ways to the callback listener
 *
 * @author ab
 */
public class WayIterator extends MapCreatorBase
{
  private WayListener listener;

  public WayIterator( WayListener wayListener )
  {
    listener = wayListener;
  }

  public void processFile(File wayfile) throws Exception
  {
    System.out.println( "*** WayIterator reading: " + wayfile );

    listener.wayFileStart( wayfile );

    DataInputStream di = new DataInputStream( new BufferedInputStream ( new FileInputStream( wayfile ) ) );
    try
    {
      for(;;)
      {
        WayData w = new WayData( di );
        listener.nextWay( w );
      }
    }
    catch( EOFException eof )
    {
      di.close();
    }
    listener.wayFileEnd( wayfile );
  }
}
