package btools.router;

import java.util.ArrayList;
import java.util.List;

import btools.mapaccess.MatchedWaypoint;

/**
 * Result of a greedy round-trip planning attempt.
 * Contains the route, quality metrics, and diagnostic metadata.
 */
public class RoundTripResult {

  private OsmTrack track;
  private List<OsmNodeNamed> loopWaypoints;
  private List<MatchedWaypoint> matchedWaypoints;
  private int totalDistanceMeters;
  private double reusedEdgeRatio;
  private boolean withinTolerance;
  private int attemptsUsed;
  private int subRoutesChosen;
  private final List<String> diagnostics = new ArrayList<>();
  private String fallbackReason;
  private List<OsmTrack> legTracks; // per-leg sub-route tracks from greedy planner

  public OsmTrack getTrack() {
    return track;
  }

  public void setTrack(OsmTrack track) {
    this.track = track;
  }

  public List<OsmNodeNamed> getLoopWaypoints() {
    return loopWaypoints;
  }

  public void setLoopWaypoints(List<OsmNodeNamed> loopWaypoints) {
    this.loopWaypoints = loopWaypoints;
  }

  public List<MatchedWaypoint> getMatchedWaypoints() {
    return matchedWaypoints;
  }

  public void setMatchedWaypoints(List<MatchedWaypoint> matchedWaypoints) {
    this.matchedWaypoints = matchedWaypoints;
  }

  public int getTotalDistanceMeters() {
    return totalDistanceMeters;
  }

  public void setTotalDistanceMeters(int totalDistanceMeters) {
    this.totalDistanceMeters = totalDistanceMeters;
  }

  public double getReusedEdgeRatio() {
    return reusedEdgeRatio;
  }

  public void setReusedEdgeRatio(double reusedEdgeRatio) {
    this.reusedEdgeRatio = reusedEdgeRatio;
  }

  public boolean isWithinTolerance() {
    return withinTolerance;
  }

  public void setWithinTolerance(boolean withinTolerance) {
    this.withinTolerance = withinTolerance;
  }

  public int getAttemptsUsed() {
    return attemptsUsed;
  }

  public void setAttemptsUsed(int attemptsUsed) {
    this.attemptsUsed = attemptsUsed;
  }

  public int getSubRoutesChosen() {
    return subRoutesChosen;
  }

  public void setSubRoutesChosen(int subRoutesChosen) {
    this.subRoutesChosen = subRoutesChosen;
  }

  public List<String> getDiagnostics() {
    return diagnostics;
  }

  public void addDiagnostic(String message) {
    diagnostics.add(message);
  }

  public String getFallbackReason() {
    return fallbackReason;
  }

  public void setFallbackReason(String fallbackReason) {
    this.fallbackReason = fallbackReason;
  }

  public List<OsmTrack> getLegTracks() {
    return legTracks;
  }

  public void setLegTracks(List<OsmTrack> legTracks) {
    this.legTracks = legTracks;
  }
}
