---
parent: Developers
---

# Android Service

BRouter exposes an [Android
Service](https://developer.android.com/guide/components/services) which can be
used by other applications to calculate routes. See `IBRouterService.aidl` for
the interface definition.


## Some words on the input rules (app and server)

We have some parts of input:

### lonlats

The lonlats parameter is a list of positions where the routing should go along. It is recommended to use this instead of the two parameter lons and lats.

When there are more than two points the 'via' points may be off the perfect route - in lower zoom level it is not always clear if a point meets the best way.

The profile parameter 'correctMisplacedViaPoints' tries to avoid this situation.

On the other hand, it would be fatal if this point is not reached when you want to go there.
There are to choices to manage that:
- add a poi to the 'pois' list
- name the point in lonlats list

Another feature of BRouter is routing via beelines.
Define a straight starting point in the 'lonlats' list with a 'd' (direct). The second point needs no declaration.

This contradicts the naming rules in 'lonlats'. If the point is to be given a name, the router parameter 'straight' can be used instead and filled with the index of the point.

'nogos', 'polylines' and 'polygons' are also lists of positions.
Please note: when they have a parameter 'weight' the result is not an absolute nogo it is weighted to the other ways.

### routing parameter

This parameters are needed to tell BRouter what to do.

### using profiles

For calulation BRouter uses a set of rules defined in a profile. See description of profile [rules](https://github.com/abrensch/brouter/blob/master/docs/developers/profile_developers_guide.md).

Here we talk about how we let BRouter know witch profile to use.
There are three ways:

1. use the parameter 'v' and 'fast'
```
   "v"-->[motorcar|bicycle|foot]
   "fast"-->[0|1]
   This enables BRouter to look into the file serviceconfig.dat.
   In there BRouter find the profile associated for e.g bicyle_fast trekking
   This could be changed by the user calling the BRouter app server-mode.
```

2. use the profile parameter
```
   profile=trekking
   It needs an available file in the BRouter profile folder e.g. trekking.brf
```

3. use a remote profile
```
   remoteProfile=a long string with routing rules
   This is saved in BRouter profile folder temporary with the file name 'remote.brf'
```


### profile parameter

Profile parameters affect the result of a profile.
The variables inside a profile predefine a value e.g. avoidsteps=1
A parameter call gives the chance to change this start value without changing the profile e.g. avoidsteps=0
For the app it is a list of params concatenated by '&'. E.g. extraParams=avoidferry=1&avoidsteps=0
The server calls profile params by a prefix 'profile:'. E.g. ...&profile:avoidferry=1&profile:avoidsteps=0

By using this parameter logic, there is no need to edit a profile before sending.

### using profile parameter inside an app

To be flexible it is possible to send a profile to BRouter - server or app.

Another variant is to send parameters for an existing profile that are different from the original profile.

With the version  1.7.1 it is possible to collect parameters from the profile.
The variable parameters are defined like this
```
assign avoid_path            = false  # %avoid_path% | Set to true to avoid pathes | boolean
```
You probably know that from the web client, it builds an option dialog for this.
Now you could do that with an calling app.

What to do to get it work?

- First copy the [RoutingParam](brouter-routing-app/src/main/java/btools/routingapp/RoutingParam.java) class to your source - use the same name and package name.
- Second analyze the profile for which you need the parameter.
  This [BRouter routine](https://github.com/abrensch/brouter/blob/086503e529da7c044cc0f88f86c394fdb574d6cf/brouter-routing-app/src/main/java/btools/routingapp/RoutingParameterDialog.java#L103) can do that, just copy it to your source to use it in your app.
  It builds a List<RoutingParam> you could send to BRouter app.
- You find the call of BRouter app in comment at [RoutingParameterDialog](https://github.com/abrensch/brouter/blob/086503e529da7c044cc0f88f86c394fdb574d6cf/brouter-routing-app/src/main/java/btools/routingapp/RoutingParameterDialog.java#L33)


### silent app call

The app can be started from other apps by using a call like this

```
Intent intent = new Intent();
intent.setClassName("btools.routingapp", "btools.routingapp.BRouterActivity");
intent.putExtra("runsilent", true);
startActivity(intent);
```

This suppress the first question after installation for the BRouter path, generates the BRouter folders in main space  and starts the download dialog.

### silent app call

The app can be started from other apps by using a call like this

```
Intent intent = new Intent();
intent.setClassName("btools.routingapp", "btools.routingapp.BRouterActivity");
intent.putExtra("runsilent", true);
startActivity(intent);
```

This suppress the first question after installation for the BRouter path, generates the BRouter folders in main space  and starts the download dialog.

## other routing engine modes in app

### get elevation

"engineMode=2" allows a client to only request an elevation for a point. This can be restricted with "waypointCatchingRange".
