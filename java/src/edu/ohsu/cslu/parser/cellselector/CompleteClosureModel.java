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

package edu.ohsu.cslu.parser.cellselector;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.logging.Level;

import cltool4j.BaseCommandlineTool;
import cltool4j.BaseLogger;
import edu.ohsu.cslu.grammar.Grammar;
import edu.ohsu.cslu.parser.ChartParser;
import edu.ohsu.cslu.parser.ParseTask;
import edu.ohsu.cslu.perceptron.CompleteClosureClassifier;
import edu.ohsu.cslu.perceptron.CompleteClosureSequence;
import edu.ohsu.cslu.perceptron.ConstituentBoundaryFeatureExtractor;
import edu.ohsu.cslu.perceptron.TagSequence;
import edu.ohsu.cslu.perceptron.Tagger;

/**
 * Implements 'Complete Closure', using a discriminative model to classify each cell as open or closed. The method is
 * fully described in Bodenstab et al., 2011, "Beam-Width Prediction for Efficient Context-Free Parsing". This class is
 * a reimplementation of the original work, and depends on a model trained with {@link CompleteClosureClassifier} and a
 * POS-tagger trained with {@link Tagger}.
 * 
 * TODO Merge this with {@link CompleteClosureClassifier}? (i.e., the same class can descend from
 * {@link BaseCommandlineTool} and implement {@link CellSelectorModel}).
 * 
 * @author Aaron Dunlop
 * @since Feb 14, 2013
 */
public class CompleteClosureModel implements CellSelectorModel {
    private static final long serialVersionUID = 1L;

    private CompleteClosureClassifier classifier;
    private Tagger posTagger;

    public CompleteClosureModel(final InputStream is) throws IOException, ClassNotFoundException {

        final ObjectInputStream ois = new ObjectInputStream(is);
        classifier = (CompleteClosureClassifier) ois.readObject();
        posTagger = (Tagger) ois.readObject();
    }

    public CompleteClosureModel(final File classifierModel, final Grammar grammar) throws IOException,
            ClassNotFoundException {

        this.classifier = new CompleteClosureClassifier(grammar);
        classifier.readModel(new FileInputStream(classifierModel));
        this.posTagger = classifier.posTagger;
    }

    public CellSelector createCellSelector() {
        return new CompleteClosureSelector();
    }

    public class CompleteClosureSelector extends CellSelector {

        @Override
        public void initSentence(final ChartParser<?, ?> p, final ParseTask task) {

            super.initSentence(p, task);
            final short sentenceLength = (short) p.chart.size();

            // POS-tag the sentence with a discriminative tagger
            // TODO Use the same lexicon, tagSet, etc. We already have a mapped int[] representation of the sentence, we
            // shouldn't need to do it again. But this is safe to start with.
            final TagSequence tagSequence = new TagSequence(task.sentence, posTagger);
            task.posTags = posTagger.classify(tagSequence);

            // Classify each chart cell, in left-to-right, bottom-up order
            final ShortArrayList tmpCellIndices = new ShortArrayList(sentenceLength * sentenceLength);

            // All span-1 cells are open
            for (short start = 0; start < sentenceLength; start++) {
                tmpCellIndices.add(start);
                tmpCellIndices.add((short) (start + 1));
            }

            final CompleteClosureSequence sequence = task.inputTree != null ? new CompleteClosureSequence(
                    task.inputTree.binarize(task.grammar.grammarFormat, task.grammar.binarization()), classifier)
                    : new CompleteClosureSequence(task.tokens, task.posTags, classifier);

            boolean sentenceCorrectlyClassified = true;
            for (short span = 2; span <= sentenceLength; span++) {
                for (short start = 0; start < sentenceLength - span + 1; start++) {

                    final boolean closed = classifier.classify(sequence,
                            ConstituentBoundaryFeatureExtractor.cellIndex(start, start + span, sentenceLength, true));

                    // TODO Remove after debugging is complete
                    if (closed
                            && BaseLogger.singleton().isLoggable(Level.FINER)
                            && task.inputTree != null
                            && !sequence.classification(ConstituentBoundaryFeatureExtractor.cellIndex(start, start
                                    + span, sentenceLength, true))) {
                        sentenceCorrectlyClassified = false;
                    }

                    if (!closed) {
                        tmpCellIndices.add(start);
                        tmpCellIndices.add((short) (start + span));
                    }
                }
            }

            if (!sentenceCorrectlyClassified) {
                BaseLogger.singleton().finer(String.format("Misclassified sentence of length %d", sentenceLength));
            }

            // Populate cellIndices with open cells
            this.cellIndices = tmpCellIndices.toShortArray();
            this.openCells = cellIndices.length / 2;

            if (BaseLogger.singleton().isLoggable(Level.FINE)) {
                BaseLogger.singleton().fine(
                        String.format("Sentence length: %d. Total cells: %d  Open cells: %d", sentenceLength,
                                sentenceLength * (sentenceLength + 1) / 2, tmpCellIndices.size() / 2));
            }
        }

        @Override
        public void reset(final boolean enableConstraints) {
            super.reset(enableConstraints);

            if (!enableConstraints) {
                // Replace cellIndices with all chart cells.
                final int sentenceLength = parser.chart.size();
                openCells = sentenceLength * (sentenceLength + 1) / 2;
                if (cellIndices == null || cellIndices.length < openCells * 2) {
                    cellIndices = new short[openCells * 2];
                }

                int i = 0;
                for (short span = 1; span <= sentenceLength; span++) {
                    for (short start = 0; start < sentenceLength - span + 1; start++) { // beginning
                        cellIndices[i++] = start;
                        cellIndices[i++] = (short) (start + span);
                    }
                }
            }
        }

        // @Override
        // public String toString() {
        // final int n = beamWidthValues.length;
        // final StringBuilder cellStats = new StringBuilder(4096);
        // for (int start = 0; start < n; start++) {
        // for (int end = n; end > start; end--) {
        // int x = beamWidthValues[start][end];
        // if (x > 5)
        // x = 5;
        // x = 5 - x;
        // if (onlyFactored[start][end]) {
        // cellStats.append(start + "," + end + "=FACT ");
        // } else if (x > 0) {
        // cellStats.append(start + "," + end + "=" + x + " ");
        // }
        // }
        // }
        // return cellStats.toString();
        // }
    }
}