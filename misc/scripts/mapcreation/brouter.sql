--  calculation of new tags (estimated_noise_class, estimated_river_class,estimated_forest_class, estimated_town_class, estimated_traffic_class)
--  formatted by https://sqlformat.darold.net/
SET client_encoding TO UTF8;

-- prepare the lines table with a new index and a new column
SELECT
    now();

ANALYZE;

SELECT
    now();

SELECT
    osm_id,
    highway,
    maxspeed,
    way,
    waterway,
    li.natural,
    width,
    oneway,
    st_length (way) / st_length (ST_Transform (way, 4326)::geography) AS merca_coef INTO TABLE lines_bis
FROM
    lines li;

SELECT
    now();

DROP TABLE lines;

ALTER TABLE lines_bis RENAME TO lines;

CREATE INDEX lines_osm_id_idx ON lines (osm_id) WITH (fillfactor = '100');

CREATE INDEX lines_way_idx ON public.lines USING gist (way) WITH (fillfactor = '100');

ANALYZE lines;

--  generation of pseudo-tags
-- 1: noise
-- create a table with the segments producing noise (motorway, primary and secondary) and a noise_factor for each.
-- the noise_factor depends on the highway type, maxspeed and oneway
-- oneway is basically given on motorway segments, in some cases also on primary...
-- then 2 segments exist on the same route, so noise_factor per segment is lower!!!!
SELECT
    now();

SELECT
    osm_id::bigint,
    highway,
    maxspeed,
    CASE WHEN maxspeed IS NULL
        OR (NOT (maxspeed ~ '^\d+(\.\d+)?$'))
        OR maxspeed::numeric > '105' THEN
        -- maxspeed not defined OR not numeric / usable OR  > 105 km/h
        CASE WHEN highway IN ('motorway', 'motorway_link', 'trunk', 'trunk_link') THEN
            0.6
        WHEN highway IN ('primary', 'primary_link') THEN
            CASE WHEN oneway IS NULL
                OR oneway NOT IN ('yes', 'true', '1') THEN
                0.66
            ELSE
                0.45
            END
        WHEN highway IN ('secondary') THEN
            0.33
        ELSE
            0
        END
    WHEN maxspeed::numeric > '75' THEN
        -- 75 < maxspeed <= 105
        CASE WHEN highway IN ('motorway', 'motorway_link', 'trunk', 'trunk_link') THEN
            0.55
        WHEN highway IN ('primary', 'primary_link') THEN
            CASE WHEN oneway IS NULL
                OR oneway NOT IN ('yes', 'true', '1') THEN
                0.66
            ELSE
                0.45
            END
        WHEN highway IN ('secondary') THEN
            0.33
        ELSE
            0
        END
    ELSE
        -- maxspeed <= 75
        CASE WHEN highway IN ('motorway', 'motorway_link', 'trunk', 'trunk_link') THEN
            0.4
        WHEN highway IN ('primary', 'primary_link') THEN
            CASE WHEN oneway IS NULL
                OR oneway NOT IN ('yes', 'true', '1') THEN
                0.4
            ELSE
                0.3
            END
        WHEN highway IN ('secondary') THEN
            0.2
        ELSE
            0
        END
    END AS noise_factor,
    way AS way,
    ST_Buffer (way, 75 * merca_coef) AS way75,
    merca_coef INTO TABLE noise_emittnew
FROM
    lines li
WHERE
    highway IS NOT NULL
    AND highway IN ('motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link', 'secondary');

SELECT
    now();

CREATE INDEX noise_emittnew_osm_id_idx ON noise_emittnew (osm_id) WITH (fillfactor = '100');

-- modify noise_factor by very small segments
SELECT
    now();

UPDATE
    noise_emittnew
SET
    noise_factor = noise_factor * (st_length (way) / merca_coef) / 20
WHERE (st_length (way) / merca_coef) < 20;

SELECT
    now();

ANALYZE noise_emittnew;

SELECT
    now();

-- create a tuple (highway + noise source) of the highways having noise (for perf tuning)
SELECT
    dd.osm_id::bigint AS lines_osm_id,
    dd.highway,
    dd.merca_coef,
    dd.way,
    q.osm_id AS noise_osm_id INTO TABLE tuples_with_noise
FROM
    lines dd
    INNER JOIN noise_emittnew AS q ON ST_Intersects (dd.way, q.way75)
WHERE
    dd.highway IS NOT NULL
    AND dd.highway NOT IN ('proposed', 'construction')
    AND (st_length (dd.way) / dd.merca_coef) < 40000;

--group by dd.osm_id, dd.highway, dd.merca_coef, dd.way;
SELECT
    now();

CREATE INDEX tuples_with_noise_osm_id_idx ON tuples_with_noise (lines_osm_id) WITH (fillfactor = '100');

SELECT
    now();

ANALYZE tuples_with_noise;

SELECT
    lines_osm_id AS osm_id,
    way,
    merca_coef INTO TABLE lines_with_noise
FROM
    tuples_with_noise
GROUP BY
    lines_osm_id,
    merca_coef,
    way;

SELECT
    now();

CREATE INDEX lineswithnoise_osm_id_idx ON lines_with_noise (osm_id) WITH (fillfactor = '100');

ANALYZE lines_with_noise;

-- calculate noise using "lines_with_noise"
--  split each segment with noise into 20 meter sections and calculate the noise per section
--  the average giving the noise for the segment
SELECT
    now();

WITH lines_split AS (
    SELECT
        osm_id,
        merca_coef,
        ST_LineSubstring (d.way, startfrac, LEAST (endfrac, 1)) AS way,
        len / merca_coef AS lgt_seg_real
    FROM (
        SELECT
            osm_id,
            merca_coef,
            way,
            st_length (way) len,
            (20 * merca_coef) sublen
        FROM
            lines_with_noise) AS d
        CROSS JOIN LATERAL (
            SELECT
                i,
                (sublen * i) / len AS startfrac,
                (sublen * (i + 1)) / len AS endfrac
            FROM
                generate_series(0, floor(len / sublen)::integer) AS t (i)
                -- skip last i if line length is exact multiple of sublen
            WHERE (sublen * i) / len <> 1.0) AS d2
)
SELECT
    m.osm_id::bigint losmid,
    m.lgt_seg_real,
    st_distance (m.way, t.way) / m.merca_coef AS dist,
    -- the line below delivers the same result as above !!!!!!! but need much more time (* 7 !)
    -- st_distance(st_transform(m.way, 4326)::geography, st_transform(t.way, 4326)::geography) as distgeog,
    t.noise_factor INTO TABLE noise_tmp2newz
FROM
    lines_split AS m
    INNER JOIN tuples_with_noise AS q ON m.osm_id = q.lines_osm_id
    INNER JOIN noise_emittnew t ON t.osm_id = q.noise_osm_id
WHERE
    st_distance (m.way, t.way) / m.merca_coef < 75;

SELECT
    now();

ANALYZE noise_tmp2newz;

-- group
SELECT
    now();

-- calculate an indicator per section (1 / d*d here) and reduce the results by taking the average on the osm_segment
SELECT
    losmid,
    m.lgt_seg_real,
    sum(noise_factor / ((dist + 15) / 15)) / ((m.lgt_seg_real / 20)::integer + 1) AS sum_noise_factor INTO noise_tmp2new
FROM
    noise_tmp2newz m
GROUP BY
    m.losmid,
    m.lgt_seg_real;

SELECT
    now();

DROP TABLE noise_tmp2newz;

DROP TABLE tuples_with_noise;

DROP TABLE noise_emittnew;

ANALYZE noise_tmp2new;

SELECT
    now();

-- add noise from Airports...
-- polygons  of the international airports
SELECT
    name,
    st_buffer (way, (700 * st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 3857)) / st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 4326)::geography))) AS way INTO TABLE poly_airportnew
FROM
    polygons
WHERE
    aeroway = 'aerodrome'
    AND aerodrome = 'international';

SELECT
    now();

ANALYZE poly_airportnew;

SELECT
    m.osm_id::bigint losmid,
    -- st_area(st_intersection(m.way,  q.way))      / (st_area(m.way) * 1.5)
    -- 1 / 1.5
    (700 - (st_distance (m.way, q.way) / m.merca_coef)) / (700 * 1.5) AS dist_factor INTO TABLE noise_airportnew
FROM
    lines AS m
    INNER JOIN poly_airportnew AS q ON ST_intersects (m.way, q.way)
WHERE
    m.highway IS NOT NULL
ORDER BY
    dist_factor DESC;

SELECT
    now();

ANALYZE noise_airportnew;

SELECT
    losmid,
    sum(noise_factor) AS sum_noise_factor INTO TABLE noise_tmp3new
FROM ((
        SELECT
            losmid,
            sum_noise_factor AS noise_factor
        FROM
            noise_tmp2new AS nois1)
    UNION (
        SELECT
            losmid,
            dist_factor AS noise_factor
        FROM
            noise_airportnew AS nois2)) AS nois_sum
GROUP BY
    losmid;

SELECT
    now();

ANALYZE noise_tmp3new;

-- create the noise classes
SELECT
    now();

SELECT
    losmid,
    CASE WHEN y.sum_noise_factor < 0.06 THEN
        '1'
    WHEN y.sum_noise_factor < 0.13 THEN
        '2'
    WHEN y.sum_noise_factor < 0.26 THEN
        '3'
    WHEN y.sum_noise_factor < 0.45 THEN
        '4'
    WHEN y.sum_noise_factor < 0.85 THEN
        '5'
    ELSE
        '6'
    END AS noise_class INTO TABLE noise_tags
FROM
    noise_tmp3new y
WHERE
    y.sum_noise_factor > 0.01
ORDER BY
    noise_class;

SELECT
    now();

ANALYZE noise_tags;

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

-------------------------------------------------------------------------
-- 2: create tags for river
-- create a table with the segments and polygons with "river" (or water!)
SELECT
    now();

WITH river_from_polygons AS (
    SELECT
        osm_id::bigint,
        way,
        ST_Buffer (way, 110 * st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 3857)) / st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 4326)::geography)) AS way2
    FROM
        polygons q
    WHERE
        q.natural IN ('water', 'bay', 'beach', 'wetland')
        AND (q.water IS NULL
            OR q.water NOT IN ('wastewater'))
        AND st_area (ST_Transform (q.way, 4326)::geography) BETWEEN 1000 AND 5000000000
),
river_from_lines AS (
    SELECT
        osm_id::bigint,
        way,
        ST_Buffer (way, 80 * merca_coef) AS way2
    FROM
        lines q
    WHERE
        q.waterway IN ('river', 'canal', 'fairway')
        OR q.natural IN ('coastline')
    ORDER BY
        way
),
river_coastline AS (
    SELECT
        osm_id::bigint,
        way,
        ST_Buffer (ST_ExteriorRing (way), 100 * st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 3857)) / st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 4326)::geography)) AS way2
    FROM
        polygons p
    WHERE
        st_area (ST_Transform (p.way, 4326)::geography) > 1000
        AND (p.natural IN ('coastline')
            AND st_length (way) < 100000)
    ORDER BY
        way
)
SELECT
    * INTO river_proxy
FROM (
    SELECT
        *
    FROM
        river_from_polygons part1
    UNION
    SELECT
        *
    FROM
        river_from_lines part2
    UNION
    SELECT
        *
    FROM
        river_coastline part3) AS sumriver;

SELECT
    now();

CREATE INDEX river_proxy_osm_id_idx ON river_proxy (osm_id) WITH (fillfactor = '100');

ANALYZE river_proxy;

SELECT
    now();

-- create a tuple (highway + noise source) of the highways in river proxymity (for perf tuning)
SELECT
    dd.osm_id::bigint AS lines_osm_id,
    dd.merca_coef,
    dd.way,
    q.osm_id AS river_osm_id INTO TABLE tuples_with_river
FROM
    lines dd
    INNER JOIN river_proxy AS q ON ST_Intersects (dd.way, q.way2)
WHERE
    dd.highway IS NOT NULL
    AND dd.highway NOT IN ('proposed', 'construction')
    AND (st_length (dd.way) / dd.merca_coef) < 40000;

SELECT
    now();

CREATE INDEX tuples_with_river_osm_id_idx ON tuples_with_river (lines_osm_id) WITH (fillfactor = '100');

SELECT
    now();

ANALYZE tuples_with_river;

SELECT
    now();

-- create a table of highways with river...
SELECT
    lines_osm_id AS osm_id,
    way,
    merca_coef INTO TABLE lines_with_river
FROM
    tuples_with_river
GROUP BY
    lines_osm_id,
    merca_coef,
    way;

SELECT
    now();

CREATE INDEX lineswithriver_osm_id_idx ON lines_with_river (osm_id) WITH (fillfactor = '100');

ANALYZE lines_with_river;

SELECT
    now();

-- calculate river factor using "lines_with_river"
--  split each segment with river into 20 meter sections and calculate the river per section
--  the average giving the river_factor for the segment
WITH lines_split AS (
    SELECT
        osm_id,
        merca_coef,
        ST_LineSubstring (d.way, startfrac, LEAST (endfrac, 1)) AS way,
        len / merca_coef AS lgt_seg_real
    FROM (
        SELECT
            osm_id,
            merca_coef,
            way,
            st_length (way) len,
            (20 * merca_coef) sublen
        FROM
            lines_with_river) AS d
        CROSS JOIN LATERAL (
            SELECT
                i,
                (sublen * i) / len AS startfrac,
                (sublen * (i + 1)) / len AS endfrac
            FROM
                generate_series(0, floor(len / sublen)::integer) AS t (i)
                -- skip last i if line length is exact multiple of sublen
            WHERE (sublen * i) / len <> 1.0) AS d2
)
SELECT
    m.osm_id::bigint losmid,
    m.lgt_seg_real,
    st_distance (m.way, t.way) / m.merca_coef AS dist INTO TABLE river_tmp2newz
FROM
    lines_split AS m
    INNER JOIN tuples_with_river AS q ON m.osm_id = q.lines_osm_id
    INNER JOIN river_proxy t ON t.osm_id = q.river_osm_id
WHERE
    st_distance (m.way, t.way) / m.merca_coef < 165;

SELECT
    now();

ANALYZE river_tmp2newz;

SELECT
    now();

SELECT
    losmid,
    m.lgt_seg_real,
    sum(1 / ((dist + 50) / 50)) / ((m.lgt_seg_real / 20)::integer + 1) AS sum_river_factor INTO river_tmp2new
FROM
    river_tmp2newz m
GROUP BY
    m.losmid,
    m.lgt_seg_real;

SELECT
    now();

DROP TABLE river_tmp2newz;

DROP TABLE tuples_with_river;

DROP TABLE river_proxy;

ANALYZE river_tmp2new;

SELECT
    now();

SELECT
    losmid,
    CASE WHEN y.sum_river_factor < 0.22 THEN
        '1'
    WHEN y.sum_river_factor < 0.35 THEN
        '2'
    WHEN y.sum_river_factor < 0.5 THEN
        '3'
    WHEN y.sum_river_factor < 0.75 THEN
        '4'
    WHEN y.sum_river_factor < 0.98 THEN
        '5'
    ELSE
        '6'
    END AS river_class INTO TABLE river_tags
FROM
    river_tmp2new y
WHERE
    y.sum_river_factor > 0.03;

SELECT
    now();

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

-------------------------------------------------------------
-- create pseudo-tags for forest
--
-- create first a table of the polygons with forest
SELECT
    now();

SELECT
    osm_id::bigint,
    leisure,
    landuse,
    p.natural,
    p.water,
    way,
    ST_Buffer (way, 32.15 * st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 3857)) / st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 4326)::geography)) AS way32 INTO TABLE osm_poly_forest
FROM
    polygons p
WHERE
    st_area (ST_Transform (p.way, 4326)::geography) > 1500
    AND ((p.landuse IN ('forest', 'allotments', 'flowerbed', 'orchard', 'vineyard', 'recreation_ground', 'village_green'))
        OR p.leisure IN ('garden', 'park', 'nature_reserve'))
    AND st_area (ST_Transform (p.way, 4326)::geography) BETWEEN 5000 AND 5000000000
ORDER BY
    way;

SELECT
    now();

CREATE INDEX osm_poly_forest_osm_id_idx ON osm_poly_forest (osm_id) WITH (fillfactor = '100');

SELECT
    now();

ANALYZE osm_poly_forest;

-- create a table of the lines within forests (green_factor is nomally 1, but 5 is better to calculate class 6 )
SELECT
    now();

SELECT
    m.osm_id::bigint,
    m.highway,
    6 AS green_factor INTO TABLE lines_within_forest
FROM
    lines AS m
    INNER JOIN osm_poly_forest q ON ST_Within (m.way, q.way)
WHERE
    m.highway IS NOT NULL
GROUP BY
    m.osm_id,
    m.highway,
    m.way;

CREATE INDEX lines_within_forest_osm_id_idx ON lines_within_forest (osm_id) WITH (fillfactor = '100');

SELECT
    now();

ANALYZE lines_within_forest;

-- create a tuple table (lines+polygons) of the lines near but not within forests
SELECT
    m.osm_id::bigint AS lines_osm_id,
    m.highway,
    m.merca_coef,
    m.way AS lines_way,
    q.osm_id AS forest_osm_id INTO TABLE tuples_limit_forest
FROM
    lines AS m
    INNER JOIN osm_poly_forest AS q ON ST_Intersects (m.way, q.way32)
WHERE
    m.highway IS NOT NULL
    AND m.highway NOT IN ('proposed', 'construction')
    AND (st_length (m.way) / m.merca_coef) < 40000
    AND m.osm_id::bigint NOT IN (
        SELECT
            osm_id
        FROM
            lines_within_forest);

SELECT
    now();

CREATE INDEX tuples_lines_osm_id_idx ON tuples_limit_forest (lines_osm_id) WITH (fillfactor = '100');

SELECT
    now();

ANALYZE tuples_limit_forest;

SELECT
    now();

-- create a table with only the lines near but not within forests
SELECT
    m.lines_osm_id osm_id,
    m.highway,
    m.merca_coef,
    m.lines_way AS way INTO TABLE lines_limit_forest
FROM
    tuples_limit_forest AS m
GROUP BY
    m.lines_osm_id,
    m.highway,
    m.merca_coef,
    m.lines_way;

SELECT
    now();

CREATE INDEX lines_limit_forest_osm_id_idx ON lines_limit_forest (osm_id) WITH (fillfactor = '100');

SELECT
    now();

ANALYZE lines_limit_forest;

-- calculate the forest factor (or green_factor) for the lines at forest limit..
-- spilt the line into 20 meter sections
-- calculate the distance section-forest
SELECT
    now();

WITH lines_split AS (
    SELECT
        osm_id,
        highway,
        merca_coef,
        ST_LineSubstring (d.way, startfrac, LEAST (endfrac, 1)) AS way,
        len / merca_coef AS lgt_seg_real
    FROM (
        SELECT
            osm_id,
            highway,
            merca_coef,
            way,
            st_length (way) len,
            (20 * merca_coef) sublen
        FROM
            lines_limit_forest) AS d
        CROSS JOIN LATERAL (
            SELECT
                i,
                (sublen * i) / len AS startfrac,
                (sublen * (i + 1)) / len AS endfrac
            FROM
                generate_series(0, floor(len / sublen)::integer) AS t (i)
                -- skip last i if line length is exact multiple of sublen
            WHERE (sublen * i) / len <> 1.0) AS d2
)
SELECT
    m.osm_id,
    lgt_seg_real,
    st_distance (m.way, t.way) / m.merca_coef AS dist INTO TABLE forest_tmp2newz
FROM
    lines_split AS m
    INNER JOIN tuples_limit_forest AS q ON m.osm_id = q.lines_osm_id
    INNER JOIN osm_poly_forest t ON t.osm_id = q.forest_osm_id
WHERE
    st_distance (m.way, t.way) / m.merca_coef < 65;

SELECT
    now();

ANALYZE forest_tmp2newz;

SELECT
    now();

SELECT
    m.osm_id,
    m.lgt_seg_real,
    sum(1 / ((dist + 25) / 25)) / ((m.lgt_seg_real / 20)::integer + 1) AS green_factor INTO forest_tmp2new
FROM
    forest_tmp2newz m
GROUP BY
    m.osm_id,
    m.lgt_seg_real;

SELECT
    now();

ANALYZE forest_tmp2new;

DROP TABLE forest_tmp2newz;

DROP TABLE osm_poly_forest;

DROP TABLE tuples_limit_forest;

SELECT
    now();

-- merge lines_within_forest with lines_limit_forest
WITH forest_tmp3new AS (
    SELECT
        *
    FROM (
        SELECT
            osm_id,
            green_factor
        FROM
            forest_tmp2new AS part1
        UNION
        SELECT
            osm_id,
            green_factor
        FROM
            lines_within_forest) AS part2
)
SELECT
    y.osm_id losmid,
    CASE WHEN y.green_factor < 0.32 THEN
        '1'
    WHEN y.green_factor < 0.5 THEN
        '2'
    WHEN y.green_factor < 0.7 THEN
        '3'
    WHEN y.green_factor < 0.92 THEN
        '4'
    WHEN y.green_factor < 5 THEN
        '5'
    ELSE
        '6'
    END AS forest_class INTO TABLE forest_tags
FROM
    forest_tmp3new y
WHERE
    y.green_factor > 0.1;

ANALYZE forest_tags;

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

SELECT
    now();

-- create tables for traffic
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

ANALYZE primsecter15k;

SELECT
    now();

-- consistency check on population, first evaluate and documents it
WITH cities_x AS (
    SELECT
        a.name,
        a.name_en,
        place,
        osm_id,
        replace(a.population, '.', '')::bigint population,
        a.way
    FROM
        cities a
    WHERE
        a.population IS NOT NULL
        AND isnumeric (a.population)
        AND a.place IN ('village', 'town', 'city', 'municipality'))
SELECT
    name,
    name_en,
    place,
    population,
    osm_id
FROM
    cities_x a
WHERE (place = 'village'
    AND a.population > 90000)
    OR (place = 'town'
        AND a.population > 1500000)
    OR (place = 'city'
        AND a.population > 40000000)
ORDER BY
    place,
    Population DESC;

WITH cities_relx AS (
    SELECT
        a.name,
        a.name_en,
        place,
        osm_id,
        replace(a.population, '.', '')::bigint population,
        a.way
    FROM
        cities_rel a
    WHERE
        a.population IS NOT NULL
        AND isnumeric (a.population)
        AND a.place IN ('village', 'town', 'city', 'municipality'))
SELECT
    name,
    name_en,
    place,
    population,
    osm_id
FROM
    cities_relx a
WHERE (place = 'village'
    AND a.population > 90000)
    OR (place = 'town'
        AND a.population > 1500000)
    OR (place = 'city'
        AND a.population > 40000000)
    OR (place IS NULL
        AND a.population > 40000000)
ORDER BY
    place,
    Population DESC;

-- now store the inconstencies
WITH cities_x AS (
    SELECT
        a.name,
        a.name_en,
        place,
        osm_id,
        replace(a.population, '.', '')::bigint population,
        a.way
    FROM
        cities a
    WHERE
        a.population IS NOT NULL
        AND isnumeric (a.population)
        AND a.place IN ('village', 'town', 'city', 'municipality'))
SELECT
    name,
    name_en,
    place,
    population,
    osm_id INTO TABLE cities_incon
FROM
    cities_x a
WHERE (place = 'village'
    AND a.population > 90000)
    OR (place = 'town'
        AND a.population > 1500000)
    OR (place = 'city'
        AND a.population > 40000000)
ORDER BY
    place,
    Population DESC;

WITH cities_relx AS (
    SELECT
        a.name,
        a.name_en,
        place,
        osm_id,
        replace(a.population, '.', '')::bigint population,
        a.way
    FROM
        cities_rel a
    WHERE
        a.population IS NOT NULL
        AND isnumeric (a.population)
        AND a.place IN ('village', 'town', 'city', 'municipality'))
SELECT
    name,
    name_en,
    place,
    population,
    osm_id INTO TABLE cities_rel_incon
FROM
    cities_relx a
WHERE (place = 'village'
    AND a.population > 90000)
    OR (place = 'town'
        AND a.population > 1500000)
    OR (place = 'city'
        AND a.population > 40000000)
    OR (place IS NULL
        AND a.population > 40000000)
ORDER BY
    place,
    Population DESC;

-- and eliminate the inconsistencies
UPDATE
    cities
SET
    population = 0
WHERE
    osm_id IN (
        SELECT
            osm_id
        FROM
            cities_incon);

UPDATE
    cities_rel
SET
    population = 0
WHERE
    osm_id IN (
        SELECT
            osm_id
        FROM
            cities_rel_incon);

SELECT
    now();

-- create a new "town" table based on cities_rel (with a valid/numeric population) AND fetch by need the population from the cities table)
-- clean the cities table (when population is null or population is not numeric or unusable)
SELECT
    a.name,
    a.name_en,
    replace(a.population, '.', '')::bigint population,
    a.way INTO TABLE cities_ok
FROM
    cities a
WHERE
    a.population IS NOT NULL
    AND isnumeric (a.population)
    AND a.place IN ('village', 'town', 'city', 'municipality');

ANALYZE cities_ok;

SELECT
    now();

-- clean the cities_rel table (when population is not numeric or unusable)
SELECT
    a.name AS name,
    a.name_en AS name_en,
    a.place AS place,
    a.admin_level,
    CASE WHEN a.population IS NOT NULL
        AND isnumeric (a.population) THEN
        a.population::numeric
    ELSE
        NULL
    END AS population,
    a.way INTO TABLE cities_rel_ok
FROM
    cities_rel a
WHERE
    boundary IN ('administrative', 'ceremonial');

CREATE INDEX cities_ok_idx ON public.cities_ok USING gist (way) WITH (fillfactor = '100');

CREATE INDEX cities_rel_ok_idx ON public.cities_rel_ok USING gist (way) WITH (fillfactor = '100');

SELECT
    now();

ANALYZE cities_rel_ok;

-- select town + population + way starting with cities_ok .... (to catch specials cases as ex. "Berlin" which is tagged with "admin_level=4")
--
SELECT
    a.name AS name,
    a.name_en AS name_en,
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
        WHERE (st_area (b.way) / 1000000 < 10000
            AND b.admin_level = '8'
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (st_area (b.way) / 1000000 < 10000
        AND b.admin_level = '8'
        AND a.name = b.name
        AND st_intersects (a.way, b.way))
LIMIT 1)
-- Australia admin_level=7
    WHEN (
        SELECT
            way
        FROM
            cities_rel_ok b
        WHERE (st_area (b.way) / 1000000 < 10000
            AND b.admin_level = '7'
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (st_area (b.way) / 1000000 < 10000
        AND b.admin_level = '7'
        AND a.name = b.name
        AND st_intersects (a.way, b.way))
LIMIT 1)
-- Paris admin_level=6 (old !)
    WHEN (
        SELECT
            way
        FROM
            cities_rel_ok b
        WHERE (st_area (b.way) / 1000000 < 10000
            AND b.admin_level = '6'
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (st_area (b.way) / 1000000 < 10000
        AND b.admin_level = '6'
        AND a.name = b.name
        AND st_intersects (a.way, b.way))
LIMIT 1)
-- Bengkulu admin_level=5!
    WHEN (
        SELECT
            way
        FROM
            cities_rel_ok b
        WHERE (st_area (b.way) / 1000000 < 10000
            AND b.admin_level = '5'
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (st_area (b.way) / 1000000 < 10000
        AND b.admin_level = '5'
        AND a.name = b.name
        AND st_intersects (a.way, b.way))
LIMIT 1)
-- Berlin admin_level=4! , but Beijing/Shangai administrative-regions have the same name and  area>20000 km*2 !!!
    WHEN (
        SELECT
            way
        FROM
            cities_rel_ok b
        WHERE (st_area (b.way) / 1000000 < 10000
            AND b.admin_level = '4'
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (st_area (b.way) / 1000000 < 10000
        AND b.admin_level = '4'
        AND a.name = b.name
        AND st_intersects (a.way, b.way))
LIMIT 1)
-- London admin_level is null
    WHEN (
        SELECT
            way
        FROM
            cities_rel_ok b
        WHERE (st_area (b.way) / 1000000 < 10000
            AND b.admin_level IS NULL
            AND b.place IN ('town', 'city', 'village', 'municipality')
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (st_area (b.way) / 1000000 < 10000
        AND b.admin_level IS NULL
        AND b.place IN ('town', 'city', 'village', 'municipality')
        AND a.name = b.name
        AND st_intersects (a.way, b.way))
LIMIT 1)
-- Singapore admin_level is 2, place=city in cities_rel
    WHEN (
        SELECT
            way
        FROM
            cities_rel_ok b
        WHERE (st_area (b.way) / 1000000 < 10000
            AND b.admin_level = '2'
            AND b.place IN ('town', 'city', 'village', 'municipality')
            AND a.name = b.name
            AND st_intersects (a.way, b.way))
    LIMIT 1) IS NOT NULL THEN
(
    SELECT
        way
    FROM
        cities_rel_ok b
    WHERE (st_area (b.way) / 1000000 < 10000
        AND b.admin_level = '2'
        AND b.place IN ('town', 'city', 'village', 'municipality')
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

CREATE INDEX cities_intermed3_idx ON public. cities_intermed3 USING gist (way) WITH (fillfactor = '100');

SELECT
    now();

ANALYZE cities_intermed3;

-- select town + population + way starting with cities_rel_ok ....
SELECT
    a.name AS name,
    a.name_en AS name_en,
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
    AND (a.place IS NULL
        OR a.place IN ('town', 'city', 'village', 'municipality'))
ORDER BY
    a.name;

SELECT
    now();

-- merge
WITH intermed5 AS (
    SELECT
        name,
        max(name_en) AS name_en,
        max(population) AS population,
        way,
        max(way0) AS way0,
        st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 3857)) / st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 4326)::geography) AS merca_coef
    FROM ((
            SELECT
                name,
                name_en,
                population,
                way,
                way0
            FROM
                cities_intermed3)
        UNION (
            SELECT
                name,
                name_en,
                population,
                way,
                way0
            FROM
                cities_intermed4)) a
    WHERE
        population IS NOT NULL
    GROUP BY
        name,
        way
    ORDER BY
        population
)
SELECT
    name,
    name_en,
    population,
    way,
    CASE WHEN way0 IS NULL THEN
        st_centroid (way)::geometry
    ELSE
        way0::geometry
    END AS way0,
    merca_coef INTO TABLE cities_all
FROM (
    SELECT
        *
    FROM
        intermed5 a) b;

SELECT
    now();

ANALYZE cities_all;

-------------------------------------------
-- create tags for  TRAFFIC
-----------------------------------------
-- OSM data used to calculate/estimate the traffic:
--    population of towns (+ distance from position to the towns)
--    industrial& retail areas (landuse=industrial/retail)  (consider surface of the areas and distance from position)
--    airports international
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
    AND q.population > 200
GROUP BY
    m.osm_id,
    m.highway,
    m.way
ORDER BY
    populate_factor;

SELECT
    now();

ANALYZE traffic_tmp;

-- prepare some special tables
--  the intersections motorway_link with primary/secondary/tertiary deliver the motorway acccesses....
SELECT
    * INTO TABLE lines_link
FROM
    lines
WHERE
    highway IN ('motorway_link', 'trunk_link');

SELECT
    m.osm_id losmid,
    m.highway,
    m.way,
    ST_Expand (m.way, 643 * st_length (ST_Transform (m.way, 3857)) / st_length (ST_Transform (m.way, 4326)::geography)) way2,
    ST_Expand (m.way, 1286 * st_length (ST_Transform (m.way, 3857)) / st_length (ST_Transform (m.way, 4326)::geography)) way3,
    st_length (ST_Transform (m.way, 3857)) / st_length (ST_Transform (m.way, 4326)::geography) AS merca_coef INTO TABLE motorway_access
FROM
    lines AS m
    INNER JOIN lines_link AS q ON ST_Intersects (m.way, q.way)
WHERE
    m.highway IN ('primary', 'secondary', 'tertiary')
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

ANALYZE motorway_access;

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
    sum(st_length (q.way) / (6430 * q.merca_coef)) motorway_factor INTO TABLE motorway_access_2000
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

ANALYZE motorway_access_2000;

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
    -- where (q.ele not ~ '^\d+(\.\d+)?$') and q.ele :: decimal > 400
GROUP BY
    m.osm_id,
    m.way
ORDER BY
    peak_cnt DESC;

SELECT
    now();

--
-- traffic due to industrial or retail areas ... (exceptions/not considered: solar & wind parks!)
-- traffic due to aerodromes
--
SELECT
    now();

SELECT
    name,
    way,
    st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 3857)) / st_length (ST_Transform (st_makeline (st_startpoint (way), st_centroid (way)), 4326)::geography) AS merca_coef INTO TABLE poly_industri
FROM
    polygons
WHERE (landuse IN ('industrial', 'retail'))
    OR (aeroway = 'aerodrome'
        AND aerodrome = 'international')
    AND (plant_method IS NULL
        OR plant_method NOT IN ('photovoltaic'))
    AND (plant_source IS NULL
        OR plant_source NOT IN ('solar', 'wind'));

SELECT
    now();

ANALYZE poly_industri;

SELECT
    name,
    way,
    ST_Centroid (way) way0,
    ST_Buffer (way, 12860 * merca_coef) AS way2,
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
    INNER JOIN industri AS q ON ST_intersects (m.way0, q.way2)
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
    generate_grid((ST_GeomFromText('POLYGON((0 9000000, -18000000 9000000, -18000000 -9000000, 0 -9000000, 0  9000000))')), 10000, 3857);

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
    motorway_factor = -15
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

-- create town tags.................
--  create "town" tags
-- get the highways within the town
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
    lines AS m
    INNER JOIN cities_all AS q ON ST_Within (m.way, q.way)
WHERE
    m.highway IS NOT NULL
    AND q.population > '50000'
ORDER BY
    town_factor DESC;

SELECT
    now();

ANALYSE town_tmp;

--
SELECT
    losmid::bigint,
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

ANALYSE town_tags;

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

ANALYSE all_tags;

SELECT
    now();

