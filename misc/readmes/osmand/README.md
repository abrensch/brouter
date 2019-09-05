Using BRouter on Android with OSMAnd
====================================

BRouter integration in OSMAnd changed a lot during the summer of 2019. This
guide assumes you are using the BRouter Android app in version 1.5.0 or higher
as well as OSMAnd in version 3.4 or higher.


## Installing BRouter app on your Android device

First, install the BRouter app on your Android device from
[F-Droid](https://f-droid.org/packages/btools.routingapp) or [Google Play
Store](https://play.google.com/store/apps/details?id=btools.routingapp). You
can also build the BRouter Android app yourself.

<a href="https://f-droid.org/packages/btools.routingapp" target="_blank">
<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="90"/></a>
<a href="https://play.google.com/store/apps/details?id=btools.routingapp" target="_blank">
<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="90"/></a>


## Downloading BRouter segments

Then, launch the BRouter app and choose "Download Manager". Zoom in and select
the areas you want to route in. Then click "Start Download" and BRouter will
start downloading the [segments](http://brouter.de/brouter/segments4/) files
for the selected areas.

<img src="./brouter-main.png" alt="Main menu of BRouter android app"/>

<img src="./brouter-grid.png" alt="Grid selection of segments to download"/>

Note that you will have to repeat this step periodically, whenever you want to have an
updated version of the OSM data used for the routing.


## Selecting profiles to use

Once this is done, start again the BRouter app and choose the "BRouter App"
entry on the main menu. Select the routing profile you want to use and click
"Server-Mode". Then, tick the boxes for the routing modes you want to use this
profile for. You can use two different profiles per transportation mode, which
will be mapped to the "shortest" and "fastest" presets (these are just
labelling) in OSMAnd.

<img src="./brouter-profiles.png" alt="Profiles selection"/>

<img src="./brouter-profiles-summary.png" alt="Profiles selection summary"/>


## Configure OSMAnd to make use of BRouter offline navigation

You can now create an "Application profile" in OSMAnd which will be using
BRouter for offline routing. Go to Settings -> Application profiles -> Add and
create a new profile based on the base profile of your choice (cycling here,
for bicycle routing), with a custom name of your choice ("BRouter" on the
screenshot below) and making use of "BRouter (offline)" for navigation.

<img src="./brouter-osmand.png" alt="BRouter configuration in OSMAnd
application profiles"/>

The BRouter app should be launched before OSMAnd for this specific entry to
appear in OSMAnd. Therefore, if you cannot find "BRouter (offline)" navigation
option, you should force quit OSMAnd and restart it.
