Import new tags for noise, green and water feature


- A PostgreSQL and a osm2pgsql installation is needed
  see https://www.postgresql.org/
  and https://osm2pgsql.org/

- and a jdbc driver
  see https://jdbc.postgresql.org/download/


- prepare database

```
# postgres createdb --encoding=UTF8  -U postgres osm

# postgres psql  -U postgres osm --command='CREATE EXTENSION postgis;'
```

- import to database and create

```
# osm2pgsql  -c -s -d osm -U postgres -W -H localhost -P 5432 -O flex  -S brouter_cfg.lua /path/to/file.pbf
```


- generate new tags inside the database

```
# psql -d osm -U postgres  -H localhost -P 5432  -f brouter.sql
```

- prepare generation of pbf

  - when using database and new tagging an other lookups.dat is needed, use lookups_db.dat and rename

  - script needs a jdbc in the classpath

    `... -cp ../postgresql-42.6.0.jar;../brouter_fc.jar ...`

  - script needs a call with jdbc parameter

    define the database parameter

    `JDBC="jdbc:postgresql://localhost/osm?user=postgres&password=xyz&ssl=false"`

    call it with OsmFastCutter as last parameter (behind pbf file)

    `... btools.mapcreator.OsmFastCutter ... ../planet-new.osm.pbf $(JDBC)`


