package edu.ohsu.cslu.parser;

import edu.ohsu.cslu.grammar.CsrSparseMatrixGrammar;
import edu.ohsu.cslu.grammar.Tokenizer.Token;
import edu.ohsu.cslu.parser.traversal.ChartTraversal.ChartTraversalType;

public class CsrSparseMatrixVectorParser extends SparseMatrixVectorParser {

    private final CsrSparseMatrixGrammar csrSparseMatrixGrammar;

    public CsrSparseMatrixVectorParser(final CsrSparseMatrixGrammar grammar, final ChartTraversalType traversalType) {
        super(grammar, traversalType);
        this.csrSparseMatrixGrammar = grammar;
    }

    @Override
    protected void initParser(final int sentLength) {
        chartSize = sentLength;
        chart = new BaseChartCell[chartSize][chartSize + 1];

        // The chart is (chartSize+1)*chartSize/2
        for (int start = 0; start < chartSize; start++) {
            for (int end = start + 1; end < chartSize + 1; end++) {
                chart[start][end] = new DenseVectorChartCell(chart, start, end, (CsrSparseMatrixGrammar) grammar);
            }
        }
        rootChartCell = chart[0][chartSize];
    }

    // TODO Do this with a matrix multiply?
    @Override
    protected void addLexicalProductions(final Token[] sent) throws Exception {
        super.addLexicalProductions(sent);
        for (int start = 0; start < chartSize; start++) {
            ((DenseVectorChartCell) chart[start][start + 1]).finalizeCell();
        }
    }

    @Override
    protected void visitCell(final ChartCell cell) {

        final DenseVectorChartCell spvChartCell = (DenseVectorChartCell) cell;
        // TODO Change ChartCell.start() and end() to return shorts (since we shouldn't have to handle sentences longer than 32767)
        final short start = (short) cell.start();
        final short end = (short) cell.end();

        final long t0 = System.currentTimeMillis();

        // int totalProducts = 0;

        // TODO Change this constructor to a factory method
        final CrossProductVector crossProductVector = crossProductUnion(start, end);

        final long t1 = System.currentTimeMillis();
        final double crossProductTime = t1 - t0;

        // Multiply the unioned vector with the grammar matrix and populate the current cell with the
        // vector resulting from the matrix-vector multiplication
        binarySpmvMultiply(crossProductVector, spvChartCell);

        final long t2 = System.currentTimeMillis();
        final double binarySpmvTime = t2 - t1;

        // Handle unary productions
        // TODO: This only goes through unary rules one time, so it can't create unary chains unless such chains are encoded in the grammar. Iterating a few times would probably
        // work, although it's a big-time hack.
        unarySpmvMultiply(spvChartCell);

        final long t3 = System.currentTimeMillis();
        final double unarySpmvTime = t3 - t2;

        // TODO We won't need to do this once we're storing directly into the packed array
        spvChartCell.finalizeCell();

        // System.out.format("Visited cell: %2d,%2d (%5d ms). Cross-product: %6d/%6d combinations (%5.0f ms, %4.2f/ms), Multiply: %5d edges (%5.0f ms, %4.2f /ms)\n", start, end, t3
        // - t0, crossProductSize, totalProducts, crossProductTime, crossProductSize / crossProductTime, edges, spmvTime, edges / spmvTime);
        totalCrossProductTime += crossProductTime;
        totalSpMVTime += binarySpmvTime + unarySpmvTime;
    }

    @Override
    public void binarySpmvMultiply(final CrossProductVector crossProductVector, final DenseVectorChartCell chartCell) {

        final int[] binaryRuleMatrixRowIndices = csrSparseMatrixGrammar.binaryRuleMatrixRowIndices();
        final int[] binaryRuleMatrixColumnIndices = csrSparseMatrixGrammar.binaryRuleMatrixColumnIndices();
        final float[] binaryRuleMatrixProbabilities = csrSparseMatrixGrammar.binaryRuleMatrixProbabilities();

        final float[] crossProductProbabilities = crossProductVector.probabilities;
        final short[] crossProductMidpoints = crossProductVector.midpoints;

        final int[] chartCellChildren = chartCell.children;
        final float[] chartCellProbabilities = chartCell.probabilities;
        final short[] chartCellMidpoints = chartCell.midpoints;

        // Iterate over possible parents (matrix rows)
        for (int parent = 0; parent < grammar.numNonTerms(); parent++) {

            // Production winningProduction = null;
            float winningProbability = Float.NEGATIVE_INFINITY;
            int winningChildren = Integer.MIN_VALUE;
            short winningMidpoint = 0;

            // Iterate over possible children of the parent (columns with non-zero entries)
            for (int i = binaryRuleMatrixRowIndices[parent]; i < binaryRuleMatrixRowIndices[parent + 1]; i++) {
                final int grammarChildren = binaryRuleMatrixColumnIndices[i];
                final float grammarProbability = binaryRuleMatrixProbabilities[i];

                final float crossProductProbability = crossProductProbabilities[grammarChildren];
                final float jointProbability = grammarProbability + crossProductProbability;

                if (jointProbability > winningProbability) {
                    winningProbability = jointProbability;
                    winningChildren = grammarChildren;
                    winningMidpoint = crossProductMidpoints[grammarChildren];
                }
            }

            if (winningProbability != Float.NEGATIVE_INFINITY) {
                chartCellChildren[parent] = winningChildren;
                chartCellProbabilities[parent] = winningProbability;
                chartCellMidpoints[parent] = winningMidpoint;
            }
        }
    }

    @Override
    public void unarySpmvMultiply(final DenseVectorChartCell chartCell) {

        final int[] unaryRuleMatrixRowIndices = csrSparseMatrixGrammar.unaryRuleMatrixRowIndices();
        final int[] unaryRuleMatrixColumnIndices = csrSparseMatrixGrammar.unaryRuleMatrixColumnIndices();
        final float[] unaryRuleMatrixProbabilities = csrSparseMatrixGrammar.unaryRuleMatrixProbabilities();

        final int[] chartCellChildren = chartCell.children;
        final float[] chartCellProbabilities = chartCell.probabilities;
        final short[] chartCellMidpoints = chartCell.midpoints;
        final short chartCellEnd = (short) chartCell.end();

        // Iterate over possible parents (matrix rows)
        for (int parent = 0; parent < grammar.numNonTerms(); parent++) {

            float winningProbability = chartCellProbabilities[parent];
            int winningChildren = Integer.MIN_VALUE;
            short winningMidpoint = 0;

            // Iterate over possible children of the parent (columns with non-zero entries)
            for (int i = unaryRuleMatrixRowIndices[parent]; i < unaryRuleMatrixRowIndices[parent + 1]; i++) {

                final int grammarChildren = unaryRuleMatrixColumnIndices[i];
                final int child = csrSparseMatrixGrammar.unpackLeftChild(grammarChildren);
                final float grammarProbability = unaryRuleMatrixProbabilities[i];

                final float currentProbability = chartCell.probabilities[child];
                final float jointProbability = grammarProbability + currentProbability;

                if (jointProbability > winningProbability) {
                    winningProbability = jointProbability;
                    winningChildren = grammarChildren;
                    winningMidpoint = chartCellEnd;
                }
            }

            if (winningChildren != Integer.MIN_VALUE) {
                chartCellChildren[parent] = winningChildren;
                chartCellProbabilities[parent] = winningProbability;
                chartCellMidpoints[parent] = winningMidpoint;
            }
        }
    }
}
