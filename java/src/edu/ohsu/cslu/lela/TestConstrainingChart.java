/*
 * Copyright 2010, 2011 Aaron Dunlop and Nathan Bodenstab
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
package edu.ohsu.cslu.lela;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.StringReader;

import org.junit.Before;
import org.junit.Test;

import edu.ohsu.cslu.datastructs.narytree.BinaryTree;
import edu.ohsu.cslu.datastructs.narytree.NaryTree;
import edu.ohsu.cslu.datastructs.narytree.NaryTree.Factorization;
import edu.ohsu.cslu.grammar.CsrSparseMatrixGrammar;
import edu.ohsu.cslu.grammar.GrammarFormatType;
import edu.ohsu.cslu.grammar.SparseMatrixGrammar;
import edu.ohsu.cslu.grammar.SymbolSet;
import edu.ohsu.cslu.tests.JUnit;

/**
 * Unit tests for {@link ConstrainingChart}.
 * 
 * @author Aaron Dunlop
 * @since Jan 15, 2011
 */
public class TestConstrainingChart {

    ProductionListGrammar plGrammar0;
    SparseMatrixGrammar csrGrammar0;

    @Before
    public void setUp() throws IOException {
        // Induce a grammar from the sample tree
        final StringCountGrammar sg = new StringCountGrammar(new StringReader(AllLelaTests.STRING_SAMPLE_TREE), null,
                null);

        // Construct a SparseMatrixGrammar from the induced grammar
        plGrammar0 = new ProductionListGrammar(sg);
        csrGrammar0 = new CsrSparseMatrixGrammar(plGrammar0.binaryProductions, plGrammar0.unaryProductions,
                plGrammar0.lexicalProductions, plGrammar0.vocabulary, plGrammar0.lexicon, GrammarFormatType.Berkeley,
                SparseMatrixGrammar.PerfectIntPairHashPackingFunction.class);
    }

    /**
     * Tests constructing a {@link ConstrainingChart} from a gold tree and then re-extracting that tree from the chart.
     * 
     * @throws IOException
     */
    @Test
    public void testGoldTreeConstructor() throws IOException {

        final ConstrainingChart cc = new ConstrainingChart(BinaryTree.read(AllLelaTests.STRING_SAMPLE_TREE,
                String.class), csrGrammar0);

        // The chart should size itself according to the longest unary chain
        assertEquals(2, cc.maxUnaryChainLength());

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
        assertEquals(2, cc.unaryChainLength(0, 5));
        assertEquals(Float.NEGATIVE_INFINITY, cc.getInside(0, 4, b), .001f);

        assertEquals(0, cc.getInside(0, 2, a), .001f);
        assertEquals(1, cc.unaryChainLength(0, 2));
        assertEquals(Float.NEGATIVE_INFINITY, cc.getInside(0, 2, b), .001f);

        assertEquals(0, cc.getInside(0, 3, a), .001f);
        assertEquals(1, cc.unaryChainLength(0, 3));
        assertEquals(Float.NEGATIVE_INFINITY, cc.getInside(0, 2, b), .001f);

        assertEquals(0, cc.getInside(3, 5, b), .001f);
        assertEquals(1, cc.unaryChainLength(3, 5));
        assertEquals(Float.NEGATIVE_INFINITY, cc.getInside(3, 5, a), .001f);

        assertEquals(2, cc.unaryChainLength(3, 4));
        assertEquals(0, cc.getInside(3, 4, b), .001f);

        assertEquals(1, cc.unaryChainLength(4, 5));

        // And ensure that the extracted parse matches the input gold tree
        assertEquals(AllLelaTests.STRING_SAMPLE_TREE, cc.extractBestParse(vocabulary.getIndex("top")).toString());

        JUnit.assertArrayEquals(new short[][] { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 4 }, { 4, 5 }, { 0, 2 }, { 3, 5 },
                { 0, 3 }, { 0, 5 } }, cc.openCells);

        assertArrayEquals(new short[] { 1, 2, 4, -1, -1, 1, -1, -1, -1, 2, -1, -1, 13, 4, 13 }, cc.parentCellIndices);
        assertArrayEquals(new short[] { 5, 9, 13, -1, -1, 0, -1, -1, -1, 1, -1, -1, 14, 2, 12 }, cc.siblingCellIndices);
    }

    @Test
    public void testLongUnaryChain() throws IOException {
        // Try from a problematic tree from the Penn Treebank
        // Induce a grammar from the tree and construct a SparseMatrixGrammar
        final ProductionListGrammar plg = new ProductionListGrammar(new StringCountGrammar(new StringReader(
                AllLelaTests.TREE_WITH_LONG_UNARY_CHAIN), Factorization.RIGHT, GrammarFormatType.Berkeley));
        final SparseMatrixGrammar csrg = new CsrSparseMatrixGrammar(plg.binaryProductions, plg.unaryProductions,
                plg.lexicalProductions, plg.vocabulary, plg.lexicon, GrammarFormatType.Berkeley,
                SparseMatrixGrammar.PerfectIntPairHashPackingFunction.class);

        final ConstrainingChart cc = new ConstrainingChart(NaryTree.read(AllLelaTests.TREE_WITH_LONG_UNARY_CHAIN,
                String.class).factor(GrammarFormatType.Berkeley, Factorization.RIGHT), csrg);

        // Verify some unary chain lengths
        assertEquals(3, cc.maxUnaryChainLength());
        assertEquals(1, cc.unaryChainLength(0, 1));
        assertEquals(1, cc.unaryChainLength(2, 3));
        assertEquals(2, cc.unaryChainLength(3, 4));
        assertEquals(3, cc.unaryChainLength(4, 5));

        // Ensure that the extracted parse matches the input gold tree
        assertEquals(AllLelaTests.TREE_WITH_LONG_UNARY_CHAIN,
                BinaryTree.read(cc.extractBestParse(0).toString(), String.class).unfactor(GrammarFormatType.Berkeley)
                        .toString());
    }

    @Test
    public void testWithInternalStartSymbol() {
        final String bracketedTree = "(top (a (top (a c) (b c))) (b c))";
        final ConstrainingChart cc = new ConstrainingChart(BinaryTree.read(bracketedTree, String.class), csrGrammar0);
        // Ensure that the extracted parse matches the input gold tree
        assertEquals(bracketedTree, cc.extractBestParse(plGrammar0.vocabulary.getIndex("top")).toString());
    }

    @Test
    public void testConstructFromConstrainedChart() {
        fail("Not Implemented");

        // JUnit.assertArrayEquals(new short[][] { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 4 }, { 4, 5 }, { 0, 2 }, { 3, 5 },
        // { 0, 3 }, { 0, 5 } }, cc.openCells);
    }
}
