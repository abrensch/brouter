--  calculation of new tags (estimated_noise_class, estimated_river_class,estimated_forest_class, estimated_town_class, estimated_traffic_class)
--  formatted by https://sqlformat.darold.net/

SET client_encoding TO UTF8;

SELECT
    now();

CREATE OR REPLACE FUNCTION isnumeric (string text)
    RETURNS bool
    AS $$
BEGIN
    PERFORM
        string::numeric;
    RETURN TRUE;
EXCEPTION
    WHEN invalid_text_representation THEN
        RETURN FALSE;
END;

$$
LANGUAGE plpgsql
SECURITY INVOKER;

-- create new tables for tuning
SELECT
    osm_id::bigint,
    highway,
    waterway,
    width,
    maxspeed,
    CASE WHEN maxspeed IS NULL THEN
        0
        --when not isnumeric(maxspeed)       then 0
    WHEN NOT (maxspeed ~ '^\d+(\.\d+)?$') THEN
        0
    WHEN maxspeed::numeric > '105' THEN
        1
    WHEN maxspeed::numeric > '75' THEN
        2
    ELSE
        3
    END AS maxspeed_class
    --  "buffer radius" was initially created with 50 meters at a lat 50 degrees....  ==> ST_Buffer(way,50)
    -- but, using geometry "projection", to get same results by a calculation of the planet (latitude between -80, +85) this value should be adapted to the latitude of the highways...
,
    --
    ST_Buffer (way, 32.15 * st_length (ST_Transform (way, 3857)) / st_length (ST_Transform (way, 4326)::geography)) AS way INTO TABLE osm_line_buf_50
FROM
    lines
WHERE
    highway IS NOT NULL
    OR waterway IN ('river', 'canal');

SELECT
    now();

-- modify "way" by large waterways !!" (example Rhein ==> width = 400 ....) enlarge a bit the "50 meter" buffer
UPDATE
    osm_line_buf_50
SET
    way = st_buffer (way, (width::numeric / 10))
WHERE
    waterway = 'river'
    AND width IS NOT NULL
    AND (width ~ '^[0-9\.]+$')
    AND width::numeric > 15;

SELECT
    osm_id::bigint,
    leisure,
    landuse,
    p.natural,
    p.water,
    ST_Buffer (way, 32.15 * st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 3857)) / st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 4326)::geography)) AS way INTO TABLE osm_poly_buf_50
FROM
    polygons p
WHERE
    -- do not consider small surfaces
    st_area (p.way) > 1000
    AND p.natural IN ('water')
    OR (p.landuse IN ('forest', 'allotments', 'flowerbed', 'orchard', 'vineyard', 'recreation_ground', 'village_green')
        OR p.leisure IN ('garden', 'park', 'nature_reserve'));

SELECT
    osm_id::bigint,
    leisure,
    landuse,
    p.natural,
    p.water,
    way INTO TABLE osm_poly_no_buf
FROM
    polygons p
WHERE
    -- do not consider small surfaces
    st_area (p.way) > 1000
    AND p.natural IN ('water')
    OR (p.landuse IN ('forest', 'allotments', 'flowerbed', 'orchard', 'vineyard', 'recreation_ground', 'village_green')
        OR p.leisure IN ('garden', 'park', 'nature_reserve'));

SELECT
    osm_id::bigint,
    leisure,
    landuse,
    p.natural,
    p.water,
    ST_Buffer (way, 45 * st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 3857)) / st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 4326)::geography)) AS way INTO TABLE osm_poly_buf_120
FROM
    osm_poly_buf_50 p;

SELECT
    now();

-- create indexes
CREATE INDEX osm_line_buf_50_idx ON public.osm_line_buf_50 USING gist (way) WITH (fillfactor = '100');

ANALYZE;

SELECT
    osm_id,
    highway,
    way,
    ST_Expand (way, 9645 * st_length (ST_Transform (way, 3857)) / st_length (ST_Transform (way, 4326)::geography)) way2,
    ST_Centroid (way) way0,
    st_length (ST_Transform (way, 3857)) / st_length (ST_Transform (way, 4326)::geography) AS merca_coef INTO TABLE primsecter15k
FROM
    lines
WHERE
    highway IN ('primary', 'primary_link', 'secondary', 'secondary_link', 'tertiary');

CREATE INDEX primsecter15k_idx2 ON public.primsecter15k USING gist (way2) WITH (fillfactor = '100');

CREATE INDEX primsecter15k_idx1 ON public.primsecter15k USING gist (way) WITH (fillfactor = '100');

CREATE INDEX primsecter15k_idx0 ON public.primsecter15k USING gist (way0) WITH (fillfactor = '100');

SELECT
    now();

-- create a new "town" table based on cities_rel (with a valid/numeric population) AND fetch by need the population from the cities table)
-- clean the cities table (when population is null or population is not numeric or unusable)
SELECT
    a.name,
    REPLACE(a.population, '.', '')::bigint population,
    a.way INTO cities_ok
FROM
    cities a
WHERE
    a.population IS NOT NULL
    AND isnumeric (a.population)
    AND a.place IN ('town', 'city', 'municipality');

-- clean the cities_rel table (when population is not numeric or unusable)
SELECT
    a.name AS name,
    a.place AS place,
    a.admin_level,
    CASE WHEN a.population IS NOT NULL
        AND isnumeric (a.population) THEN
        a.population::numeric
    ELSE
        NULL
    END AS population,
    a.way INTO cities_rel_ok
FROM
    cities_rel a;

CREATE INDEX cities_ok_idx ON public.cities_ok USING gist (way) WITH (fillfactor = '100');

CREATE INDEX cities_rel_ok_idx ON public.cities_rel_ok USING gist (way) WITH (fillfactor = '100');

-- select town + population + way starting with cities_ok .... (to catch specials cases as ex. "Berlin" which is tagged with "admin_level=4")
SELECT
    a.name AS name,
    st_x (a.way),
    st_y (a.way),
    a.population,
    CASE
    -- limit 1 is is necessary because some osm data are inconsistent (==> 2 relations with the name " Krynki" and quite same x/y)
    WHEN (
        SELECT
            way
        FROM
            cities_rel_ok b
        WHERE (b.admin_level = '8'
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (b.admin_level = '8'
        AND a.name = b.name
        AND st_intersects (a.way, b.way))
LIMIT 1)
-- Australia admin_level=7
    WHEN (
        SELECT
            way
        FROM
            cities_rel_ok b
        WHERE (b.admin_level = '7'
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (b.admin_level = '7'
        AND a.name = b.name
        AND st_intersects (a.way, b.way))
LIMIT 1)
-- Paris admin_level=6
    WHEN (
        SELECT
            way
        FROM
            cities_rel_ok b
        WHERE (b.admin_level = '6'
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (b.admin_level = '6'
        AND a.name = b.name
        AND st_intersects (a.way, b.way))
LIMIT 1)
-- Berlin admin_level=4
    WHEN (
        SELECT
            way
        FROM
            cities_rel_ok b
        WHERE (b.admin_level = '4'
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (b.admin_level = '4'
        AND a.name = b.name
        AND st_intersects (a.way, b.way))
LIMIT 1)
-- London admin_level is null
    WHEN (
        SELECT
            way
        FROM
            cities_rel_ok b
        WHERE (b.admin_level IS NULL
            AND b.place IN ('city', 'town')
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (b.admin_level IS NULL
        AND b.place IN ('city', 'town')
        AND a.name = b.name
        AND st_intersects (a.way, b.way))
LIMIT 1)
-- Αθήνα No DATA at all...
-- possible solution ????? no, better null!
-- else  st_buffer(way, (10 *sqrt(a.population)))
-- else null
-- at least the "traffic" can be estimated (not "town)
ELSE
    st_buffer (a.way, 10)
    END AS way,
    a.way AS way0 INTO cities_intermed3
FROM
    cities_ok a
ORDER BY
    name;

-- select town + population + way starting with cities_rel_ok ....
SELECT
    a.name AS name,
    st_area (a.way) st_area,
    CASE WHEN a.population IS NOT NULL THEN
        a.population
        -- "max" is necessary because some osm data are inconsistent (==> 2 nodes with the name Titisee-Neustadt and quite same x/y)
    ELSE
        (
            SELECT
                max(population)
            FROM
                cities_intermed3 b
            WHERE (a.name = b.name
                AND st_intersects (a.way, b.way)))
    END AS population,
    a.way,
    NULL::geometry AS way0 INTO cities_intermed4
FROM
    cities_rel_ok a
WHERE
    a.admin_level = '8'
ORDER BY
    a.name;

-- merge
SELECT
    name,
    max(population) AS population,
    way,
    max(way0) AS way0,
    st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 3857)) / st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 4326)::geography) AS merca_coef INTO cities_intermed5
FROM ((
        SELECT
            name,
            population,
            way,
            way0
        FROM
            cities_intermed3)
    UNION (
        SELECT
            name,
            population,
            way,
            way0
        FROM
            cities_intermed4)) a
WHERE
    population IS NOT NULL
    -- and population > 20000
GROUP BY
    name,
    way
ORDER BY
    population;

SELECT
    name,
    population,
    way,
    CASE WHEN way0 IS NULL THEN
        st_centroid (way)::geometry
    ELSE
        way0::geometry
    END AS way0,
    merca_coef INTO TABLE cities_all
FROM
    cities_intermed5;

SELECT
    now();

-- create tags for noise
-- create raw data
--     when several highways-segments are producing noise, aggregate the noises using the "ST_Union" of the segments!
--     (better as using "sum" or "max" that do not deliver good factors)
SELECT
    m.osm_id losmid,
    m.highway lhighway,
    q.highway AS qhighway,
    q.maxspeed_class,
    CASE WHEN q.highway IN ('motorway', 'motorway_link', 'trunk', 'trunk_link')
        AND q.maxspeed_class < 1.1 THEN
        st_area (st_intersection (m.way, ST_Union (q.way))) / st_area (m.way)
    WHEN q.highway IN ('motorway', 'motorway_link', 'trunk', 'trunk_link') THEN
        st_area (st_intersection (m.way, ST_Union (q.way))) / (1.5 * st_area (m.way))
    WHEN q.highway IN ('primary', 'primary_link')
        AND q.maxspeed_class < 2.1 THEN
        st_area (st_intersection (m.way, ST_Union (q.way))) / (2 * st_area (m.way))
    WHEN q.highway IN ('primary', 'primary_link') THEN
        st_area (st_intersection (m.way, ST_Union (q.way))) / (3 * st_area (m.way))
    WHEN q.highway IN ('secondary')
        AND q.maxspeed_class < 2.1 THEN
        st_area (st_intersection (m.way, ST_Union (q.way))) / (3 * st_area (m.way))
    WHEN q.highway IN ('secondary') THEN
        st_area (st_intersection (m.way, ST_Union (q.way))) / (5 * st_area (m.way))
    END AS noise_factor INTO TABLE noise_tmp
FROM
    osm_line_buf_50 AS m
    INNER JOIN osm_line_buf_50 AS q ON ST_Intersects (m.way, q.way)
WHERE
    m.highway IS NOT NULL
    AND q.highway IN ('motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link', 'secondary')
GROUP BY
    losmid,
    lhighway,
    m.way,
    q.highway,
    q.maxspeed_class
ORDER BY
    noise_factor DESC;

SELECT
    now();

-- aggregate data:
-- on "maxspeed_class take the sum of several highways (having different maxspeed-class) union is then not done, but not very frequent
-- on "phighway"      take the sum of several highways (as probably several highways are producing noise at the point!
SELECT
    losmid,
    lhighway,
    sum(noise_factor) AS sum_noise_factor INTO TABLE noise_tmp2
FROM
    noise_tmp
GROUP BY
    losmid,
    lhighway
ORDER BY
    sum_noise_factor DESC;

-- create the noise classes
SELECT
    losmid,
    CASE WHEN y.sum_noise_factor < 0.1 THEN
        '1'
    WHEN y.sum_noise_factor < 0.25 THEN
        '2'
    WHEN y.sum_noise_factor < 0.4 THEN
        '3'
    WHEN y.sum_noise_factor < 0.55 THEN
        '4'
    WHEN y.sum_noise_factor < 0.8 THEN
        '5'
    ELSE
        '6'
    END AS noise_class INTO TABLE noise_tags
FROM
    noise_tmp2 y
WHERE
    y.sum_noise_factor > 0.01;

SELECT
    count(*)
FROM
    noise_tags;

SELECT
    noise_class,
    count(*)
FROM
    noise_tags
GROUP BY
    noise_class
ORDER BY
    noise_class;

DROP TABLE noise_tmp2;

SELECT
    now();

-- create tags for river
SELECT
    xid,
    sum(water_river_see) AS river_see INTO TABLE river_tmp
FROM (
    SELECT
        m.osm_id AS xid,
        st_area (st_intersection (m.way, ST_Union (q.way))) / st_area (m.way) AS water_river_see
    FROM
        osm_line_buf_50 AS m
        INNER JOIN osm_poly_buf_120 AS q ON ST_Intersects (m.way, q.way)
    WHERE
        m.highway IS NOT NULL
        -- and st_area(q.way) > 90746  !!! filter on very small surfaces was set above !!!!!!!!!
        AND q.natural IN ('water')
        AND (q.water IS NULL
            OR q.water NOT IN ('wastewater'))
    GROUP BY
        m.osm_id,
        m.way
    UNION
    SELECT
        m.osm_id AS xid,
        st_area (st_intersection (m.way, ST_Union (q.way))) / st_area (m.way) AS water_river_see
    FROM
        osm_line_buf_50 AS m
        INNER JOIN osm_line_buf_50 AS q ON ST_Intersects (m.way, q.way)
    WHERE
        m.highway IS NOT NULL
        AND q.waterway IN ('river', 'canal')
    GROUP BY
        m.osm_id,
        m.way) AS abcd
GROUP BY
    xid
ORDER BY
    river_see DESC;

SELECT
    y.xid losmid,
    CASE WHEN y.river_see < 0.17 THEN
        '1'
    WHEN y.river_see < 0.35 THEN
        '2'
    WHEN y.river_see < 0.57 THEN
        '3'
    WHEN y.river_see < 0.80 THEN
        '4'
    WHEN y.river_see < 0.95 THEN
        '5'
    ELSE
        '6'
    END AS river_class INTO TABLE river_tags
FROM
    river_tmp y
WHERE
    y.river_see > 0.05;

SELECT
    count(*)
FROM
    river_tags;

SELECT
    river_class,
    count(*)
FROM
    river_tags
GROUP BY
    river_class
ORDER BY
    river_class;

SELECT
    now();

-- create tags for forest
SELECT
    m.osm_id,
    m.highway,
    st_area (st_intersection (m.way, ST_Union (q.way))) / st_area (m.way) AS green_factor INTO TABLE forest_tmp
FROM
    osm_line_buf_50 AS m
    INNER JOIN osm_poly_no_buf AS q ON ST_Intersects (m.way, q.way)
WHERE
    m.highway IS NOT NULL
    AND ((q.landuse IN ('forest', 'allotments', 'flowerbed', 'orchard', 'vineyard', 'recreation_ground', 'village_green'))
        OR q.leisure IN ('garden', 'park', 'nature_reserve'))
    AND (st_area (ST_Transform (q.way, 4326)::geography) / 1000000) < 5000
GROUP BY
    m.osm_id,
    m.highway,
    m.way
ORDER BY
    green_factor DESC;

--
SELECT
    y.osm_id losmid,
    CASE WHEN y.green_factor < 0.1 THEN
        NULL
    WHEN y.green_factor < 0.2 THEN
        '1'
    WHEN y.green_factor < 0.4 THEN
        '2'
    WHEN y.green_factor < 0.6 THEN
        '3'
    WHEN y.green_factor < 0.8 THEN
        '4'
    WHEN y.green_factor < 0.98 THEN
        '5'
    ELSE
        '6'
    END AS forest_class INTO TABLE forest_tags
FROM
    forest_tmp y
WHERE
    y.green_factor > 0.1;

SELECT
    count(*)
FROM
    forest_tags;

SELECT
    forest_class,
    count(*)
FROM
    forest_tags
GROUP BY
    forest_class
ORDER BY
    forest_class;

SELECT
    now();

--  create "town" tags
-- get the highways which intersect the town
SELECT
    m.osm_id losmid,
    m.highway lhighway,
    CASE WHEN q.population::decimal > '2000000' THEN
        1
    WHEN q.population::decimal > '1000000' THEN
        0.8
    WHEN q.population::decimal > '400000' THEN
        0.6
    WHEN q.population::decimal > '150000' THEN
        0.4
    WHEN q.population::decimal > '80000' THEN
        0.2
    ELSE
        0.1
    END AS town_factor INTO TABLE town_tmp
FROM
    osm_line_buf_50 AS m
    INNER JOIN cities_all AS q ON ST_Intersects (m.way, q.way)
WHERE
    m.highway IS NOT NULL
    AND q.population > '50000'
ORDER BY
    town_factor DESC;

--
SELECT
    losmid,
    CASE WHEN y.town_factor = 0.1 THEN
        '1'
    WHEN y.town_factor = 0.2 THEN
        '2'
    WHEN y.town_factor = 0.4 THEN
        '3'
    WHEN y.town_factor = 0.6 THEN
        '4'
    WHEN y.town_factor = 0.8 THEN
        '5'
    ELSE
        '6'
    END AS town_class INTO TABLE town_tags
FROM (
    SELECT
        losmid,
        max(town_factor) AS town_factor
    FROM
        town_tmp y
    GROUP BY
        losmid) y;

SELECT
    count(*)
FROM
    town_tags;

SELECT
    town_class,
    count(*)
FROM
    town_tags
GROUP BY
    town_class
ORDER BY
    town_class;

--
--  substract the ways from town with a green tag (because administrative surface are some times too large)
--
DELETE FROM town_tags
WHERE losmid IN (
        SELECT
            losmid
        FROM
            forest_tags
        WHERE
            forest_class NOT IN ('1'));

DELETE FROM town_tags
WHERE losmid IN (
        SELECT
            losmid
        FROM
            river_tags
        WHERE
            river_class NOT IN ('1'));

SELECT
    count(*)
FROM
    town_tags;

SELECT
    town_class,
    count(*)
FROM
    town_tags
GROUP BY
    town_class
ORDER BY
    town_class;

SELECT
    now();

-------------------------------------------
-- create tags for  TRAFFIC
-----------------------------------------
-- OSM data used to calculate/estimate the traffic:
--    population of towns (+ distance from position to the towns)
--    industrial areas (landuse=industrial)  (+ surface of the areas and distance from position)
--    motorway density (traffic on motorways decreases traffic on primary/secondary/tertiary)         calculated on grid
--    highway density  (traffic decreases when more primary/secondary/tertiary highways are available) calculated on grid
--    exceptions: near junctions between motorways and primary/secondary/tertiary the traffic increases on the primary/secondary/tertiary..
--    mountain-ranges  (high peaks)  traffic is generally on highways in such regions higher as only generated by local population or industrial areas
-- calculate traffic from the population (for each segment of type primary secondary tertiary)
-- SUM of (population of each town < 100 km) / ( town-radius + 2500 + dist(segment-position to the town) ** 2 )
--  town-radius is calculated as sqrt(population)
SELECT
    now();

SELECT
    m.osm_id losmid,
    m.highway lhighway,
    CASE WHEN m.highway = 'tertiary' THEN
        sum(10000 * q.population::numeric / power(((8 * sqrt(q.population::numeric)) + 500 + ((ST_Distance (m.way0, q.way0) * 50) / (q.merca_coef * 32.15))), 2) * 0.4)
    WHEN m.highway IN ('secondary', 'secondary_link') THEN
        sum(10000 * q.population::numeric / power(((8 * sqrt(q.population::numeric)) + 500 + ((ST_Distance (m.way0, q.way0) * 50) / (q.merca_coef * 32.15))), 2) * 0.6)
    ELSE
        sum(10000 * q.population::numeric / power(((8 * sqrt(q.population::numeric)) + 500 + ((ST_Distance (m.way0, q.way0) * 50) / (q.merca_coef * 32.15))), 2))
    END AS populate_factor INTO TABLE traffic_tmp
FROM
    primsecter15k AS m
    INNER JOIN cities_all AS q ON ST_DWithin (m.way0, q.way0, ((3215 * q.merca_coef) + ((64300 * q.merca_coef) * q.population / (q.population + 10000))))
WHERE
    m.highway IS NOT NULL
    --and m.highway in ('primary','primary_link','secondary', 'secondary_link', 'tertiary')
    AND q.population > 200
GROUP BY
    m.osm_id,
    m.highway,
    m.way
ORDER BY
    populate_factor;

SELECT
    now();

-- prepare some special tables
--  the intersections motorway_link with primary/secondary/tertiary deliver the motorway acccesses....
SELECT
    m.osm_id losmid,
    m.highway,
    m.way,
    ST_Expand (m.way, 643 * st_length (ST_Transform (m.way, 3857)) / st_length (ST_Transform (m.way, 4326)::geography)) way2,
    ST_Expand (m.way, 1286 * st_length (ST_Transform (m.way, 3857)) / st_length (ST_Transform (m.way, 4326)::geography)) way3,
    st_length (ST_Transform (m.way, 3857)) / st_length (ST_Transform (m.way, 4326)::geography) AS merca_coef INTO TABLE motorway_access
FROM
    lines AS m
    INNER JOIN lines AS q ON ST_Intersects (m.way, q.way)
WHERE
    q.highway IN ('motorway_link', 'trunk_link')
    AND m.highway IN ('primary', 'secondary', 'tertiary')
GROUP BY
    m.osm_id,
    m.highway,
    m.way;

SELECT
    now();

CREATE INDEX motorway_access_idx2 ON public.motorway_access USING gist (way2) WITH (fillfactor = '100');

SELECT
    now();

CREATE INDEX motorway_access_idx3 ON public.motorway_access USING gist (way3) WITH (fillfactor = '100');

SELECT
    now();

-- find out all the primary/secondary/tertiary within 1000 m and 2000 m from a motorway access
SELECT
    now();

SELECT
    m.osm_id losmid,
    sum(st_length (q.way) / (6430 * q.merca_coef)) motorway_factor INTO TABLE motorway_access_1000
FROM
    lines AS m
    INNER JOIN motorway_access AS q ON ST_Intersects (m.way, q.way2)
WHERE
    m.highway IN ('primary', 'primary_link', 'secondary', 'secondary_link', 'tertiary')
GROUP BY
    m.osm_id,
    m.way
ORDER BY
    motorway_factor;

SELECT
    now();

SELECT
    m.osm_id losmid,
    sum(st_length (q.way) / (6430 * merca_coef)) motorway_factor INTO TABLE motorway_access_2000
FROM
    lines AS m
    INNER JOIN motorway_access AS q ON ST_Intersects (m.way, q.way3)
WHERE
    m.highway IN ('primary', 'primary_link', 'secondary', 'secondary_link', 'tertiary')
GROUP BY
    m.osm_id,
    m.way
ORDER BY
    motorway_factor;

SELECT
    now();

--
--  special regions: mountain_range with "peaks" ==> few highways ==> higher traffic !!!
--  calculate the "peak_density"
SELECT
    now();

SELECT
    m.osm_id losmid,
    count(q.*) AS peak_cnt,
    sum(q.ele::decimal) peak_sum_ele INTO TABLE peak_density
FROM
    primsecter15k AS m
    INNER JOIN peak AS q ON ST_Intersects (m.way2, q.way)
WHERE (q.ele ~ '^[0-9\.]+$')
    AND q.ele::decimal > 400
GROUP BY
    m.osm_id,
    m.way
ORDER BY
    peak_cnt DESC;

SELECT
    now();

--
-- traffic due to industrial parcs ...
--
SELECT
    now();

SELECT
    name,
    way,
    st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 3857)) / st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 4326)::geography) AS merca_coef INTO TABLE poly_industri
FROM
    polygons
WHERE
    landuse = 'industrial';

SELECT
    name,
    way,
    ST_Centroid (way) way0,
    st_area (way) * power(50 / (32.15 * merca_coef), 2) areaReal,
    merca_coef INTO industri
FROM
    poly_industri
WHERE
    st_area (way) * power(50 / (32.15 * merca_coef), 2) > 20000;

SELECT
    now();

SELECT
    m.osm_id losmid,
    m.highway lhighway,
    CASE WHEN m.highway = 'tertiary' THEN
        sum(areaReal / power((sqrt(areaReal) + 500 + (ST_Distance (m.way0, q.way0) * 50) / (32.15 * q.merca_coef)), 2) * 0.6)
    WHEN m.highway IN ('secondary', 'secondary_link') THEN
        sum(areaReal / power((sqrt(areaReal) + 500 + (ST_Distance (m.way0, q.way0) * 50) / (32.15 * q.merca_coef)), 2) * 0.8)
    ELSE
        sum(areaReal / power((sqrt(areaReal) + 500 + (ST_Distance (m.way0, q.way0) * 50) / (32.15 * q.merca_coef)), 2))
    END AS industrial_factor INTO industri_tmp
FROM
    primsecter15k AS m
    INNER JOIN industri AS q ON ST_dwithin (m.way0, q.way0, (12860 * q.merca_coef))
GROUP BY
    m.osm_id,
    m.highway,
    m.way
ORDER BY
    industrial_factor;

SELECT
    now();

-- create a grid to allow a fast calculation for highway_density and motorway_density
CREATE OR REPLACE FUNCTION generate_grid (bound_polygon geometry, grid_step integer, srid integer DEFAULT 2180)
    RETURNS TABLE (
        id bigint,
        geom geometry)
    LANGUAGE plpgsql
    AS $function$
DECLARE
    Xmin int;
    Xmax int;
    Ymax int;
    Ymin int;
    query_text text;
BEGIN
    Xmin := floor(ST_XMin (bound_polygon));
    Xmax := ceil(ST_XMax (bound_polygon));
    Ymin := floor(ST_YMin (bound_polygon));
    Ymax := ceil(ST_YMax (bound_polygon));
    query_text := 'select row_number() over() id, st_makeenvelope(s1, s2, s1+$5, s2+$5, $6) geom
   from generate_series($1, $2+$5, $5) s1, generate_series ($3, $4+$5, $5) s2';
    RETURN QUERY EXECUTE query_text
    USING Xmin, Xmax, Ymin, Ymax, grid_step, srid;
END;
$function$;

CREATE TABLE grid1 AS
SELECT
    id,
    geom
FROM
    generate_grid((ST_GeomFromText('POLYGON((0 9000000, 18000000 9000000, 18000000 -9000000, 0 -9000000, 0  9000000))')),10000,3857);

CREATE TABLE grid2 AS
SELECT
    id,
    geom
FROM
    generate_grid((ST_GeomFromText('POLYGON((0 9000000, -18000000 9000000, -18000000 -9000000, 0 -9000000, 0  9000000))')),10000,3857);

SELECT
    geom INTO TABLE grid
FROM ((
        SELECT
            geom
        FROM
            grid1)
    UNION (
        SELECT
            geom
        FROM
            grid2)) a;

-- GRID  HIGHWAY_DENSITY
SELECT
    now();

SELECT
    sum(st_length (q.way) / (6430 * (st_length (ST_Transform (q.way, 3857)) / st_length (ST_Transform (q.way, 4326)::geography)))) highway_factor,
    m.geom way INTO TABLE grid_highway_density
FROM
    lines AS q
    INNER JOIN grid AS m ON ST_Intersects (q.way, m.geom)
WHERE
    q.highway IN ('primary', 'primary_link', 'secondary', 'secondary_link', 'tertiary')
GROUP BY
    m.geom
ORDER BY
    highway_factor;

SELECT
    now();

-- GRID MOTORWAY_DENSITY
SELECT
    now();

SELECT
    sum(st_length (q.way) / (6430 * (st_length (ST_Transform (q.way, 3857)) / st_length (ST_Transform (q.way, 4326)::geography)))) motorway_factor,
    m.geom way INTO TABLE grid_motorway_density
FROM
    lines AS q
    INNER JOIN grid AS m ON ST_Intersects (q.way, m.geom)
WHERE
    q.highway IN ('motorway', 'motorway_link', 'trunk', 'trunk_link')
GROUP BY
    m.geom
ORDER BY
    motorway_factor;

SELECT
    now();

-- CREATE INDEX grid_idx ON public.grid USING gist (geom) WITH (fillfactor='100');
CREATE INDEX grid_hwd_idx ON public.grid_highway_density USING gist (way) WITH (fillfactor = '100');

CREATE INDEX grid_mwd_idx ON public.grid_motorway_density USING gist (way) WITH (fillfactor = '100');

-- collect all exceptions on 1 table
SELECT
    now();

SELECT
    y.osm_id losmid,
    CASE WHEN q.motorway_factor IS NULL THEN
        0
    ELSE
        q.motorway_factor
    END AS motorway_factor,
    CASE WHEN x.peak_sum_ele IS NULL THEN
        0
    WHEN x.peak_sum_ele > 500000 THEN
        4
    ELSE
        x.peak_sum_ele / 125000
    END AS peak_sum_ele,
    CASE WHEN z.highway_factor IS NULL THEN
        0
    ELSE
        z.highway_factor
    END AS highway_factor,
    CASE WHEN w.industrial_factor IS NULL THEN
        0
    WHEN w.industrial_factor > 1 THEN
        (1500 * 50)
    ELSE
        (w.industrial_factor * 1500 * 50)
    END AS industrial_factor INTO TABLE except_all_tmp
FROM
    lines y
    LEFT OUTER JOIN grid_motorway_density AS q ON st_dwithin (q.way, y.way, 5500)
    LEFT OUTER JOIN peak_density AS x ON y.osm_id = x.losmid
    LEFT OUTER JOIN industri_tmp AS w ON y.osm_id = w.losmid
    LEFT OUTER JOIN grid_highway_density AS z ON st_dwithin (z.way, y.way, 5500)
WHERE
    y.highway IN ('primary', 'primary_link', 'secondary', 'secondary_link', 'tertiary');

SELECT
    now();

SELECT
    losmid,
    peak_sum_ele,
    avg(highway_factor) highway_factor,
    avg(motorway_factor) motorway_factor,
    industrial_factor INTO TABLE except_all
FROM
    except_all_tmp
GROUP BY
    losmid,
    peak_sum_ele,
    industrial_factor;

SELECT
    now();

--  Do not apply the positiv effect of "motorway density" in proximity of motorway accesses!!!!
UPDATE
    except_all
SET
    motorway_factor = 0
WHERE
    losmid IN (
        SELECT
            losmid
        FROM
            motorway_access_2000);

-- quite direct at motorway accesses set a negativ effect !!!!
UPDATE
    except_all
SET
    motorway_factor = - 15
WHERE
    losmid IN (
        SELECT
            losmid
        FROM
            motorway_access_1000);

SELECT
    now();

-- class calculation with modifications using peaks, motorway_density and highway_density...
--
SELECT
    y.losmid::bigint,
    CASE WHEN ((y.populate_factor * 1200 * (1 + q.peak_sum_ele)) + q.industrial_factor) / ((30 + q.motorway_factor) * (50 + q.highway_factor)) < 6 THEN
        '1'
    WHEN ((y.populate_factor * 1200 * (1 + q.peak_sum_ele)) + q.industrial_factor) / ((30 + q.motorway_factor) * (50 + q.highway_factor)) < 10 THEN
        '2'
    WHEN ((y.populate_factor * 1200 * (1 + q.peak_sum_ele)) + q.industrial_factor) / ((30 + q.motorway_factor) * (50 + q.highway_factor)) < 19 THEN
        '3'
    WHEN ((y.populate_factor * 1200 * (1 + q.peak_sum_ele)) + q.industrial_factor) / ((30 + q.motorway_factor) * (50 + q.highway_factor)) < 35 THEN
        '4'
    WHEN ((y.populate_factor * 1200 * (1 + q.peak_sum_ele)) + q.industrial_factor) / ((30 + q.motorway_factor) * (50 + q.highway_factor)) < 70 THEN
        '5'
    ELSE
        '6'
    END AS traffic_class INTO TABLE traffic_tags
FROM
    traffic_tmp y
    LEFT OUTER JOIN except_all AS q ON y.losmid = q.losmid
ORDER BY
    traffic_class DESC;

SELECT
    now();

--statistics
SELECT
    traffic_class,
    count(losmid) cnt
FROM
    traffic_tags
GROUP BY
    traffic_class
ORDER BY
    traffic_class;

--
--  put all tags together in 1 table (1 "direct" access per way in mapcreator)
--
SELECT
    losmid::bigint AS losmid,
    noise_class,
    river_class,
    forest_class,
    town_class,
    traffic_class INTO TABLE all_tags
FROM
    river_tags
    NATURAL
    FULL OUTER JOIN noise_tags
    NATURAL
    FULL OUTER JOIN forest_tags
    NATURAL
    FULL OUTER JOIN town_tags
    NATURAL
    FULL OUTER JOIN traffic_tags
ORDER BY
    losmid;

CREATE INDEX all_tags_ind ON all_tags (losmid, noise_class, river_class, forest_class, town_class, traffic_class) WITH (fillfactor = '100');

ANALYSE;

SELECT
    now();

