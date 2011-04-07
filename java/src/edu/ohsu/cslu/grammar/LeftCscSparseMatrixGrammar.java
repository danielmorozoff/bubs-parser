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
package edu.ohsu.cslu.grammar;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;

/**
 * Stores a sparse-matrix grammar in compressed-sparse-column (CSC) format
 * 
 * Assumes fewer than 2^30 total non-terminals combinations (see {@link SparseMatrixGrammar} documentation for details).
 * 
 * @author Aaron Dunlop
 * @since Jan 24, 2010
 * 
 * @version $Revision$ $Date$ $Author$
 */
public class LeftCscSparseMatrixGrammar extends CscSparseMatrixGrammar {

    /**
     * Indices in {@link #cscBinaryPopulatedColumns} of initial columns for each non-terminal. Indexed by left
     * non-terminal. Length is 1 greater than V, to simplify loops.
     */
    public final int[] cscBinaryLeftChildStartIndices;

    /**
     * Indices in {@link #cscBinaryPopulatedColumns} of final columns for each non-terminal. Indexed by left
     * non-terminal. Length is 1 greater than V, to simplify loops.
     */
    public final int[] cscBinaryLeftChildEndIndices;

    public LeftCscSparseMatrixGrammar(final Reader grammarFile,
            final Class<? extends PackingFunction> cartesianProductFunctionClass) throws IOException {
        super(grammarFile, cartesianProductFunctionClass);

        this.cscBinaryLeftChildStartIndices = new int[numNonTerms() + 1];
        this.cscBinaryLeftChildEndIndices = new int[numNonTerms() + 1];
        init();
    }

    public LeftCscSparseMatrixGrammar(final Reader grammarFile) throws IOException {
        this(grammarFile, null);
    }

    public LeftCscSparseMatrixGrammar(final String grammarFile) throws IOException {
        this(new FileReader(grammarFile));
    }

    public LeftCscSparseMatrixGrammar(final Grammar g, final Class<? extends PackingFunction> functionClass) {
        super(g, functionClass);

        // Initialization code duplicated from constructor above to allow these fields to be final
        this.cscBinaryLeftChildStartIndices = new int[numNonTerms() + 1];
        this.cscBinaryLeftChildEndIndices = new int[numNonTerms() + 1];
        init();
    }

    public LeftCscSparseMatrixGrammar(final Grammar g) {
        this(g, PerfectIntPairHashPackingFunction.class);
    }

    private void init() {
        Arrays.fill(cscBinaryLeftChildStartIndices, -1);
        Arrays.fill(cscBinaryLeftChildEndIndices, -1);

        for (int i = 0; i < cscBinaryPopulatedColumns.length; i++) {
            final int leftChild = packingFunction.unpackLeftChild(cscBinaryPopulatedColumns[i]);
            if (cscBinaryLeftChildStartIndices[leftChild] < 0) {
                cscBinaryLeftChildStartIndices[leftChild] = i;
            }
            cscBinaryLeftChildEndIndices[leftChild] = i;
        }
    }

}
