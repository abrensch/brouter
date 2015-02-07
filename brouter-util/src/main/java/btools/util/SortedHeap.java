package btools.util;

import java.util.ArrayList;
import java.util.List;



/**
 * Memory efficient Heap to get the lowest-key value
 * of a set of key-object pairs
 *
 * @author ab
 */
public class SortedHeap<V>
{
  private int[][] al;
  private int[] pa;
  private int[] lp; // the low pointers
  
  private Object[][] vla; // value list array

  protected static final int MAXLISTS = 31; // enough for size Integer.MAX_VALUE

  private int size;
  private boolean isClear = false;
  
  public SortedHeap()
  {
    clear();
  }

  /**
   * @return the lowest key value, or null if none
   */
  public V popLowestKeyValue()
  {
      int minId = 0;
      int minIdx = -1;
      for ( int i=1;; i++ )
      {
    	int[] ali = al[i];
    	if ( ali == null ) break;
        int lpi = lp[i];
        if ( lpi < ali.length )
        {
          int currentId = ali[lpi];
          if ( minIdx < 0 || currentId < minId )
          {
            minIdx = i;
            minId = currentId;
          }
        }
      }
      
      if ( minIdx == -1 ) return null;
      
      int lp_minIdx = lp[minIdx]++;
      Object[] vla_minIdx = vla[minIdx];
      V res =(V)vla_minIdx[lp_minIdx];
      vla_minIdx[lp_minIdx] = null;
      size--;
      return res;
  }

  /**
   * add a key value pair to the heap
   *
   * @param id the key to insert
   * @param value the value to insert object
   */
  public void add( int key, V value )
  {
	isClear = false;
	size++;
	
	// trivial shortcut if first array empty
	if ( lp[1] == 1)
	{
	  al[1][0] = key;
	  vla[1][0] = value;
	  lp[1] = 0;
	  return;
	}
	// trivial shortcut if second array empty
	if ( lp[2] > 0 )
	{
      int[] al2 = al[2];
	  Object[] vla2 = vla[2];
	  int key1;
	  Object val1;
	  if ( lp[2] == 2 )
	  {
	    key1 = al[1][0];
	    val1 = vla[1][0];
		lp[1] = 1;
	  }
	  else // == 1
	  {
	    key1 = al2[1];
	    val1 = vla2[1];
	  }
	  lp[2] = 0;
	  if ( key1 < key )
	  {
	    al2[0] = key1;
	    vla2[0] = val1;
		al2[1] = key;
		vla2[1] = value;
	  }
	  else
	  {
        al2[1] = key1;
		vla2[1] = val1;
		al2[0] = key;
		vla2[0] = value;
	  }
	  return;
	}
	  
    // put the new entry in the first array
    al[0][0] = key;
    vla[0][0] = value;

    pa[0] = 1;
    pa[1] = 1;
    pa[2] = 2;

    // determine the first array big enough to take them all
    int cnt = 4; // value count up to idx
    int idx = 3;
    int n = 4;

    for(;;)
    {
      cnt += n-lp[idx];
      if ( cnt <= n ) break;
      pa[idx++] = n;
      n <<= 1;
    }

    if ( idx == MAXLISTS )
    {
      throw new IllegalArgumentException( "overflow" );
    }
    
    // create it if not existant
    if ( al[idx] == null )
    {
      al[idx] = new int[n];
      vla[idx] = new Object[n];
    }
    
    int[] al_t = al[idx];
    Object[] vla_t = vla[idx];
    int lp_t = lp[idx];
    
    // shift down content if any
    if ( lp_t < n )
    {
        System.arraycopy(al_t, lp_t, al_t, 0, n-lp_t);
        System.arraycopy(vla_t, lp_t, vla_t, 0, n-lp_t);
    }
    lp[idx] = 0;
    pa[idx] = n - lp_t;
    

    // now merge the contents of arrays 0...idx-1 into idx
    while ( cnt > 0 )
    {
      int i=0;
      while( pa[i] == lp[i] )
      {
        i++;
      }
      int maxId = al[i][pa[i]-1];
      int maxIdx = i;

      for ( i++; i<=idx; i++ )
      {
        int p = pa[i];
        if ( p > lp[i] )
        {
          int currentId = al[i][p-1];
          if ( currentId > maxId )
          {
            maxIdx = i;
            maxId = currentId;
          }
        }
      }

      // current maximum found, copy to target array
      --n;
      al[idx][n] = maxId;
      vla[idx][n] = vla[maxIdx][pa[maxIdx]-1];

      --cnt;
      --pa[maxIdx];
    }
    lp[idx] = n;
    while(--idx > 0) lp[idx] = al[idx].length;
  }

  public void clear()
  {
	  if ( !isClear )
	  {
		isClear = true;
		size = 0;
		
	    // pointer array
	    pa = new int[MAXLISTS];

	    lp = new int[MAXLISTS];

	    // allocate key lists
	    al = new int[MAXLISTS][];
	    al[0] = new int[1]; // make the first arrays
	    al[1] = new int[1];
	    al[2] = new int[2];

	    // same for the values
	    vla = new Object[MAXLISTS][];
	    vla[0] = new Object[1];
	    vla[1] = new Object[1];
	    vla[2] = new Object[2];
	    
	    int n = 1;
	    lp[0] = 0;
	    for( int idx=1; idx < MAXLISTS; idx++ )
	    {
	      lp[idx] = n;
	      n <<= 1;
	    }
	  }
  }

  public List<V> getExtract()
  {
	  int div = size / 1000 + 1;
	  
	  ArrayList<V> res = new ArrayList<V>(size / div );
      int cnt = 0;
      for ( int i=1;; i++ )
      {
    	int[] ali = al[i];
    	if ( ali == null ) break;
        int lpi = lp[i];
        Object[] vlai = vla[i];
        int n = ali.length;
        while ( lpi < n )
        {
        	if ( (++cnt) % div == 0 )
        	{
              res.add( (V)vla[i][lpi] );
        	}
        	lpi++;
        }
      }
      return res;
  }

}
