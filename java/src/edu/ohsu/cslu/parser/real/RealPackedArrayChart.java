/*
 * Copyright 2010-2012 Aaron Dunlop and Nathan Bodenstab
 * 
 * This file is part of the BUBS Parser.
 * 
 * The BUBS Parser is free software: you can redistribute it and/or 
 * modify  it under the terms of the GNU Affero General Public License 
 * as published by the Free Software Foundation, either version 3 of 
 * the License, or (at your option) any later version.
 * 
 * The BUBS Parser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with the BUBS Parser. If not, see <http://www.gnu.org/licenses/>
 */

package edu.ohsu.cslu.parser.real;

import java.util.Arrays;

import cltool4j.GlobalConfigProperties;
import edu.ohsu.cslu.datastructs.narytree.BinaryTree;
import edu.ohsu.cslu.datastructs.narytree.Tree;
import edu.ohsu.cslu.grammar.Grammar;
import edu.ohsu.cslu.grammar.Production;
import edu.ohsu.cslu.grammar.SparseMatrixGrammar;
import edu.ohsu.cslu.grammar.Vocabulary;
import edu.ohsu.cslu.parser.ParseTask;
import edu.ohsu.cslu.parser.Parser;
import edu.ohsu.cslu.parser.Parser.DecodeMethod;
import edu.ohsu.cslu.parser.chart.Chart;
import edu.ohsu.cslu.parser.chart.PackedArrayChart;
import edu.ohsu.cslu.parser.chart.PackedArrayChart.PackedArrayChartCell;
import edu.ohsu.cslu.parser.chart.ParallelArrayChart;
import edu.ohsu.cslu.parser.real.RealInsideOutsideCscSparseMatrixGrammar.PackingFunction;
import edu.ohsu.cslu.util.IEEEDoubleScaling;
import edu.ohsu.cslu.util.Strings;

/**
 * Stores all grammar probabilities in the real domain (as 64-bit doubles) instead of the log domain. Used for
 * inside-outside parsing, with chart probability storage scaled by scaling tools to avoid numeric underflow. The
 * approach is adapted from the Berkeley parser. Individual probability calculations are more expensive (requiring
 * double-precision floating-point multiplies instead of simple 32-bit adds), but it avoids the expense and
 * precision-loss of repeated <code>logsumexp</code> operations.
 * 
 * This alternate representation means a lot of copy-and-paste code, mostly from {@link ParallelArrayChart} and
 * {@link PackedArrayChart}.
 * 
 * @see RealInsideOutsideCscSparseMatrixGrammar
 * 
 * @author Aaron Dunlop
 * @since May 3, 2013
 */
public class RealPackedArrayChart extends Chart {

    public final RealInsideOutsideCscSparseMatrixGrammar sparseMatrixGrammar;

    /**
     * The maximum number of entries allowed per cell. For exhaustive search, this must be equal to the size of the
     * grammar's vocabulary, but for pruned search, we can limit cell population, reducing the chart's memory footprint
     */
    protected int beamWidth;
    protected int lexicalRowBeamWidth;

    /**
     * Start indices for each cell. Computed from cell start and end indices and stored in the chart for convenience
     */
    public final int[] cellOffsets;

    /**
     * Scaling steps for inside probabilities (@link {@link #insideProbabilities}) in each cell (see
     * {@link IEEEDoubleScaling}).
     * 
     * TODO Change to byte[] instead of int? (And adapt IEEEDoubleScaling and other consumers)
     */
    public final int[] insideScalingSteps;

    /**
     * Scaling steps for outside probabilities (@link {@link #outsideProbabilities}) in each cell (see
     * {@link IEEEDoubleScaling}).
     * 
     * TODO Change to byte[] instead of int? (And adapt IEEEDoubleScaling and other consumers)
     */
    public final int[] outsideScalingSteps;

    /** The number of cells in this chart */
    public final int cells;

    protected final int chartArraySize;

    /**
     * Parallel arrays storing non-terminals, inside probabilities, and the grammar rules and midpoints which produced
     * them. Entries for each cell begin at indices from {@link #cellOffsets}.
     */
    public final double[] insideProbabilities;
    public final double[] outsideProbabilities;
    public final int[] packedChildren;
    public final short[] midpoints;

    /**
     * Parallel array storing non-terminals (parallel to {@link ParallelArrayChart#insideProbabilities},
     * {@link ParallelArrayChart#packedChildren}, and {@link ParallelArrayChart#midpoints}. Entries for each cell begin
     * at indices from {@link #cellOffsets}.
     */
    public final short[] nonTerminalIndices;

    /**
     * The number of non-terminals populated in each cell. Indexed by cell index ({@link #cellIndex(int, int)} ).
     */
    public final int[] numNonTerminals;

    /**
     * The index in the main chart array of the first non-terminal in each cell which is valid as a left child. Indexed
     * by cell index ({@link #cellIndex(int, int)}).
     */
    private final int[] minLeftChildIndex;

    /**
     * The index in the main chart array of the last non-terminal in each cell which is valid as a left child. Indexed
     * by cell index ({@link #cellIndex(int, int)}).
     */
    private final int[] maxLeftChildIndex;

    /**
     * The index in the main chart array of the last non-terminal in each cell which is valid as a right child. Indexed
     * by cell index ({@link #cellIndex(int, int)}).
     */
    private final int[] minRightChildIndex;

    /**
     * The index in the main chart array of the last non-terminal in each cell which is valid as a right child. Indexed
     * by cell index ({@link #cellIndex(int, int)}).
     */
    private final int[] maxRightChildIndex;

    // Default lambda to 0 (max-recall) if unset
    private final double lambda = GlobalConfigProperties.singleton().getFloatProperty(Parser.PROPERTY_MAXC_LAMBDA, 0f);

    /**
     * Parallel array of max-c scores (see Goodman, 1996); indexed by cellIndex. Stored as instance variables instead of
     * locals purely for debugging and visualization via {@link #toString()}.
     */
    private final short[] maxcEntries;
    private final double[] maxcScores;
    private final short[] maxcMidpoints;
    private final short[] maxcUnaryChildren;

    /**
     * Vocabulary used form max-c calculations (the split vocabulary for AMBR-Max, and the unsplit vocabulary for
     * AMBR-Sum
     */
    private Vocabulary maxcVocabulary;

    /**
     * Parallel array of max-q scores (see Petrov, 2007). Stored as instance variables instead of locals purely for
     * debugging and visualization via {@link #toString()}.
     * 
     * maxQ = current-cell q * child cell q's (accumulating max-rule product up the chart) All 2-d arrays indexed by
     * cellIndex and base (Markov-0) parent.
     */
    final double[][] maxQ;
    final short[][] maxQMidpoints;
    final short[][] maxQLeftChildren;
    final short[][] maxQRightChildren;

    protected final ThreadLocal<PackedArrayChart.TemporaryChartCell> threadLocalTemporaryCells;

    /**
     * Constructs a chart
     * 
     * @param parseTask Current task
     * @param sparseMatrixGrammar Grammar
     * @param beamWidth
     * @param lexicalRowBeamWidth
     */
    public RealPackedArrayChart(final ParseTask parseTask,
            final RealInsideOutsideCscSparseMatrixGrammar sparseMatrixGrammar, final int beamWidth,
            final int lexicalRowBeamWidth) {

        super(parseTask, sparseMatrixGrammar);
        this.sparseMatrixGrammar = sparseMatrixGrammar;
        this.beamWidth = Math.min(beamWidth, sparseMatrixGrammar.numNonTerms());
        this.lexicalRowBeamWidth = Math.min(lexicalRowBeamWidth, sparseMatrixGrammar.numNonTerms());

        cells = size * (size + 1) / 2;

        this.chartArraySize = chartArraySize(this.size, this.beamWidth, this.lexicalRowBeamWidth);
        this.insideProbabilities = new double[chartArraySize];
        this.outsideProbabilities = new double[chartArraySize];
        this.packedChildren = new int[chartArraySize];
        this.midpoints = new short[chartArraySize];

        this.cellOffsets = new int[cells];
        this.insideScalingSteps = new int[cells];
        this.outsideScalingSteps = new int[cells];

        // TODO Do we need to do this?
        // Initialize outside probabilities to 1. If we're doing posterior inference, we'll populate them appropriately;
        // otherwise, posterior decoding methods will reduce to inside probabilities only.
        // Arrays.fill(outsideProbabilities, 1);

        // Calculate all cell offsets, etc
        for (int start = 0; start < size; start++) {
            for (int end = start + 1; end <= size; end++) {
                final int cellIndex = cellIndex(start, end);
                cellOffsets[cellIndex] = cellOffset(start, end);
            }
        }

        numNonTerminals = new int[cells];
        minLeftChildIndex = new int[cells];
        maxLeftChildIndex = new int[cells];
        minRightChildIndex = new int[cells];
        maxRightChildIndex = new int[cells];
        maxcVocabulary = sparseMatrixGrammar.nonTermSet.baseVocabulary();

        nonTerminalIndices = new short[chartArraySize];

        reset(parseTask);

        // Temporary cell storage for each cell-level thread
        this.threadLocalTemporaryCells = new ThreadLocal<PackedArrayChart.TemporaryChartCell>() {
            @Override
            protected PackedArrayChart.TemporaryChartCell initialValue() {
                return new PackedArrayChart.TemporaryChartCell(grammar, false);
            }
        };

        switch (parseTask.decodeMethod) {

        case Goodman:
        case SplitSum:
            final int maxcArraySize = parseTask.tokens.length * (parseTask.tokens.length + 1) / 2;
            this.maxcEntries = new short[maxcArraySize];
            this.maxcScores = new double[maxcArraySize];
            this.maxcMidpoints = new short[maxcArraySize];
            this.maxcUnaryChildren = new short[maxcArraySize];

            this.maxQ = null;
            this.maxQMidpoints = null;
            this.maxQLeftChildren = null;
            this.maxQRightChildren = null;
            break;

        case MaxRuleProd:
            this.maxQ = new double[cells][maxcVocabulary.size()];
            this.maxQMidpoints = new short[cells][maxcVocabulary.size()];
            this.maxQLeftChildren = new short[cells][maxcVocabulary.size()];
            this.maxQRightChildren = new short[cells][maxcVocabulary.size()];

            this.maxcEntries = null;
            this.maxcScores = null;
            this.maxcMidpoints = null;
            this.maxcUnaryChildren = null;
            break;

        case ViterbiMax:
            this.maxQ = null;
            this.maxQMidpoints = null;
            this.maxQLeftChildren = null;
            this.maxQRightChildren = null;
            this.maxcEntries = null;
            this.maxcScores = null;
            this.maxcMidpoints = null;
            this.maxcUnaryChildren = null;
            break;

        default:
            throw new UnsupportedOperationException("Unsupported decoding method: " + parseTask.decodeMethod);
        }

    }

    // @Override
    // public abstract ParallelArrayChartCell getCell(final int start, final int end);

    /**
     * Returns the offset of the specified cell in the parallel chart arrays (note that this computation must agree with
     * that of {@link #cellIndex(int, int)}
     * 
     * @param start
     * @param end
     * @return the offset of the specified cell in the parallel chart arrays
     */
    protected int cellOffset(final int start, final int end) {

        if (start < 0 || start > size) {
            throw new IllegalArgumentException("Illegal start: " + start);
        }

        if (end <= start || end > size) {
            throw new IllegalArgumentException("Illegal end: " + end);
        }

        final int priorCellBeamWidths = cellIndex(start, end) * this.beamWidth;
        // If this cell is in the lexical row, we've seen 'start' prior lexical entries; otherwise we've seen the one in
        // this diagonal too, so 'start + 1'
        final int priorLexicalCells = (end - start == 1) ? start : start + 1;
        return priorCellBeamWidths + priorLexicalCells * (this.lexicalRowBeamWidth - this.beamWidth);
    }

    public final int offset(final int cellIndex) {
        return cellOffsets[cellIndex];
    }

    public final int chartArraySize() {
        return chartArraySize;
    }

    public int chartArraySize(final int newSize, final int newBeamWidth, final int newLexicalRowBeamWidth) {
        return newSize * newLexicalRowBeamWidth + (cells - size) * newBeamWidth;
    }

    public int beamWidth() {
        return beamWidth;
    }

    public int lexicalRowBeamWidth() {
        return lexicalRowBeamWidth;
    }

    /**
     * Re-initializes the chart data structures, facilitating reuse of the chart for multiple sentences. Subclasses must
     * ensure that the data structure state following {@link #reset(ParseTask, int, int)} is identical to that of a
     * newly constructed chart.
     * 
     * @param task
     * @param newBeamWidth
     * @param newLexicalRowBeamWidth
     */
    public void reset(final ParseTask task, final int newBeamWidth, final int newLexicalRowBeamWidth) {
        this.beamWidth = newBeamWidth;
        this.lexicalRowBeamWidth = newLexicalRowBeamWidth;
        reset(task);
    }

    @Override
    public String toString() {
        return toString(false, false);
    }

    public String toString(final boolean formatFractions, final boolean includeEmptyCells) {
        final StringBuilder sb = new StringBuilder();

        for (int span = 1; span <= size; span++) {
            for (int start = 0; start <= size - span; start++) {
                final int end = start + span;
                final RealPackedArrayChartCell cell = getCell(start, end);
                if (cell.getNumNTs() > 0 || includeEmptyCells) {
                    sb.append(cell.toString(formatFractions));
                    sb.append("\n\n");
                }
            }
        }

        return sb.toString();
    }

    protected static String formatCellEntry(final RealInsideOutsideCscSparseMatrixGrammar g, final int nonterminal,
            final int childProductions, final double insideProbability, final int midpoint,
            final boolean formatFractions) {

        if (childProductions == 0 || childProductions == Integer.MIN_VALUE) {
            return String.format("%s -> ? (%.5f, %d)\n", g.mapNonterminal(nonterminal), insideProbability, midpoint);
        }

        final int leftChild = g.packingFunction().unpackLeftChild(childProductions);
        final int rightChild = g.packingFunction().unpackRightChild(childProductions);

        if (rightChild == Production.UNARY_PRODUCTION) {
            // Unary Production
            return String.format("%s -> %s (%.10f, %d)\n", g.mapNonterminal(nonterminal), g.mapNonterminal(leftChild),
                    insideProbability, midpoint);
        } else if (rightChild == Production.LEXICAL_PRODUCTION) {
            // Lexical Production
            return String.format("%s -> %s (%.10f, %d)\n", g.mapNonterminal(nonterminal), g.mapLexicalEntry(leftChild),
                    insideProbability, midpoint);
        } else {
            return String.format("%s -> %s %s (%.10f, %d)\n", g.mapNonterminal(nonterminal),
                    g.mapNonterminal(leftChild), g.mapNonterminal(rightChild), insideProbability, midpoint);
        }
    }

    @Override
    public void reset(final ParseTask task) {
        this.parseTask = task;
        this.size = task.sentenceLength();
        Arrays.fill(numNonTerminals, 0);

        for (int start = 0; start < size; start++) {
            for (int end = start + 1; end <= size; end++) {
                final int cellIndex = cellIndex(start, end);
                final int offset = cellOffset(start, end);

                cellOffsets[cellIndex] = offset;
                minLeftChildIndex[cellIndex] = offset;
                maxLeftChildIndex[cellIndex] = offset - 1;
                minRightChildIndex[cellIndex] = offset;
                maxRightChildIndex[cellIndex] = offset - 1;
            }
        }
    }

    @Override
    public BinaryTree<String> extractBestParse(final int start, final int end, final int parent) {
        final RealPackedArrayChartCell packedCell = getCell(start, end);

        if (packedCell == null) {
            return null;
        }

        // Find the index of the non-terminal in the chart storage
        final int i = Arrays.binarySearch(nonTerminalIndices, packedCell.offset, packedCell.offset
                + numNonTerminals[packedCell.cellIndex], (short) parent);
        if (i < 0) {
            return null;
        }
        final int edgeChildren = packedChildren[i];
        final short edgeMidpoint = midpoints[i];

        final BinaryTree<String> subtree = new BinaryTree<String>(sparseMatrixGrammar.nonTermSet.getSymbol(parent));
        final int leftChild = sparseMatrixGrammar.packingFunction().unpackLeftChild(edgeChildren);
        final int rightChild = sparseMatrixGrammar.packingFunction().unpackRightChild(edgeChildren);

        if (rightChild == Production.UNARY_PRODUCTION) {
            subtree.addChild(extractBestParse(start, end, leftChild));

        } else if (rightChild == Production.LEXICAL_PRODUCTION) {
            subtree.addChild(new BinaryTree<String>(sparseMatrixGrammar.lexSet.getSymbol(parseTask.tokens[start])));

        } else {
            // binary production
            subtree.addChild(extractBestParse(start, edgeMidpoint, leftChild));
            subtree.addChild(extractBestParse(edgeMidpoint, end, rightChild));
        }
        return subtree;
    }

    @Override
    public float getInside(final int start, final int end, final int nonTerminal) {
        final int cellIndex = cellIndex(start, end);
        final int offset = offset(cellIndex);
        final int index = Arrays.binarySearch(nonTerminalIndices, offset, offset + numNonTerminals[cellIndex],
                (short) nonTerminal);
        if (index < 0) {
            return Float.NEGATIVE_INFINITY;
        }
        return (float) IEEEDoubleScaling.logLikelihood(insideProbabilities[index], insideScalingSteps[cellIndex]);
    }

    @Override
    public float getOutside(final int start, final int end, final int nonTerminal) {
        final int cellIndex = cellIndex(start, end);
        final int offset = offset(cellIndex);
        final int index = Arrays.binarySearch(nonTerminalIndices, offset, offset + numNonTerminals[cellIndex],
                (short) nonTerminal);
        if (index < 0) {
            return Float.NEGATIVE_INFINITY;
        }
        return (float) IEEEDoubleScaling.logLikelihood(outsideProbabilities[index], insideScalingSteps[cellIndex]);
    }

    /**
     * Decodes the packed parse forest using the specified decoding method (e.g., {@link DecodeMethod#Goodman},
     * {@link DecodeMethod#MaxRuleProd}, etc.)
     * 
     * @return The extracted binary tree
     */
    public BinaryTree<String> decode() {

        switch (parseTask.decodeMethod) {

        case Goodman:
            computeGoodmanMaxc();
            return extractMaxcParse(0, size);

        case SplitSum:
            computeSplitSumMaxc();
            return extractMaxcParse(0, size);

        case MaxRuleProd:
            return decodeMaxRuleProductParse((RealInsideOutsideCscSparseMatrixGrammar) grammar);

        case ViterbiMax:
            // TODO Rename extractBestParse to extractViterbiParse, switch references to use decode() instead.
            return extractBestParse(grammar.startSymbol);

        default:
            throw new UnsupportedOperationException("Decoding method " + parseTask.decodeMethod + " not implemented");
        }
    }

    /**
     * Computes 'maxc', per the algorithm in Figure 1 of Joshua Goodman, 1996, 'Parsing Algorithms and Metrics'.
     * 
     * Uses lambda as per Equation 7, Appendix A of Hollingshead and Roark, 'Pipeline Iteration'.
     * 
     */
    private void computeGoodmanMaxc() {

        final PackingFunction pf = sparseMatrixGrammar.packingFunction;
        maxcVocabulary = sparseMatrixGrammar.nonTermSet;
        initMaxc();

        // Start symbol inside-probability (e in Goodman's notation)
        final double startSymbolInsideProbability = startSymbolInsideProbability();

        for (short span = 1; span <= size; span++) {
            for (short start = 0; start < size - span + 1; start++) {
                final short end = 0;
                final int cellIndex = cellIndex(start, end);
                final int offset = offset(cellIndex);

                // maxg = max(posterior probability / e).
                double maxg = Double.NEGATIVE_INFINITY;
                for (int i = offset; i < offset + numNonTerminals[cellIndex]; i++) {

                    final short nt = nonTerminalIndices[i];

                    // Factored non-terminals do not contribute to the final parse tree, so their maxc score is 0
                    final double g = maxcVocabulary.isFactored(nt) ? 0 : (insideProbabilities[i]
                            * outsideProbabilities[i] / startSymbolInsideProbability)
                            - (span > 1 ? lambda : 0);

                    // Bias toward recovering unary parents in the case of a tie (e.g., when ROOT and S are tied in the
                    // top cell)
                    final boolean unaryParent = pf.unpackRightChild(packedChildren[i]) == Production.UNARY_PRODUCTION;
                    if (g > maxg || (g == maxg && unaryParent)) {
                        maxg = g;
                        maxcEntries[cellIndex] = nt;

                        // Addition to Goodman's algorithm: if the best path to the highest-scoring non-terminal was
                        // through a unary child, record that unary child as well. Note that this requires we store
                        // unary backpointers during the inside parsing pass.
                        if (unaryParent) {
                            maxcUnaryChildren[cellIndex] = (short) pf.unpackLeftChild(packedChildren[i]);
                        } else {
                            maxcUnaryChildren[cellIndex] = Short.MIN_VALUE;
                        }
                    }
                }

                if (span == 1) {
                    maxcScores[cellIndex] = maxg;
                } else {
                    // Iterate over possible binary child cells, to find the maximum midpoint ('max split')
                    double bestSplit = Double.NEGATIVE_INFINITY;

                    // Search possible midpoints for the largest scaling step (minimum scaling factor). Scaling steps
                    // are large
                    // enough that we can reasonably assume that the probability mass of midpoints with smaller scaling
                    // steps will
                    // be inconsequential.
                    final int minScalingStep = minInsideScalingStep(start, end);

                    for (short midpoint = (short) (start + 1); midpoint < end; midpoint++) {

                        // Skip midpoints with scaling steps that won't contribute meaningfully to the total probability
                        // mass
                        final int scalingStep = insideScalingSteps[cellIndex(start, midpoint)]
                                + insideScalingSteps[cellIndex(midpoint, end)];
                        if (scalingStep > minScalingStep) {
                            continue;
                        }

                        // maxc = max(posterior probability) + max(maxc(children)). Also store midpoints for use when
                        // extracting the parse tree
                        final double split = maxcScores[cellIndex(start, midpoint)]
                                + maxcScores[cellIndex(midpoint, end)];
                        if (split > bestSplit) {
                            bestSplit = split;
                            maxcScores[cellIndex] = maxg + split;
                            maxcMidpoints[cellIndex] = midpoint;
                        }
                    }
                }
            }
        }
    }

    /**
     * Initializes maxc arrays.
     */
    private void initMaxc() {
        Arrays.fill(maxcEntries, Short.MIN_VALUE);
        Arrays.fill(maxcScores, Double.NEGATIVE_INFINITY);
        Arrays.fill(maxcMidpoints, Short.MIN_VALUE);
        Arrays.fill(maxcUnaryChildren, Short.MIN_VALUE);
    }

    /**
     * Finds the inside probability of the start symbol in the top cell.
     * 
     * @return Start-symbol inside probability
     */
    private double startSymbolInsideProbability() {
        final int topCellIndex = cellIndex(0, size);
        final int startSymbolIndex = entryIndex(offset(topCellIndex), numNonTerminals[topCellIndex],
                sparseMatrixGrammar.startSymbol);
        if (startSymbolIndex < 0) {
            throw new IllegalArgumentException("Parse failure");
        }
        final double startSymbolInsideProbability = insideProbabilities[startSymbolIndex];

        return startSymbolInsideProbability;
    }

    /**
     * Sums over unsplit categories while computing 'maxc', per the algorithm in Figure 1 of Joshua Goodman, 1996,
     * 'Parsing Algorithms and Metrics'.
     * 
     * Uses lambda as per {@link #computeGoodmanMaxc()} - from Equation 7, Appendix A of Hollingshead and Roark,
     * 'Pipeline Iteration'.
     */
    private void computeSplitSumMaxc() {

        final PackingFunction pf = sparseMatrixGrammar.packingFunction;
        maxcVocabulary = sparseMatrixGrammar.nonTermSet.baseVocabulary();
        initMaxc();

        // Start symbol inside-probability (e in Goodman's notation)
        final double startSymbolInsideProbability = startSymbolInsideProbability();

        for (short span = 1; span <= size; span++) {
            for (short start = 0; start < size - span + 1; start++) {
                final short end = (short) (start + span);
                final int cellIndex = cellIndex(start, end);
                final int offset = offset(cellIndex);

                // maxg = max(posterior probability / e).
                final double[] baseSumProbabilities = new double[maxcVocabulary.size()];
                final short[] unaryChildren = new short[maxcVocabulary.size()];
                Arrays.fill(unaryChildren, Short.MIN_VALUE);

                final double[] maxBaseProbabilities = new double[maxcVocabulary.size()];

                for (int i = offset; i < offset + numNonTerminals[cellIndex]; i++) {
                    final double posteriorProbability = insideProbabilities[i] * outsideProbabilities[i];
                    final short baseNt = sparseMatrixGrammar.nonTermSet.getBaseIndex(nonTerminalIndices[i]);

                    baseSumProbabilities[baseNt] = baseSumProbabilities[baseNt] * posteriorProbability;

                    if (posteriorProbability > maxBaseProbabilities[baseNt]) {
                        maxBaseProbabilities[baseNt] = posteriorProbability;
                        // If the (current) maximum-probability split of the base nonterminal is a unary parent, record
                        // the base nonterminal as a unary parent with the appropriate unary child
                        if (pf.unpackRightChild(packedChildren[i]) == Production.UNARY_PRODUCTION) {
                            unaryChildren[baseNt] = sparseMatrixGrammar.nonTermSet.getBaseIndex((short) pf
                                    .unpackLeftChild(packedChildren[i]));
                        } else {
                            unaryChildren[baseNt] = Short.MIN_VALUE;
                        }
                    }
                }
                double maxg = Double.NEGATIVE_INFINITY;

                // Compute g scores for each base NT and record the max
                for (short baseNt = 0; baseNt < baseSumProbabilities.length; baseNt++) {

                    // Factored non-terminals do not contribute to the final parse tree, so their maxc score is 0
                    final double g = maxcVocabulary.isFactored(baseNt) ? 0 : Math.exp(baseSumProbabilities[baseNt]
                            - startSymbolInsideProbability)
                            - (span > 1 ? lambda : 0);

                    // Bias toward recovering unary parents in the case of a tie (e.g., when ROOT and S are tied in the
                    // top cell)
                    if (g > maxg || (g == maxg && unaryChildren[baseNt] >= 0)) {
                        maxg = g;
                        maxcEntries[cellIndex] = baseNt;
                    }
                }

                // Addition to Goodman's algorithm: if the best path to the highest-scoring non-terminal was
                // through a unary child, record that unary child as well. Note that this requires we store
                // unary backpointers during the inside parsing pass.
                maxcUnaryChildren[cellIndex] = unaryChildren[maxcEntries[cellIndex]];

                // For span-1 cells, maxc = maxg
                if (span == 1) {
                    maxcScores[cellIndex] = maxg;
                } else {
                    // For span > 1, we must iterate over possible binary child cells, to find the maximum midpoint
                    // ('max split')
                    double bestSplit = Double.NEGATIVE_INFINITY;

                    // Search possible midpoints for the largest scaling step (minimum scaling factor). Scaling steps
                    // are large
                    // enough that we can reasonably assume that the probability mass of midpoints with smaller scaling
                    // steps will
                    // be inconsequential.
                    final int minScalingStep = minInsideScalingStep(start, end);

                    for (short midpoint = (short) (start + 1); midpoint < end; midpoint++) {

                        // Skip midpoints with scaling steps that won't contribute meaningfully to the total probability
                        // mass
                        final int scalingStep = insideScalingSteps[cellIndex(start, midpoint)]
                                + insideScalingSteps[cellIndex(midpoint, end)];
                        if (scalingStep > minScalingStep) {
                            continue;
                        }

                        // maxc = max(posterior probability) + max(maxc(children)). Also store midpoints for use when
                        // extracting the parse tree
                        final double split = maxcScores[cellIndex(start, midpoint)]
                                + maxcScores[cellIndex(midpoint, end)];
                        if (split > bestSplit) {
                            bestSplit = split;
                            maxcScores[cellIndex] = maxg + split;
                            maxcMidpoints[cellIndex] = midpoint;
                        }
                    }
                }
            }
        }
    }

    /**
     * Extracts the max-recall/max-precision/combined parse (Goodman's 'Labeled Recall' algorithm), using the maxc
     * values computed by {@link #decode(DecodeMethod)}.
     * 
     * Precondition: {@link #maxcEntries} is populated with the indices of the non-terminals (in {@link #maxcVocabulary}
     * ) which maximize the decoding criteria. {@link maxcScores} is populated with the scores of those entries.
     * 
     * @param start
     * @param end
     * @return extracted binary tree
     */
    private BinaryTree<String> extractMaxcParse(final int start, final int end) {

        final short startSymbol = maxcVocabulary.startSymbol();
        final PackingFunction pf = sparseMatrixGrammar.packingFunction;

        final int cellIndex = cellIndex(start, end);
        final int offset = offset(cellIndex);
        final int numNonTerms = numNonTerminals[cellIndex];

        // Find the non-terminal which maximizes the decoding metric (e.g. Goodman's max-constituent or Petrov's
        // max-rule)
        short parent = maxcEntries[cellIndex];

        // If the maxc score of the parent is negative (except for start symbol), add a 'dummy' symbol instead, which
        // will be removed when the tree is unfactored.
        final String sParent = (maxcScores[cellIndex] > 0 || parent == startSymbol || end - start == 1) ? maxcVocabulary
                .getSymbol(parent) : Tree.NULL_LABEL;
        final BinaryTree<String> tree = new BinaryTree<String>(sParent);
        BinaryTree<String> subtree = tree;

        if (end - start == 1) {

            BinaryTree<String> unaryTree = subtree;

            if (parseTask.decodeMethod == DecodeMethod.SplitSum) {
                // Find the maximum-probability split of the unsplit parent non-terminal
                double maxSplitProb = 0;
                short splitParent = -1;
                for (int i = offset; i < offset + numNonTerms; i++) {
                    final double posteriorProbability = insideProbabilities[i] * outsideProbabilities[i];
                    if (posteriorProbability > maxSplitProb
                            && sparseMatrixGrammar.nonTermSet.getBaseIndex(nonTerminalIndices[i]) == parent) {
                        maxSplitProb = posteriorProbability;
                        splitParent = nonTerminalIndices[i];
                    }
                }
                parent = splitParent;
            }

            // Find the index of the current parent in the chart storage and follow the unary productions down to
            // the lexical entry
            int i;
            for (i = entryIndex(offset, numNonTerms, parent); pf.unpackRightChild(packedChildren[i]) != Production.LEXICAL_PRODUCTION; i = entryIndex(
                    offset, numNonTerms, parent)) {
                parent = (short) pf.unpackLeftChild(packedChildren[i]);
                unaryTree = unaryTree
                        .addChild(new BinaryTree<String>(sparseMatrixGrammar.nonTermSet.getSymbol(parent)));
            }
            unaryTree.addChild(new BinaryTree<String>(sparseMatrixGrammar.lexSet.getSymbol(pf
                    .unpackLeftChild(packedChildren[i]))));
            return subtree;
        }

        final short edgeMidpoint = maxcMidpoints[cellIndex];

        if (maxcUnaryChildren[cellIndex] >= 0) {
            // Unary production - we currently only allow one level of unary in span > 1 cells.
            subtree = subtree.addChild((maxcScores[cellIndex] > 0 || parent == startSymbol) ? maxcVocabulary
                    .getSymbol(maxcUnaryChildren[cellIndex]) : Tree.NULL_LABEL);
        }

        // Binary production
        subtree.addChild(extractMaxcParse(start, edgeMidpoint));
        subtree.addChild(extractMaxcParse(edgeMidpoint, end));

        return tree;
    }

    /**
     * Computes max-rule-product parse, as described in Figure 3 of Petrov and Klein, 1997, 'Improved Inference for
     * Unlexicalized Parsing'.
     */
    private BinaryTree<String> decodeMaxRuleProductParse(final RealInsideOutsideCscSparseMatrixGrammar cscGrammar) {

        maxcVocabulary = cscGrammar.nonTermSet.baseVocabulary();

        // Start symbol inside-probability (P_in(root,0,n) in Petrov's notation)
        final double startSymbolInsideProbability = startSymbolInsideProbability();

        for (short span = 1; span <= size; span++) {
            for (short start = 0; start < size - span + 1; start++) {

                final short end = (short) (start + span);
                final int cellIndex = cellIndex(start, end);
                Arrays.fill(maxQMidpoints[cellIndex], (short) -1);
                Arrays.fill(maxQ[cellIndex], 0);

                // Initialize lexical entries in the score arrays - sum outside probability x production probability
                // over all nonterminal splits
                if (end - start == 1) {
                    final double[] r = new double[maxcVocabulary.size()];

                    final int offset = offset(cellIndex);
                    for (int i = offset; i < offset + numNonTerminals[cellIndex]; i++) {
                        final short parent = nonTerminalIndices[i];
                        if (grammar.isPos(parent)) {
                            final short baseParent = cscGrammar.nonTermSet.getBaseIndex(parent);
                            maxQMidpoints[cellIndex][baseParent] = end;
                            // Left child is implied by marking the production as lexical. Unaries will be handled
                            // below.
                            r[baseParent] = r[baseParent] + outsideProbabilities[i]
                                    * cscGrammar.lexicalLogProbability(parent, parseTask.tokens[start]);
                            maxQRightChildren[cellIndex][baseParent] = Production.LEXICAL_PRODUCTION;
                        }
                    }
                    for (int baseParent = 0; baseParent < maxcVocabulary.size(); baseParent++) {
                        if (r[baseParent] > 0) {
                            maxQ[cellIndex][baseParent] = r[baseParent] / startSymbolInsideProbability;
                        }
                    }
                }

                final int parentStart = offset(cellIndex);
                final int parentEnd = parentStart + numNonTerminals[cellIndex];

                // Search possible midpoints for the largest scaling step (minimum scaling factor). Scaling steps are
                // large
                // enough that we can reasonably assume that the probability mass of midpoints with smaller scaling
                // steps will
                // be inconsequential.
                final int minScalingStep = minInsideScalingStep(start, end);

                // Iterate over all possible midpoints
                for (short midpoint = (short) (start + 1); midpoint <= end - 1; midpoint++) {

                    // Skip midpoints with scaling steps that won't contribute meaningfully to the total probability
                    // mass
                    final int scalingStep = insideScalingSteps[cellIndex(start, midpoint)]
                            + insideScalingSteps[cellIndex(midpoint, end)];
                    if (scalingStep > minScalingStep) {
                        continue;
                    }

                    // Since Petrov's 'r' is a sum over unsplit categories, we compute a temporary r array for each
                    // midpoint and then maximize over midpoints
                    // currentMidpointR is indexed by base parent, base left child, base right child
                    final double[][][] r = new double[maxcVocabulary.size()][][];

                    final int leftCellIndex = cellIndex(start, midpoint);

                    final int leftStart = minLeftChildIndex(leftCellIndex);
                    final int leftEnd = maxLeftChildIndex(leftCellIndex);

                    final RealPackedArrayChartCell rightChildCell = getCell(midpoint, end);
                    rightChildCell.allocateTemporaryStorage();
                    final double[] rightChildInsideProbabilities = rightChildCell.tmpCell.insideProbabilities;

                    // Iterate over parents
                    for (int i = parentStart; i < parentEnd; i++) {
                        final short parent = nonTerminalIndices[i];
                        final short baseParent = cscGrammar.nonTermSet.getBaseIndex(parent);
                        final double parentOutside = outsideProbabilities[i];

                        // And over children in the left child cell
                        for (int j = leftStart; j <= leftEnd; j++) {
                            final short leftChild = nonTerminalIndices[j];
                            final short baseLeftChild = cscGrammar.nonTermSet.getBaseIndex(leftChild);

                            // // If we've already found a q for this parent greater than the left-child maxQ, we can
                            // // short-circuit without computing r
                            // if (maxQ[leftCellIndex][baseLeftChild] < maxQ[cellIndex][baseParent]) {
                            // continue;
                            // }

                            final int column = cscGrammar.rightChildPackingFunction.pack(parent, leftChild);
                            if (column == Integer.MIN_VALUE) {
                                continue;
                            }

                            final double leftChildInside = insideProbabilities[j];

                            // Iterate over grammar rules
                            for (int k = cscGrammar.rightChildCscBinaryColumnOffsets[column]; k < cscGrammar.rightChildCscBinaryColumnOffsets[column + 1]; k++) {
                                final short rightChild = cscGrammar.rightChildCscBinaryRowIndices[k];

                                final double rightChildInside = rightChildInsideProbabilities[rightChild];
                                if (rightChildInside == 0.0) {
                                    continue;
                                }

                                final short baseRightChild = cscGrammar.nonTermSet.getBaseIndex(rightChild);

                                // // Again, we can short-circuit based on the combined child maxQ
                                // if (maxQ[leftCellIndex][baseLeftChild] + maxQ[rightCellIndex][baseRightChild] <
                                // maxQ[cellIndex][baseParent]) {
                                // continue;
                                // }

                                // Allocate space in current-midpoint r array if needed
                                allocateChildArray(r, baseParent, baseLeftChild);

                                r[baseParent][baseLeftChild][baseRightChild] = r[baseParent][baseLeftChild][baseRightChild]
                                        + cscGrammar.rightChildCscBinaryProbabilities[k]
                                        * leftChildInside
                                        * rightChildInside / parentOutside;

                                // TODO True max-rule decoding should incorporate the child q's too. We (mistakenly)
                                // haven't been doing that thus far. It seems to be working better this way, but we
                                // would be able to prune the search space if we do 'real' max-rule.
                                final double q = r[baseParent][baseLeftChild][baseRightChild]
                                        / startSymbolInsideProbability;
                                // TODO * maxQ[leftChildCellIndex][baseLeftChild] *
                                // maxQ[rightChildCellIndex][baseRightChild]

                                if (q > maxQ[cellIndex][baseParent]) {
                                    maxQ[cellIndex][baseParent] = q;
                                    maxQLeftChildren[cellIndex][baseParent] = baseLeftChild;
                                    maxQRightChildren[cellIndex][baseParent] = baseRightChild;
                                    maxQMidpoints[cellIndex][baseParent] = midpoint;
                                }
                            }
                        }
                    }
                }

                // Compute unary scores - iterate over populated children (matrix columns). Indexed by base parent and
                // child
                final double[][] unaryR = unaryR(cscGrammar, cellIndex);

                // Replace any binary or lexical parent scores which are beat by unaries
                for (short baseParent = 0; baseParent < unaryR.length; baseParent++) {
                    final double[] parentUnaryR = unaryR[baseParent];
                    if (parentUnaryR == null) {
                        continue;
                    }
                    for (short baseChild = 0; baseChild < parentUnaryR.length; baseChild++) {
                        // Preclude unary chains. Not great, but it's one way to prevent infinite unary loops
                        if (parentUnaryR[baseChild] - startSymbolInsideProbability > maxQ[cellIndex][baseParent]
                                && maxQRightChildren[cellIndex][baseChild] != Production.UNARY_PRODUCTION) {

                            maxQ[cellIndex][baseParent] = parentUnaryR[baseChild] / startSymbolInsideProbability;
                            maxQMidpoints[cellIndex][baseParent] = end;
                            maxQLeftChildren[cellIndex][baseParent] = baseChild;
                            maxQRightChildren[cellIndex][baseParent] = Production.UNARY_PRODUCTION;
                        }
                    }
                }
            }
        }

        return extractMaxQParse(0, size, maxcVocabulary.startSymbol(), maxcVocabulary);
    }

    private double[][] unaryR(final RealInsideOutsideCscSparseMatrixGrammar cscGrammar, final int cellIndex) {

        final double[][] unaryR = new double[maxcVocabulary.size()][];

        // Iterate over children in the cell
        final int offset = offset(cellIndex);
        for (int i = offset; i < offset + numNonTerminals[cellIndex]; i++) {
            final short child = nonTerminalIndices[i];
            final short baseChild = cscGrammar.nonTermSet.getBaseIndex(child);
            final double childInsideProbability = insideProbabilities[i];

            // And over grammar rules matching the child
            for (int j = cscGrammar.cscUnaryColumnOffsets[child]; j < cscGrammar.cscUnaryColumnOffsets[child + 1]; j++) {

                final short parent = cscGrammar.cscUnaryRowIndices[j];
                final int parentIndex = entryIndex(offset(cellIndex), numNonTerminals[cellIndex], parent);
                if (parentIndex < 0) {
                    continue;
                }
                // Parent outside x production probability x child inside
                final double jointScore = outsideProbabilities[parentIndex] * cscGrammar.cscUnaryProbabilities[j]
                        * childInsideProbability;
                final short baseParent = cscGrammar.nonTermSet.getBaseIndex(parent);

                if (unaryR[baseParent] == null) {
                    unaryR[baseParent] = new double[maxcVocabulary.size()];
                }
                unaryR[baseParent][baseChild] = unaryR[baseParent][baseChild] + jointScore;
            }
        }

        return unaryR;
    }

    private void allocateChildArray(final double[][][] currentMidpointR, final short baseParent,
            final short baseLeftChild) {

        if (currentMidpointR[baseParent] == null) {
            currentMidpointR[baseParent] = new double[maxcVocabulary.size()][];
        }
        if (currentMidpointR[baseParent][baseLeftChild] == null) {
            currentMidpointR[baseParent][baseLeftChild] = new double[maxcVocabulary.size()];
        }
    }

    private BinaryTree<String> extractMaxQParse(final int start, final int end, final int parent,
            final Vocabulary vocabulary) {

        final RealPackedArrayChartCell packedCell = getCell(start, end);

        if (packedCell == null) {
            return null;
        }
        final int cellIndex = packedCell.cellIndex;

        final short edgeMidpoint = maxQMidpoints[cellIndex][parent];

        final BinaryTree<String> subtree = new BinaryTree<String>(vocabulary.getSymbol(parent));
        final short leftChild = maxQLeftChildren[cellIndex][parent];
        final short rightChild = maxQRightChildren[cellIndex][parent];

        if (rightChild == Production.UNARY_PRODUCTION) {
            subtree.addChild(extractMaxQParse(start, end, leftChild, vocabulary));

        } else if (rightChild == Production.LEXICAL_PRODUCTION) {
            subtree.addChild(new BinaryTree<String>(sparseMatrixGrammar.lexSet.getSymbol(parseTask.tokens[start])));

        } else {
            // binary production
            subtree.addChild(extractMaxQParse(start, edgeMidpoint, leftChild, vocabulary));
            subtree.addChild(extractMaxQParse(edgeMidpoint, end, rightChild, vocabulary));
        }
        return subtree;
    }

    /**
     * Returns the index in the parallel chart array of the specified parent in the cell with the specified offset
     * 
     * @param offset The offset of the target cell
     * @param cellPopulation Number of non-terminals populated in the target cell
     * @param parent The parent to search for in the target cell
     * @return the index in the parallel chart array of the specified parent in the cell with the specified offset
     */
    private int entryIndex(final int offset, final int cellPopulation, final short parent) {
        return Arrays.binarySearch(nonTerminalIndices, offset, offset + cellPopulation, parent);
    }

    @Override
    public void updateInside(final int start, final int end, final int nonTerminal, final float insideProb) {
        throw new UnsupportedOperationException("Not supported by PackedArrayChart");
    }

    @Override
    public RealPackedArrayChartCell getCell(final int start, final int end) {
        return new RealPackedArrayChartCell(start, end);
    }

    public final int minLeftChildIndex(final int cellIndex) {
        return minLeftChildIndex[cellIndex];
    }

    public final int maxLeftChildIndex(final int cellIndex) {
        return maxLeftChildIndex[cellIndex];
    }

    public final int minRightChildIndex(final int cellIndex) {
        return minRightChildIndex[cellIndex];
    }

    public final int maxRightChildIndex(final int cellIndex) {
        return maxRightChildIndex[cellIndex];
    }

    public int[] numNonTerminals() {
        return numNonTerminals;
    }

    // Added by Nate
    public int cellNonTermStartIndex(final short start, final short end) {
        return offset(cellIndex(start, end));
    }

    public int cellNonTermEndIndex(final short start, final short end) {
        return cellNonTermStartIndex(start, end) + numNonTerminals()[cellIndex(start, end)] - 1;
    }

    /**
     * Returns the minimum scaling step over a set of midpoints
     * 
     * @param start
     * @param end
     * @return the minimum scaling step over a set of midpoints
     */
    protected int minInsideScalingStep(final short start, final short end) {
        if (end - start == 1) {
            return 0;
        }
        int minScalingStep = Integer.MAX_VALUE;
        for (short midpoint = (short) (start + 1); midpoint <= end - 1; midpoint++) {
            final int scalingStep = insideScalingSteps[cellIndex(start, midpoint)]
                    + insideScalingSteps[cellIndex(midpoint, end)];
            if (scalingStep < minScalingStep) {
                minScalingStep = scalingStep;
            }
        }
        return minScalingStep;
    }

    /**
     * Returns the minimum scaling step over a set of sibling/parent cell candidates
     * 
     * @param start
     * @param end
     * @return the minimum scaling step over a set of midpoints
     */
    protected int minOutsideScalingStep(final short start, final short end) {
        int minScalingStep = Integer.MAX_VALUE;
        for (int parentStart = 0; parentStart < start; parentStart++) {
            final int scalingStep = outsideScalingSteps[cellIndex(parentStart, start)]
                    + outsideScalingSteps[cellIndex(parentStart, start)];
            if (scalingStep < minScalingStep) {
                minScalingStep = scalingStep;
            }
        }
        for (int parentEnd = end + 1; parentEnd <= size(); parentEnd++) {
            final int scalingStep = outsideScalingSteps[cellIndex(end, parentEnd)]
                    + outsideScalingSteps[cellIndex(start, parentEnd)];
            if (scalingStep < minScalingStep) {
                minScalingStep = scalingStep;
            }
        }
        return minScalingStep;
    }

    public class RealPackedArrayChartCell extends ChartCell {

        public TemporaryChartCell tmpCell;

        protected final int cellIndex;
        protected final int offset;

        public RealPackedArrayChartCell(final int start, final int end) {
            super(start, end);

            cellIndex = cellIndex(start, end);
            offset = cellOffsets[cellIndex];
        }

        /**
         * Returns the start index of this cell in the main packed array
         * 
         * @return the start index of this cell in the main packed array
         */
        public final int offset() {
            return offset;
        }

        /**
         * Copy and pack entries from temporary array into the main chart array
         */
        @Override
        public void finalizeCell() {

            if (tmpCell == null) {
                return;
            }

            // Copy all populated entries from temporary storage
            boolean foundMinLeftChild = false, foundMinRightChild = false;
            int nonTerminalOffset = offset;

            minLeftChildIndex[cellIndex] = offset;
            maxLeftChildIndex[cellIndex] = offset - 1;
            minRightChildIndex[cellIndex] = offset;
            maxRightChildIndex[cellIndex] = offset - 1;

            for (short nonTerminal = 0; nonTerminal < tmpCell.insideProbabilities.length; nonTerminal++) {

                if (tmpCell.insideProbabilities[nonTerminal] > 0.0 && tmpCell.outsideProbabilities[nonTerminal] >= 0.0) {

                    nonTerminalIndices[nonTerminalOffset] = nonTerminal;
                    insideProbabilities[nonTerminalOffset] = tmpCell.insideProbabilities[nonTerminal];
                    packedChildren[nonTerminalOffset] = tmpCell.packedChildren[nonTerminal];
                    midpoints[nonTerminalOffset] = tmpCell.midpoints[nonTerminal];

                    if (sparseMatrixGrammar.isValidLeftChild(nonTerminal)) {
                        if (!foundMinLeftChild) {
                            minLeftChildIndex[cellIndex] = nonTerminalOffset;
                            foundMinLeftChild = true;
                        }
                        maxLeftChildIndex[cellIndex] = nonTerminalOffset;
                    }

                    if (sparseMatrixGrammar.isValidRightChild(nonTerminal)) {
                        if (!foundMinRightChild) {
                            minRightChildIndex[cellIndex] = nonTerminalOffset;
                            foundMinRightChild = true;
                        }
                        maxRightChildIndex[cellIndex] = nonTerminalOffset;
                    }

                    if (tmpCell.outsideProbabilities != null) {
                        outsideProbabilities[nonTerminalOffset] = tmpCell.outsideProbabilities[nonTerminal];
                    }

                    nonTerminalOffset++;
                }
            }

            numNonTerminals[cellIndex] = nonTerminalOffset - offset;
            insideScalingSteps[cellIndex] = tmpCell.insideScalingStep;
            outsideScalingSteps[cellIndex] = tmpCell.outsideScalingStep;
            this.tmpCell = null;
        }

        /**
         * Special-case to populate a cell with a single entry without a linear search of the temporary storage
         * 
         * @param entryNonTerminal
         * @param entryInsideProbability
         * @param entryPackedChildren
         * @param entryMidpoint
         */
        public void finalizeCell(final short entryNonTerminal, final double entryInsideProbability,
                final int entryPackedChildren, final short entryMidpoint) {

            minLeftChildIndex[cellIndex] = offset;
            maxLeftChildIndex[cellIndex] = offset - 1;
            minRightChildIndex[cellIndex] = offset;
            maxRightChildIndex[cellIndex] = offset - 1;

            nonTerminalIndices[offset] = entryNonTerminal;
            insideProbabilities[offset] = entryInsideProbability;
            packedChildren[offset] = entryPackedChildren;
            midpoints[offset] = entryMidpoint;

            if (sparseMatrixGrammar.isValidLeftChild(entryNonTerminal)) {
                maxLeftChildIndex[cellIndex] = offset;
            }

            if (sparseMatrixGrammar.isValidRightChild(entryNonTerminal)) {
                maxRightChildIndex[cellIndex] = offset;
            }

            numNonTerminals[cellIndex] = 1;
            insideScalingSteps[cellIndex] = tmpCell.insideScalingStep;
            this.tmpCell = null;
        }

        /**
         * Special-case to finalize an empty cell
         */
        public void finalizeEmptyCell() {

            minLeftChildIndex[cellIndex] = offset;
            maxLeftChildIndex[cellIndex] = offset - 1;
            minRightChildIndex[cellIndex] = offset;
            maxRightChildIndex[cellIndex] = offset - 1;

            numNonTerminals[cellIndex] = 0;
            insideScalingSteps[cellIndex] = 0;
            this.tmpCell = null;
        }

        @Override
        public float getInside(final int nonTerminal) {
            if (tmpCell != null) {
                return (float) Math.log(tmpCell.insideProbabilities[nonTerminal]);
            }

            final int index = Arrays.binarySearch(nonTerminalIndices, offset, offset + numNonTerminals[cellIndex],
                    (short) nonTerminal);
            if (index < 0) {
                return Float.NEGATIVE_INFINITY;
            }
            return (float) Math.log(insideProbabilities[index]);
        }

        public short getMidpoint(final short nonTerminal) {
            if (tmpCell != null) {
                return tmpCell.midpoints[nonTerminal];
            }

            final int index = Arrays.binarySearch(nonTerminalIndices, offset, offset + numNonTerminals[cellIndex],
                    nonTerminal);
            if (index < 0) {
                return -1;
            }
            return midpoints[index];
        }

        @Override
        public void updateInside(final Production p, final ChartCell leftCell, final ChartCell rightCell,
                final float insideProbability) {
            // Allow update of cells created without temporary storage, even though this will be inefficient;
            // it should be rare, and is at least useful for unit testing.
            allocateTemporaryStorage();

            final int parent = p.parent;
            numEdgesConsidered++;

            if (insideProbability > tmpCell.insideProbabilities[p.parent]) {
                if (p.isBinaryProd()) {
                    tmpCell.packedChildren[parent] = sparseMatrixGrammar.packingFunction().pack((short) p.leftChild,
                            (short) p.rightChild);
                } else if (p.isLexProd()) {
                    tmpCell.packedChildren[parent] = sparseMatrixGrammar.packingFunction().packLexical(p.leftChild);
                } else {
                    tmpCell.packedChildren[parent] = sparseMatrixGrammar.packingFunction().packUnary(
                            (short) p.leftChild);
                }
                tmpCell.insideProbabilities[parent] = insideProbability;

                // Midpoint == end for unary productions
                tmpCell.midpoints[parent] = leftCell.end();

                numEdgesAdded++;
            }
        }

        @Override
        public void updateInside(final ChartEdge edge) {

            // Allow update of cells created without temporary storage, even though this will be inefficient;
            // it should be rare, and is at least useful for unit testing.
            allocateTemporaryStorage();

            final int parent = edge.prod.parent;
            numEdgesConsidered++;

            if (edge.inside() > tmpCell.insideProbabilities[parent]) {

                if (edge.prod.isBinaryProd()) {
                    tmpCell.packedChildren[parent] = sparseMatrixGrammar.packingFunction().pack(
                            (short) edge.prod.leftChild, (short) edge.prod.rightChild);
                } else if (edge.prod.isLexProd()) {
                    tmpCell.packedChildren[parent] = sparseMatrixGrammar.packingFunction().packLexical(
                            edge.prod.leftChild);
                } else {
                    tmpCell.packedChildren[parent] = sparseMatrixGrammar.packingFunction().packUnary(
                            (short) edge.prod.leftChild);
                }
                tmpCell.insideProbabilities[parent] = edge.inside();

                // Midpoint == end for unary productions
                tmpCell.midpoints[parent] = edge.leftCell.end();

                numEdgesAdded++;
            }
        }

        /**
         * Populates lexical production probabilities.
         * 
         * @param child
         * @param parents
         * @param probabilities
         */
        public void storeLexicalProductions(final int child, final short[] parents, final double[] probabilities) {

            // Allow update of cells created without temporary storage, even though this will be inefficient;
            // it should be rare, and is at least useful for unit testing.
            allocateTemporaryStorage();

            final int packedChild = sparseMatrixGrammar.packingFunction().packLexical(child);

            for (int i = 0; i < parents.length; i++) {
                final short parent = parents[i];
                tmpCell.insideProbabilities[parent] = probabilities[i];
                tmpCell.packedChildren[parent] = packedChild;
            }
        }

        @Override
        public ChartEdge getBestEdge(final int nonTerminal) {
            int edgeChildren;
            short edgeMidpoint;

            if (tmpCell != null) {
                if (tmpCell.insideProbabilities[nonTerminal] == 0.0) {
                    return null;
                }

                edgeChildren = tmpCell.packedChildren[nonTerminal];
                edgeMidpoint = tmpCell.midpoints[nonTerminal];

            } else {
                final int index = Arrays.binarySearch(nonTerminalIndices, offset, offset + numNonTerminals[cellIndex],
                        (short) nonTerminal);
                if (index < 0) {
                    return null;
                }
                edgeChildren = packedChildren[index];
                edgeMidpoint = midpoints[index];
            }

            final int leftChild = sparseMatrixGrammar.packingFunction().unpackLeftChild(edgeChildren);
            final int rightChild = sparseMatrixGrammar.packingFunction().unpackRightChild(edgeChildren);
            final RealPackedArrayChartCell leftChildCell = getCell(start, edgeMidpoint);
            final RealPackedArrayChartCell rightChildCell = edgeMidpoint < end ? (RealPackedArrayChartCell) getCell(
                    edgeMidpoint, end) : null;

            Production p;
            if (rightChild == Production.LEXICAL_PRODUCTION) {
                final float probability = sparseMatrixGrammar.lexicalLogProbability((short) nonTerminal, leftChild);
                p = new Production(nonTerminal, leftChild, probability, true, sparseMatrixGrammar);

            } else if (rightChild == Production.UNARY_PRODUCTION) {
                final float probability = sparseMatrixGrammar.unaryLogProbability((short) nonTerminal,
                        (short) leftChild);
                p = new Production(nonTerminal, leftChild, probability, false, sparseMatrixGrammar);

            } else {
                final float probability = sparseMatrixGrammar.binaryLogProbability((short) nonTerminal, edgeChildren);
                p = new Production(nonTerminal, leftChild, rightChild, probability, sparseMatrixGrammar);
            }
            return new ChartEdge(p, leftChildCell, rightChildCell);
        }

        @Override
        public int getNumNTs() {
            if (tmpCell != null) {
                int count = 0;
                for (short nt = 0; nt < tmpCell.insideProbabilities.length; nt++) {
                    if (tmpCell.insideProbabilities[nt] > 0.0) {
                        count++;
                    }
                }
                return count;
            }

            return numNonTerminals[cellIndex(start, end)];
        }

        @Override
        public int getNumUnfactoredNTs() {
            if (tmpCell != null) {
                int count = 0;
                for (short nt = 0; nt < tmpCell.insideProbabilities.length; nt++) {
                    if (tmpCell.insideProbabilities[nt] > 0.0 && !grammar.nonTermSet.isFactored(nt)) {
                        count++;
                    }
                }
                return count;
            }

            int count = 0;
            for (int i = offset; i < offset + numNonTerminals[cellIndex(start, end)]; i++) {
                if (!grammar.nonTermSet.isFactored(nonTerminalIndices[i])) {
                    count++;
                }
            }
            return count;
        }

        public int leftChildren() {
            if (tmpCell != null) {
                int count = 0;
                for (int i = 0; i < tmpCell.insideProbabilities.length; i++) {
                    if (tmpCell.insideProbabilities[i] > 0.0 && sparseMatrixGrammar.isValidLeftChild(i)) {
                        count++;
                    }
                }
                return count;
            }

            return maxLeftChildIndex[cellIndex(start, end)] - minLeftChildIndex[cellIndex(start, end)] + 1;
        }

        public int rightChildren() {
            if (tmpCell != null) {
                int count = 0;
                for (int i = 0; i < tmpCell.insideProbabilities.length; i++) {
                    if (tmpCell.insideProbabilities[i] > 0.0 && sparseMatrixGrammar.isValidRightChild(i)) {
                        count++;
                    }
                }
                return count;
            }

            return maxRightChildIndex[cellIndex(start, end)] - minRightChildIndex[cellIndex(start, end)] + 1;
        }

        /**
         * Returns the index of the first non-terminal in this cell which is valid as a left child. The grammar must be
         * sorted right, both, left, unary-only, as in {@link Grammar}.
         * 
         * @return the index of the first non-terminal in this cell which is valid as a left child.
         */
        public final int minLeftChildIndex() {
            return minLeftChildIndex[cellIndex];
        }

        /**
         * Returns the index of the last non-terminal in this cell which is valid as a left child. The grammar must be
         * sorted right, both, left, unary-only, as in {@link Grammar}.
         * 
         * @return the index of the last non-terminal in this cell which is valid as a left child.
         */
        public final int maxLeftChildIndex() {
            return maxLeftChildIndex[cellIndex];
        }

        /**
         * Returns the index of the first non-terminal in this cell which is valid as a right child. The grammar must be
         * sorted right, both, left, unary-only, as in {@link Grammar}.
         * 
         * @return the index of the first non-terminal in this cell which is valid as a right child.
         */
        public final int minRightChildIndex() {
            return minRightChildIndex[cellIndex];
        }

        /**
         * Returns the index of the last non-terminal in this cell which is valid as a right child. The grammar must be
         * sorted right, both, left, unary-only, as in {@link Grammar}.
         * 
         * @return the index of the last non-terminal in this cell which is valid as a right child.
         */
        public final int maxRightChildIndex() {
            return maxRightChildIndex[cellIndex];
        }

        /**
         * Warning: Not truly thread-safe, since it doesn't validate that the two cells belong to the same chart.
         */
        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof PackedArrayChartCell)) {
                return false;
            }

            final PackedArrayChartCell packedArrayChartCell = (PackedArrayChartCell) o;
            return (packedArrayChartCell.start() == start && packedArrayChartCell.end() == end);
        }

        @Override
        public String toString() {
            return toString(false);
        }

        public void allocateTemporaryStorage() {
            allocateTemporaryStorage(false, false);
        }

        public void allocateTemporaryStorage(final boolean allocateOutsideProbabilities,
                final boolean copyOutsideProbabilities) {
            // Allocate storage
            if (tmpCell == null) {
                // TODO Use the thread-local version. This will require deciding at thread-local init time whether to
                // allocate outside probability array
                // this.tmpCell = threadLocalTemporaryCells.get();
                // this.tmpCell.clear();
                this.tmpCell = new TemporaryChartCell(grammar);

                // Copy from main chart array to temporary parallel array
                for (int i = offset; i < offset + numNonTerminals[cellIndex]; i++) {
                    final int nonTerminal = nonTerminalIndices[i];
                    tmpCell.packedChildren[nonTerminal] = packedChildren[i];
                    tmpCell.insideProbabilities[nonTerminal] = insideProbabilities[i];
                    tmpCell.midpoints[nonTerminal] = midpoints[i];

                    if (copyOutsideProbabilities) {
                        tmpCell.outsideProbabilities[nonTerminal] = outsideProbabilities[i];
                    }
                }
            }
        }

        public String toString(final boolean formatFractions) {
            final StringBuilder sb = new StringBuilder(256);

            sb.append("RealPackedArrayChartCell[" + start + "][" + end + "] with " + getNumNTs() + " (of "
                    + sparseMatrixGrammar.numNonTerms() + ") edges\n");

            switch (parseTask.decodeMethod) {

            case ViterbiMax:
                return viterbiToString(false);

            case Goodman:
            case SplitSum:
                sb.append(maxcToString(formatFractions));
                break;
            case MaxRuleProd:
                sb.append(maxqToString(formatFractions));
                break;
            default:
                throw new UnsupportedOperationException("Unsupported decoding method " + parseTask.decodeMethod);
            }
            return sb.toString();
        }

        public String viterbiToString(final boolean formatFractions) {
            final StringBuilder sb = new StringBuilder(256);

            sb.append("PackedArrayChartCell[" + start + "][" + end + "] with " + getNumNTs() + " (of "
                    + sparseMatrixGrammar.numNonTerms() + ") edges\n");

            if (tmpCell == null) {
                // Format entries from the main chart array
                for (int index = offset; index < offset + numNonTerminals[cellIndex]; index++) {
                    final int childProductions = packedChildren[index];
                    final double insideProbability = insideProbabilities[index];
                    final double outsideProbability = outsideProbabilities[index];
                    final int midpoint = midpoints[index];

                    final int nonTerminal = nonTerminalIndices[index];

                    sb.append(formatCellEntry(nonTerminal, childProductions, insideProbability, midpoint,
                            outsideProbability, formatFractions));
                }

            } else {
                // Format entries from temporary cell storage
                for (int nonTerminal = 0; nonTerminal < sparseMatrixGrammar.numNonTerms(); nonTerminal++) {

                    if (tmpCell.insideProbabilities[nonTerminal] > 0.0) {
                        sb.append(formatCellEntry(nonTerminal, tmpCell.packedChildren[nonTerminal],
                                tmpCell.insideProbabilities[nonTerminal], tmpCell.midpoints[nonTerminal],
                                tmpCell.outsideProbabilities[nonTerminal], formatFractions));
                    }
                }
            }
            return sb.toString();
        }

        private String maxcToString(final boolean formatFractions) {
            final StringBuilder sb = new StringBuilder(256);

            if (maxcScores[cellIndex] > 0.0) {
                if (maxcUnaryChildren[cellIndex] < 0) {
                    sb.append(String.format("  MaxC = %s (%.5f, %d)", maxcVocabulary.getSymbol(maxcEntries[cellIndex]),
                            maxcScores[cellIndex], maxcMidpoints[cellIndex]));
                } else {
                    sb.append(String.format("  MaxC = %s -> %s (%.5f, %d)",
                            maxcVocabulary.getSymbol(maxcEntries[cellIndex]),
                            maxcVocabulary.getSymbol(maxcUnaryChildren[cellIndex]), maxcScores[cellIndex],
                            maxcMidpoints[cellIndex]));
                }
            }

            sb.append('\n');

            if (tmpCell == null) {
                // Format entries from the main chart array
                for (int index = offset; index < offset + numNonTerminals[cellIndex]; index++) {
                    final int childProductions = packedChildren[index];
                    final double insideProbability = insideProbabilities[index];
                    final double outsideProbability = outsideProbabilities[index];
                    final int midpoint = midpoints[index];

                    final int nonTerminal = nonTerminalIndices[index];

                    sb.append(formatCellEntry(nonTerminal, childProductions, insideProbability, midpoint,
                            outsideProbability, formatFractions));
                }
            } else {
                // Format entries from temporary cell storage
                for (int nonTerminal = 0; nonTerminal < sparseMatrixGrammar.numNonTerms(); nonTerminal++) {

                    if (tmpCell.insideProbabilities[nonTerminal] > 0.0) {
                        final int childProductions = tmpCell.packedChildren[nonTerminal];
                        final double insideProbability = tmpCell.insideProbabilities[nonTerminal];
                        final double outsideProbability = tmpCell.outsideProbabilities[nonTerminal];
                        final int midpoint = tmpCell.midpoints[nonTerminal];

                        sb.append(formatCellEntry(nonTerminal, childProductions, insideProbability, midpoint,
                                outsideProbability, formatFractions));
                    }
                }
            }
            return sb.toString();
        }

        private String formatCellEntry(final int nonterminal, final int childProductions,
                final double insideProbability, final int midpoint, final double outsideProbability,
                final boolean formatFractions) {

            final int leftChild = sparseMatrixGrammar.packingFunction().unpackLeftChild(childProductions);
            final int rightChild = sparseMatrixGrammar.packingFunction().unpackRightChild(childProductions);

            if (formatFractions) {
                if (rightChild == Production.UNARY_PRODUCTION) {
                    // Unary Production
                    return String.format("%s -> %s (%s, %d) outside=%s\n",
                            sparseMatrixGrammar.nonTermSet.getSymbol(nonterminal),
                            sparseMatrixGrammar.nonTermSet.getSymbol(leftChild), Strings.fraction(insideProbability),
                            midpoint, Strings.fraction(outsideProbability));
                } else if (rightChild == Production.LEXICAL_PRODUCTION) {
                    // Lexical Production
                    return String.format("%s -> %s (%s, %d) outside=%s\n",
                            sparseMatrixGrammar.nonTermSet.getSymbol(nonterminal),
                            sparseMatrixGrammar.mapLexicalEntry(leftChild), Strings.fraction(insideProbability),
                            midpoint, Strings.fraction(outsideProbability));
                } else {
                    return String.format("%s -> %s %s (%s, %d) outside=%s\n",
                            sparseMatrixGrammar.nonTermSet.getSymbol(nonterminal),
                            sparseMatrixGrammar.nonTermSet.getSymbol(leftChild),
                            sparseMatrixGrammar.nonTermSet.getSymbol(rightChild), Strings.fraction(insideProbability),
                            midpoint, Strings.fraction(outsideProbability));
                }
            }

            if (rightChild == Production.UNARY_PRODUCTION) {
                // Unary Production
                return String.format("%s -> %s (%.10f, %d) outside=%.10f\n",
                        sparseMatrixGrammar.nonTermSet.getSymbol(nonterminal),
                        sparseMatrixGrammar.nonTermSet.getSymbol(leftChild), insideProbability, midpoint,
                        outsideProbability);
            } else if (rightChild == Production.LEXICAL_PRODUCTION) {
                // Lexical Production
                return String
                        .format("%s -> %s (%.10f, %d) outside=%.10f\n",
                                sparseMatrixGrammar.nonTermSet.getSymbol(nonterminal),
                                sparseMatrixGrammar.mapLexicalEntry(leftChild), insideProbability, midpoint,
                                outsideProbability);
            } else {
                return String.format("%s -> %s %s (%.10f, %d) outside=%.10f\n",
                        sparseMatrixGrammar.nonTermSet.getSymbol(nonterminal),
                        sparseMatrixGrammar.nonTermSet.getSymbol(leftChild),
                        sparseMatrixGrammar.nonTermSet.getSymbol(rightChild), insideProbability, midpoint,
                        outsideProbability);
            }
        }

        private String maxqToString(final boolean formatFractions) {
            final StringBuilder sb = new StringBuilder(256);

            for (short baseNt = 0; baseNt < maxcVocabulary.size(); baseNt++) {
                if (maxQ[cellIndex][baseNt] != 0) {

                    sb.append(formatCellEntry(baseNt, maxQLeftChildren[cellIndex][baseNt],
                            maxQRightChildren[cellIndex][baseNt], maxQ[cellIndex][baseNt],
                            maxQMidpoints[cellIndex][baseNt], formatFractions));
                }
            }
            return sb.toString();
        }

        private String formatCellEntry(final short nonterminal, final short leftChild, final short rightChild,
                final double score, final short midpoint, final boolean formatFractions) {

            if (formatFractions) {
                if (rightChild == Production.UNARY_PRODUCTION) {
                    // Unary Production
                    return String.format("%s -> %s (%s, %d)\n", maxcVocabulary.getSymbol(nonterminal),
                            maxcVocabulary.getSymbol(leftChild), Strings.fraction(score), midpoint);
                } else if (rightChild == Production.LEXICAL_PRODUCTION) {
                    // Lexical Production
                    return String.format("%s -> %s (%s, %d)\n", maxcVocabulary.getSymbol(nonterminal),
                            sparseMatrixGrammar.mapLexicalEntry(parseTask.tokens[midpoint - 1]),
                            Strings.fraction(score), midpoint);
                } else {
                    return String.format("%s -> %s %s (%s, %d)\n", maxcVocabulary.getSymbol(nonterminal),
                            maxcVocabulary.getSymbol(leftChild), maxcVocabulary.getSymbol(rightChild),
                            Strings.fraction(score), midpoint);
                }
            }

            if (rightChild == Production.UNARY_PRODUCTION) {
                // Unary Production
                return String.format("%s -> %s (%.10f, %d)\n", maxcVocabulary.getSymbol(nonterminal),
                        maxcVocabulary.getSymbol(leftChild), score, midpoint);
            } else if (rightChild == Production.LEXICAL_PRODUCTION) {
                // Lexical Production
                return String.format("%s -> %s (%.10f, %d)\n", maxcVocabulary.getSymbol(nonterminal),
                        sparseMatrixGrammar.mapLexicalEntry(parseTask.tokens[midpoint - 1]), score, midpoint);
            } else {
                return String.format("%s -> %s %s (%.10f, %d)\n", maxcVocabulary.getSymbol(nonterminal),
                        maxcVocabulary.getSymbol(leftChild), maxcVocabulary.getSymbol(rightChild), score, midpoint);
            }
        }
    }

    public final static class TemporaryChartCell {

        public final int[] packedChildren;
        public final double[] insideProbabilities;
        public final double[] outsideProbabilities;
        public final short[] midpoints;
        public int insideScalingStep = 0;
        public int outsideScalingStep = 0;

        private final SparseMatrixGrammar sparseMatrixGrammar;

        public TemporaryChartCell(final Grammar grammar) {
            this.packedChildren = new int[grammar.numNonTerms()];
            this.insideProbabilities = new double[grammar.numNonTerms()];
            this.midpoints = new short[grammar.numNonTerms()];
            this.sparseMatrixGrammar = grammar instanceof SparseMatrixGrammar ? (SparseMatrixGrammar) grammar : null;
            this.outsideProbabilities = new double[grammar.numNonTerms()];

            clear();
        }

        public void clear() {
            Arrays.fill(insideProbabilities, 0);
            Arrays.fill(outsideProbabilities, 0);
        }

        // @Override
        // public String toString() {
        // return toString(false);
        // }
        //
        // public String toString(final boolean formatFractions) {
        // final StringBuilder sb = new StringBuilder(128);
        //
        // for (int nonTerminal = 0; nonTerminal < sparseMatrixGrammar.numNonTerms(); nonTerminal++) {
        //
        // if (insideProbabilities[nonTerminal] != 0) {
        // sb.append(formatCellEntry(nonTerminal, packedChildren[nonTerminal],
        // insideProbabilities[nonTerminal], midpoints[nonTerminal],
        // outsideProbabilities[nonTerminal], formatFractions));
        // }
        // }
        //
        // return sb.toString();
        // }
    }
}