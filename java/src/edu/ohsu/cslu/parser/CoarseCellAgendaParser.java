package edu.ohsu.cslu.parser;

import java.util.Collection;
import java.util.PriorityQueue;

import edu.ohsu.cslu.grammar.LeftHashGrammar;
import edu.ohsu.cslu.grammar.Grammar.Production;
import edu.ohsu.cslu.parser.CellChart.ChartCell;
import edu.ohsu.cslu.parser.CellChart.ChartEdge;
import edu.ohsu.cslu.parser.util.ParseTree;

public class CoarseCellAgendaParser extends ChartParser<LeftHashGrammar, CellChart> {

    float[][] maxEdgeFOM;
    PriorityQueue<ChartCell> spanAgenda;

    public CoarseCellAgendaParser(final ParserOptions opts, final LeftHashGrammar grammar) {
        super(opts, grammar);
    }

    @Override
    protected void initParser(final int n) {
        super.initParser(n);
        this.maxEdgeFOM = new float[chart.size()][chart.size() + 1];
        this.spanAgenda = new PriorityQueue<ChartCell>();

        // The chart is (chart.size()+1)*chart.size()/2
        for (int start = 0; start < chart.size(); start++) {
            for (int end = start + 1; end < chart.size() + 1; end++) {
                maxEdgeFOM[start][end] = Float.NEGATIVE_INFINITY;
            }
        }
    }

    @Override
    public ParseTree findBestParse(final String sentence) throws Exception {
        ChartCell cell;
        final int sent[] = grammar.tokenizer.tokenizeToIndex(sentence);
        currentSentence = sentence;

        initParser(sent.length);
        addLexicalProductions(sent);
        edgeSelector.init(this);
        addUnaryExtensionsToLexProds();

        for (int i = 0; i < chart.size(); i++) {
            expandFrontier(chart.getCell(i, i + 1));
        }

        while (hasNext() && !hasCompleteParse()) {
            cell = next();
            // System.out.println(" nextCell: " + cell);
            visitCell(cell);
            expandFrontier(cell);
        }

        return extractBestParse();
    }

    protected void visitCell(final ChartCell cell) {
        final int start = cell.start(), end = cell.end();
        Collection<Production> possibleProds;
        ChartEdge edge;
        final ChartEdge[] bestEdges = new ChartEdge[grammar.numNonTerms()]; // inits to null

        final int maxEdgesToAdd = (int) ParserOptions.param2;

        for (int mid = start + 1; mid <= end - 1; mid++) { // mid point
            final ChartCell leftCell = chart.getCell(start, mid);
            final ChartCell rightCell = chart.getCell(mid, end);
            for (final int leftNT : leftCell.getLeftChildNTs()) {
                for (final int rightNT : rightCell.getRightChildNTs()) {
                    possibleProds = grammar.getBinaryProductionsWithChildren(leftNT, rightNT);
                    if (possibleProds != null) {
                        for (final Production p : possibleProds) {
                            edge = chart.new ChartEdge(p, leftCell, rightCell);
                            addEdgeToArray(edge, bestEdges);
                        }
                    }
                }
            }
        }

        addBestEdgesToChart(cell, bestEdges, maxEdgesToAdd);
    }

    protected void addEdgeToArray(final ChartEdge edge, final ChartEdge[] bestEdges) {
        final int parent = edge.prod.parent;
        if (bestEdges[parent] == null || edge.fom > bestEdges[parent].fom) {
            bestEdges[parent] = edge;
        }
    }

    private void addEdgeToAgenda(final ChartEdge edge, final PriorityQueue<ChartEdge> agenda) {
        agenda.add(edge);
    }

    protected void addBestEdgesToChart(final ChartCell cell, final ChartEdge[] bestEdges, final int maxEdgesToAdd) {
        ChartEdge edge, unaryEdge;
        int numAdded = 0;

        final PriorityQueue<ChartEdge> agenda = new PriorityQueue<ChartEdge>();
        for (int i = 0; i < bestEdges.length; i++) {
            if (bestEdges[i] != null) {
                addEdgeToAgenda(bestEdges[i], agenda);
            }
        }

        while (agenda.isEmpty() == false && numAdded <= maxEdgesToAdd) {
            edge = agenda.poll();
            // addedEdge = cell.addEdge(edge);
            // if (addedEdge) {
            final int nt = edge.prod.parent;
            final float insideProb = edge.inside();
            if (insideProb > cell.getInside(edge.prod.parent)) {
                cell.updateInside(nt, insideProb);
                // System.out.println(" addingEdge: " + edge);
                numAdded++;
                // Add unary productions to agenda so they can compete with binary productions
                for (final Production p : grammar.getUnaryProductionsWithChild(edge.prod.parent)) {
                    unaryEdge = chart.new ChartEdge(p, cell);
                    addEdgeToAgenda(unaryEdge, agenda);
                }
            }
        }

        // TODO: should I decrease the maxEdgeFOM here according to the best edge NOT in the chart?
        // won't this just be overrun when we expand the frontier?
        if (agenda.isEmpty()) {
            maxEdgeFOM[cell.start()][cell.end()] = Float.NEGATIVE_INFINITY;
        } else {
            maxEdgeFOM[cell.start()][cell.end()] = agenda.peek().fom;
        }
    }

    protected boolean hasNext() {
        return true;
    }

    protected ChartCell next() {
        // return spanAgenda.poll();
        ChartCell bestSpan = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int span = 1; span <= chart.size(); span++) {
            for (int beg = 0; beg < chart.size() - span + 1; beg++) { // beginning
                if (maxEdgeFOM[beg][beg + span] > bestScore) {
                    bestScore = maxEdgeFOM[beg][beg + span];
                    bestSpan = chart.getCell(beg, beg + span);
                }
            }
        }
        return bestSpan;
    }

    @Override
    protected void addLexicalProductions(final int sent[]) throws Exception {
        // ChartEdge newEdge;
        ChartCell cell;
        for (int i = 0; i < chart.size(); i++) {
            cell = chart.getCell(i, i + 1);
            for (final Production lexProd : grammar.getLexicalProductionsWithChild(sent[i])) {
                // newEdge = chart.new ChartEdge(lexProd, chart.getCell(i, i + 1));
                // chart.getCell(i, i + 1).addEdge(newEdge);
                cell.updateInside(lexProd, lexProd.prob);
            }
        }
    }

    public void addUnaryExtensionsToLexProds() {
        for (int i = 0; i < chart.size(); i++) {
            final ChartCell cell = chart.getCell(i, i + 1);
            for (final int pos : cell.getPosNTs()) {
                for (final Production unaryProd : grammar.getUnaryProductionsWithChild(pos)) {
                    // cell.addEdge(unaryProd, cell, null, cell.getBestEdge(pos).inside + unaryProd.prob);
                    cell.updateInside(unaryProd, cell.getInside(pos) + unaryProd.prob);
                }
            }
        }
    }

    // protected void addSpanToFrontier(final ChartCell span) {
    // //System.out.println("AgendaPush: " + edge.spanLength() + " " + edge.inside + " " + edge.fom);
    // if (maxEdgeFOM[span.start()][span.end()] > Float.NEGATIVE_INFINITY) {
    // spanAgenda.remove(span);
    // }
    // spanAgenda.add(span);
    // }

    protected void expandFrontier(final ChartCell cell) {

        // connect edge as possible right non-term
        for (int start = 0; start < cell.start(); start++) {
            setSpanMaxEdgeFOM(chart.getCell(start, cell.start()), cell);
        }

        // connect edge as possible left non-term
        for (int end = cell.end() + 1; end <= chart.size(); end++) {
            setSpanMaxEdgeFOM(cell, chart.getCell(cell.end(), end));
        }
    }

    protected void setSpanMaxEdgeFOM(final ChartCell leftCell, final ChartCell rightCell) {
        ChartEdge edge;
        final int start = leftCell.start(), end = rightCell.end();
        float bestFOM = maxEdgeFOM[start][end];

        // System.out.println(" setSpanMax: " + leftCell + " && " + rightCell);

        Collection<Production> possibleProds;
        for (final int leftNT : leftCell.getLeftChildNTs()) {
            for (final int rightNT : rightCell.getRightChildNTs()) {
                possibleProds = grammar.getBinaryProductionsWithChildren(leftNT, rightNT);
                if (possibleProds != null) {
                    for (final Production p : possibleProds) {
                        // final float prob = p.prob + leftCell.getInside(leftNT) + rightCell.getInside(rightNT);
                        edge = chart.new ChartEdge(p, leftCell, rightCell);
                        // System.out.println(" considering: " + edge);
                        if (edge.fom > bestFOM) {
                            bestFOM = edge.fom;
                        }
                    }
                }
            }
        }

        if (bestFOM > maxEdgeFOM[start][end]) {
            final ChartCell parentCell = chart.getCell(start, end);
            // if (maxEdgeFOM[start][end] > Float.NEGATIVE_INFINITY) {
            // spanAgenda.remove(parentCell);
            // }
            maxEdgeFOM[start][end] = bestFOM;
            parentCell.fom = bestFOM;
            // spanAgenda.add(parentCell);
            // System.out.println(" addingSpan: " + parentCell);
        }
    }
}
