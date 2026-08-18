---
parent: Developers
title: Developer Guide
---


### Starting point

A Git account is required to participate in this project. This allows you to create a personal fork of the BRouter project and set up a development branch, which is then cloned to your development PC. From there, a working copy is created for actual development.

There are thus three levels:
- BRouter master

- Personal BRouter clone for the new feature/error handling
  this maintains the connection to the master

- Personal BRouter working copy
  This is the area where the actual work takes place.
  Here you have everything you need for your new feature, including helper classes, extra resources, and more.


#### What does that mean

- the local branch and the working copy have no contact
  Working copy has no connect to git.

- all work is done in the working copy.
  No one is interested to see what you are doing there.

- all testing is done in the working copy.
  No one is interested to see what you are doing there.

- when you find your new routines/changes are fine, copy only the changed or new files to the local branch
  Test if this is compileable by `gradlew clean build`.

- most new features need a test routine as well
  Generate a test for general functionality like test for null, 0, -1, +1, Integer.Max Value something like that, to signal your function is fine.
  Do not add more resources.
  Test if this is compileable by `gradlew clean build`.

- before the final check in for a pull request, check if a doc is needed


#### Some useful rules for a start

- plan the new features as small as possible.
  A discussion before could be helpful.
  Save resources, means: save project space on git, save running actions on git, save time for reviews.

- implement only for your plan
  New ideas can be done later in a new plan.
  You may find errors along your way that have nothing to do with your plan.
  Leave it as it is. May be collect it for a later review and a new plan.

- there are several docs, please have a view into the doc folder

- the main source is in poor Java
  A new feature should follow this way and not add more dependencies.
  If it is unavoidable, please discuss this before adding it.


### Testing

- keep in mind this software is used as a server and as an Android service
  That means it should return on the same input the same output as before when a new feature is added.
  This will not work if the task is to change the output.
  New features normally have new parameter to activate them.

- running `gradlew clean build` in your working copy generates several reports.
  Check them regularly.

- new features must not break backwards compatibility on Android

- new features must not replace existing working functions breaking user applications

- new features must not break / change how existing features work

- new features must work like other routers and old version from day one, as it is what users expect

- if the pull request adds too many new classes, they better be in separate packages for easy maintenance

- BRouter operates as a service: a client sends a request, BRouter responds, and the client proceeds to use the result.
  This means that timing should be monitored whenever the code is modified or extended.

### Using AI

- feel free to use AI for your development

- the description of a new feature should be done by the developer

- you should understand each line the AI generates
  Means you have to review all code before check in.

- keep your AI outside the repository

- check if AI licence meets BRouter [MIT licence](https://github.com/abrensch/brouter/tree/master?tab=MIT-1-ov-file)

### Review

- the review should respect and check this rules

- a final submit of a pull request should be done as squashed commit (collect all commits to one at merge time)
