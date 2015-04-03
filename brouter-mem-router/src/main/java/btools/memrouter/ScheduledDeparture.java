/**
 * A specific departure
 * (relative to a certain station and line)
 *
 * @author ab
 */
package btools.memrouter;



final class ScheduledDeparture
{
	long waitTime;
	long rideTime;
	OffsetSet offsets;

	@Override
	public String toString()
	{
	  return "wait=" + waitTime + " ride=" + rideTime + " offsets=" + offsets;
	}
}
