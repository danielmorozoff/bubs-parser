/*
 * Copyright 2010-2014, Oregon Health & Science University
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
 * along with the BUBS Parser. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Further documentation and contact information is available at
 *   https://code.google.com/p/bubs-parser/ 
 */
package edu.ohsu.cslu.parser.fom;

/**
 * Simple edge ranking by inside grammar probability.
 * 
 * @author Nathan Bodenstab
 */

public final class InsideProb extends FigureOfMeritModel {

    public final static FigureOfMerit INSTANCE = new InsideProb().new InsideProbFom();

    public InsideProb() {
        super(FOMType.Inside);
    }

    @Override
    public FigureOfMerit createFOM() {
        return INSTANCE;
    }

    public class InsideProbFom extends FigureOfMerit {

        private static final long serialVersionUID = 1L;

        @Override
        public float calcFOM(final int start, final int end, final short parent, final float insideProbability) {
            return normInside(start, end, insideProbability);
        }

        @Override
        public float calcLexicalFOM(final int start, final int end, final short parent, final float insideProbability) {
            return insideProbability;
        }
    }
}
