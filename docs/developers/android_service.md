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

### profile parameter

Profile parameters affect the result of a profile.
For the app it is a list of params concatenated by '&'. E.g. extraParams=avoidferry=1&avoidsteps=0
The server calls profile params by a prefix 'profile:'. E.g. ...&profile:avoidferry=1&profile:avoidsteps=0

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
