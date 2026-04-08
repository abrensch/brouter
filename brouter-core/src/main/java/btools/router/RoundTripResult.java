package btools.router;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a greedy round-trip planning attempt.
 * Contains the route, quality metrics, and diagnostic metadata.
 */
public class RoundTripResult {

  private OsmTrack track;
  private int totalDistanceMeters;
  private int totalAscentMeters;
  private double reusedEdgeRatio;
  private boolean withinTolerance;
  private int attemptsUsed;
  private int subRoutesChosen;
  private final List<String> diagnostics = new ArrayList<>();
  private String fallbackReason;

  public OsmTrack getTrack() {
    return track;
  }

  public void setTrack(OsmTrack track) {
    this.track = track;
  }

  public int getTotalDistanceMeters() {
    return totalDistanceMeters;
  }

  public void setTotalDistanceMeters(int totalDistanceMeters) {
    this.totalDistanceMeters = totalDistanceMeters;
  }

  public int getTotalAscentMeters() {
    return totalAscentMeters;
  }

  public void setTotalAscentMeters(int totalAscentMeters) {
    this.totalAscentMeters = totalAscentMeters;
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
}
