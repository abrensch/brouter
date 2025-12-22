Import new tags for noise, green and water feature


- A PostgreSQL and a osm2pgsql installation is needed
  see https://www.postgresql.org/
  and https://osm2pgsql.org/

- and a jdbc driver
  see https://jdbc.postgresql.org/download/


- prepare database

```
# postgres createdb --encoding=UTF8 -U postgres osm

# postgres psql -U postgres -d osm --command='CREATE EXTENSION postgis;'

# postgres psql -U postgres -d osm --command='CREATE EXTENSION hstore;'
```

- import to database and create

```
# osm2pgsql -c -s -d osm -U postgres -W -H localhost -P 5432 -O flex -S brouter_cfg.lua /path/to/file.pbf
```


- generate new tags inside the database (use `<` if `-f` does not work)

```
# postgres psql -d osm -U postgres -f brouter.sql
```

- prepare generation of pbf

  - lookups.dat has to contain the new tags from the database

  - script needs a jdbc in the classpath (on UNIX and Linux use a colon `:` as delimiter)

    `... -cp ../postgresql-42.6.0.jar;../brouter.jar ...`

  - script needs a call with jdbc parameter

    define the database parameter

    `JDBC="jdbc:postgresql://localhost/osm?user=postgres&password=xyz&ssl=false"`

  - export the tags from database to filesystem

    `java -Xmx6144M -Xms6144M -Xms6144M -cp %CLASSPATH% btools.mapcreator.DatabasePseudoTagProvider $(JDBC) db_tags.csv.gz`

  - call it with OsmFastCutter as last parameter (behind pbf file)

    `... btools.mapcreator.OsmFastCutter ... ../planet-new.osm.pbf db_tags.csv.gz`


_Note:_ The last two steps can be omitted if the database is used directly

  - remove the pseudo tag file generation

  - call OsmFasCutter with database parameter insteed of file name

    `... btools.mapcreator.OsmFastCutter ... ../planet-new.osm.pbf $(JDBC)`
