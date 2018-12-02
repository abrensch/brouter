package btools.mapcreator;

import com.google.protobuf.InvalidProtocolBufferException;
import org.openstreetmap.osmosis.osmbinary.Fileformat;
import org.openstreetmap.osmosis.osmbinary.Osmformat;
import btools.util.LongList;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Converts PBF block data into decoded entities ready to be passed into an Osmosis pipeline. This
 * class is designed to be passed into a pool of worker threads to allow multi-threaded decoding.
 * <p/>
 * @author Brett Henderson
 */
public class BPbfBlobDecoder
{
    private String blobType;
    private byte[] rawBlob;

    private OsmParser parser;

    /**
     * Creates a new instance.
     * <p/>
     * @param blobType The type of blob.
     * @param rawBlob The raw data of the blob.
     * @param listener The listener for receiving decoding results.
     */
    public BPbfBlobDecoder( String blobType, byte[] rawBlob, OsmParser parser )
    {
        this.blobType = blobType;
        this.rawBlob = rawBlob;
        this.parser = parser;
    }

    public void process() throws Exception
    {
            if ("OSMHeader".equals(blobType))
            {
                processOsmHeader(readBlobContent());

            } else if ("OSMData".equals(blobType))
            {
                processOsmPrimitives(readBlobContent());

            } else
            {
              System.out.println("Skipping unrecognised blob type " + blobType);
            }
    }

    private byte[] readBlobContent() throws IOException
    {
        Fileformat.Blob blob = Fileformat.Blob.parseFrom(rawBlob);
        byte[] blobData;

        if (blob.hasRaw())
        {
            blobData = blob.getRaw().toByteArray();
        } else if (blob.hasZlibData())
        {
            Inflater inflater = new Inflater();
            inflater.setInput(blob.getZlibData().toByteArray());
            blobData = new byte[blob.getRawSize()];
            try
            {
                inflater.inflate(blobData);
            } catch (DataFormatException e)
            {
                throw new RuntimeException("Unable to decompress PBF blob.", e);
            }
            if (!inflater.finished())
            {
                throw new RuntimeException("PBF blob contains incomplete compressed data.");
            }
        } else
        {
            throw new RuntimeException("PBF blob uses unsupported compression, only raw or zlib may be used.");
        }

        return blobData;
    }

    private void processOsmHeader( byte[] data ) throws InvalidProtocolBufferException
    {
        Osmformat.HeaderBlock header = Osmformat.HeaderBlock.parseFrom(data);

        // Build the list of active and unsupported features in the file.
        List<String> supportedFeatures = Arrays.asList("OsmSchema-V0.6", "DenseNodes");
        List<String> activeFeatures = new ArrayList<String>();
        List<String> unsupportedFeatures = new ArrayList<String>();
        for (String feature : header.getRequiredFeaturesList())
        {
            if (supportedFeatures.contains(feature))
            {
                activeFeatures.add(feature);
            } else
            {
                unsupportedFeatures.add(feature);
            }
        }

        // We can't continue if there are any unsupported features. We wait
        // until now so that we can display all unsupported features instead of
        // just the first one we encounter.
        if (unsupportedFeatures.size() > 0)
        {
            throw new RuntimeException("PBF file contains unsupported features " + unsupportedFeatures);
        }

    }

    private Map<String, String> buildTags( List<Integer> keys, List<Integer> values, BPbfFieldDecoder fieldDecoder )
    {

        Iterator<Integer> keyIterator = keys.iterator();
        Iterator<Integer> valueIterator = values.iterator();
        if (keyIterator.hasNext())
        {
            Map<String, String> tags = new HashMap<String, String>();
            while (keyIterator.hasNext())
            {
                String key = fieldDecoder.decodeString(keyIterator.next());
                String value = fieldDecoder.decodeString(valueIterator.next());
                tags.put(key, value);
            }
            return tags;
        }
        return null;
    }

    private void processNodes( List<Osmformat.Node> nodes, BPbfFieldDecoder fieldDecoder )
    {
        for (Osmformat.Node node : nodes)
        {
            Map<String, String> tags = buildTags(node.getKeysList(), node.getValsList(), fieldDecoder);

            parser.addNode( node.getId(), tags, fieldDecoder.decodeLatitude(node
                    .getLat()), fieldDecoder.decodeLatitude(node.getLon()));
        }
    }

    private void processNodes( Osmformat.DenseNodes nodes, BPbfFieldDecoder fieldDecoder )
    {
        List<Long> idList = nodes.getIdList();
        List<Long> latList = nodes.getLatList();
        List<Long> lonList = nodes.getLonList();

        Iterator<Integer> keysValuesIterator = nodes.getKeysValsList().iterator();

        long nodeId = 0;
        long latitude = 0;
        long longitude = 0;

        for (int i = 0; i < idList.size(); i++)
        {
            // Delta decode node fields.
            nodeId += idList.get(i);
            latitude += latList.get(i);
            longitude += lonList.get(i);

            // Build the tags. The key and value string indexes are sequential
            // in the same PBF array. Each set of tags is delimited by an index
            // with a value of 0.
            Map<String, String> tags = null;
            while (keysValuesIterator.hasNext())
            {
                int keyIndex = keysValuesIterator.next();
                if (keyIndex == 0)
                {
                    break;
                }
                int valueIndex = keysValuesIterator.next();

                if (tags == null)
                {
                    tags = new HashMap<String, String>();
                }

                tags.put(fieldDecoder.decodeString(keyIndex), fieldDecoder.decodeString(valueIndex));
            }

            parser.addNode( nodeId, tags, ((double) latitude) / 10000000, ((double) longitude) / 10000000);
        }
    }

    private void processWays( List<Osmformat.Way> ways, BPbfFieldDecoder fieldDecoder )
    {
        for (Osmformat.Way way : ways)
        {
            Map<String, String> tags = buildTags(way.getKeysList(), way.getValsList(), fieldDecoder);

            // Build up the list of way nodes for the way. The node ids are
            // delta encoded meaning that each id is stored as a delta against
            // the previous one.
            long nodeId = 0;
            LongList wayNodes = new LongList( 16 );
            for (long nodeIdOffset : way.getRefsList())
            {
                nodeId += nodeIdOffset;
                wayNodes.add(nodeId);
            }

            parser.addWay( way.getId(), tags, wayNodes );
        }
    }

    private LongList fromWid;
    private LongList toWid;
    private LongList viaNid;

    private LongList addLong( LongList ll, long l )
    {
      if ( ll == null )
      {
        ll = new LongList( 1 );
      }
      ll.add( l );
      return ll;
    }

    private LongList buildRelationMembers(
            List<Long> memberIds, List<Integer> memberRoles, List<Osmformat.Relation.MemberType> memberTypes,
            BPbfFieldDecoder fieldDecoder )
    {
        LongList wayIds = new LongList( 16 );

        fromWid = toWid = viaNid = null;

        Iterator<Long> memberIdIterator = memberIds.iterator();
        Iterator<Integer> memberRoleIterator = memberRoles.iterator();
        Iterator<Osmformat.Relation.MemberType> memberTypeIterator = memberTypes.iterator();

        // Build up the list of relation members for the way. The member ids are
        // delta encoded meaning that each id is stored as a delta against
        // the previous one.
        long refId = 0;
        while (memberIdIterator.hasNext())
        {
            Osmformat.Relation.MemberType memberType = memberTypeIterator.next();
            refId += memberIdIterator.next();

            String role = fieldDecoder.decodeString( memberRoleIterator.next() );

            if ( memberType == Osmformat.Relation.MemberType.WAY ) // currently just waymembers
            {
              wayIds.add( refId );
              if ( "from".equals( role ) ) fromWid = addLong( fromWid, refId );
              if ( "to".equals( role ) ) toWid = addLong( toWid, refId );
            }
            if ( memberType == Osmformat.Relation.MemberType.NODE ) // currently just waymembers
            {
              if ( "via".equals( role ) ) viaNid = addLong( viaNid, refId );
            }
        }
        return wayIds;
    }

    private void processRelations( List<Osmformat.Relation> relations, BPbfFieldDecoder fieldDecoder )
    {
        for (Osmformat.Relation relation : relations)
        {
            Map<String, String> tags = buildTags(relation.getKeysList(), relation.getValsList(), fieldDecoder);

            LongList wayIds = buildRelationMembers( relation.getMemidsList(), relation.getRolesSidList(),
                    relation.getTypesList(), fieldDecoder);

            parser.addRelation( relation.getId(), tags, wayIds, fromWid, toWid, viaNid );
        }
    }

    private void processOsmPrimitives( byte[] data ) throws InvalidProtocolBufferException
    {
        Osmformat.PrimitiveBlock block = Osmformat.PrimitiveBlock.parseFrom(data);
        BPbfFieldDecoder fieldDecoder = new BPbfFieldDecoder(block);

        for (Osmformat.PrimitiveGroup primitiveGroup : block.getPrimitivegroupList())
        {
            processNodes(primitiveGroup.getDense(), fieldDecoder);
            processNodes(primitiveGroup.getNodesList(), fieldDecoder);
            processWays(primitiveGroup.getWaysList(), fieldDecoder);
            processRelations(primitiveGroup.getRelationsList(), fieldDecoder);
        }
    }

}
