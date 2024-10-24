package io.muserver;

import java.nio.ByteBuffer;

class HuffmanDecoder {

    static HeaderString decodeFrom(ByteBuffer bb, int len, HeaderString.Type type) {
        var ascii = new byte[len];
        int i = 0;
        var node = root;
        var isFinished = false;
        while (!isFinished) {
            int b = bb.get() & 0xff;
            for (int j = 7; j >= 0; j--) {
                var isLeft = (b & (1 << j)) == 0;
                var child = isLeft ? node.left : node.right;
                if (child == null) {
                    break;
                }
                node = child;
                if (node.leaf) {
                    ascii[i] = (byte) node.c;
                    i++;
                    if (i == len) {
                        isFinished = true;
                        break;
                    }
                    node = root;
                }
            }
        }

        return HeaderString.valueOf(ascii, type);
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
        private Node left, right;

        private Node(boolean leaf, char c, Node left, Node right) {
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
