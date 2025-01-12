package io.muserver;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

class HuffmanDecoder {

    static HeaderString decodeFrom(ByteBuffer bb, int len, HeaderString.Type type) {
        var sb = new StringBuilder();
        var node = root;
        while (len > 0) {
            len--;

            int b = bb.get() & 0xff;
            for (int j = 7; j >= 0; j--) {
                var isLeft = (b & (1 << j)) == 0;
                node = isLeft ? node.left : node.right;
                if (node.leaf) {
                    char c = node.c;
                    sb.append(c);
                    node = root;
                }
            }
        }

        return HeaderString.valueOf(sb, type);
    }

    private HuffmanDecoder() {}

    private static final Node root;

    static {
        root = new Node(false, (char)0, null, null);
        char c = 0;
        for (HuffmanEncoder.HuffmanChar hc : HuffmanEncoder.mapping) {
                var node = root;
                for (int i = 0; i < hc.bitLength; i++) {
                    int mask = 1 << ((hc.bitLength - i)-1);
                    var isLeft = (hc.lsb & mask) == 0;
                    var isLeaf = i == hc.bitLength - 1;
                    if (isLeft) {
                        if (node.left == null) {
                            node.left = new Node(isLeaf, isLeaf ? c : (char)0, null, null);
                        }
                        node = node.left;
                    } else {
                        if (node.right == null) {
                            node.right = new Node(isLeaf, isLeaf ? c : (char)0, null, null);
                        }
                        node = node.right;
                    }
                }
            c++;
        }
    }

    private static class Node {
        private final boolean leaf;
        private final char c;
        @Nullable
        private Node left, right;

        private Node(boolean leaf, char c, @Nullable Node left, @Nullable Node right) {
            this.leaf = leaf;
            this.c = c;
            this.left = left;
            this.right = right;
        }

        @Override
        public String toString() {
            var sb = new StringBuilder();
            toString(sb, 0);
            return sb.toString();
        }

        private void toString(StringBuilder sb, int indent) {
            sb.append(leaf ? c : '-').append('\n');
            if (left != null) {
                sb.append(" ".repeat(indent)).append("0 -> ");
                left.toString(sb, indent + 2);
            }
            if (right != null) {
                sb.append(" ".repeat(indent)).append("1 -> ");
                right.toString(sb, indent + 2);
            }

        }
    }

}
