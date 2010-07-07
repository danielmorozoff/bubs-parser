package edu.ohsu.cslu.parser.ml;

import edu.ohsu.cslu.grammar.LeftCscSparseMatrixGrammar;
import edu.ohsu.cslu.grammar.SparseMatrixGrammar.CartesianProductFunction;
import edu.ohsu.cslu.hash.PerfectInt2IntHash;
import edu.ohsu.cslu.parser.ParserOptions;
import edu.ohsu.cslu.parser.chart.PackedArrayChart;
import edu.ohsu.cslu.parser.chart.PackedArrayChart.PackedArrayChartCell;

/**
 * Parser implementation which loops over all combinations of left and right child cell populations (cartesian
 * product of observed left and right non-terminals) and probes into the grammar for each combination using a
 * lookup into a perfect hash.
 * 
 * @author Aaron Dunlop
 * @since Jun 14, 2010
 * 
 * @version $Revision$ $Date$ $Author$
 */
public class CartesianProductHashSpmlParser extends
        SparseMatrixLoopParser<LeftCscSparseMatrixGrammar, PackedArrayChart> {

    private final PerfectInt2IntHash childPair2ColumnOffsetHash;
    private final int[] hashedCscParallelArrayIndices;

    public CartesianProductHashSpmlParser(final ParserOptions opts, final LeftCscSparseMatrixGrammar grammar) {
        super(opts, grammar);

        childPair2ColumnOffsetHash = new PerfectInt2IntHash(grammar.cscBinaryPopulatedColumns);
        hashedCscParallelArrayIndices = new int[childPair2ColumnOffsetHash.hashtableSize()];
        for (int i = 0; i < grammar.cscBinaryPopulatedColumns.length; i++) {
            final int childPair = grammar.cscBinaryPopulatedColumns[i];
            hashedCscParallelArrayIndices[childPair2ColumnOffsetHash.hashcode(childPair)] = i;
        }
    }

    public CartesianProductHashSpmlParser(final LeftCscSparseMatrixGrammar grammar) {
        this(new ParserOptions(), grammar);
    }

    @Override
    protected void initParser(final int sentLength) {
        if (chart != null && chart.size() >= sentLength) {
            chart.clear(sentLength);
        } else {
            // TODO Consolidate chart construction in a superclass using the genericized grammar
            chart = new PackedArrayChart(sentLength, grammar);
        }
        super.initParser(sentLength);
    }

    @Override
    protected void visitCell(final short start, final short end) {

        final CartesianProductFunction cpf = grammar.cartesianProductFunction();
        final PackedArrayChartCell targetCell = chart.getCell(start, end);
        targetCell.allocateTemporaryStorage();

        final int[] targetCellChildren = targetCell.tmpPackedChildren;
        final float[] targetCellProbabilities = targetCell.tmpInsideProbabilities;
        final short[] targetCellMidpoints = targetCell.tmpMidpoints;

        // Iterate over all possible midpoints
        for (short midpoint = (short) (start + 1); midpoint <= end - 1; midpoint++) {
            final int leftCellIndex = chart.cellIndex(start, midpoint);
            final int rightCellIndex = chart.cellIndex(midpoint, end);

            // Iterate over children in the left child cell
            final int leftStart = chart.minLeftChildIndex(leftCellIndex);
            final int leftEnd = chart.maxLeftChildIndex(leftCellIndex);

            final int rightStart = chart.minRightChildIndex(rightCellIndex);
            final int rightEnd = chart.maxRightChildIndex(rightCellIndex);

            for (int i = leftStart; i <= leftEnd; i++) {
                final int leftChild = chart.nonTerminalIndices[i];

                // TODO Skip non-terminals which never occur as left children

                final float leftProbability = chart.insideProbabilities[i];

                // And over children in the right child cell
                for (int j = rightStart; j <= rightEnd; j++) {

                    final int childPair = cpf.pack(leftChild, chart.nonTerminalIndices[j]);
                    if (childPair == Integer.MIN_VALUE) {
                        continue;
                    }

                    final int hashcode = childPair2ColumnOffsetHash.hashcode(childPair);
                    if (hashcode < 0) {
                        continue;
                    }
                    final int index = hashedCscParallelArrayIndices[hashcode];

                    final float childProbability = leftProbability + chart.insideProbabilities[j];

                    for (int k = grammar.cscBinaryPopulatedColumnOffsets[index]; k < grammar.cscBinaryPopulatedColumnOffsets[index + 1]; k++) {

                        final float jointProbability = grammar.cscBinaryProbabilities[k] + childProbability;
                        final int parent = grammar.cscBinaryRowIndices[k];

                        if (jointProbability > targetCellProbabilities[parent]) {
                            targetCellChildren[parent] = childPair;
                            targetCellProbabilities[parent] = jointProbability;
                            targetCellMidpoints[parent] = midpoint;
                        }
                    }
                }
            }
        }

        // Apply unary rules
        unarySpmv(targetCell);

        targetCell.finalizeCell();
    }
}