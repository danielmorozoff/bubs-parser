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
package edu.ohsu.cslu.util;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Util test suite.
 * 
 * @author Aaron Dunlop
 * @since Sep 25, 2008
 * 
 *        $Id$
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({ TestMath.class, TestStrings.class, TestMutableEnumeration.class, TestEvalb.class,
        TestPorterStemmer.class, TestIEEEDoubleScaling.class, TestArff.class, TestArrays.class })
public class AllUtilTests {
}
