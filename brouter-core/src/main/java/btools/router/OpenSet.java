/**
 * Implementation for the open-set
 * that should be somewhat faster
 * and memory-efficient than the original
 * version based on java.util.TreeSet
 * 
 * It relies on the two double-linked
 * lists implemented in OsmPath
 *
 * @author ab
 */
package btools.router;

import btools.mapaccess.OsmNode;

public class OpenSet
{
  private OsmPath start = new OsmPath();
  private OsmPath index2 = new OsmPath();
  
  private int addCount = 0;

  private int size = 0;
  
  public void clear()
  {
    start.nextInSet = null;
    start.nextInIndexSet = null;
    index2.nextInIndexSet = null;
    size = 0;
    addCount = 0;
  }

  public void add( OsmPath path )
  {
	int ac = path.adjustedCost;
	OsmPath p1 = index2;

	// fast forward along index2
	while( p1.nextInIndexSet != null && p1.nextInIndexSet.adjustedCost < ac )
    {
      p1 = p1.nextInIndexSet;
    }
    if ( p1 == index2 )
    {
      p1 = start;
    }

    // search using index1
	for(;;)
    {
	  if ( p1.nextInIndexSet != null && p1.nextInIndexSet.adjustedCost < ac )
	  {
        p1 = p1.nextInIndexSet;
	  }
	  else if ( p1.nextInSet != null && p1.nextInSet.adjustedCost < ac )
	  {
        p1 = p1.nextInSet;
	  }
	  else
	  {
	    break;
	  }
    }
    OsmPath p2 = p1.nextInSet;

    p1.nextInSet = path;
	path.prevInSet = p1;
	path.nextInSet = p2;
	if ( p2 != null ) { p2.prevInSet = path; }
	size++;

	addCount++;
	
	// feed random samples to the indices
	if ( (addCount & 31) == 0 )
	{
	  addIndex( path, start );
	}
	else if ( (addCount & 1023) == 1023 )
	{
	  addIndex( path, index2 );
	}
  }

  public void remove( OsmPath path )
  {
	OsmPath p1 = path.prevInSet;
	OsmPath p2 = path.nextInSet;
	if ( p1 == null )
	{
	  return; // not in set
	}
	path.prevInSet = null;
	path.nextInSet = null;
	if ( p2 != null )
	{
      p2.prevInSet = p1;
	}
	p1.nextInSet = p2;
	
	removeIndex( path );

	size--;
  }

  public OsmPath first()
  {
    return start.nextInSet;
  }

  public int size()
  {
    return size;
  }

  public int[] getExtract()
  {
	  int div = size / 1000 + 1;
	  
      int[] res =  new int[size/div * 2];
      int i = 0;
      int cnt = 0;
      for( OsmPath p = start.nextInSet; p != null; p = p.nextInSet )
      {
    	if ( (++cnt) % div == 0 )
    	{
          OsmNode n = p.getLink().targetNode;
          res[i++] = n.ilon;
          res[i++] = n.ilat;
    	}
      }
      return res;
  }

  // index operations

  private void addIndex( OsmPath path, OsmPath index )
  {
	int ac = path.adjustedCost;
	OsmPath p1 = index;
	OsmPath p2 = p1.nextInIndexSet;
	while( p2 != null && p2.adjustedCost < ac )
	{
	  p1 = p2;
      p2 = p2.nextInIndexSet;
	}
	p1.nextInIndexSet = path;
	path.prevInIndexSet = p1;
	path.nextInIndexSet = p2;
	if ( p2 != null ) { p2.prevInIndexSet = path; }
  }


  private void removeIndex( OsmPath path )
  {
	OsmPath p1 = path.prevInIndexSet;
	OsmPath p2 = path.nextInIndexSet;
	if ( p1 == null )
	{
	  return; // not in set
	}
	path.prevInIndexSet = null;
	path.nextInIndexSet = null;
	if ( p2 != null )
	{
      p2.prevInIndexSet = p1;
	}
	p1.nextInIndexSet = p2;
  }

}
