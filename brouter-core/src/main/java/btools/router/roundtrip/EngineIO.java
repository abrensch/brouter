package btools.router.roundtrip;

import btools.router.OsmTrack;

/** Engine seam role: logging and track output. */
public interface EngineIO {

  /** Log line; a no-op in quiet child engines (AUTO candidates). */
  void logInfo(String msg);

  void logException(Throwable t);

  /** Compact form of {@link #logException}. */
  void logThrowable(Throwable t);

  /** Write the adopted track to the engine's configured output. */
  void writeAdoptedTrackOutput(OsmTrack track);

  /** Merge duplicate voice hints (engine-side: needs VoiceHint internals). */
  void consolidateRoundTripVoiceHints(OsmTrack track);
}
