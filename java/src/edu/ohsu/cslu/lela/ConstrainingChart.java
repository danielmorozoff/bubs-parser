package edu.ohsu.cslu.lela;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import edu.ohsu.cslu.datastructs.narytree.BinaryTree;
import edu.ohsu.cslu.grammar.SparseMatrixGrammar;
import edu.ohsu.cslu.parser.chart.PackedArrayChart;

/**
 * Represents a parse chart populated with a constraining gold parse tree.
 * 
 * @author Aaron Dunlop
 */
public class ConstrainingChart extends PackedArrayChart {

    short[][] openCells;

    protected final int[] unaryChainLength;

    /** The length of the longest unary chain (i.e., the binary parent + any unary parents) */
    protected final int maxUnaryChainLength;

    /**
     * Populates a chart based on a gold tree, with one entry per cell (+ unary productions, if any). This chart can
     * then be used to constrain parses with a split grammar.
     * 
     * @param goldTree
     * @param sparseMatrixGrammar
     */
    public ConstrainingChart(final BinaryTree<String> goldTree, final SparseMatrixGrammar sparseMatrixGrammar) {

        super(goldTree.leaves(), ConstrainedChart.chartArraySize(goldTree.leaves(), goldTree.maxUnaryChainLength()),
                sparseMatrixGrammar);

        this.maxUnaryChainLength = goldTree.maxUnaryChainLength() + 1;
        this.beamWidth = this.lexicalRowBeamWidth = maxUnaryChainLength;
        this.unaryChainLength = new int[size * (size + 1) / 2];
        final IntArrayList tokenList = new IntArrayList();

        short start = 0;
        for (final BinaryTree<String> node : goldTree.preOrderTraversal()) {
            if (node.isLeaf()) {
                // Increment the start index every time we process a leaf. The lexical entry was already
                // populated (see below)
                start++;
                continue;
            }

            final int end = start + node.leaves();
            final short parent = (short) sparseMatrixGrammar.nonTermSet.getInt(node.label());
            final int cellIndex = cellIndex(start, end);
            final int unaryChainHeight = node.unaryChainHeight();
            if (unaryChainLength[cellIndex] == 0) {
                unaryChainLength[cellIndex] = unaryChainHeight + 1;
            }

            // Find the index of this non-terminal in the main chart array.
            // Unary children are positioned _after_ parents
            final int cellOffset = cellOffset(start, end);
            final int i = cellOffset + unaryChainLength[cellIndex] - unaryChainHeight - 1;

            nonTerminalIndices[i] = parent;

            if (node.rightChild() == null) {
                if (node.leftChild().isLeaf()) {
                    // Lexical production
                    midpoints[cellIndex] = 0;
                    final int child = sparseMatrixGrammar.lexSet.getIndex(node.leftChild().label());
                    packedChildren[i] = sparseMatrixGrammar.packingFunction.packLexical(child);

                    tokenList.add(child);
                } else {
                    // Unary production
                    final short child = (short) sparseMatrixGrammar.nonTermSet.getIndex(node.leftChild().label());
                    packedChildren[i] = sparseMatrixGrammar.packingFunction.packUnary(child);
                }
            } else {
                // Binary production
                final short leftChild = (short) sparseMatrixGrammar.nonTermSet.getIndex(node.leftChild().label());
                final short rightChild = (short) sparseMatrixGrammar.nonTermSet.getIndex(node.rightChild().label());
                packedChildren[i] = sparseMatrixGrammar.packingFunction.pack(leftChild, rightChild);
                midpoints[cellIndex] = (short) (start + node.leftChild().leaves());
            }
        }

        // Populate openCells with the start/end pairs of each populated cell. Used by {@link
        // ConstrainedCellSelector}
        this.openCells = new short[size * 2 - 1][2];
        int i = 0;
        for (short span = 1; span <= size; span++) {
            for (short s = 0; s < size - span + 1; s++) {
                if (nonTerminalIndices[cellOffset(s, s + span)] != Short.MIN_VALUE) {
                    openCells[i][0] = s;
                    openCells[i][1] = (short) (s + span);
                    i++;
                }
            }
        }

        this.tokens = tokenList.toIntArray();

        // Calculate all cell offsets
        for (int st = 0; st < size; st++) {
            for (int end = st + 1; end <= size; end++) {
                final int cellIndex = cellIndex(st, end);
                cellOffsets[cellIndex] = cellOffset(st, end);
            }
        }
    }

    protected ConstrainingChart(final ConstrainingChart constrainingChart, final int chartArraySize,
            final SparseMatrixGrammar sparseMatrixGrammar) {

        super(constrainingChart.size(), chartArraySize, sparseMatrixGrammar);

        this.unaryChainLength = constrainingChart.unaryChainLength;
        this.maxUnaryChainLength = constrainingChart.maxUnaryChainLength;
        this.openCells = constrainingChart.openCells;
    }

    @Override
    public float getInside(final int start, final int end, final int nonTerminal) {

        final int offset = cellOffsets[cellIndex(start, end)];
        for (int i = offset; i < offset + beamWidth; i++) {
            if (nonTerminalIndices[i] == nonTerminal) {
                return 0;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public BinaryTree<String> extractBestParse(final int start, final int end, final int parent) {
        return extractInsideParse(start, end);
    }

    public BinaryTree<String> extractInsideParse(final int start, final int end) {

        final int cellIndex = cellIndex(start, end);
        final int cellOffset = cellOffset(start, end);
        int entryIndex = cellOffset;

        final BinaryTree<String> tree = new BinaryTree<String>(
                grammar.nonTermSet.getSymbol(nonTerminalIndices[cellOffset]));
        BinaryTree<String> subtree = tree;

        // Add unary productions and binary parent
        while (entryIndex < cellOffset + unaryChainLength[cellIndex] - 1) {
            subtree = subtree.addChild(grammar.nonTermSet.getSymbol(nonTerminalIndices[++entryIndex]));
        }

        if (packedChildren[entryIndex] < 0) {
            // Lexical production
            final String sChild = grammar.lexSet.getSymbol(sparseMatrixGrammar.cartesianProductFunction()
                    .unpackLeftChild(packedChildren[entryIndex]));
            subtree.addChild(new BinaryTree<String>(sChild));
        } else {
            // Binary production
            final short edgeMidpoint = midpoints[cellIndex(start, end)];
            subtree.addChild(extractInsideParse(start, edgeMidpoint));
            subtree.addChild(extractInsideParse(edgeMidpoint, end));
        }
        return tree;
    }

    /**
     * @param cellIndex
     * @return The length of the unary chain in the specified cell (1 <= length <= maxUnaryChainLength).
     */
    int unaryChainLength(final int cellIndex) {
        return unaryChainLength[cellIndex];
    }

    /**
     * @param start
     * @param end
     * @return The length of the unary chain in the specified cell (1 <= length <= maxUnaryChainLength).
     */
    int unaryChainLength(final int start, final int end) {
        return unaryChainLength(cellIndex(start, end));
    }

    /**
     * For unit testing
     * 
     * @return maximum unary chain length
     */
    int maxUnaryChainLength() {
        return maxUnaryChainLength;
    }

    @Override
    public String toString() {
        return toString(true);
    }

    @Override
    public String toString(final boolean formatFractions) {
        final StringBuilder sb = new StringBuilder(1024);

        for (int span = 1; span <= size; span++) {
            for (int start = 0; start <= size - span; start++) {
                final int end = start + span;
                final int cellIndex = cellIndex(start, end);
                final int offset = offset(cellIndex);

                // Skip empty cells
                if (nonTerminalIndices[offset] < 0) {
                    continue;
                }

                sb.append("ConstrainedChartCell[" + start + "][" + end + "]\n");

                // Format entries from the main chart array
                // TODO Format unary productions
                for (int index = offset; index < offset + 2; index++) {
                    final int nonTerminal = nonTerminalIndices[index];

                    if (nonTerminal < 0) {
                        continue;
                    }

                    final int childProductions = packedChildren[index];
                    final int midpoint = midpoints[cellIndex];

                    sb.append(formatCellEntry(nonTerminal, childProductions, 0, midpoint, formatFractions));
                }
                sb.append("\n\n");
            }
        }

        return sb.toString();
    }
}
