--  calculation of new tags (estimated_noise_class, estimated_river_class,estimated_forest_class, estimated_town_class, estimated_traffic_class)
--  Ess Bee version 08/05/2023

set client_encoding to UTF8;
select now();

-- create new tables for tuning
SELECT  osm_id :: bigint , highway, waterway, width, maxspeed,
case 
when maxspeed is null              then 0  
when not (maxspeed ~ '^[0-9\.]+$') then 0  
when maxspeed :: numeric > '105'   then 1
when maxspeed :: numeric > '75'    then 2
else                                    3
end as maxspeed_class
, ST_Buffer(way,50) as way
into table osm_line_buf_50
from lines where highway is not null or waterway in ('river','canal');

select now();

-- modify "way" by large waterways !!" (example Rhein ==> width = 400 ....) enlarge a bit the "50 meter" buffer
UPDATE
    osm_line_buf_50
SET
    way = st_buffer(way, (width :: numeric / 10))
WHERE
    waterway = 'river' and width is not null and (width ~ '^[0-9\.]+$') and width :: numeric > 15;



SELECT  osm_id :: bigint,   leisure, landuse, p.natural, p.water, ST_Buffer(way,50) as way
into table osm_poly_buf_50
from polygons p where 
-- do not consider small surfaces
st_area(p.way) > 1000
and p.natural in ('water') or (p.landuse in ('forest','allotments','flowerbed','orchard','vineyard','recreation_ground','village_green')  
     or p.leisure in ( 'park','nature_reserve'));

SELECT  osm_id :: bigint,   leisure, landuse, p.natural, p.water, ST_Buffer(way,70) as way
into table osm_poly_buf_120
from osm_poly_buf_50 p ;

select now();

-- create indexes
CREATE INDEX osm_line_buf_50_idx ON public.osm_line_buf_50 USING gist (way) WITH (fillfactor='100');
ANALYZE;


select
 osm_id, highway, way, 
ST_Expand(way, 15000) way2,
ST_Centroid(way) way0
into table primsecter15k
from lines
where  highway in ('primary','primary_link', 'secondary','secondary_link', 'tertiary');


CREATE INDEX primsecter15k_idx2 ON public.primsecter15k USING gist (way2) WITH (fillfactor='100');

CREATE INDEX primsecter15k_idx1 ON public.primsecter15k USING gist (way) WITH (fillfactor='100');
CREATE INDEX primsecter15k_idx0 ON public.primsecter15k USING gist (way0) WITH (fillfactor='100');

select now();



-- create a new "town" table based on cities_rel (with a valid/numeric population) AND fetch by need the population from the cities table)  

-- clean the cities table (when population is null or population is not numeric or unusable)
select 
a.name,  
REPLACE(a.population, '.', '') :: bigint population,
a.way  
into cities_ok 
from cities a where a.population is not null and (a.population ~ '^[0-9\.]+$')
and a.place in ('town', 'city', 'municipality'); 

-- clean the cities_rel table (when population is not numeric or unusable)
select 
a.name as name,
a.admin_level, 
case
when a.population is not null and (a.population ~ '^[0-9\.]+$') then REPLACE(a.population, '.', '') :: bigint
else null
end as population,
a.way
into cities_rel_ok
from cities_rel a;

-- select town + population + way starting with cities_rel_ok .... 
select 
a.name as name, 
case
when a.population is not null then a.population
when b.population is not null then b.population
else null
end as population,
a.way
into cities_intermed1
from cities_rel_ok a
left outer join cities_ok b on a.name = b.name  
where  a.admin_level = '8'
order by a.name;

-- select town + population + way starting with cities_ok .... (to catch specials cases as ex. "Berlin" which is tagged with "admin_level=4")

select 
a.name as name, 
a.population,
case
when b.way is not null then b.way
-- stupid case (ex. "Ebingen": no relation available, so no administrattive surface ... create a dummy area with 2000 m radius !
else st_buffer(a.way, 2000)
end as way
into cities_intermed2
from cities_ok a
left outer join cities_rel_ok b on a.name = b.name
and b.way is not null
and b.admin_level = '8'
order by name;

select name,
max (population) as population,
way,
st_centroid(way) as way0
into cities_all
from ((select * from cities_intermed1 where population is not null) union (select * from cities_intermed2)) a 
where population is not null
-- and population > 20000
group by name, way
order by population;
select now();


-- create tags for noise 

-- create raw data
--     when several highways-segments are producing noise, aggregate the noises using the "ST_Union" of the segments!
--     (better as using "sum" or "max" that do not deliver good factors) 

SELECT
    m.osm_id losmid, m.highway lhighway, q.highway as qhighway, q.maxspeed_class,
    case 
    when q.highway in ('motorway', 'motorway_link','trunk','trunk_link') and q.maxspeed_class < 1.1 then
     	st_area(st_intersection(m.way, ST_Union( q.way)))      / st_area(m.way)
    when q.highway in ('motorway', 'motorway_link','trunk','trunk_link')  then
     	st_area(st_intersection(m.way, ST_Union( q.way)))   	 / (1.5 * st_area(m.way))

    when q.highway in ('primary','primary_link')                        and q.maxspeed_class < 2.1  then
	st_area(st_intersection(m.way, ST_Union( q.way)))    	/ (2 * st_area(m.way))
    when q.highway in ('primary','primary_link')      then
	st_area(st_intersection(m.way, ST_Union( q.way)))     / (3 * st_area(m.way))

    when q.highway in ('secondary')                                    and q.maxspeed_class < 2.1 then
	st_area(st_intersection(m.way, ST_Union( q.way)))    	/ (3 * st_area(m.way))
    when q.highway in ('secondary') then 
	st_area(st_intersection(m.way, ST_Union( q.way)))    	/ (5 * st_area(m.way))
    end
as noise_factor
into table noise_tmp
FROM osm_line_buf_50 AS m
INNER JOIN osm_line_buf_50 AS q ON ST_Intersects(m.way, q.way) 
WHERE m.highway is not null
and q.highway in ('motorway', 'motorway_link','trunk','trunk_link','primary','primary_link','secondary')
GROUP BY losmid, lhighway, m.way, q.highway, q.maxspeed_class
order by noise_factor desc;

select now();


-- aggregate data: 
-- on "maxspeed_class take the sum of several highways (having different maxspeed-class) union is then not done, but not very frequent 
-- on "phighway"      take the sum of several highways (as probably several highways are producing noise at the point!

select losmid, lhighway, sum(noise_factor) as sum_noise_factor
into table noise_tmp2 
from noise_tmp 
group by losmid, lhighway
order by sum_noise_factor desc;


-- create the noise classes
SELECT    losmid, 
case
  when y.sum_noise_factor < 0.1  then '1'
  when y.sum_noise_factor < 0.25 then '2'
  when y.sum_noise_factor < 0.4  then '3'
  when y.sum_noise_factor < 0.55 then '4'
  when y.sum_noise_factor < 0.8  then '5'
  else '6' 
end as noise_class
into table noise_tags
from noise_tmp2 y 
where y.sum_noise_factor > 0.01;

select count(*) from noise_tags;
select noise_class, count(*) from noise_tags group by noise_class order by noise_class;

drop table noise_tmp2;
select now();



-- create tags for river



select xid , sum(water_river_see) as river_see 
into table river_tmp
from (
SELECT     m.osm_id  as xid,
  st_area(st_intersection(m.way, ST_Union( q.way))) / st_area(m.way)
  as water_river_see
FROM osm_line_buf_50 AS m
INNER JOIN osm_poly_buf_120 AS q ON ST_Intersects(m.way, q.way) 
WHERE  m.highway is not null
-- and st_area(q.way) > 90746  !!! filter on very small surfaces was set above !!!!!!!!!
and q.natural in ('water') and (q.water is null or q.water not in ('wastewater'))
GROUP BY m.osm_id, m.way
union
SELECT    m.osm_id as xid, 
    st_area(st_intersection(m.way, ST_Union( q.way))) / st_area(m.way)
     as water_river_see
FROM osm_line_buf_50 AS m
INNER JOIN osm_line_buf_50 AS q ON ST_Intersects(m.way, q.way) 
WHERE m.highway is not null
and
q.waterway in ('river','canal') 
GROUP BY m.osm_id, m.way
) as abcd
GROUP BY xid
order by river_see desc;


SELECT     y.xid losmid, 
case
  when y.river_see < 0.17  then '1'
  when y.river_see < 0.35  then '2'
  when y.river_see < 0.57  then '3'
  when y.river_see < 0.85  then '4'
  when y.river_see < 1   then '5'
  else '6' 
end as river_class
into table river_tags
from river_tmp y where y.river_see > 0.05;

select count(*) from river_tags;
select river_class, count(*) from river_tags group by river_class order by river_class;

select now();



-- create tags for forest



SELECT
    m.osm_id, m.highway,
    st_area(st_intersection(m.way, ST_Union( q.way))) / st_area(m.way)    
 as green_factor
into table forest_tmp
FROM osm_line_buf_50 AS m
INNER JOIN osm_poly_buf_50 AS q ON ST_Intersects(m.way, q.way) 
WHERE m.highway is not null
 and 
((q.landuse in ('forest','allotments','flowerbed','orchard','vineyard','recreation_ground','village_green') ) 
     or q.leisure in ( 'garden','park','nature_reserve'))
GROUP BY m.osm_id, m.highway, m.way
order by green_factor desc;

--
SELECT  y.osm_id losmid, 
case
  when y.green_factor < 0.1  then null
  when y.green_factor < 0.3  then '1'
  when y.green_factor < 0.6  then '2'
  when y.green_factor < 0.9    then '3'
  when y.green_factor < 1  then '4'
  when y.green_factor < 1.3   then '5'
  else '6' 
end as forest_class
into table forest_tags
from forest_tmp y where y.green_factor  > 0.1;

select count(*) from forest_tags;
select forest_class, count(*) from forest_tags group by forest_class order by forest_class;

select now();

--  create "town" tags
 
-- get the highways which intersect the town

SELECT
    m.osm_id losmid, m.highway lhighway,
    case 
    when q.population :: decimal > '2000000'    then  1
    when q.population :: decimal > '1000000'    then  0.8
    when q.population :: decimal > '400000'    then  0.6
    when q.population :: decimal > '150000'    then  0.4
    when q.population :: decimal > '80000'    then  0.2
    else 0.1  
    end  as town_factor
into table town_tmp
FROM osm_line_buf_50 AS m
INNER JOIN cities_all AS q ON ST_Intersects(m.way, q.way) 
WHERE m.highway is not null 
and q.population  > '50000' 
order by town_factor desc;

--

SELECT    losmid, 
case
  when y.town_factor =  0.1  then '1'
  when y.town_factor =  0.2  then '2'
  when y.town_factor =  0.4  then '3'
  when y.town_factor =  0.6  then '4'
  when y.town_factor =  0.8  then '5'
  else '6' 
end as town_class
into table town_tags
from 
(SELECT    losmid, max (town_factor) as town_factor
from town_tmp y
group by losmid) y;

select count(*) from town_tags;
select town_class, count(*) from town_tags group by town_class order by town_class;


--
--  substract the ways from town with a green tag (because administrative surface are some times too large)
--
delete from town_tags
where losmid in 
(SELECT losmid     FROM forest_tags);

delete from town_tags
where losmid in 
(SELECT losmid     FROM river_tags);


select count(*) from town_tags;
select town_class, count(*) from town_tags group by town_class order by town_class;

select now();



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

select now();
SELECT
    m.osm_id losmid, m.highway lhighway,
case
  when m.highway = 'tertiary' then
sum ( 10000 * q.population :: numeric /
    power( ((8 * sqrt(q.population :: numeric))  + 500 + ST_Distance(m.way0, q.way0) ) , 2)  * 0.4 )
  when m.highway in ('secondary', 'secondary_link') then
sum ( 10000 * q.population :: numeric /
    power( ((8 * sqrt(q.population :: numeric))  + 500 + ST_Distance(m.way0, q.way0) ) , 2)  * 0.6 )
  else
sum ( 10000 * q.population :: numeric /
    power( ((8 * sqrt(q.population :: numeric))  + 500 + ST_Distance(m.way0, q.way0) ) , 2)  )
  end
as populate_factor
into table traffic_tmp
FROM primsecter15k AS m
INNER JOIN cities_all AS q ON ST_DWithin(m.way0, q.way0, (5000 + (100000 * q.population / (q.population +  10000) )))
WHERE m.highway is not null
--and m.highway in ('primary','primary_link','secondary', 'secondary_link', 'tertiary')
and q.population > 200
GROUP BY m.osm_id, m.highway, m.way
order by populate_factor;
select now();

-- prepare some special tables 
--  the intersections motorway_link with primary/secondary/tertiary deliver the motorway acccesses....

SELECT
    m.osm_id losmid, 
 m.highway,
m.way,
ST_Expand(m.way, 1000) way2,
ST_Expand(m.way, 2000) way3
into table motorway_access
FROM lines AS m
INNER JOIN lines AS q ON ST_Intersects(m.way, q.way) 
WHERE q.highway  in ('motorway_link','trunk_link')
and m.highway in ('primary', 'secondary', 'tertiary')
group by m.osm_id, m.highway, m.way;

CREATE INDEX motorway_access_idx ON public.motorway_access USING gist (way2) WITH (fillfactor='100');
select now();



-- find out all the primary/secondary/tertiary within 1000 m and 2000 m from a motorway access
select now();

SELECT
    m.osm_id losmid, 
sum ( st_length(q.way) / (10000 )  )  motorway_factor
into table motorway_access_1000
FROM lines AS m
inner JOIN motorway_access AS q ON ST_Intersects(m.way, q.way2) 
WHERE m.highway in ('primary', 'primary_link', 'secondary','secondary_link', 'tertiary')
GROUP BY m.osm_id, m.way 
order by motorway_factor;
select now();

SELECT
    m.osm_id losmid, 
sum ( st_length(q.way) / (10000 )  )  motorway_factor
into table motorway_access_2000
FROM lines AS m
inner JOIN motorway_access AS q ON ST_Intersects(m.way, q.way3) 
WHERE m.highway in ('primary', 'primary_link', 'secondary','secondary_link', 'tertiary')
GROUP BY m.osm_id, m.way 
order by motorway_factor;
select now();



--
--  special regions: mountain_range with "peaks" ==> few highways ==> higher traffic !!! 
--  calculate the "peak_density"

select now();
SELECT
    m.osm_id losmid, 
count(  q.*) as peak_cnt,
sum (q.ele :: decimal) peak_sum_ele
into table peak_density
FROM primsecter15k AS m
INNER JOIN peak AS q ON ST_Intersects(m.way2, q.way) 
where (q.ele ~ '^[0-9\.]+$') and q.ele :: decimal > 400
GROUP BY m.osm_id, m.way 
order by peak_cnt desc;
select now();

--
-- traffic due to industrial parcs ...
--
select now();

select 
name, 
way, 
ST_Centroid(way) way0,
st_area(way) area,  
sqrt(st_area(way)) sqrt_area 
into industri
from polygons 
WHERE landuse = 'industrial'
and st_area(way) > 20000;

select now();
SELECT
    m.osm_id losmid, m.highway lhighway,
case
  when m.highway = 'tertiary' then 
sum ( area  / 
    power( ( sqrt_area  + 500 + ST_Distance(m.way0, q.way0) ) , 2)  * 0.6 )
  when m.highway in ('secondary', 'secondary_link') then 
sum ( area  / 
    power( ( sqrt_area  + 500 + ST_Distance(m.way0, q.way0) ) , 2)  * 0.8 )
  else 
sum ( area  / 
    power( ( sqrt_area  + 500 + ST_Distance(m.way0, q.way0) ) , 2)   )
  end 
as industrial_factor
into industri_tmp
FROM primsecter15k AS m
INNER JOIN industri AS q ON ST_dwithin(m.way0, q.way0, 20000) 
GROUP BY m.osm_id, m.highway, m.way 
order by industrial_factor;

select now();

-- create a grid to allow a fast calculation for highway_density and motorway_density

CREATE OR replace FUNCTION generate_grid(bound_polygon geometry, grid_step integer, srid integer default 2180)
RETURNS table(id bigint, geom geometry)
LANGUAGE plpgsql
AS $function$
DECLARE
Xmin int;
Xmax int;
Ymax int;
Ymin int;
query_text text;
BEGIN
Xmin := floor(ST_XMin(bound_polygon));
Xmax := ceil(ST_XMax(bound_polygon));
Ymin := floor(ST_YMin(bound_polygon));
Ymax := ceil(ST_YMax(bound_polygon));
   
  query_text := 'select row_number() over() id, st_makeenvelope(s1, s2, s1+$5, s2+$5, $6) geom
   from generate_series($1, $2+$5, $5) s1, generate_series ($3, $4+$5, $5) s2';

RETURN QUERY EXECUTE query_text using Xmin, Xmax, Ymin, Ymax, grid_step, srid;
END;
$function$
;

create table grid1 as select id, geom from generate_grid((ST_GeomFromText('POLYGON((0 9000000, 18000000 9000000, 18000000 -9000000, 0 -9000000, 0  9000000))')),10000,3857);
create table grid2 as select id, geom from generate_grid((ST_GeomFromText('POLYGON((0 9000000, -18000000 9000000, -18000000 -9000000, 0 -9000000, 0  9000000))')),10000,3857);

select geom into table grid from ((select  geom from grid1) union (select  geom from grid2)) a;

-- GRID  HIGHWAY_DENSITY
select now();
SELECT
sum ( st_length(q.way) / (10000 )  )  highway_factor,
m.geom way
into table grid_highway_density
FROM lines AS q
INNER JOIN grid AS m ON ST_Intersects(q.way, m.geom) 
where q.highway in ('primary','primary_link','secondary', 'secondary_link', 'tertiary')
GROUP BY  m.geom
order by highway_factor;
select now();

-- GRID MOTORWAY_DENSITY

select now();
SELECT
sum ( st_length(q.way) / (10000 )  )  motorway_factor,
m.geom way
into table grid_motorway_density
FROM lines AS q
INNER JOIN grid AS m ON ST_Intersects(q.way, m.geom) 
where q.highway in ('motorway', 'motorway_link','trunk','trunk_link')
GROUP BY  m.geom
order by motorway_factor;
select now();

-- CREATE INDEX grid_idx ON public.grid USING gist (geom) WITH (fillfactor='100');
CREATE INDEX grid_hwd_idx ON public.grid_highway_density USING gist (way) WITH (fillfactor='100');
CREATE INDEX grid_mwd_idx ON public.grid_motorway_density USING gist (way) WITH (fillfactor='100');


-- collect all exceptions on 1 table
select now();
SELECT    y.osm_id losmid,
  case when q.motorway_factor is null then 0
   else q.motorway_factor
   end as motorway_factor,
  case when x.peak_sum_ele is null then 0
       when x.peak_sum_ele > 500000 then 4
   else x.peak_sum_ele / 125000
    end as peak_sum_ele,
  case when z.highway_factor is null then 0
     else z.highway_factor
    end as highway_factor,
  case when w.industrial_factor is null then 0
       when w.industrial_factor > 1 then (1500 * 50)
       else (w.industrial_factor * 1500 * 50) 
    end as industrial_factor
 into table except_all_tmp
 from lines y
 left outer JOIN grid_motorway_density AS q ON st_dwithin(q.way, y.way, 5500)
 left outer JOIN peak_density AS x ON y.osm_id = x.losmid
 left outer JOIN industri_tmp AS w ON y.osm_id = w.losmid
 left outer JOIN grid_highway_density AS z ON st_dwithin(z.way, y.way, 5500)
 where y.highway in ('primary','primary_link','secondary','secondary_link', 'tertiary');
 select now();

select losmid, peak_sum_ele, avg(highway_factor) highway_factor, avg(motorway_factor) motorway_factor, industrial_factor 
into table except_all
from except_all_tmp
group by  losmid, peak_sum_ele, industrial_factor;

select now();

--  Do not apply the positiv effect of "motorway density" in proximity of motorway accesses!!!! 
UPDATE except_all
SET motorway_factor = 0
where losmid in (select losmid from motorway_access_2000);

-- quite direct at motorway accesses set a negativ effect !!!! 
UPDATE except_all
SET motorway_factor = -15
where losmid in (select losmid from motorway_access_1000);


select now();

-- class calculation with modifications using peaks, motorway_density and highway_density... 
-- 

SELECT    y.losmid :: bigint, 
case
  when ((y.populate_factor * 1200 * (1 + q.peak_sum_ele)) + q.industrial_factor) / ((30 + q.motorway_factor ) * (50 + q.highway_factor))  < 6  then '1'
  when ((y.populate_factor * 1200 * (1 + q.peak_sum_ele)) + q.industrial_factor) / ((30 + q.motorway_factor ) * (50 + q.highway_factor))  < 10 then '2'
  when ((y.populate_factor * 1200 * (1 + q.peak_sum_ele)) + q.industrial_factor) / ((30 + q.motorway_factor ) * (50 + q.highway_factor))  < 19  then '3'
  when ((y.populate_factor * 1200 * (1 + q.peak_sum_ele)) + q.industrial_factor) / ((30 + q.motorway_factor ) * (50 + q.highway_factor))  < 35 then '4'
  when ((y.populate_factor * 1200 * (1 + q.peak_sum_ele)) + q.industrial_factor) / ((30 + q.motorway_factor ) * (50 + q.highway_factor))  < 70 then '5'
  else '6' 
end as traffic_class
into table traffic_tags
from traffic_tmp y 
left outer JOIN except_all AS q ON y.losmid = q.losmid
order by traffic_class desc;

select now();

--statistics 
select traffic_class , count(losmid) cnt from traffic_tags group by traffic_class order by traffic_class;


--
--  put all tags together in 1 table (1 "direct" access per way in mapcreator)
--


select losmid :: bigint as losmid, noise_class, river_class, forest_class, town_class, traffic_class  
into table all_tags 
from river_tags 
natural full outer join noise_tags 
natural full outer join forest_tags 
natural full outer join town_tags 
natural full outer join traffic_tags 
order by losmid;

create index all_tags_ind on all_tags(losmid, noise_class, river_class, forest_class, town_class, traffic_class) WITH (fillfactor='100');
analyse;

select now();