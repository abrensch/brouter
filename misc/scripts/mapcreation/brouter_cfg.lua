-- special config to calcule pseudo-tags /  "Brouter project"

local srid = 3857


-- 3857 (projection) SHOULD BE USED here for distance calculation ... (not srid = 4326 !)
-- https://gis.stackexchange.com/questions/48949/epsg-3857-or-4326-for-web-mapping

local tables = {}

tables.lines = osm2pgsql.define_way_table('lines', {
   { column = 'name', type = 'text' },
   { column = 'osm_id', type = 'text' },
   { column = 'highway', type = 'text' },
   { column = 'maxspeed', type = 'text' },
   { column = 'waterway', type = 'text' },
   { column = 'width', type = 'text' },
   { column = 'way', type = 'linestring', projection = srid, not_null = true },
})

tables.polygons = osm2pgsql.define_area_table('polygons', {
   { column = 'osm_id', type = 'text' },
   { column = 'type', type = 'text' },
   { column = 'boundary', type = 'text' },
   { column = 'name', type = 'text' },
   { column = 'place', type = 'text' },
   { column = 'population', type = 'text' },
   { column = 'landuse', type = 'text' },
   { column = 'leisure', type = 'text' },
   { column = 'natural', type = 'text' },
   { column = 'water', type = 'text' },
   { column = 'power', type = 'text' },
   { column = 'plant_method', type = 'text' },
   { column = 'plant_source', type = 'text' },
   { column = 'aeroway', type = 'text' },
   { column = 'aerodrome', type = 'text' },
   { column = 'way', type = 'geometry', projection = srid, not_null = true },
})


tables.cities = osm2pgsql.define_node_table('cities', {
   { column = 'name', type = 'text' },
   { column = 'place', type = 'text' },
   { column = 'admin_level', type = 'text' },
   { column = 'osm_id', type = 'text' },
   { column = 'population', type = 'text' },
   { column = 'way', type = 'geometry', projection = srid, not_null = true },
})

-- create a table for cities from special relation
tables.cities_rel = osm2pgsql.define_relation_table('cities_rel', {
   { column = 'reltype', type = 'text' },
   { column = 'admin_level', type = 'text' },
   { column = 'boundary', type = 'text' },
   { column = 'name', type = 'text' },
   { column = 'place', type = 'text' },
   { column = 'osm_id', type = 'text' },
   { column = 'population', type = 'text' },
   { column = 'way', type = 'geometry', projection = srid, not_null = true },
})

-- create a table for peaks from nodes
tables.peak = osm2pgsql.define_node_table('peak', {
   { column = 'name', type = 'text' },
   { column = 'natural', type = 'text' },
   { column = 'osm_id', type = 'text' },
   { column = 'ele', type = 'text' },
   { column = 'way', type = 'geometry', projection = srid, not_null = true },
})

-- Helper function that looks at the tags and decides if this is possibly
-- an area.
function has_area_tags(tags)
   if tags.area == 'yes' then
      return true
   end
   if tags.area == 'no' then
      return false
   end

   return tags.place
          or tags.population
end

function get_plant_source(tags)
   local source = nil

   for _, key in ipairs({'source'}) do
      local a = tags['plant:' .. key]
      if a then
         source = a
      end
   end

   return source
end


function get_plant_method(tags)
   local method = nil

   for _, key in ipairs({'method'}) do
      local a = tags['plant:' .. key]
      if a then
         method = a
      end
   end

   return method
end

function osm2pgsql.process_node(object)

   if (object.tags.place == 'city' or object.tags.place == 'town' or object.tags.place == 'municipality') and has_area_tags(object.tags)  then
      tables.cities:insert({
         osm_id = object.id,
         name = object.tags.name,
         place = object.tags.place,
         admin_level = object.tags.admin_level,
         population = object.tags.population,
         way   = object:as_point()
      })
   end

   if (object.tags.natural == 'peak')   then
      tables.peak:insert({
         natural = object.tags.natural,
         name     = object.tags.name,
         ele = object.tags.ele,
         osm_id = object.id,
         way = object:as_point()
      })

   end

end

function osm2pgsql.process_way(object)
   local way_type = object:grab_tag('type')

   if  ( object.tags.natural == 'water') or (object.tags.landuse ~= nil ) or (object.tags.leisure ~= nil ) then
      tables.polygons:insert({
         name     = object.tags.name,
         osm_id    = object.id,
         type  = way_type,
         landuse = object.tags.landuse,
         leisure     = object.tags.leisure,
         natural    = object.tags.natural,
         water = object.tags.water,
         power = object.tags.power,
         plant_source = get_plant_source(object.tags),
         plant_method = get_plant_method(object.tags),
         aeroway = object.tags.aeroway,
         aerodrome = object.tags.aerodrome,
         way = object:as_polygon()
     })
   end

   if ( object.tags.highway ~= nil) or  ( object.tags.waterway ~= nil) then
      tables.lines:insert({
         name = object.tags.name,
         osm_id =  object.id,
         highway = object.tags.highway,
         waterway = object.tags.waterway,
         width = object.tags.width,
         maxspeed = object.tags.maxspeed,
         way = object:as_linestring()
      })
   end

end

function osm2pgsql.process_relation(object)
   local relation_type = object:grab_tag('type')


   tables.polygons:insert({
      osm_id    = object.id,
      type  = relation_type,
      boundary  = object.tags.boundary,
      name     = object.tags.name,
      place    = object.tags.place,
      population = object.tags.population,
      landuse = object.tags.landuse,
      leisure     = object.tags.leisure,
      natural    = object.tags.natural,
      water = object.tags.water,
      power = object.tags.power,
      plant_source = get_plant_source(object.tags),
      plant_method = get_plant_method(object.tags),
      aeroway = object.tags.aeroway,
      aerodrome = object.tags.aerodrome,
      way = object:as_multipolygon()
   })

   --   if (relation_type == 'boundary') and has_area_tags(object.tags)  then
   if (relation_type == 'boundary')   then
      tables.cities_rel:insert({
         reltype    = object.tags.relation_type,
         boundary = object.tags.boundary,
         admin_level = object.tags.admin_level,
         name     = object.tags.name,
         place    = object.tags.place,
         population = object.tags.population,
         osm_id = object.id,
         way = object:as_multipolygon()
      })
   end

end
