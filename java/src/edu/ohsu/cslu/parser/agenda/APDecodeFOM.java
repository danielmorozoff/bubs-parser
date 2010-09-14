package edu.ohsu.cslu.parser.agenda;

import edu.ohsu.cslu.grammar.LeftRightListsGrammar;
import edu.ohsu.cslu.grammar.Grammar.Production;
import edu.ohsu.cslu.parser.ParserDriver;
import edu.ohsu.cslu.parser.chart.CellChart.ChartEdge;
import edu.ohsu.cslu.parser.chart.CellChart.HashSetChartCell;
import edu.ohsu.cslu.parser.util.Log;
import edu.ohsu.cslu.parser.util.ParseTree;

public class APDecodeFOM extends APWithMemory {

    // only change from APWithMemory is that we are using the edge's
    // FOM score instead of the inside score to determine the "best" edge
    // in the chart. This causes the edge's score to be computed as:
    // score(A-> B C, [i,j,k]) = FOM(edge) + FOM(B[i,j]) + FOM(C[j,k])

    public APDecodeFOM(final ParserDriver opts, final LeftRightListsGrammar grammar) {
        super(opts, grammar);
    }

    @Override
    public ParseTree findBestParse(final int[] tokens) throws Exception {
        ChartEdge edge;
        HashSetChartCell cell;

        initParser(tokens);
        addLexicalProductions(tokens);
        edgeSelector.init(chart);

        for (int i = 0; i < tokens.length; i++) {
            cell = chart.getCell(i, i + 1);
            for (final int nt : cell.getPosNTs()) {
                expandFrontier(nt, cell);
            }
        }

        while (!agenda.isEmpty() && !chart.hasCompleteParse(grammar.startSymbol)) {
            edge = agenda.poll(); // get and remove top agenda edge
            nAgendaPop += 1;

            cell = chart.getCell(edge.start(), edge.end());
            final int nt = edge.prod.parent;

            // System.out.println(edge + " best=" + cell.getInside(nt));

            // final float insideProb = edge.inside();
            final float fomScore = edge.fom; // ** THE CHANGE **

            if (fomScore > cell.getInside(nt)) {
                cell.bestEdge[nt] = edge;
                cell.updateInside(nt, fomScore);
                // if A->B C is added to chart but A->X Y was already in this chart cell, then the
                // first edge must have been better than the current edge because we pull edges
                // from the agenda best-first. This also means that the entire frontier
                // has already been added.
                expandFrontier(nt, cell);
                nChartEdges += 1;
            }
        }

        if (agenda.isEmpty()) {
            Log.info(1, "WARNING: Agenda is empty.  All edges have been added to chart.");
        }

        return chart.extractBestParse(grammar.startSymbol);
    }

    @Override
    protected void addLexicalProductions(final int sent[]) throws Exception {
        HashSetChartCell cell;

        // add lexical productions and unary productions to the base cells of the chart
        for (int i = 0; i < chart.size(); i++) {
            cell = chart.getCell(i, i + 1);
            for (final Production lexProd : grammar.getLexicalProductionsWithChild(sent[i])) {
                // Add lexical prods directly to the chart instead of to the agenda because
                // the boundary FOM (and possibly others use the surrounding POS tags to calculate
                // the fit of a new edge. If the POS tags don't exist yet (are still in the agenda)
                // it skew probs (to -Infinity) and never allow some edges that should be allowed
                cell.updateInside(lexProd, lexProd.prob);

            }
        }
    }

}