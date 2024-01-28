package btools.statcoding.huffman;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;

import btools.statcoding.BitOutputStream;

/**
 * Encoder for huffman-encoding objects. <br>
 * <br>
 * It detects identical objects and sorts them into a huffman-tree according to
 * their frequencies. <br>
 * <br>
 * Adapted for 2-pass encoding (pass 1: statistic collection, pass 2: encoding).
 */
public abstract class HuffmanEncoder<V> {

    protected BitOutputStream bos;

    private final HashMap<Object, TreeNode> symbols = new HashMap<>();
    private int pass;
    private long nextDistinctObjectId;

    /**
     * Encode an object. In pass 1 this gathers statistics, in pass 2 this actually
     * writes the huffman code to the underlying output stream.
     *
     * @param obj the object to encode
     */
    public void encodeObject(Object obj) throws IOException {
        TreeNode tn = symbols.get(obj);
        if (pass == 2) {
            if (tn == null) {
                throw new IllegalArgumentException("symbol was not seen in pass 1: " + obj);
            }
            bos.encodeBits(tn.bits, tn.code);
        } else {
            if (tn == null) {
                tn = new TreeNode(nextDistinctObjectId++);
                tn.obj = obj;
                symbols.put(obj, tn);
            }
            tn.frequency++;
        }
    }

    /**
     * Initialize the encoder. Must be called at the beginning of each of the 2
     * encoding passes. For pass 1 this only increments the pass-counter (and the
     * given bit stream is allowed to be null). For pass 2 this encodes the tree and
     * registers the bit stream for subsequent data encoding. Calling init more then
     * twice can be used to delegate data encoding to other bit streams.
     *
     * @param bos the bit stream to use for encoding tree and data
     */
    public void init(BitOutputStream bos) throws IOException {
        this.bos = bos;
        pass = Math.min(pass + 1, 2);
        if (pass == 2) { // encode the dictionary in pass 2

            boolean hasSymbols = !symbols.isEmpty();
            bos.encodeBit(hasSymbols);
            if (hasSymbols) {
                PriorityQueue<TreeNode> queue = new PriorityQueue<>(2 * symbols.size(),
                        new TreeNode.FrequencyComparator());
                queue.addAll(symbols.values());
                while (queue.size() > 1) {
                    TreeNode node = new TreeNode(nextDistinctObjectId++);
                    node.child1 = queue.poll();
                    node.child2 = queue.poll();
                    node.frequency = node.child1.frequency + node.child2.frequency;
                    queue.add(node);
                }
                TreeNode root = queue.poll();
                encodeTree(root, 0, 0);
            }
        }
    }

    private void encodeTree(TreeNode node, int bits, long code) throws IOException {
        node.bits = bits;
        node.code = code;
        boolean isNode = node.child1 != null;
        bos.encodeBit(isNode);
        if (isNode) {
            encodeTree(node.child1, bits + 1, (code << 1) );
            encodeTree(node.child2, bits + 1, (code << 1) | 1L);
        } else {
            encodeObjectToStream((V)node.obj);
        }
    }

    /**
     * Encode the objects that this huffman encoder operates on into the underlying
     * bit stream. This method is called while encoding the huffman tree.
     *
     * @param obj the object to encode
     */
    protected abstract void encodeObjectToStream(V obj) throws IOException;

    /**
     * Get a summary on the statistics used to build the current huffman tree as a
     * textual line. Reported are the total numbers of symbols seen, the number of
     * distinct values (=leafs of the tree), the total number of bits needed to
     * encode all symbols and the entropy. The ratio of encoding bits and entropy
     * shows how efficient huffman coding is on that data. (For arithmetic coding
     * encoding bits and entropy would be nearly equal).
     *
     * @return statistic summary as a textline
     */
    public String getStats() {
        double entropy = 0.;
        long bits = 0L;
        long totFreq = 0L;
        int distinct = 0;
        for (TreeNode tn : symbols.values()) {
            totFreq += tn.frequency;
            bits += tn.frequency * tn.bits;
            entropy += Math.log(tn.frequency) * tn.frequency;
            distinct++;
        }
        entropy = (Math.log(totFreq) * totFreq - entropy) / Math.log(2);
        return "symbols=" + totFreq + " distinct=" + distinct + " bits=" + bits + " entropy=" + entropy;
    }

    private static final class TreeNode {
        Object obj;
        int bits;
        long frequency, code;
        TreeNode child1, child2;
        long id; // serial id to make the comparator well-defined for equal frequencies

        TreeNode(long id) {
            this.id = id;
        }

        static class FrequencyComparator implements Comparator<TreeNode> {

            @Override
            public int compare(TreeNode tn1, TreeNode tn2) {
                // to avoid ordering instability, decide on the id if frequency is equal
                int result = Long.compare(tn1.frequency, tn2.frequency);
                return result != 0 ? result : Long.compare(tn1.id, tn2.id);
            }
        }

    }
}
