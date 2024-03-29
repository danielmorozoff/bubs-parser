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
package edu.ohsu.cslu.datastructs.matrices;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.fail;

import org.cjunit.FilteredRunner;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link FixedPointShortMatrix}
 * 
 * @author Aaron Dunlop
 * @since Oct 4, 2008
 * 
 *        $Id$
 */
@RunWith(FilteredRunner.class)
public class TestFixedPointShortMatrix extends DenseFloatingPointMatrixTestCase {

    private float[][] sampleArray;
    private float[][] sampleArray2;

    private float[][] symmetricArray;
    private float[][] symmetricArray2;

    @Override
    protected Matrix create(final float[][] array, final boolean symmetric) {
        return new FixedPointShortMatrix(array, 2);
    }

    @Override
    protected String matrixType() {
        return "fixed-point-short";
    }

    @Override
    @Before
    public void setUp() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("matrix type=" + matrixType() + " precision=2 rows=3 columns=4 symmetric=false\n");
        sb.append("11.11 22.22 33.33 44.44\n");
        sb.append("55.55 66.66 77.77 88.88\n");
        sb.append("99.99 10.00 11.11 12.11\n");
        stringSampleMatrix = sb.toString();

        sampleArray = new float[][] { { 11.11f, 22.22f, 33.33f, 44.44f }, { 55.55f, 66.66f, 77.77f, 88.88f },
                { 99.99f, 10.00f, 11.11f, 12.11f } };
        sampleMatrix = new FixedPointShortMatrix(sampleArray, 2);

        sb = new StringBuilder();
        sb.append("matrix type=" + matrixType() + " precision=4 rows=3 columns=4 symmetric=false\n");
        sb.append("0.1111 0.2222 0.3333 0.4444\n");
        sb.append("0.5555 0.6666 0.7777 0.8888\n");
        sb.append("0.9999 0.1000 0.1111 0.1222\n");
        stringSampleMatrix2 = sb.toString();

        sampleArray2 = new float[][] { { 0.1111f, 0.2222f, 0.3333f, 0.4444f }, { 0.5555f, 0.6666f, 0.7777f, 0.8888f },
                { 0.9999f, 0.1000f, 0.1111f, 0.1222f } };
        sampleMatrix2 = new FixedPointShortMatrix(sampleArray2, 4);

        sb = new StringBuilder();
        sb.append("matrix type=" + matrixType() + " precision=2 rows=5 columns=5 symmetric=true\n");
        sb.append("0.00\n");
        sb.append("11.11 22.22\n");
        sb.append("33.33 44.44 55.55\n");
        sb.append("66.66 77.77 88.88 99.99\n");
        sb.append("10.00 11.11 12.22 13.33 14.44\n");
        stringSymmetricMatrix = sb.toString();

        symmetricArray = new float[][] { { 0f }, { 11.11f, 22.22f }, { 33.33f, 44.44f, 55.55f },
                { 66.66f, 77.77f, 88.88f, 99.99f }, { 10.00f, 11.11f, 12.22f, 13.33f, 14.44f } };
        symmetricMatrix = new FixedPointShortMatrix(symmetricArray, 2, true);

        sb = new StringBuilder();
        sb.append("matrix type=" + matrixType() + " precision=4 rows=5 columns=5 symmetric=true\n");
        sb.append("0.0000\n");
        sb.append("0.1111 0.2222\n");
        sb.append("0.3333 0.4444 0.5555\n");
        sb.append("0.6666 0.7777 0.8888 0.9999\n");
        sb.append("0.1000 0.1111 0.1222 0.1333 0.1444\n");
        stringSymmetricMatrix2 = sb.toString();

        symmetricArray2 = new float[][] { { 0 }, { 0.1111f, 0.2222f }, { 0.3333f, 0.4444f, 0.5555f },
                { 0.6666f, 0.7777f, 0.8888f, 0.9999f }, { 0.1000f, 0.1111f, 0.1222f, 0.1333f, 0.1444f } };
        symmetricMatrix2 = new FixedPointShortMatrix(symmetricArray2, 4, true);

        matrixClass = FixedPointShortMatrix.class;
    }

    /**
     * Tests constructing a FixedPointShortMatrix - specifically, verifies that out-of-range values will throw
     * {@link IllegalArgumentException}
     * 
     * @throws Exception if something bad happens
     */
    @SuppressWarnings("unused")
    @Test
    public void testConstructors() throws Exception {
        final float[][] floatArray = new float[][] { { 11.11f, 22.22f, 33.33f, 44.44f } };
        // This should work
        new FixedPointShortMatrix(floatArray, 2);

        // But 33.33 and 44.44 are out-of-range for a matrix of precision 4
        try {
            new FixedPointShortMatrix(floatArray, 4);
            fail("Expected IllegalArgumentException");
        } catch (final IllegalArgumentException expected) {
            assertEquals("value out of range", expected.getMessage());
        }

        // And similarly when reading in a serialized matrix
        StringBuilder sb = new StringBuilder();
        sb = new StringBuilder();
        sb.append("matrix type=" + matrixType() + " precision=4 rows=3 columns=4 symmetric=false\n");
        sb.append("11.11  22.22\n");
        sb.append("33.33  44.44\n");
        final String badMatrix = sb.toString();

        try {
            Matrix.Factory.read(badMatrix);
            fail("Expected IllegalArgumentException");
        } catch (final IllegalArgumentException expected) {
            assertEquals("value out of range", expected.getMessage());
        }
    }

    /**
     * Tests setting matrix elements
     * 
     * @throws Exception if something bad happens
     */
    @Override
    @Test
    public void testSet() throws Exception {
        super.testSet();

        // Verify IllegalArgumentException if the value is out of range
        try {
            sampleMatrix2.set(2, 0, 4);
        } catch (final IllegalArgumentException expected) {
            assertEquals("value out of range", expected.getMessage());
        }

        try {
            sampleMatrix2.set(2, 0, 3.5f);
        } catch (final IllegalArgumentException expected) {
            assertEquals("value out of range", expected.getMessage());
        }
    }

    @Override
    public void testInfinity() throws Exception {
        assertEquals(Short.MAX_VALUE / 100f, sampleMatrix.infinity(), .01f);
        assertEquals(Short.MAX_VALUE / 10000f, sampleMatrix2.infinity(), .01f);
    }

    @Override
    public void testNegativeInfinity() throws Exception {
        assertEquals(Short.MIN_VALUE / 100f, sampleMatrix.negativeInfinity(), .01f);
        assertEquals(Short.MIN_VALUE / 10000f, sampleMatrix2.negativeInfinity(), .01f);
    }

    @Test
    @Ignore
    @Override
    public void testAddShortMatrix() {
        // Override to skip adding a short matrix
    }

    /**
     * Tests equals() method
     * 
     * @throws Exception if something bad happens
     */
    @Test
    public void testEquals() throws Exception {
        assertEquals(sampleMatrix, new FixedPointShortMatrix(sampleArray, 2));
        assertEquals(sampleMatrix2, new FixedPointShortMatrix(sampleArray2, 4));
        assertEquals(symmetricMatrix, new FixedPointShortMatrix(symmetricArray, 2, true));
        assertEquals(symmetricMatrix2, new FixedPointShortMatrix(symmetricArray2, 4, true));
    }
}
