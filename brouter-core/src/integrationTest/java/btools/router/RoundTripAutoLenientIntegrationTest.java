package btools.router;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * AUTO-competition lenient/strict best-effort adoption on the Dreieich fixture.
 *
 * <p>This is the core lenient-default product behavior and previously had no
 * coverage. When AUTO's candidates all fail a QUALITY-tier check (here: a
 * fastbike loop forced onto path/track — "profile-hostile" surface — on the
 * tiny fixture network) but produce a rideable loop:
 * <ul>
 *   <li><b>lenient (default)</b>: the best-effort loop is adopted and returned
 *       with a {@code Warning:} advisory rather than discarded;</li>
 *   <li><b>strict ({@code roundTripStrictQuality=1})</b>: the same QUALITY
 *       failure is hard-rejected, so AUTO returns no track and a clean error.</li>
 * </ul>
 */
public class RoundTripAutoLenientIntegrationTest {

  @Before
  public void seedPavedClassification() {
    // The hostile-surface QUALITY check only fires for a paved profile; the
    // real engine probes this during setup, seed it for determinism.
    RoundTripQualityGate.putPavedClassificationForTest("fastbike", true);
  }

  /** Run a generated fastbike AUTO round-trip (no algorithm, no vias) that the
   *  fixture forces onto profile-hostile surface — a QUALITY-tier failure. */
  private static RoutingEngine runFastbikeAuto(boolean strict) {
    return RoundTripFixture.engine("fastbike", 90, 1000, rc -> {
      rc.roundTripLength = 6000;
      rc.roundTripStrictQuality = strict;
    });
  }

  private static String infoMessage(OsmTrack track) {
    if (track == null) return null;
    if (track.messageList != null && !track.messageList.isEmpty()) return track.messageList.get(0);
    return track.message;
  }

  @Test
  public void autoLenientlyAdoptsQualityFailedLoopWithWarning() {
    RoutingEngine re = runFastbikeAuto(/*strict*/ false);

    OsmTrack track = re.getFoundTrack();
    // The fixture MUST force a hostile-surface QUALITY failure that the lenient
    // default then adopts with a Warning. Assert (not assume) the precondition,
    // so fixture/profile drift that stops forcing it fails loudly instead of
    // silently skipping this load-bearing test.
    Assert.assertNotNull("AUTO must produce a best-effort track to adopt", track);
    String msg = infoMessage(track);
    Assert.assertTrue("fixture must still yield a lenient QUALITY warning (msg=" + msg + ")",
      msg != null && msg.contains("Warning:"));

    Assert.assertNull("lenient adoption must not set an error", re.getErrorMessage());
    Assert.assertTrue("adopted loop must have positive distance", track.distance > 0);
    Assert.assertTrue("advisory must name the QUALITY issue (profile-hostile or distance ratio): " + msg,
      msg.contains("profile-hostile") || msg.contains("distance ratio"));
    Assert.assertTrue("advisory must point at the strict opt-out: " + msg,
      msg.contains("roundTripStrictQuality=1"));
    Assert.assertTrue("message must show AUTO adopted a best-effort candidate: " + msg,
      msg.contains("AUTO selected"));
  }

  @Test
  public void autoStrictQualitySuppressesAdoptionAndFailsCleanly() {
    RoutingEngine re = runFastbikeAuto(/*strict*/ true);

    // Strict mode: every candidate hard-rejects the QUALITY failure, so the
    // children produce no track and AUTO reports a clean "no acceptable route"
    // error instead of adopting a best-effort loop.
    Assert.assertNull("strict mode must not adopt a QUALITY-failed loop", re.getFoundTrack());
    String err = re.getErrorMessage();
    Assert.assertNotNull("strict failure must carry a clean error", err);
    Assert.assertTrue("error must be the AUTO no-acceptable-route message: " + err,
      err.contains("no acceptable route"));
  }
}
