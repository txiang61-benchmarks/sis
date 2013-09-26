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
package org.apache.sis.internal.util;

import java.util.Random;
import java.math.BigDecimal;
import java.math.MathContext;
import java.lang.reflect.Field;
import org.apache.sis.util.ArraysExt;
import org.apache.sis.test.TestCase;
import org.apache.sis.test.TestUtilities;
import org.apache.sis.test.DependsOnMethod;
import org.apache.sis.test.DependsOn;
import org.junit.Test;

import static org.junit.Assert.*;
import static java.lang.StrictMath.*;


/**
 * Tests {@link DoubleDouble} using {@link BigDecimal} as the references.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.4
 * @version 0.4
 * @module
 */
@DependsOn(org.apache.sis.math.MathFunctionsTest.class)
public final strictfp class DoubleDoubleTest extends TestCase {
    /**
     * Number of iterations in each test method.
     */
    private static final int NUM_ITERATIONS = 1000;

    /**
     * The tolerance factor (as a multiplicand) for the addition and subtraction operations.
     */
    private static final double ADD_TOLERANCE_FACTOR = 1;

    /**
     * The tolerance factor (as a multiplicand) for the multiplication and division operations.
     * This is a tolerance factor in units of {@link DoubleDouble#error} ULP, so even a "scary"
     * factor like 1E+4 should be very small compared to the {@link DoubleDouble#value}.
     */
    private static final double PRODUCT_TOLERANCE_FACTOR = 10000;

    /**
     * The random number generator to use for the test.
     */
    private final Random random = TestUtilities.createRandomNumberGenerator();

    /**
     * Returns the next {@code double} random value. The scale factor is a power of two
     * in order to change only the exponent part of the IEEE representation.
     */
    private double nextRandom() {
        return random.nextDouble() * 2048 - 1024;
    }

    /**
     * Fetches the next {@code DoubleDouble} random values and store them in the given object.
     */
    private void nextRandom(final DoubleDouble dd) {
        dd.setToSum(nextRandom(), nextRandom());
    }

    /**
     * Returns a {@code BigDecimal} representation of the given {@code DoubleDouble} value.
     */
    private static BigDecimal toBigDecimal(final DoubleDouble dd) {
        return new BigDecimal(dd.value).add(new BigDecimal(dd.error));
    }

    /**
     * Asserts that the given {@code DoubleDouble} is normalized and has a value equals to the expected one.
     * More specifically:
     *
     * <ul>
     *   <li>the {@link DoubleDouble#value} is strictly equals to the expected value, and</li>
     *   <li>the {@link DoubleDouble#error} is not greater than 1 ULP of the above value.</li>
     * </ul>
     */
    private static void assertNormalizedAndEquals(final double expected, final DoubleDouble actual) {
        assertTrue("DoubleDouble is not normalized.", abs(actual.error) <= ulp(actual.value));
        assertEquals("Unexpected arithmetic result.", expected, actual.value, 0.0);
    }

    /**
     * Asserts that the result of some operation is equals to the expected value,
     * up to a tolerance value determined by the extended arithmetic precision.
     *
     * @param expected The expected value, computed using {@code BigInteger} arithmetic.
     * @param actual The actual value.
     * @param ef Multiplication factor for the tolerance threshold.
     */
    private static void assertExtendedEquals(final BigDecimal expected, final DoubleDouble actual, final double ef) {
        final BigDecimal value = toBigDecimal(actual);
        final double delta = abs(expected.subtract(value).doubleValue());
        final double threshold = ulp(actual.error) * ef;
        if (!(delta <= threshold)) { // Use ! for catching NaN values.
            fail("Arithmetic error:\n" +
                 "  Expected:   " + expected  + '\n' +
                 "  Actual:     " + value     + '\n' +
                 "  Difference: " + delta     + '\n' +
                 "  Threshold:  " + threshold + '\n' +
                 "  Value ULP:  " + ulp(actual.value) + '\n');
        }
    }

    /**
     * Tests {@link DoubleDouble#normalize()}.
     */
    @Test
    @DependsOnMethod("testSetToQuickSum")
    public void testNormalize() {
        final DoubleDouble dd = new DoubleDouble();
        for (int i=0; i<NUM_ITERATIONS; i++) {
            double a = nextRandom();
            double b = nextRandom();
            if (abs(a) < abs(b)) {
                final double t = a;
                a = b;
                b = t;
            }
            dd.value = a;
            dd.error = b;
            dd.normalize();
            assertNormalizedAndEquals(a + b, dd);
            assertExtendedEquals(new BigDecimal(a).add(new BigDecimal(b)), dd, ADD_TOLERANCE_FACTOR);
        }
    }

    /**
     * Tests {@link DoubleDouble#setToQuickSum(double, double)}.
     */
    @Test
    public void testSetToQuickSum() {
        final DoubleDouble dd = new DoubleDouble();
        for (int i=0; i<NUM_ITERATIONS; i++) {
            double a = nextRandom();
            double b = nextRandom();
            if (abs(a) < abs(b)) {
                final double t = a;
                a = b;
                b = t;
            }
            dd.setToQuickSum(a, b);
            assertNormalizedAndEquals(a + b, dd);
            assertExtendedEquals(new BigDecimal(a).add(new BigDecimal(b)), dd, ADD_TOLERANCE_FACTOR);
        }
    }

    /**
     * Tests {@link DoubleDouble#setToSum(double, double)}.
     */
    @Test
    public void testSetToSum() {
        final DoubleDouble dd = new DoubleDouble();
        for (int i=0; i<NUM_ITERATIONS; i++) {
            final double a = nextRandom();
            final double b = nextRandom();
            dd.setToSum(a, b);
            assertNormalizedAndEquals(a + b, dd);
            assertExtendedEquals(new BigDecimal(a).add(new BigDecimal(b)), dd, ADD_TOLERANCE_FACTOR);
        }
    }

    /**
     * Tests {@link DoubleDouble#setToProduct(double, double)}.
     */
    @Test
    public void testSetToProduct() {
        final DoubleDouble dd = new DoubleDouble();
        for (int i=0; i<NUM_ITERATIONS; i++) {
            final double a = nextRandom();
            final double b = nextRandom();
            dd.setToProduct(a, b);
            assertNormalizedAndEquals(a * b, dd);
            assertExtendedEquals(new BigDecimal(a).multiply(new BigDecimal(b)), dd, ADD_TOLERANCE_FACTOR);
        }
    }

    /**
     * Tests {@link DoubleDouble#add(DoubleDouble)}.
     */
    @Test
    @DependsOnMethod({"testSetToSum", "testNormalize"})
    public void testAdd() {
        final DoubleDouble dd = new DoubleDouble();
        final DoubleDouble op = new DoubleDouble();
        for (int i=0; i<NUM_ITERATIONS; i++) {
            nextRandom(dd);
            nextRandom(op);
            final BigDecimal expected = toBigDecimal(dd).add(toBigDecimal(op));
            dd.add(op); // Must be after 'expected' computation.
            assertExtendedEquals(expected, dd, ADD_TOLERANCE_FACTOR);
        }
    }

    /**
     * Tests {@link DoubleDouble#multiply(DoubleDouble)}.
     */
    @Test
    @DependsOnMethod({"testSetToProduct", "testNormalize"})
    public void testMultiply() {
        final DoubleDouble dd = new DoubleDouble();
        final DoubleDouble op = new DoubleDouble();
        for (int i=0; i<NUM_ITERATIONS; i++) {
            nextRandom(dd);
            nextRandom(op);
            final BigDecimal expected = toBigDecimal(dd).multiply(toBigDecimal(op), MathContext.DECIMAL128);
            dd.multiply(op); // Must be after 'expected' computation.
            assertExtendedEquals(expected, dd, PRODUCT_TOLERANCE_FACTOR);
        }
    }

    /**
     * Tests {@link DoubleDouble#divide(DoubleDouble)}.
     */
    @Test
    @DependsOnMethod({"testMultiply", "testSetToSum", "testSetToQuickSum"})
    public void testDivide() {
        final DoubleDouble dd = new DoubleDouble();
        final DoubleDouble op = new DoubleDouble();
        for (int i=0; i<NUM_ITERATIONS; i++) {
            nextRandom(dd);
            nextRandom(op);
            final BigDecimal expected = toBigDecimal(dd).divide(toBigDecimal(op), MathContext.DECIMAL128);
            dd.divide(op); // Must be after 'expected' computation.
            assertExtendedEquals(expected, dd, PRODUCT_TOLERANCE_FACTOR);
        }
    }

    /**
     * List of all {@link DoubleDouble#VALUES} as string decimal representation.
     */
    private static final String[] PREDEFINED_VALUES = {
        "0.000001",
        "0.00001",
        "0.0001",
        "0.001",
        "0.01",
        "0.1",
        "0.3048",
        "0.9"
    };

    /**
     * Ensures that the {@link DoubleDouble#VALUES} elements are sorted in strictly increasing order.
     * Also ensures that {@link DoubleDouble#ERRORS} has the same number of elements.
     *
     * @throws ReflectiveOperationException Should never happen.
     */
    @Test
    public void testValuesSorted() throws ReflectiveOperationException {
        Field field = DoubleDouble.class.getDeclaredField("VALUES");
        field.setAccessible(true);
        double[] array = (double[]) field.get(null);
        assertTrue(ArraysExt.isSorted(array, true));
        assertEquals(PREDEFINED_VALUES.length, array.length);

        field = DoubleDouble.class.getDeclaredField("ERRORS");
        field.setAccessible(true);
        array = (double[]) field.get(null);
        assertEquals(PREDEFINED_VALUES.length, array.length);
    }

    /**
     * Tests {@link DoubleDouble#errorForWellKnownValue(double)}.
     */
    @Test
    @DependsOnMethod("testValuesSorted")
    public void testErrorForWellKnownValue() {
        for (final String text : PREDEFINED_VALUES) {
            final double     value         = Double.valueOf(text);
            final BigDecimal accurate      = new BigDecimal(text);
            final BigDecimal approximation = new BigDecimal(value);
            final double     expected      = accurate.subtract(approximation).doubleValue();
            assertEquals(text,  expected, DoubleDouble.errorForWellKnownValue( value), 0);
            assertEquals(text, -expected, DoubleDouble.errorForWellKnownValue(-value), 0);
            assertFalse("There is no point to define an entry for values having no error.", expected == 0);
        }
    }
}
