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
package edu.ohsu.cslu.parser.fom;


/**
 * Normalizes inside grammar probability by span length.
 * 
 * @author Nathan Bodenstab
 */
public class NormalizedInsideProb extends InsideProb {

    private static final long serialVersionUID = 1L;

    // @Override
    // public float calcFOM(final ChartEdge edge) {
    // final int spanLength = edge.end() - edge.start();
    // // return edge.inside() + spanLength * ParserDriver.param1;
    // return edge.inside() + spanLength;
    // }

    @Override
    public float calcFOM(final int start, final int end, final short parent, final float insideProbability) {
        return insideProbability;
    }

    @Override
    public float calcLexicalFOM(final int start, final int end, final short parent, final float insideProbability) {
        return insideProbability;
    }

}