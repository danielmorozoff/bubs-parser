package edu.ohsu.cslu.parser.spmv;

import edu.ohsu.cslu.grammar.SparseMatrixGrammar;
import edu.ohsu.cslu.grammar.Grammar.Production;
import edu.ohsu.cslu.parser.ExhaustiveChartParser;
import edu.ohsu.cslu.parser.ParserOptions;
import edu.ohsu.cslu.parser.SparseMatrixParser;
import edu.ohsu.cslu.parser.chart.ParallelArrayChart;
import edu.ohsu.cslu.parser.chart.Chart.ChartCell;
import edu.ohsu.cslu.parser.chart.PackedArrayChart.PackedArrayChartCell;
import edu.ohsu.cslu.parser.ml.SparseMatrixLoopParser;

/**
 * A class of parser which performs the grammar intersection in each cell by:
 * <ol>
 * <li>Finding the cartesian product of possible child productions in child cells across all possible
 * midpoints.
 * <li>Multiplying that cartesian product vector by the grammar matrix (stored in a sparse format).
 * <ol>
 * 
 * Subclasses use a variety of sparse matrix grammar representations, and differ in how they perform the
 * cartesian product. Some implementations perform the vector and matrix operations on GPU hardware throgh
 * OpenCL.
 * 
 * @author Aaron Dunlop
 * @since Mar 26, 2010
 * 
 * @version $Revision$ $Date$ $Author$
 */
public abstract class SparseMatrixVectorParser<G extends SparseMatrixGrammar, C extends ParallelArrayChart>
        extends SparseMatrixParser<G, C> {

    protected final float[] cartesianProductProbabilities;
    protected final short[] cartesianProductMidpoints;

    public long startTime = 0;
    public long totalCartesianProductTime = 0;
    public long totalCartesianProductUnionTime = 0;
    public long totalSpMVTime = 0;

    /**
     * True if we're collecting detailed counts of cell populations, cartesian-product sizes, etc. Set from
     * {@link ParserOptions}, but duplicated here as a final variable, so that the JIT can eliminate
     * potentially-expensive counting code when we don't need it.
     * 
     * TODO Move up to {@link ExhaustiveChartParser} (or even higher) and share with
     * {@link SparseMatrixLoopParser}
     */
    protected final boolean collectDetailedStatistics;

    public SparseMatrixVectorParser(final ParserOptions opts, final G grammar) {
        super(opts, grammar);
        cartesianProductProbabilities = new float[grammar.cartesianProductFunction().packedArraySize()];
        cartesianProductMidpoints = new short[cartesianProductProbabilities.length];
        this.collectDetailedStatistics = opts.collectDetailedStatistics();
    }

    public SparseMatrixVectorParser(final G grammar) {
        this(new ParserOptions(), grammar);
    }

    /**
     * Multiplies the grammar matrix (stored sparsely) by the supplied cartesian product vector (stored
     * densely), and populates this chart cell.
     * 
     * @param cartesianProductVector
     * @param chartCell
     */
    public abstract void binarySpmv(final CartesianProductVector cartesianProductVector,
            final ChartCell chartCell);

    /**
     * Multiplies the unary grammar matrix (stored sparsely) by the contents of this cell (stored densely),
     * and populates this chart cell. Used to populate unary rules.
     * 
     * @param chartCell
     */
    @Override
    public void unarySpmv(final ChartCell chartCell) {

        final PackedArrayChartCell packedArrayCell = (PackedArrayChartCell) chartCell;
        packedArrayCell.allocateTemporaryStorage();

        final int[] chartCellChildren = packedArrayCell.tmpPackedChildren;
        final float[] chartCellProbabilities = packedArrayCell.tmpInsideProbabilities;
        final short[] chartCellMidpoints = packedArrayCell.tmpMidpoints;
        final short chartCellEnd = (short) chartCell.end();

        unarySpmv(chartCellChildren, chartCellProbabilities, chartCellMidpoints, 0, chartCellEnd);
    }

    @Override
    protected void initParser(final int sentLength) {
        startTime = System.currentTimeMillis();
        totalSpMVTime = 0;
        totalCartesianProductTime = 0;
        totalCartesianProductUnionTime = 0;
    }

    /**
     * Takes the cartesian product of all potential child-cell combinations. Unions those cartesian products
     * together, saving the maximum probability child combinations.
     * 
     * @param start
     * @param end
     * @return Unioned cartesian product
     */
    protected abstract CartesianProductVector cartesianProductUnion(final int start, final int end);

    @Override
    public String getStatHeader() {
        return String.format("%8s, %10s, %8s, %8s", "Total", "X-product", "X-union", "SpMV");
    }

    @Override
    public String getStats() {
        final long totalTime = System.currentTimeMillis() - startTime;
        return String.format("%8.1f, %10d, %8d, %8d", totalTime / 1000f, totalCartesianProductTime,
            totalCartesianProductUnionTime, totalSpMVTime);
    }

    public final static class CartesianProductVector {

        private final SparseMatrixGrammar grammar;
        final float[] probabilities;
        final short[] midpoints;
        final int[] populatedLeftChildren;
        private int size = 0;

        public CartesianProductVector(final SparseMatrixGrammar grammar, final float[] probabilities,
                final short[] midpoints, final int[] populatedLeftChildren, final int size) {
            this.grammar = grammar;
            this.probabilities = probabilities;
            this.midpoints = midpoints;
            this.populatedLeftChildren = populatedLeftChildren;
            this.size = size;
        }

        public CartesianProductVector(final SparseMatrixGrammar grammar, final float[] probabilities,
                final short[] midpoints, final int size) {
            this(grammar, probabilities, midpoints, null, size);
        }

        public final int size() {
            return size;
        }

        public final float probability(final int children) {
            return probabilities[children];
        }

        public final short midpoint(final int children) {
            return midpoints[children];
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder(256);
            for (int i = 0; i < probabilities.length; i++) {
                // Some parsers initialize the midpoints and use 0 as `unpopulated'. Others initialize the
                // probabilities and use Float.NEGATIVE_INFINITY. Since toString() isn't time-crucial, check
                // both.
                if (midpoints[i] != 0 && probabilities[i] != Float.NEGATIVE_INFINITY) {
                    final int leftChild = grammar.cartesianProductFunction().unpackLeftChild(i);
                    final int rightChild = grammar.cartesianProductFunction().unpackRightChild(i);
                    final int midpoint = midpoints[i];
                    final float probability = probabilities[i];

                    if (rightChild == Production.UNARY_PRODUCTION) {
                        // Unary production
                        sb.append(String.format("%s (%d) %.3f (%d)\n", grammar.mapNonterminal(leftChild),
                            leftChild, probability, midpoint));

                    } else if (rightChild == Production.LEXICAL_PRODUCTION) {
                        // Lexical production
                        sb.append(String.format("%s (%d) %.3f (%d)\n", grammar.mapLexicalEntry(leftChild),
                            leftChild, probability, midpoint));

                    } else {
                        // Binary production
                        sb.append(String.format("%s (%d),%s (%d) %.3f (%d)\n", grammar
                            .mapNonterminal(leftChild), leftChild, grammar.mapNonterminal(rightChild),
                            rightChild, probability, midpoint));
                    }
                }
            }
            return sb.toString();
        }
    }
}