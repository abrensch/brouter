package btools.router;

import org.junit.Test;

import static org.junit.Assert.assertThrows;

/**
 * Meta-tests for the loop validator itself: feed deliberately broken synthetic
 * tracks to {@link RoundTripFixture#assertValidLoop} and confirm each defect is
 * actually rejected. Without these, a mis-implemented or vacuous assertion could
 * pass green forever. A clean synthetic loop is the positive control.
 */
public class RoundTripAssertionTest {

  // ~330 m radius polygon centred on Dreieich; consecutive vertices ~170 m apart.
  private static OsmTrack loop(int vertices) {
    OsmTrack t = new OsmTrack();
    double cx = 8.72, cy = 50.0, r = 0.003;
    for (int i = 0; i < vertices; i++) {
      double a = 2 * Math.PI * i / vertices;
      t.nodes.add(node(cx + r * Math.cos(a), cy + r * Math.sin(a)));
    }
    t.nodes.add(t.nodes.get(0)); // close the loop
    return t;
  }

  private static OsmPathElement node(double lon, double lat) {
    return OsmPathElement.create(
      180000000 + (int) (lon * 1e6), 90000000 + (int) (lat * 1e6), (short) 0, null);
  }

  private static VoiceHint hint(int cmd, int indexInTrack, float angle, int roundaboutExit) {
    VoiceHint h = new VoiceHint();
    h.cmd = cmd;
    h.indexInTrack = indexInTrack;
    h.angle = angle;
    h.roundaboutExit = roundaboutExit;
    return h;
  }

  private static void addHint(OsmTrack t, VoiceHint h) {
    t.voiceHints = new VoiceHintList();
    t.voiceHints.list.add(h);
  }

  private static void assertRejected(OsmTrack bad, String why) {
    assertThrows(why + " should be rejected", AssertionError.class,
      () -> RoundTripFixture.assertValidLoop(bad, why, 30.0));
  }

  // ---- positive control: a clean loop (optionally with a valid hint) must pass ----

  @Test
  public void cleanLoopPasses() {
    OsmTrack t = loop(12);
    addHint(t, hint(VoiceHint.TL, 3, -60f, 0));
    RoundTripFixture.assertValidLoop(t, "cleanLoop", 30.0);
  }

  /** A roundabout hint's angle is cumulative rotation and may exceed 180 — must NOT be rejected. */
  @Test
  public void roundaboutAngleOver180Passes() {
    OsmTrack t = loop(12);
    addHint(t, hint(VoiceHint.RNDB, 4, -229f, 1));
    RoundTripFixture.assertValidLoop(t, "roundabout", 30.0);
  }

  // ---- negative controls: each defect must be detected ----

  @Test
  public void degenerateLoopRejected() {
    assertRejected(loop(4), "degenerate (5 nodes)"); // 4 vertices + close = 5 < 10
  }

  @Test
  public void nonClosingLoopRejected() {
    OsmTrack t = loop(12);
    t.nodes.set(t.nodes.size() - 1, node(8.72 + 0.01, 50.0)); // end ~700 m from start
    assertRejected(t, "non-closing");
  }

  @Test
  public void beelineSegmentRejected() {
    OsmTrack t = loop(12);
    t.nodes.set(5, node(8.75, 50.02)); // a multi-km jump to/from this vertex
    assertRejected(t, "beeline");
  }

  @Test
  public void highReuseRejected() {
    // Out-and-back: go out along 7 vertices, then retrace them — ~100% reuse.
    OsmTrack t = new OsmTrack();
    double cx = 8.72, cy = 50.0, r = 0.003;
    for (int i = 0; i <= 6; i++) {
      t.nodes.add(node(cx + r * Math.cos(2 * Math.PI * i / 12), cy + r * Math.sin(2 * Math.PI * i / 12)));
    }
    for (int i = 5; i >= 0; i--) {
      t.nodes.add(node(cx + r * Math.cos(2 * Math.PI * i / 12), cy + r * Math.sin(2 * Math.PI * i / 12)));
    }
    assertRejected(t, "high-reuse out-and-back");
  }

  @Test
  public void voiceHintNegativeIndexRejected() {
    OsmTrack t = loop(12);
    addHint(t, hint(VoiceHint.TL, -1, -60f, 0));
    assertRejected(t, "voice-hint negative index");
  }

  @Test
  public void voiceHintIndexOutOfRangeRejected() {
    OsmTrack t = loop(12);
    addHint(t, hint(VoiceHint.TL, 999, -60f, 0));
    assertRejected(t, "voice-hint index past end");
  }

  @Test
  public void voiceHintBeelineCommandRejected() {
    OsmTrack t = loop(12);
    addHint(t, hint(VoiceHint.BL, 3, 0f, 0));
    assertRejected(t, "beeline (BL) voice hint");
  }

  @Test
  public void voiceHintOutOfRangeAngleRejected() {
    OsmTrack t = loop(12);
    addHint(t, hint(VoiceHint.TL, 3, 200f, 0)); // non-roundabout angle > 180
    assertRejected(t, "out-of-range turn angle");
  }
}
