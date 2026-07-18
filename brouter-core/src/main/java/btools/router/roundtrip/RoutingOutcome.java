package btools.router.roundtrip;

import btools.router.OsmTrack;

/**
 * Outcome of one engine routing-pipeline run ({@code doRouting}): the result
 * track and the engine error, read right after the run. The round-trip
 * track-XOR-error contract is enforced at final publication, not here.
 */
public final class RoutingOutcome {

  public final OsmTrack track;
  public final String error;

  public RoutingOutcome(OsmTrack track, String error) {
    this.track = track;
    this.error = error;
  }
}
