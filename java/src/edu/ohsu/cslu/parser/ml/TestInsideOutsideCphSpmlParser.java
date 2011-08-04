package edu.ohsu.cslu.parser.ml;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import cltool4j.ConfigProperties;
import cltool4j.GlobalConfigProperties;
import edu.ohsu.cslu.grammar.Grammar;
import edu.ohsu.cslu.grammar.InsideOutsideCscSparseMatrixGrammar;
import edu.ohsu.cslu.grammar.SparseMatrixGrammar.PerfectIntPairHashPackingFunction;
import edu.ohsu.cslu.parser.Parser;
import edu.ohsu.cslu.parser.ParserDriver;
import edu.ohsu.cslu.parser.SparseMatrixParser;
import edu.ohsu.cslu.parser.chart.InsideOutsideChart;
import edu.ohsu.cslu.tests.JUnit;

public class TestInsideOutsideCphSpmlParser {

    private SparseMatrixParser<InsideOutsideCscSparseMatrixGrammar, InsideOutsideChart> parser;

    /** WSJ section 24 sentences 1-20 */
    protected static ArrayList<String[]> sentences = new ArrayList<String[]>();

    /**
     * Reads in the first 20 sentences of WSJ section 24. Run once for the class, prior to execution of the first test
     * method.
     * 
     * @throws Exception if unable to read
     */
    @BeforeClass
    public static void suiteSetUp() throws Exception {
        // Read test sentences
        // TODO Parameterize test sentences (this will require a custom Runner implementation)
        final BufferedReader tokenizedReader = new BufferedReader(new InputStreamReader(
                JUnit.unitTestDataAsStream("parsing/wsj.24.tokens.1-20")));

        final BufferedReader parsedReader = new BufferedReader(new InputStreamReader(
                JUnit.unitTestDataAsStream("parsing/wsj.24.parsed.R2.cyk.1-20")));

        for (String sentence = tokenizedReader.readLine(); sentence != null; sentence = tokenizedReader.readLine()) {
            final String parsedSentence = parsedReader.readLine();
            sentences.add(new String[] { sentence, parsedSentence });
        }
    }

    @Before
    public void setUp() throws Exception {
        final InsideOutsideCscSparseMatrixGrammar grammar = new InsideOutsideCscSparseMatrixGrammar(
                JUnit.unitTestDataAsReader("grammars/eng.R2.gr.gz"), PerfectIntPairHashPackingFunction.class);

        final ConfigProperties props = GlobalConfigProperties.singleton();
        props.put(Parser.PROPERTY_MAX_BEAM_WIDTH, "30");
        props.put(Parser.PROPERTY_LEXICAL_ROW_BEAM_WIDTH, "30");
        props.put(Parser.PROPERTY_LEXICAL_ROW_UNARIES, "10");
        props.put(Parser.PROPERTY_MAX_LOCAL_DELTA, "15");

        final ParserDriver opts = new ParserDriver();
        // opts.binaryTreeOutput = true;
        parser = new InsideOutsideCphSpmlParser(opts, grammar);
    }

    @After
    public void tearDown() {
        if (parser != null) {
            parser.shutdown();
        }
    }

    @AfterClass
    public static void suiteTearDown() {
        GlobalConfigProperties.singleton().clear();
    }

    /** Simple grammar for parsing 'The fish market stands last' */
    public static Reader simpleGrammar2() throws Exception {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("format=Berkeley start=ROOT\n");
        sb.append("S => NP VP 0\n"); // 1
        sb.append("ROOT => S 0\n"); // 1
        sb.append("NP => DT NP -1.386294361\n"); // 1/4
        sb.append("NP => DT NN -1.386294361\n"); // 1/4
        sb.append("NP => NN NN -1.791759469\n"); // 1/6
        sb.append("NP => @NP NN -1.791759469\n"); // 1/6
        sb.append("NP => NN RB -1.791759469\n"); // 1/6
        sb.append("@NP => NN NN 0\n"); // 1
        sb.append("VP => VB RB -0.693147181\n"); // 1/2
        sb.append("VP => VB -1.386294361\n"); // 1/2

        sb.append(Grammar.LEXICON_DELIMITER);
        sb.append('\n');

        sb.append("DT => The 0\n");

        sb.append("NN => fish -0.980829253\n"); // 3/8
        sb.append("NN => market -2.079441542\n"); // 1/8
        sb.append("NN => stands -1.386294361\n"); // 1/4
        sb.append("NN => UNK -1.386294361\n"); // 1/4

        sb.append("VB => market -1.098612289\n"); // 1/3
        sb.append("VB => stands -1.098612289\n"); // 1/3
        sb.append("VB => last -1.098612289\n"); // 1/3

        sb.append("RB => last 0\n"); // 1

        return new StringReader(sb.toString());
    }

    @Test
    public void testSimpleGrammar2() throws Exception {

        final String sentence = "The fish market stands last";

        parser = new InsideOutsideCphSpmlParser(new ParserDriver(), new InsideOutsideCscSparseMatrixGrammar(
                simpleGrammar2(), PerfectIntPairHashPackingFunction.class));

        final String bestParseTree = parser.parseSentence(sentence).parseBracketString;
        assertEquals("(ROOT (S (NP (DT The) (NP (NN fish) (NN market))) (VP (VB stands) (RB last))))", bestParseTree);
    }

    @Test
    public void testPartialSentence2() throws Exception {
        final String sentence = "The report is due out tomorrow .";
        System.out.println(parser.parseSentence(sentence).binaryParse.toString());
        System.out.println(parser.parseSentence(sentence).parseBracketString);
    }

    @Test
    public void testSentence2() throws Exception {
        parseTreebankSentence(1);
    }

    protected void parseTreebankSentence(final int index) throws Exception {
        final String bestParseTree = parser.parseSentence(sentences.get(index)[0]).parseBracketString;
        assertEquals(sentences.get(index)[1], bestParseTree.toString());
    }

    @Test
    @Ignore
    public void testAll() throws IOException {

        final BufferedReader tokenizedReader = new BufferedReader(new InputStreamReader(
                JUnit.unitTestDataAsStream("parsing/wsj.24.tokens.1-20")));

        final BufferedReader parsedReader = new BufferedReader(new InputStreamReader(
                JUnit.unitTestDataAsStream("parsing/wsj.24.parsed.R2.beam.fom.1-20")));

        int i = 1;
        for (String sentence = tokenizedReader.readLine(); sentence != null; sentence = tokenizedReader.readLine()) {
            final String parsedSentence = parsedReader.readLine();
            System.out.println(parser.parseSentence(sentence).binaryParse.toString());
            i++;
        }
    }
}