package btools.statcoding.huffman;

import java.io.IOException;

import btools.statcoding.BitInputStream;

/**
 * Decoder for huffman-encoded objects. <br>
 * <br>
 * Uses a lookup-table of configurable size to speed up decoding. <br>
 * <br>
 * This is an abstract class because the method decodeObjectFromStream must be
 * implemented to decode the leafs of the huffman tree from the input stream.
 */
public abstract class HuffmanDecoder<V> {

    protected BitInputStream bis;

    private int lookupBits;
    private Object[] subtrees;
    private int[] lengths;

    /**
     * Decodes an object from the underlying input stream. The object returned is
     * actually not a new instance each time, but a leaf of the tree decoded during
     * init.
     *
     * @return obj the decoded object
     */
    public final V decodeObject() throws IOException {
        int idx = bis.decodeLookupIndex(lengths,lookupBits);
        Object node = subtrees[idx];
        while (node instanceof TreeNode) {
            TreeNode tn = (TreeNode) node;
            boolean nextBit = bis.decodeBit();
            node = nextBit ? tn.child2 : tn.child1;
        }
        return (V) node;
    }

    /**
     * Initialize this huffman decoder. That decodes the tree from the underlying
     * input stream and builds a lookup table of the given size.<br>
     * <br>
     * Calling init more then once can be used to use a different bit stream for
     * data decoding, not the one that was used for tree decoding. In that case the
     * lookupBits parameter is ignored.
     *
     * @param bis        the input stream to decode the tree and the symbols from
     * @param lookupBits use a lookup table of size 2^lookupBits for speedup
     */
    public void init(BitInputStream bis, int lookupBits) throws IOException {

        if (this.bis != null) {
            this.bis = bis;
            return;
        }
        if (lookupBits < 0 || lookupBits > 20) {
            throw new IllegalArgumentException("lookupBits out of range ( 0..20 ): " + lookupBits);
        }
        this.bis = bis;
        this.lookupBits = lookupBits;
        boolean hasSymbols = bis.decodeBit();
        if (hasSymbols) {
            subtrees = new Object[1 << lookupBits];
            lengths = new int[1 << lookupBits];
            decodeTree(0, 0);
        }
    }

    /**
     * Decode the objects that this huffman encoder operates on from the underlying
     * bit stream. This method is called while decoding the huffman tree.
     *
     * @return the decoded object
     */
    protected abstract V decodeObjectFromStream() throws IOException;

    private Object decodeTree(int offset, int bits) throws IOException {
        boolean isNode = bis.decodeBit();
        int step = bits <= lookupBits ? 1 << (lookupBits-bits) : 0;
        if (isNode) {
            Object child1 = decodeTree(offset, bits + 1);
            Object child2 = decodeTree(offset + (step>>1), bits + 1);
            if (bits < lookupBits) {
                return null;
            }
            TreeNode node = new TreeNode();
            node.child1 = child1;
            node.child2 = child2;
            if (bits == lookupBits) {
                subtrees[offset] = node;
                lengths[offset] = bits;
            }
            return node;
        }
        Object leaf = decodeObjectFromStream();
        if (step > 0) {
            for (int i = offset; i < offset+step; i ++) {
                subtrees[i] = leaf;
                lengths[i] = bits;
            }
        }
        return leaf;
    }

    private static final class TreeNode {
        public Object child1;
        public Object child2;
    }
}
