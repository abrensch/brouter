BRouter - Beta Version 0.9.5 - Using the car-subset datafiles
==========================================================

Starting with version 0.9.5, BRouter supports car-subset
datafiles as a workaround for the memory issue in car routing.

Car-routing, however, is still considered experimental,
mainly due to the fact that turn-restrictions are still
not handled. 

The cause of the memory overflow in long-distance car-routing
is the fact that a large fraction of the ways is not allowed
for cars and that the node-cache-pipe of brouter is not designed
to handle that. So the car-subset datafiles (".cd5") are identical
to the full datafiles (".rd5"), but do not contain the nodes
and ways that are not accessible for cars.

Using the car-subset datafiles, car-routing over longer
distances is possible, and it it somewhat faster than using
the full datafiles.

The car subset datafiles must be stored in a subdirectory
"carsubset" of the "segments2" directory, so a possible
file-system structure looks like this:

  brouter/segments2/E5_N45.rd5
  brouter/segments2/carsubset/E5_N45.cd5

You can download the cd5's from the corresponding URL
just like the rd5's.

Access to the car-subset datafiles is enabled
by assigning the routing-profile variable:

  assign   validForCars        1

as it is done for the "car-test" profile. In that case,
the router first checks for the car-subset file, and,
if it does not exist, uses the full datafile. It is
o.k. to just install the car-subset file without
having the full datafile installed.

If you want to use the car-subset datafile for bike-routing
(because you don't have the full datafile or because you
are using streets only anyhow and want better performance),
just assign the "validForCars" variable in your biking
profile as indicated above.
