package edu.ohsu.cslu.parser.chart;

import edu.ohsu.cslu.grammar.SparseMatrixGrammar;
import edu.ohsu.cslu.grammar.Grammar.Production;

/**
 * Stores a chart in a 3-way parallel array indexed by non-terminal:
 * <ol>
 * <li>Probabilities of each non-terminal (float; NEGATIVE_INFINITY for unobserved non-terminals)</li>
 * <li>Child pairs producing each non-terminal --- equivalent to the grammar rule (int)</li>
 * <li>Midpoints (short)</li>
 * </ol>
 * 
 * Those 3 pieces of information allow us to back-trace through the chart and construct the parse tree.
 * 
 * Each parallel array entry consumes 4 + 4 + 2 = 10 bytes
 * 
 * Individual cells in the parallel array are indexed by cell offsets of fixed length (the number of
 * non-terminals in the grammar).
 * 
 * The ancillary data structures are relatively small, so the total size consumed is approximately = n * (n-1)
 * / 2 * V * 10 bytes.
 * 
 * Similar to {@link PackedArrayChart}, and slightly more space-efficient, but the observed non-terminals in a
 * cell are not `packed' together at the beginning of the cell's array range. This saves a packing scan in
 * {@link DenseVectorChartCell#finalizeCell()}, but may result in less efficient access when populating
 * subsequent cells.
 * 
 * @see PackedArrayChart
 * @author Aaron Dunlop
 * @since March 15, 2010
 * 
 * @version $Revision$ $Date$ $Author$
 */
public class DenseVectorChart extends ParallelArrayChart {

    /**
     * Constructs a chart
     * 
     * @param size Sentence length
     * @param sparseMatrixGrammar Grammar
     */
    public DenseVectorChart(final int size, final SparseMatrixGrammar sparseMatrixGrammar) {
        super(size, sparseMatrixGrammar);
    }

    public void clear(final int sentenceLength) {
        this.size = sentenceLength;
    }

    @Override
    public float getInside(final int start, final int end, final int nonTerminal) {
        final int cellIndex = cellIndex(start, end);
        final int offset = cellIndex * sparseMatrixGrammar.numNonTerms();
        return insideProbabilities[offset + nonTerminal];
    }

    @Override
    public void updateInside(final int start, final int end, final int nonTerminal, final float insideProb) {
        throw new UnsupportedOperationException("Not supported by PackedArrayChart");
    }

    @Override
    public DenseVectorChartCell getCell(final int start, final int end) {
        return new DenseVectorChartCell(start, end);
    }

    public class DenseVectorChartCell extends ParallelArrayChartCell {

        public DenseVectorChartCell(final int start, final int end) {
            super(start, end);
        }

        @Override
        public float getInside(final int nonTerminal) {
            return insideProbabilities[offset + nonTerminal];
        }

        @Override
        public void updateInside(final Production p, final ChartCell leftCell, final ChartCell rightCell,
                final float insideProbability) {

            final int index = offset + p.parent;
            numEdgesConsidered++;

            if (insideProbability > insideProbabilities[index]) {
                if (p.isBinaryProd()) {
                    packedChildren[index] = sparseMatrixGrammar.cartesianProductFunction().pack(p.leftChild,
                        p.rightChild);
                } else if (p.isLexProd()) {
                    packedChildren[index] = sparseMatrixGrammar.cartesianProductFunction().packLexical(
                        p.leftChild);
                } else {
                    packedChildren[index] = sparseMatrixGrammar.cartesianProductFunction().packUnary(
                        p.leftChild);
                }
                insideProbabilities[index] = insideProbability;

                // Midpoint == end for unary productions
                midpoints[index] = (short) leftCell.end();

                numEdgesAdded++;
            }
        }

        @Override
        public void updateInside(final ChartEdge edge) {

            final int index = offset + edge.prod.parent;
            numEdgesConsidered++;

            if (edge.inside() > insideProbabilities[index]) {

                if (edge.prod.isBinaryProd()) {
                    packedChildren[index] = sparseMatrixGrammar.cartesianProductFunction().pack(
                        edge.prod.leftChild, edge.prod.rightChild);
                } else if (edge.prod.isLexProd()) {
                    packedChildren[index] = sparseMatrixGrammar.cartesianProductFunction().packLexical(
                        edge.prod.leftChild);
                } else {
                    packedChildren[index] = sparseMatrixGrammar.cartesianProductFunction().packUnary(
                        edge.prod.leftChild);
                }
                insideProbabilities[index] = edge.inside();

                // Midpoint == end for unary productions
                midpoints[index] = (short) edge.leftCell.end();

                numEdgesAdded++;
            }
        }

        @Override
        public ChartEdge getBestEdge(final int nonTerminal) {
            int edgeChildren;
            short edgeMidpoint;

            final int index = offset + nonTerminal;
            edgeChildren = packedChildren[index];
            edgeMidpoint = midpoints[index];

            final int leftChild = sparseMatrixGrammar.cartesianProductFunction()
                .unpackLeftChild(edgeChildren);
            final int rightChild = sparseMatrixGrammar.cartesianProductFunction().unpackRightChild(
                edgeChildren);
            final DenseVectorChartCell leftChildCell = getCell(start(), edgeMidpoint);
            final DenseVectorChartCell rightChildCell = edgeMidpoint < end ? (DenseVectorChartCell) getCell(
                edgeMidpoint, end) : null;

            Production p;
            if (rightChild == Production.LEXICAL_PRODUCTION) {
                final float probability = sparseMatrixGrammar.lexicalLogProbability(nonTerminal, leftChild);
                p = sparseMatrixGrammar.new Production(nonTerminal, leftChild, probability, true);

            } else if (rightChild == Production.UNARY_PRODUCTION) {
                final float probability = sparseMatrixGrammar.unaryLogProbability(nonTerminal, leftChild);
                p = sparseMatrixGrammar.new Production(nonTerminal, leftChild, probability, false);

            } else {
                final float probability = sparseMatrixGrammar.binaryLogProbability(nonTerminal, edgeChildren);
                p = sparseMatrixGrammar.new Production(nonTerminal, leftChild, rightChild, probability);
            }
            return new ChartEdge(p, leftChildCell, rightChildCell);
        }

        @Override
        public int getNumNTs() {
            int numNTs = 0;
            for (int i = offset; i < (offset + sparseMatrixGrammar.numNonTerms()); i++) {
                if (insideProbabilities[i] != Float.NEGATIVE_INFINITY) {
                    numNTs++;
                }
            }
            return numNTs;
        }

        /**
         * Warning: Not truly thread-safe, since it doesn't validate that the two cells belong to the same
         * chart.
         */
        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof DenseVectorChartCell)) {
                return false;
            }

            final DenseVectorChartCell packedArrayChartCell = (DenseVectorChartCell) o;
            return (packedArrayChartCell.start == start && packedArrayChartCell.end == end);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder(256);

            sb.append("DenseVectorChartCell[" + start() + "][" + end() + "] with " + getNumNTs() + " (of "
                    + sparseMatrixGrammar.numNonTerms() + ") edges\n");

            // Format entries from the main chart array
            for (int nonTerminal = 0; nonTerminal < sparseMatrixGrammar.numNonTerms(); nonTerminal++) {
                final int index = offset + nonTerminal;
                final float insideProbability = insideProbabilities[index];

                if (insideProbability > Float.NEGATIVE_INFINITY) {
                    final int childProductions = packedChildren[index];
                    final int midpoint = midpoints[index];
                    sb.append(formatCellEntry(index - offset, childProductions, insideProbability, midpoint));
                }
            }
            return sb.toString();
        }
    }
}
