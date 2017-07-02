package btools.mapsplitter;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;

/**
 * Iterate over a relation file
 *
 * @author ab
 */
public class RelationIterator extends MapCreatorBase
{
  private RelationListener listener;

  public RelationIterator( RelationListener relationListener )
  {
    listener = relationListener;
  }

  public void processFile(File relationfile) throws Exception
  {
    System.out.println( "*** RelationIterator reading: " + relationfile );

    listener.relationFileStart( relationfile );

    DataInputStream di = new DataInputStream( new BufferedInputStream ( new FileInputStream( relationfile ) ) );
    try
    {
      for(;;)
      {
        RelationData r = new RelationData( di );
        listener.nextRelation( r );
      }
    }
    catch( EOFException eof )
    {
      di.close();
    }
    listener.relationFileEnd( relationfile );
  }
}
