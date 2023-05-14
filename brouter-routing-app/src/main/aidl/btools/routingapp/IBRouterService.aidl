package btools.routingapp;


interface IBRouterService {


    //param params--> Map of params:
    //  "pathToFileResult"-->String with the path to where the result must be saved, including file name and extension
    //                    -->if null, the track is passed via the return argument, this should be default when Android Q or later
    //  "maxRunningTime"-->String with a number of seconds for the routing timeout, default = 60
    //  "turnInstructionFormat"-->String selecting the format for turn-instructions values: osmand, locus
    //  "trackFormat"-->[kml|gpx|json] default = gpx
    //  "lats"-->double[] array of latitudes; 2 values at least.
    //  "lons"-->double[] array of longitudes; 2 values at least.
    //  "nogoLats"-->double[] array of nogo latitudes; may be null.
    //  "nogoLons"-->double[] array of nogo longitudes; may be null.
    //  "nogoRadi"-->double[] array of nogo radius in meters; may be null.
    //  "fast"-->[0|1]
    //  "v"-->[motorcar|bicycle|foot]
    //  "remoteProfile"--> (String), net-content of a profile. If remoteProfile != null, v+fast are ignored
    //
    //  "lonlats"         = lon,lat|... (unlimited list of lon,lat waypoints separated by |)
    //                      variantes: lon,lat,d|... (from this point to the next  do a direct line)
    //                                 lon,lat,name|... (route point has a name and should not be ignored)
    //  "straight"        = idx1,idx2,.. (optional, minimum one value, index of a direct routing point in the waypoint list)
    //  "nogos"           = lon,lat,radius,weight|... (optional, list of lon, lat, radius in meters, weight (optional))
    //  "polylines"       = lon,lat,lon,lat,...,weight|... (unlimited list of lon,lat and weight (optional), lists separated by |)
    //  "polygons"        = lon,lat,lon,lat,...,weight|... (unlimited list of lon,lat and weight (optional), lists separated by |)
    //  "profile"         = profile file name without .brf
    //  "alternativeidx"  = [0|1|2|3] (optional, default 0)
    //  "exportWaypoints" = 1 to export them (optional, default is no export)
    //  "pois"            = lon,lat,name|... (optional)
    //  "extraParams"     = Bundle key=value list for a profile setup (like "profile:")
    //  "timode"          = turnInstructionMode [0=none, 1=auto-choose, 2=locus-style, 3=osmand-style, 4=comment-style, 5=gpsies-style, 6=orux-style, 7=locus-old-style] default 0
    //  "heading"         = angle (optional to give a route a start direction)
    //  "direction"       = angle (optional, used like "heading" on a recalculation request by Locus as start direction)
    //  "engineMode"      = 0 (optional, default 0, 2 = get elevation)

    // return null if all ok and no path given, the track if ok and path given, an error message if it was wrong
    //        the resultas string when 'pathToFileResult' is null, this should be default when Android Q or later
    // call in a background thread, heavy task!

    String getTrackFromParams(in Bundle params);
}
