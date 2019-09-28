Profile developers guide - Technical reference
for BRouter profile scripts
==============================================

The tag-value lookup table
--------------------------

Within the routing data files (rd5), tag information
is encoded in a binary bitstream for the way tags and
the node tags each.

To encode and decode to/from this bitstream, a lookup
table is used that contains all the tags and values
that are considered for encoding.

For each tag there are 2 special values:

- <empty> if the tag is not set or the value is empty
- "unknown" if the value is not contained in the table

Each value can have optional "aliases", these alias
values are encoded into the same binary value as the
associated primary value.

A profile must use the primary value in expressions, as
aliases trigger a parse error.  E.g. if there is a line
in lookups.dat file:

bicycle;0001245560 yes allowed

then a profile must use "bicycle=yes", as "bicycle=allowed"
gives an error.

The numbers in the lookup table are statistical
information on the frequency of the values in the
map of Germany - these are just informational and
are not processed by BRouter.


Context-Separation
------------------

Way-tags and Node-Tags are treated independently,
so there are different sections in the lookup table
as well as in the profile scripts for each context.
The special tags: "---context:way" and "---context:node"
mark the beginning of each section.

An exception from context separation is the node-context,
where variables from the way-context of the originating
way can be accessed using the "way:" prefix. For the
variable nodeaccessgranted there's an additional
legacy-hack to access it as a lookup value without prefix:

  if nodeaccessgranted=yes then ...

while in the general case the prefixed expressions are variables:

  if greater way:costfactor 5 then ...

In the profile scripts there is a third context "global"
which contains global configuration which is shared for
all contexts and is accessible by the routing engine.

The variables from the "global" section in the profile
scripts are read-only visible in the "way" and
"node" sections of the scripts.


Predefined variables in the profile scripts
-------------------------------------------

Some variable names are pre-defined and accessed by
the routing engine:

- for the global section these are:

  - 7 elevation configuration parameters:

    - downhillcost
    - downhillcutoff
    - uphillcost
    - uphillcutoff
    - elevationpenaltybuffer
    - elevationmaxbuffer
    - elevationbufferreduce

  - 3 boolean mode-hint flags

    - validForBikes
    - validForFoot
    - validForCars

  - 2 variables to change the heuristic
    coefficients for the 2 routing passes
    ( <0 disables a routing pass )

   - pass1coefficient
   - pass2coefficient

  - 3 variables to influence the generation of turn-instructions

   - turnInstructionMode      0=none, 1=auto-choose, 2=locus-style, 3=osmand-style
   - turnInstructionCatchingRange  default 40m
   - turnInstructionRoundabouts     default=true=generate explicit roundabout hints

  - variables to modify BRouter behaviour

   - processUnusedTags  ( default is false )
         If an OSM tag is unused within the profile,
         BRouter totally ignores the tag existence.
         Skipping unused tags improves BRouter speed.
         As a side effect, the tag is not even listed
         in the route segment table nor the table exported as CSV.
         Setting it to true/1, Brouter-web Data page will list
         all tags present in the RD5 file.

- for the way section these are

  - turncost
  - initialcost
  - costfactor
  - uphillcostfactor
  - downhillcostfactor
  - nodeaccessgranted
  - initialclassifier
  - priorityclassifier

- for the node section this is just

  - initialcost


The operators of the profile scripts
------------------------------------

The profile scripts use polish notation (operator first).

The "assign" operator is special: it can be used
only on the top level of the expression hierarchy
and has 2 operands:

  assign <variable-name> <expression>

It just assigns the expression value to this
variable (which can be a predefined variable or
any other variable, which in this case is defined
implicitly). The expression can be a complex expression
using other operators.

All other operators can be used recursively to an unlimited
complexity, which means that each operand can be a composed
expression starting with an operator and so on.

All expressions have one of the following basic forms:

  - <numeric value>
  - <numeric variable>
  - <lookup-match>
  - <1-op-operator> <operand>
  - <2-op-operator> <operand> <operand>
  - <3-op-operator> <operand> <operand> <operand>

- A numeric value is just a number, floating point, with "." as
  decimal separator. Boolean values are treated as numbers as well,
  with "0" = false and every nonzero value = true.

- A lookup match has the form <tag-name>=<value>, e.g. highway=primary
  Only the primary values can be used in lookup-matches, not aliases.
  The <empty> value is referred to as an empty string, e.g. access=

- 1 Operand operators are:

  not <boolean expression>

- 2 Operand operators are:

  or       <boolean expression 1> <boolean expression 2>
  and      <boolean expression 1> <boolean expression 2>
  xor      <boolean expression 1> <boolean expression 2>
  multiply <numeric expression 1> <numeric expression 2>
  add      <numeric expression 1> <numeric expression 2>
  sub      <numeric expression 1> <numeric expression 2>
  max      <numeric expression 1> <numeric expression 2>
  min      <numeric expression 1> <numeric expression 2>
  equal    <numeric expression 1> <numeric expression 2>
  greater  <numeric expression 1> <numeric expression 2>
  lesser   <numeric expression 1> <numeric expression 2>

- 3 Operand operators are:

  switch <boolean-expression> <true-expression> <false-expression>

  So the switch expression has a numeric value which is the
  true-expression if the boolean expression is true, the
  false-expression otherwise.


Syntactic Sugar
---------------

To improve the readablity of the profile scripts, some syntactic variations
are possible:

- "if then else" : "if" can be used instead of the "switch" operator, if the
  additional keywords "then" and "else" are placed between the operators:

  if <boolean-expression> then <true-expression> else <false-expression>

- Parentheses: each expression can be surrounded by parentheses: ( <expression> )
  Please note that the profile syntax, due to the polish notation, does not
  need parentheses, they are always optional. However, if there are parentheses,
  the parser checks if they really match the expression boundaries.

- or-ing lookup-matches: the pipe-symbol can be used as a short syntax for
  lookup matches where more than one value is accepted for a key:
  highway=primary|secondary|tertiary

- additional "=" symbol for "assign" operations:
  assign <variable-name> = <expression>

- boolean constants: "true" and "false" can be used instead of 1 and 0

Please note that the tokenizer always expects blank space to separate
symbols and expressions so it is not allowed to place parentheses or
the "=" symbol without separating blank space!


The initial cost classifier
---------------------------

To trigger the addition of the "initialcost", another variable is used:
"initialclassifier" - any change in the value of that variable leads
to adding the value of "initialcost".

Initial cost is used typically for a ferry, where you want to apply
a penalty independent of the length of the ferry line.

Another useful case may be an initial cost for bicycle mounting/dismounting,
having set an initialclassifier for ways without bicycle access, with high initialcost.
For backward compatibility, if "initialclassifier" = 0, it is replaced
by the costfactor.


The priority classifier
-----------------------

Priorityclassifier is a BRouter numerical parameter
calculated for ways and used for generation of pictogram/voice navigation instructions.

Higher values means the more significant (noticeable) way,
as far as it can be predicted from OSM data.

To avoid a navigation instruction flood, it was decided
that the instructions are provided only if:

1/ You are supposed to turn at a crossroad/junction
    and some other ways having the same or higher Priorityclassifier value.
OR
2/ You are supposed to go straight ahead
    and some other ways having the higher Priorityclassifier value.


The elevation buffer ( From Poutnik's glossary )
------------------------------------------------

With related 3 internal BRouter variables:
    - elevationpenaltybuffer
    - elevationmaxbuffer
    - elevationbufferreduce

the Elevation Buffer is BRouter feature to filter elevation noise along the route.
It may be real, or caused by the artefacts of used SRTM elevation data.

From every elevation change is at the first place cut out amount 10*up/downhillcutoff
per every km of the way length. What remains, starts to accumulate in the buffer.
IF cutoff demand of elevation per length is not saturated from incoming elevation,
it is applied on elevation remaining in the buffer as well.

E.g. if the way climbs 20 m along 500 m, and uphillcutoff=3.0, then 10*3.0*0.5 = 15 m
is taken away and only remaining 5 m accumulates. But if it climbed only 10 m
on those 500m, all 10 m would be "swallowed" by cutoff,
together with up to 5 m from the buffer, if there were any.

When elevation does not fit the buffer of size elevationmaxbuffer,
it is converted by up/downhillcost ratio to Elevationcost portion of Equivalentlength.
Up/downhillcostfactors are used, if defined, otherwise CostFactor is used.

elevationpenaltybuffer is BRouter variable, with default value 5(m).
   The variable value is used for 2 purposes:
   With the buffer content > elevationpenaltybuffer, it starts partially convert
   the buffered elevation to ElevationCost by Up/downhillcost, with elevation taken
        = MIN (Buffer - elevationpenaltybuffer, WayLength[km] * elevationbufferreduce*10
   The Up/downhillcost factor takes place instead of costfactor at the percentage of
   how much is WayLength[km] * elevationbufferreduce*10 is saturated
   by the buffer content above elevationpenaltybuffer.

elevationmaxbuffer - default 10(m) - is the size of the buffer, above which
   all elevation is converted to Elevationcost by Up/Downhillcost ratio,
   and - if defined - Up/downhillcostfactor fully replaces Costfactor
   in way cost calculation.

elevationbufferreduce - default 0(slope%)- is rate of conversion of the buffer content
   above elevationpenaltybuffer to ElevationCost. For a way of length L,
   the amount of converted elevation is L[km] * elevationbufferreduce[%] * 10.
   The elevation to Elevationcost conversion ratio is given by Up/downhillcost.

Example:
   Let's examine steady slopes with elevationmaxbuffer=10, elevationpenaltybuffer=5,
   elevationbufferreduce=0.5, cutoffs=1.5, Up/downhillcosts=60.

   All slopes within 0 .. 1.5% are swallowed by the cutoff.

   For slope 1.75%, there will remain 0.25%.
       That saturates the elevationbufferreduce 0.5% by 50%. That gives Way cost
       to be calculated 50% from costfactor and 50% from Up/downhillcostfactor.
       Additionally, 0.25% gives 2.5m per 1km, converted to 2.5*60 = 150m of Elevationcost.

   For slope 2.0%, there will remain 0.5%.
       That saturates the elevationbufferreduce 0.5% by 100%. That gives Way cost
       to be calculated fully from Up/downhillcostfactor. Additionally,
       0.5% gives 5m per 1km, converted to 5*60 = 300m of Elevationcost.
       Up to slope 2.0% the buffer value stays at 5m = elevationpenaltybuffer.

   For slope 2.5%, there will remain 1.0% after cutoff subtract,
       and 0.5% after the buffer reduce subtract. The remaining 0.5% accumulates in the buffer
       by rate 5 m/km. When the buffer is full (elevationmaxbuffer),
       the elevation transforms to elevationcost by full rate of 1.0%, i.e. 10 m/km,
       giving elevationcost 10*60=600 m/km.


Technical constraints
---------------------

- The costfactor is required to be >= 1, otherwise the cost-cutoff
  logic of the routing algorithm does not work and you get wrong results.

- The profile should be able to find a route with an average costfactor
  not very much larger than one, because otherwise the routing algorithm
  will not find a reasonable cost-cutoff, leading to a very large
  search area and thus to long processing times.

- Forbidden ways or nodes must be treated as very high cost, because
  there is no "forbidden" value. Technically, values >= 10000 for a
  (way-)costfactor, and >= 1000000 for a nodes "initalcost" are treated
  as infinity, so please use these as the "forbidden" values.

- Ways with costfactor >= 10000 are considered as if they did not exist at all.

- Ways with costfactor = 9999 are considered as
      if they did not exist during route calculation,
      but the navigation hint generator takes them into account.


Developing and debugging scripts
--------------------------------

For developing scripts, the "brouter-web" web-application is your
friend. You can use that either online at https://brouter.de/brouter-web
or set up a local installation.

BRouter-Web has a window at the lower left corner with a "Profile"
and a "Data" tab. Here, you can upload profile scripts and see
the individual cost calculations per way-section in the "Data"-tab.

For profile debugging activate "assign processUnusedTags = true"
to see all present OSM tags on the Data tab, not just those used in the tested profile.


Lookup-Table evolution and the the "major" and "minor" versions
---------------------------------------------------------------

The lookup-table is allowed to grow over time, to include more tags
and values as needed. To support that evolution, it carries a major
and a minor version number. These numbers are also encoded into
the routing data files, taken from the lookups.dat that is used
to pre-process the routing data files.

A major version change is considered to always break compatibility
between the routing datafiles and the lookup table.

A minor version change keeps the routing data files and the lookup-table
compatible in both directions, using the following rules:

- if the data contains a key that is not contained in the lookup
  tables, it is ignored

- if the data contains a value that is not contained in the lookup
  tables (but its key is known) that value is treated as "unknown"

- if a profile uses a key that is not present in the data,
  it sees empty (=unset) values for that key

- if a profile uses a value that is not present in the data,
  lookup matches for that value are always false.

For a minor version change it is required that tags are only
appended at the end of the table (or replace one of the dummy
tags located between the way-tags and the relation pseudo-tags),
and that values are only appended at the end of the value lists.
This is because the routing data files address tags and values
by their sequence numbers, so changing sequences would produce
garbage data.


Other resources
---------------

See https://github.com/poutnikl/Brouter-profiles/wiki/Glossary
as a complementary source about various profile internals.

