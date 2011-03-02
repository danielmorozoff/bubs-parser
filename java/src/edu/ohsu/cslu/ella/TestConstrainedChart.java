package edu.ohsu.cslu.ella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.StringReader;

import org.junit.Before;
import org.junit.Test;

import edu.ohsu.cslu.datastructs.narytree.BinaryTree;
import edu.ohsu.cslu.datastructs.narytree.BinaryTree.Factorization;
import edu.ohsu.cslu.datastructs.narytree.NaryTree;
import edu.ohsu.cslu.grammar.CsrSparseMatrixGrammar;
import edu.ohsu.cslu.grammar.Grammar.GrammarFormatType;
import edu.ohsu.cslu.grammar.SparseMatrixGrammar;
import edu.ohsu.cslu.grammar.SymbolSet;

/**
 * Unit tests for {@link ConstrainedChart}.
 * 
 * @author Aaron Dunlop
 * @since Jan 15, 2011
 * 
 * @version $Revision$ $Date$ $Author$
 */
public class TestConstrainedChart {

    ProductionListGrammar plGrammar0;
    CsrSparseMatrixGrammar csrGrammar0;

    @Before
    public void setUp() throws IOException {
        // Induce a grammar from the sample tree
        final StringCountGrammar sg = new StringCountGrammar(new StringReader(AllEllaTests.STRING_SAMPLE_TREE), null,
                null, 1);

        // Construct a SparseMatrixGrammar from the induced grammar
        plGrammar0 = new ProductionListGrammar(sg);
        csrGrammar0 = new CsrSparseMatrixGrammar(plGrammar0.binaryProductions, plGrammar0.unaryProductions,
                plGrammar0.lexicalProductions, plGrammar0.vocabulary, plGrammar0.lexicon, GrammarFormatType.Berkeley,
                SparseMatrixGrammar.PerfectIntPairHashFilterFunction.class);
    }

    /**
     * Tests constructing a {@link ConstrainedChart} from a gold tree and then re-extracting that tree from the chart.
     * 
     * @throws IOException
     */
    @Test
    public void testGoldTreeConstructor() throws IOException {

        final ConstrainedChart cc = new ConstrainedChart(
                BinaryTree.read(AllEllaTests.STRING_SAMPLE_TREE, String.class), csrGrammar0);

        // The chart should size itself according to the longest unary chain
        assertEquals(2, cc.beamWidth());

        final SymbolSet<String> vocabulary = plGrammar0.vocabulary;
        final int top = plGrammar0.vocabulary.getIndex("top");
        final int a = plGrammar0.vocabulary.getIndex("a");
        final int b = plGrammar0.vocabulary.getIndex("b");
        final int c = plGrammar0.lexicon.getIndex("c");
        final int d = plGrammar0.lexicon.getIndex("d");

        // Verify that the tokens array is initialized properly
        assertArrayEquals(new int[] { c, c, d, d, d }, cc.tokens);

        // Verify expected probabilities in a few cells
        assertEquals(0, cc.getInside(0, 5, top), .001f);
        assertEquals(0, cc.getInside(0, 5, a), .001f);
        assertEquals(Float.NEGATIVE_INFINITY, cc.getInside(0, 4, b), .001f);

        assertEquals(0, cc.getInside(0, 2, a), .001f);
        assertEquals(Float.NEGATIVE_INFINITY, cc.getInside(0, 2, b), .001f);

        assertEquals(0, cc.getInside(0, 3, a), .001f);
        assertEquals(Float.NEGATIVE_INFINITY, cc.getInside(0, 2, b), .001f);

        assertEquals(0, cc.getInside(3, 5, b), .001f);
        assertEquals(Float.NEGATIVE_INFINITY, cc.getInside(3, 5, a), .001f);

        // And ensure that the extracted parse matches the input gold tree
        assertEquals(AllEllaTests.STRING_SAMPLE_TREE, cc.extractBestParse(vocabulary.getIndex("top")).toString());
    }

    @Test
    public void testLongUnaryChain() throws IOException {
        // Try from a problematic tree from the Penn Treebank
        // Induce a grammar from the tree and construct a SparseMatrixGrammar
        final ProductionListGrammar plg = new ProductionListGrammar(new StringCountGrammar(new StringReader(
                AllEllaTests.TREE_WITH_LONG_UNARY_CHAIN), Factorization.RIGHT, GrammarFormatType.Berkeley, 0));
        final CsrSparseMatrixGrammar csrg = new CsrSparseMatrixGrammar(plg.binaryProductions, plg.unaryProductions,
                plg.lexicalProductions, plg.vocabulary, plg.lexicon, GrammarFormatType.Berkeley,
                SparseMatrixGrammar.PerfectIntPairHashFilterFunction.class);

        final ConstrainedChart cc = new ConstrainedChart(NaryTree.read(AllEllaTests.TREE_WITH_LONG_UNARY_CHAIN,
                String.class).factor(GrammarFormatType.Berkeley, Factorization.RIGHT), csrg);
        assertEquals(AllEllaTests.TREE_WITH_LONG_UNARY_CHAIN,
                BinaryTree.read(cc.extractBestParse(0).toString(), String.class).unfactor(GrammarFormatType.Berkeley)
                        .toString());
    }

    // @Test
    // public void testCountRuleObservations() {
    // fail("Not Implemented");
    // }

    @Test
    public void testWithInternalStartSymbol() {
        final String bracketedTree = "(top (a (top (a c) (b c))) (b c))";
        // final String bracketedTree = "(top (a (a (a (a c) (a c)) (b d)) (b (top (b (b d)) (a d)))))";
        final ConstrainedChart cc = new ConstrainedChart(BinaryTree.read(bracketedTree, String.class), csrGrammar0);
        // Ensure that the extracted parse matches the input gold tree
        assertEquals(bracketedTree, cc.extractBestParse(plGrammar0.vocabulary.getIndex("top")).toString());

    }
}