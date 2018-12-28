/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sis.referencing.operation.transform;

import java.util.Random;
import java.util.Iterator;
import org.opengis.util.FactoryException;
import org.opengis.geometry.DirectPosition;
import org.opengis.referencing.operation.Matrix;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.MathTransformFactory;
import org.opengis.referencing.operation.TransformException;
import org.apache.sis.internal.system.DefaultFactories;
import org.apache.sis.referencing.datum.HardCodedDatum;
import org.apache.sis.referencing.operation.matrix.Matrix2;
import org.apache.sis.referencing.operation.matrix.Matrix3;
import org.apache.sis.referencing.operation.matrix.Matrix4;
import org.apache.sis.referencing.operation.matrix.Matrices;
import org.apache.sis.geometry.GeneralDirectPosition;
import org.apache.sis.test.TestUtilities;
import org.apache.sis.test.DependsOnMethod;
import org.apache.sis.test.DependsOn;
import org.apache.sis.test.TestCase;
import org.junit.Test;

import static java.lang.Double.NaN;
import static org.opengis.test.Assert.*;


/**
 * Tests {@link TransformSeparator}.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @version 1.0
 * @since   0.7
 * @module
 */
@DependsOn({
    PassThroughTransformTest.class,
    ConcatenatedTransformTest.class
})
public final strictfp class TransformSeparatorTest extends TestCase {
    /**
     * Verifies the argument checks performed by the {@code add} methods.
     */
    @Test
    public void testArgumentChecks() {
        final TransformSeparator s = new TransformSeparator(MathTransforms.identity(8));
        try {
            s.getSourceDimensions();
            fail("Shall not return unspecified dimensions.");
        } catch (IllegalStateException e) {
            // This is the expected exception.
        }
        try {
            s.getTargetDimensions();
            fail("Shall not return unspecified dimensions.");
        } catch (IllegalStateException e) {
            // This is the expected exception.
        }
        s.addSourceDimensionRange(1, 4);
        s.addTargetDimensions(0, 3, 4);
        assertArrayEquals("sourceDimensions", new int[] {1, 2, 3}, s.getSourceDimensions());
        assertArrayEquals("targetDimensions", new int[] {0, 3, 4}, s.getTargetDimensions());
        try {
            s.addSourceDimensions(3, 4, 5);
            fail("Shall not accept non-increasing value.");
        } catch (IllegalArgumentException e) {
            // This is the expected exception.
            assertTrue(e.getMessage().contains("dimensions[0]"));
        }
        try {
            s.addTargetDimensionRange(3, 5);
            fail("Shall not accept non-increasing value.");
        } catch (IllegalArgumentException e) {
            // This is the expected exception.
            assertTrue(e.getMessage().contains("lower"));
        }
        s.addSourceDimensions(4, 6);
        s.addTargetDimensionRange(6, 8);
        assertArrayEquals("sourceDimensions", new int[] {1, 2, 3, 4, 6}, s.getSourceDimensions());
        assertArrayEquals("targetDimensions", new int[] {0, 3, 4, 6, 7}, s.getTargetDimensions());
        try {
            s.addSourceDimensions(8);
            fail("Shall not accept value out of range.");
        } catch (IllegalArgumentException e) {
            // This is the expected exception.
            assertTrue(e.getMessage().contains("dimensions[0]"));
        }
        try {
            s.addTargetDimensions(3, 8);
            fail("Shall not accept value out of range.");
        } catch (IllegalArgumentException e) {
            // This is the expected exception.
            assertTrue(e.getMessage().contains("dimensions[0]"));
        }
    }

    /**
     * Tests separation of a linear transform.
     *
     * @throws FactoryException if an error occurred while creating a new transform.
     */
    @Test
    public void testLinearTransform() throws FactoryException {
        Matrix matrix = new Matrix4(
            2, 0, 0, 7,                                         // Some random values.
            0, 5, 0, 6,
            1, 0, 3, 8,
            0, 0, 0, 1
        );
        final TransformSeparator s = new TransformSeparator(MathTransforms.linear(matrix));
        /*
         * Trivial case: no dimension specified, we should get the transform unchanged.
         */
        assertSame("transform", s.transform, s.separate());
        assertArrayEquals("sourceDimensions", new int[] {0, 1, 2}, s.getSourceDimensions());
        assertArrayEquals("targetDimensions", new int[] {0, 1, 2}, s.getTargetDimensions());
        /*
         * Filter only target dimensions. This is the easiest non-trivial case since we just
         * need to drop some rows. There is no analysis to perform on the matrix values.
         */
        matrix = Matrices.create(3, 4, new double[] {
            2, 0, 0, 7,
            1, 0, 3, 8,
            0, 0, 0, 1
        });
        s.clear();
        s.addTargetDimensions(0, 2);
        assertMatrixEquals("transform", matrix, ((LinearTransform) s.separate()).getMatrix(), STRICT);
        assertArrayEquals("sourceDimensions", new int[] {0, 1, 2}, s.getSourceDimensions());
        assertArrayEquals("targetDimensions", new int[] {0, 2},    s.getTargetDimensions());
        /*
         * Filter only source dimensions. Do not specify any target dimensions for now.
         * TransformSeparator needs to examine the matrix values and drop all target dimensions
         * that depend on an excluded source dimensions.
         */
        matrix = Matrices.create(2, 3, new double[] {
            5, 0, 6,
            0, 0, 1
        });
        s.clear();
        s.addSourceDimensions(1, 2);
        assertMatrixEquals("transform", matrix, ((LinearTransform) s.separate()).getMatrix(), STRICT);
        assertArrayEquals("sourceDimensions", new int[] {1, 2}, s.getSourceDimensions());
        assertArrayEquals("targetDimensions", new int[] {1},    s.getTargetDimensions());
        /*
         * Filter both source and target dimensions. Source dimensions 0 and 2 allow the target dimensions 0 and 2
         * (target dimension 1 is discarded because it depends on source dimension 1).  Then the target dimensions
         * are filtered for retaining only dimension 0.
         */
        matrix = Matrices.create(2, 3, new double[] {
            2, 0, 7,
            0, 0, 1
        });
        s.clear();
        s.addSourceDimensions(0, 2);
        s.addTargetDimensions(0);
        assertMatrixEquals("transform", matrix, ((LinearTransform) s.separate()).getMatrix(), STRICT);
        assertArrayEquals("sourceDimensions", new int[] {0, 2}, s.getSourceDimensions());
        assertArrayEquals("targetDimensions", new int[] {0},    s.getTargetDimensions());
        /*
         * Try again, but with the addition of a target dimension that TransformSeparator can not keep.
         * It shall cause an exception to be thrown.
         */
        s.addTargetDimensions(1);
        try {
            s.separate();
            fail("Should not have been able to separate that transform.");
        } catch (FactoryException e) {
            // This is the expected exception.
            assertNotNull(e.getMessage());
        }
    }

    /**
     * Tests separation of a linear transform containing {@link Double#NaN} values.
     *
     * @throws FactoryException if an error occurred while creating a new transform.
     */
    @Test
    public void testIncompleteTransform() throws FactoryException {
        Matrix matrix = new Matrix4(
            1,   0,   0,   7,
            0,   0,   1,   8,
            0, NaN,   0,   6,
            0,   0,   0,   1
        );
        TransformSeparator s = new TransformSeparator(MathTransforms.linear(matrix));
        s.addSourceDimensions(1);
        assertMatrixEquals("transform", new Matrix2(
               NaN, 6,
                 0, 1
        ), ((LinearTransform) s.separate()).getMatrix(), STRICT);
        assertArrayEquals(new int[] {2}, s.getTargetDimensions());
    }

    /**
     * Tests separation of a concatenated transform.
     *
     * @throws FactoryException if an error occurred while creating a new transform.
     */
    @Test
    @DependsOnMethod("testLinearTransform")
    public void testConcatenatedTransform() throws FactoryException {
        final MathTransformFactory factory = DefaultFactories.forBuildin(MathTransformFactory.class);
        final TransformSeparator s = new TransformSeparator(EllipsoidToCentricTransform.createGeodeticConversion(
                factory, HardCodedDatum.WGS84.getEllipsoid(), false), factory);

        s.addSourceDimensions(0, 1);
        s.addTargetDimensions(0, 1);
        final Iterator<MathTransform> it = MathTransforms.getSteps(s.separate()).iterator();
        assertInstanceOf("normalize",   LinearTransform.class,             it.next());
        assertInstanceOf("transform",   EllipsoidToCentricTransform.class, it.next());
        assertInstanceOf("denormalize", LinearTransform.class,             it.next());
        assertFalse(it.hasNext());
    }

    /**
     * Tests separation of a pass through transform.
     *
     * @throws FactoryException if an error occurred while creating a new transform.
     * @throws TransformException if an error occurred while transforming coordinates for comparison purpose.
     */
    @Test
    @DependsOnMethod("testLinearTransform")
    public void testPassThroughTransform() throws FactoryException, TransformException {
        /*
         * This non-linear transform increase the number of dimensions from 2 to 3.
         * In addition we let 2 dimensions passthrough before and 3 passtrough after.
         */
        final MathTransform nonLinear = new PseudoTransform(2, 3);
        final TransformSeparator s = new TransformSeparator(MathTransforms.passThrough(2, nonLinear, 3));
        /*
         * Trivial case: no dimension specified, we should get the transform unchanged.
         */
        assertSame("transform", s.transform, s.separate());
        assertArrayEquals("sourceDimensions", new int[] {0, 1, 2, 3, 4, 5, 6},    s.getSourceDimensions());
        assertArrayEquals("targetDimensions", new int[] {0, 1, 2, 3, 4, 5, 6, 7}, s.getTargetDimensions());
        /*
         * Filter only target dimensions. If the requested indices overlap the pass-through transform,
         * TransformSeparator will just concatenate a matrix after the transform for dropping dimensions.
         */
        Matrix expected = Matrices.create(4, 9, new double[] {
            0, 1, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 1, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 1, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 1
        });
        s.clear();
        s.addTargetDimensions(1, 2, 7);
        MathTransform result = s.separate();
        assertArrayEquals("sourceDimensions", new int[] {0, 1, 2, 3, 4, 5, 6}, s.getSourceDimensions());
        assertArrayEquals("targetDimensions", new int[] {1, 2, 7}, s.getTargetDimensions());
        final Random random = TestUtilities.createRandomNumberGenerator();
        compare(s.transform, MathTransforms.linear(expected), result, random);
        /*
         * Filter only target dimensions, but with indices that are all outside the pass-through transform.
         * TransformSeparator should be able to give us a simple affine transform.
         */
        expected = Matrices.create(4, 8, new double[] {
            0, 1, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 1, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 1, 0,
            0, 0, 0, 0, 0, 0, 0, 1
        });
        s.clear();
        s.addTargetDimensions(1, 5, 7);
        result = s.separate();
        assertArrayEquals ("sourceDimensions", new int[] {0, 1, 2, 3, 4, 5, 6}, s.getSourceDimensions());
        assertArrayEquals ("targetDimensions", new int[] {1, 5, 7}, s.getTargetDimensions());
        assertInstanceOf  ("separate()", LinearTransform.class, result);
        assertMatrixEquals("separate().transform2", expected, ((LinearTransform) result).getMatrix(), STRICT);
        /*
         * Filter source dimensions. If we ask only for dimensions not in the pass-through transform,
         * then TransformSeparator should return an affine transform.
         */
        expected = new Matrix3(
            1, 0, 0,
            0, 1, 0,
            0, 0, 1
        );
        s.clear();
        s.addSourceDimensions(0, 6);
        result = s.separate();
        assertArrayEquals ("sourceDimensions", new int[] {0, 6}, s.getSourceDimensions());
        assertArrayEquals ("targetDimensions", new int[] {0, 7}, s.getTargetDimensions());
        assertInstanceOf  ("separate()", LinearTransform.class, result);
        assertMatrixEquals("separate().transform2", expected, ((LinearTransform) result).getMatrix(), STRICT);
        /*
         * Filter source dimensions, now with overlapping in the pass-through transform.
         * TransformSeparator is expected to create a new PassThroughTransform.
         */
        s.clear();
        s.addSourceDimensions(1, 2, 3, 4, 5);
        result = s.separate();
        assertArrayEquals("sourceDimensions", new int[] {1, 2, 3, 4, 5}, s.getSourceDimensions());
        assertArrayEquals("targetDimensions", new int[] {1, 2, 3, 4, 5, 6}, s.getTargetDimensions());
        assertInstanceOf ("separate()", PassThroughTransform.class, result);
        assertSame  ("subTransform",  nonLinear, ((PassThroughTransform) result).subTransform);
        assertEquals("firstAffectedOrdinate", 1, ((PassThroughTransform) result).firstAffectedOrdinate);
        assertEquals("numTrailingOrdinates",  2, ((PassThroughTransform) result).numTrailingOrdinates);
    }

    /**
     * Compares coordinate computed by a reference with coordinates computed by the transform to test.
     * We use this method when we can not easily analyze the {@link MathTransform} created by the test
     * case, for example because it may have been rearranged in arbitrary ways for optimization purpose
     * (e.g. {@link PassThroughTransform#tryConcatenate(boolean, MathTransform, MathTransformFactory)}).
     *
     * @param  tr1     first half of the transform to use as a reference.
     * @param  tr2     second half of the transform to use as a reference.
     * @param  test    the transform to test.
     * @param  random  random number generator for coordinate values.
     */
    private static void compare(final MathTransform tr1, final MathTransform tr2, final MathTransform test, final Random random)
            throws TransformException
    {
        DirectPosition source   = new GeneralDirectPosition(tr1.getSourceDimensions());
        DirectPosition step     = null;
        DirectPosition expected = null;
        DirectPosition actual   = null;
        for (int t=0; t<50; t++) {
            for (int i=source.getDimension(); --i>=0;) {
                source.setOrdinate(i, random.nextDouble());
            }
            step     = tr1 .transform(source,   step);
            expected = tr2 .transform(step, expected);
            actual   = test.transform(source, actual);
            assertEquals(expected, actual);
        }
    }

    /**
     * Tests {@link TransformSeparator#getTrimSourceDimensions()}.
     *
     * @throws FactoryException if an error occurred while creating a new transform.
     */
    @Test
    @DependsOnMethod("testLinearTransform")
    public void testGetTrimSourceDimensions() throws FactoryException {
        MathTransform tr = MathTransforms.linear(Matrices.create(3, 4, new double[] {
            0,   0.5, 0,  -90,
            0.5, 0,   0, -180,
            0,   0,   0,    1}));
        /*
         * Verify that TransformSeparator does not trim anything if not requested so.
         */
        TransformSeparator s = new TransformSeparator(tr);
        assertFalse("trimSourceDimensions", s.getTrimSourceDimensions());
        assertSame("No source dimensions should be trimmed if not requested.", tr, s.separate());
        assertArrayEquals(new int[] {0, 1, 2}, s.getSourceDimensions());
        assertArrayEquals(new int[] {0, 1   }, s.getTargetDimensions());
        /*
         * Trim the last dimension (most common case).
         */
        final Matrix expected = new Matrix3(
            0,   0.5, -90,
            0.5, 0,  -180,
            0,   0,     1);
        s.setTrimSourceDimensions(true);
        assertTrue("trimSourceDimensions", s.getTrimSourceDimensions());
        MathTransform reduced = s.separate();
        assertNotEquals("separate()", tr, reduced);
        assertArrayEquals(new int[] {0, 1}, s.getSourceDimensions());
        assertArrayEquals(new int[] {0, 1}, s.getTargetDimensions());
        assertMatrixEquals("separate()", expected, MathTransforms.getMatrix(reduced), STRICT);
        /*
         * Trim the first dimension.
         */
        tr = MathTransforms.linear(Matrices.create(3, 4, new double[] {
            0, 0,   0.5, -90,
            0, 0.5, 0,  -180,
            0, 0,   0,     1}));

        s = new TransformSeparator(tr);
        s.setTrimSourceDimensions(true);
        reduced = s.separate();
        assertNotEquals("separate()", tr, reduced);
        assertArrayEquals(new int[] {1, 2}, s.getSourceDimensions());
        assertArrayEquals(new int[] {0, 1}, s.getTargetDimensions());
        assertMatrixEquals("separate()", new Matrix3(
            0,   0.5, -90,
            0.5, 0,  -180,
            0,   0,     1), MathTransforms.getMatrix(reduced), STRICT);
    }
}
