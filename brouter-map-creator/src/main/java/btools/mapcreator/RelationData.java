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

import btools.util.*;

/**
 * Container for relation data on the preprocessor level
 *
 * @author ab
 */
public class RelationData extends MapCreatorBase
{
  public long rid;
  public long description;
  public LongList ways;

  public RelationData( long id )
  {
    rid = id;
    ways = new LongList( 16 );
  }

  public RelationData( long id, LongList ways )
  {
    rid = id;
    this.ways = ways;
  }
}
