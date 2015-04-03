/**
 * Set off departure offsets (immutable)
 *
 * @author ab
 */
package btools.memrouter;


public interface OffsetSetHolder
{
	OffsetSet getOffsetSet();
	void setOffsetSet( OffsetSet offsetSet );
}
