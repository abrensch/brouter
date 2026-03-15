
\pset fieldsep ;
\a
\pset footer
\o db_tags.csv

\C #####nodetags#####

select node_id, crossing_class from crossing_tags;

\C #####waytags#####

select * from all_tags; 
\o
