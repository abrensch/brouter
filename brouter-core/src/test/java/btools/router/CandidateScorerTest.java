package btools.router;

import org.junit.Test;

import static org.junit.Assert.*;

public class CandidateScorerTest {

  private final CandidateScorer scorer = new CandidateScorer();

  @Test
  public void closerCandidateScoresBetter() {
    double closeScore = scorer.score(
      2000, 2000,  // candidate exactly at target
      0, 8000, 10000,  // total=0, return=8000, desired=10000
      90, DirectionPreference.ANY,
      1, 5,
      0.0, 5000, 5000);

    double farScore = scorer.score(
      3500, 2000,  // candidate 75% over target
      0, 8000, 10000,
      90, DirectionPreference.ANY,
      1, 5,
      0.0, 5000, 5000);

    assertTrue("Closer candidate should score lower (better)", closeScore < farScore);
  }

  @Test
  public void directionPenaltyFadesAfterStep2() {
    double step1Score = scorer.directionScore(180, DirectionPreference.N, 1);
    double step2Score = scorer.directionScore(180, DirectionPreference.N, 2);
    double step3Score = scorer.directionScore(180, DirectionPreference.N, 3);

    assertTrue("Step 1 should have full direction penalty", step1Score > 0);
    assertTrue("Step 2 should have half direction penalty", step2Score > 0);
    assertEquals("Step 3 should have zero direction penalty", 0.0, step3Score, 0.001);
    assertTrue("Step 1 penalty > step 2 penalty", step1Score > step2Score);
  }

  @Test
  public void directionAnyProducesZeroPenalty() {
    double score = scorer.directionScore(45, DirectionPreference.ANY, 1);
    assertEquals(0.0, score, 0.001);
  }

  @Test
  public void alignedDirectionScoresBetter() {
    // Heading north when preference is north
    double alignedScore = scorer.directionScore(0, DirectionPreference.N, 1);
    // Heading south when preference is north
    double oppositeScore = scorer.directionScore(180, DirectionPreference.N, 1);

    assertTrue("Aligned direction should score lower", alignedScore < oppositeScore);
  }

  @Test
  public void visitedEdgePenaltyIncreasesScore() {
    double noReuse = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.0, 5000, 5000);

    double highReuse = scorer.score(
      2000, 2000, 0, 8000, 10000,
      90, DirectionPreference.ANY, 1, 5,
      0.8, 5000, 5000);

    assertTrue("High edge reuse should increase score (worse)", highReuse > noReuse);
  }

  @Test
  public void spreadPenaltyEarlyPhase() {
    // Early phase (step 1 of 5): staying close to start is bad
    double closeToStart = scorer.spreadPenalty(500, 5000, 1, 5);
    double farFromStart = scorer.spreadPenalty(4500, 5000, 1, 5);

    assertTrue("Close to start in early phase should be penalized more", closeToStart > farFromStart);
  }

  @Test
  public void spreadPenaltyLatePhase() {
    // Late phase (step 4 of 5): being far from start is bad
    double closeToStart = scorer.spreadPenalty(500, 5000, 4, 5);
    double farFromStart = scorer.spreadPenalty(4500, 5000, 4, 5);

    assertTrue("Far from start in late phase should be penalized more", farFromStart > closeToStart);
  }

  @Test
  public void loopFeasibilityPrefersCloserToDesired() {
    // Projected total = 0 + 2000 + 8000 = 10000 (exactly desired)
    double perfect = scorer.loopFeasibilityScore(0, 2000, 8000, 10000);
    // Projected total = 0 + 2000 + 12000 = 14000 (40% over)
    double tooLong = scorer.loopFeasibilityScore(0, 2000, 12000, 10000);

    assertTrue("Perfect loop feasibility should score lower", perfect < tooLong);
    assertEquals("Perfect feasibility should be zero", 0.0, perfect, 0.001);
  }

  @Test
  public void distanceScoreSymmetric() {
    double over = scorer.distanceScore(2500, 2000);
    double under = scorer.distanceScore(1500, 2000);
    assertEquals("Equal over/under deviation should score equally", over, under, 0.001);
  }

  @Test
  public void spreadPenaltySmoothTransition() {
    // The blend should be smooth across the transition zone (phase 0.4 → 0.8).
    // No abrupt jumps between consecutive steps.
    double prev = scorer.spreadPenalty(4000, 5000, 1, 5);
    for (int step = 2; step <= 5; step++) {
      double current = scorer.spreadPenalty(4000, 5000, step, 5);
      double jump = Math.abs(current - prev);
      assertTrue("Step " + (step - 1) + "→" + step + " jump=" + jump + " should be < 0.5",
        jump < 0.5);
      prev = current;
    }
  }
}
