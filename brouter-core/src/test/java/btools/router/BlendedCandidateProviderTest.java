package btools.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class BlendedCandidateProviderTest {

  @Test
  public void blendsIsoWithGraphNativeProviderInOrder() {
    List<IsoCandidate> raw = new ArrayList<>();
    raw.add(new IsoCandidate(180014000, 50000000, 0, 1000, 1200, 0, 5, 100));
    IsochroneCandidateProvider iso = IsochroneCandidateProvider.fromPool(5000, 0, raw);

    RoundTripCandidateProvider graphNative = new SingleCandidateProvider(180028000, 50000000);
    BlendedCandidateProvider blended = new BlendedCandidateProvider(iso, graphNative);

    List<RoundTripCandidateProvider.CandidatePoint> candidates = blended.candidatesForStep(
      180000000, 50000000, 1000,
      1, 4,
      180000000, 50000000,
      0, null);

    Assert.assertEquals(2, candidates.size());
    Assert.assertEquals("iso candidate is kept first", 180014000, candidates.get(0).ilon);
    Assert.assertEquals("graph-native fallback candidate follows", 180028000, candidates.get(1).ilon);
  }

  @Test
  public void isoProviderDoesNotExposeStartCenteredTrackAsStepLeg() {
    OsmTrack startCenteredTrack = new OsmTrack();
    List<IsoCandidate> raw = new ArrayList<>();
    raw.add(new IsoCandidate(180014000, 50000000, 0, 1000, 1200, 0, 5, 100,
      startCenteredTrack));
    IsochroneCandidateProvider iso = IsochroneCandidateProvider.fromPool(5000, 0, raw);

    List<RoundTripCandidateProvider.CandidatePoint> candidates = iso.candidatesForStep(
      180000000, 50000000, 1000,
      2, 4,
      180000000, 50000000,
      0, null);

    Assert.assertEquals(1, candidates.size());
    Assert.assertNull("start-centered ISO path is not a current-step leg",
      candidates.get(0).routedTrack);
  }

  @Test
  public void emptyIsoYieldsGraphNativeOnly() {
    IsochroneCandidateProvider emptyIso =
      IsochroneCandidateProvider.fromPool(5000, 0, new ArrayList<>());
    BlendedCandidateProvider blended = new BlendedCandidateProvider(
      emptyIso, new SingleCandidateProvider(180028000, 50000000));

    List<RoundTripCandidateProvider.CandidatePoint> c = blended.candidatesForStep(
      180000000, 50000000, 1000, 1, 4, 180000000, 50000000, 0, null);

    Assert.assertEquals(1, c.size());
    Assert.assertEquals("only the graph-native candidate survives", 180028000, c.get(0).ilon);
  }

  @Test
  public void emptyGraphNativeYieldsIsoOnly() {
    List<IsoCandidate> raw = new ArrayList<>();
    raw.add(new IsoCandidate(180014000, 50000000, 0, 1000, 1200, 0, 5, 100));
    IsochroneCandidateProvider iso = IsochroneCandidateProvider.fromPool(5000, 0, raw);
    BlendedCandidateProvider blended = new BlendedCandidateProvider(iso, new EmptyProvider());

    List<RoundTripCandidateProvider.CandidatePoint> c = blended.candidatesForStep(
      180000000, 50000000, 1000, 1, 4, 180000000, 50000000, 0, null);

    Assert.assertEquals(1, c.size());
    Assert.assertEquals("only the iso candidate survives", 180014000, c.get(0).ilon);
  }

  @Test
  public void bothEmptyYieldsEmpty() {
    IsochroneCandidateProvider emptyIso =
      IsochroneCandidateProvider.fromPool(5000, 0, new ArrayList<>());
    BlendedCandidateProvider blended = new BlendedCandidateProvider(emptyIso, new EmptyProvider());

    List<RoundTripCandidateProvider.CandidatePoint> c = blended.candidatesForStep(
      180000000, 50000000, 1000, 1, 4, 180000000, 50000000, 0, null);

    Assert.assertTrue("both providers empty → no candidates", c.isEmpty());
  }

  private static final class EmptyProvider implements RoundTripCandidateProvider {
    @Override
    public List<CandidatePoint> candidatesForStep(
      int fromIlon, int fromIlat, double airRadius,
      int step, int totalSteps,
      int startIlon, int startIlat,
      double startDirection,
      OsmTrack refTrack) {
      return Collections.emptyList();
    }
  }

  private static final class SingleCandidateProvider implements RoundTripCandidateProvider {
    private final int ilon;
    private final int ilat;

    SingleCandidateProvider(int ilon, int ilat) {
      this.ilon = ilon;
      this.ilat = ilat;
    }

    @Override
    public List<CandidatePoint> candidatesForStep(
      int fromIlon, int fromIlat, double airRadius,
      int step, int totalSteps,
      int startIlon, int startIlat,
      double startDirection,
      OsmTrack refTrack) {
      CandidatePoint cp = new CandidatePoint();
      cp.ilon = ilon;
      cp.ilat = ilat;
      cp.bearing = 90;
      return Collections.singletonList(cp);
    }
  }
}
