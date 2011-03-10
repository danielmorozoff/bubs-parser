package edu.ohsu.cslu.parser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import cltool4j.BaseCommandlineTool;
import cltool4j.args4j.Option;
import edu.ohsu.cslu.grammar.Grammar;
import edu.ohsu.cslu.parser.Parser.ResearchParserType;
import edu.ohsu.cslu.parser.edgeselector.EdgeSelector;
import edu.ohsu.cslu.parser.edgeselector.EdgeSelector.EdgeSelectorType;
import edu.ohsu.cslu.perceptron.ModelTrainer;

public class ParserTrainer extends BaseCommandlineTool {

    // TODO: should combine this with ModelTrainer

    // == Parser options ==
    // @Option(name = "-p", aliases = { "--parser" }, metaVar = "parser", usage = "Parser implementation")
    // public ParserType parserType = ParserType.CKY;

    @Option(name = "-rp", metaVar = "parser", usage = "Research Parser implementation")
    private ResearchParserType researchParserType = ResearchParserType.ECPCellCrossList;

    @Option(name = "-g", required = true, metaVar = "grammar", usage = "Grammar file (text, gzipped text, or binary serialized")
    private String grammarFile = null;

    // === Possible models to train ===
    @Option(name = "-boundaryFOM", usage = "Train a Boundary Figure of Merit model")
    public boolean boundaryFOM = false;
    // public EdgeSelectorType edgeFOMType = null;

    @Option(name = "-beamConf", usage = "Train Beam Confidence model")
    public boolean beamConf = false;

    @Option(name = "-cellConstraints", usage = "Train a Cell Constraints model")
    public boolean cellConstraints = false;

    public BufferedWriter outputStream = new BufferedWriter(new OutputStreamWriter(System.out));
    public BufferedReader inputStream = new BufferedReader(new InputStreamReader(System.in));
    private Grammar grammar;

    public static void main(final String[] args) throws Exception {
        run(args);
    }

    @Override
    public void setup() throws Exception {

        grammar = ParserDriver.readGrammar(grammarFile, researchParserType, null);
    }

    @Override
    public void run() throws Exception {

        if (boundaryFOM == true) {
            // To train a BoundaryInOut FOM model we need a grammar and
            // binarized gold input trees with NTs from same grammar
            final EdgeSelector edgeSelector = EdgeSelector.create(EdgeSelectorType.BoundaryInOut, grammar, null);
            edgeSelector.train(inputStream);
            edgeSelector.writeModel(outputStream);
        } else if (beamConf == true) {
            final ModelTrainer m = new ModelTrainer();
            m.natesTraining();
            // final PerceptronCellSelector perceptronCellSelector = (PerceptronCellSelector)
            // CellSelector.create(cellSelectorType, cellModelStream, cslutScoresStream);
            // final BSCPPerceptronCellTrainer parser = new BSCPPerceptronCellTrainer(opts, (LeftHashGrammar) grammar);
            // perceptronCellSelector.train(inputStream, parser);
        } else {
            System.out.println("ERROR.");
        }
    }

}
