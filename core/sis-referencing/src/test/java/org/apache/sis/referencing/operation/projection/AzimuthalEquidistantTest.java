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
package org.apache.sis.referencing.operation.projection;

import org.opengis.referencing.operation.TransformException;
import org.apache.sis.internal.referencing.provider.MapProjection;
import org.apache.sis.test.DependsOn;
import org.junit.Test;
import org.opengis.util.FactoryException;


/**
 * Tests the {@link AzimuthalEquidistant} class.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @version 1.1
 * @since   1.1
 * @module
 */
@DependsOn(NormalizedProjectionTest.class)
public strictfp class AzimuthalEquidistantTest extends MapProjectionTestCase {
    /**
     * Returns the method to be tested.
     */
    MapProjection method() {
        return new org.apache.sis.internal.referencing.provider.AzimuthalEquidistantSpherical();
    }

    /**
     * Tests the forward projection using test point given in Snyder page 337.
     * The Snyder's test uses a sphere of radius R=3 and a center at 40°N and 100°W.
     * The test in this class modify the longitude to 10°W for avoiding to mix wraparound
     * considerations in this test.
     *
     * @throws FactoryException if an error occurred while creating the projection.
     * @throws TransformException if an error occurred while projecting the test point.
     */
    @Test
    public void testSpherical() throws FactoryException, TransformException {
        createCompleteProjection(method(),
                  3,            // Semi-major axis
                  3,            // Semi-minor axis
                -10,            // Longitude of natural origin (central-meridian)
                 40,            // Latitude of natural origin
                Double.NaN,     // Standard parallel 1
                Double.NaN,     // Standard parallel 2
                Double.NaN,     // Scale factor
                  0,            // False easting
                  0);           // False Northing

        tolerance = 2E-7;
        verifyTransform(new double[] {
            -170,               // Was 1OO°E in Snyder test, shifted by 90° in our test.
             -20                // 20°S
        }, new double[] {
            -5.8311398,
             5.5444634
        });
    }

    /**
     * Tests the point published in EPSG guidance note.
     *
     * @throws FactoryException if an error occurred while creating the projection.
     * @throws TransformException if an error occurred while projecting the test point.
     */
    @Test
    public void testEPSGPoint() throws FactoryException, TransformException {
        createCompleteProjection(method(),
                CLARKE_A,                       // Semi-major axis (Clarke 1866)
                CLARKE_B,                       // Semi-minor axis
                138 + (10 +  7.48/60)/60,       // Longitude of natural origin (central-meridian)
                  9 + (32 + 48.15/60)/60,       // Latitude of natural origin
                Double.NaN,                     // Standard parallel 1
                Double.NaN,                     // Standard parallel 2
                Double.NaN,                     // Scale factor
                40000,                          // False easting
                60000);                         // False Northing
        /*
         * Since we are testing spherical formulas with a sample point calculated
         * for ellipsoidal formulas, we have to use a high tolerance threshold.
         */
        tolerance = 20;
        verifyTransform(new double[] {
            138 + (11 + 34.908/60)/60,          // 138°11'34.908"E
              9 + (35 + 47.493/60)/60           //   9°35'47.493"N
        }, new double[] {
            42665.90,
            65509.82
        });
    }
}
