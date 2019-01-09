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
package org.apache.sis.internal.feature.jts;

import java.util.Collections;
import org.opengis.util.FactoryException;
import org.opengis.referencing.operation.TransformException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.apache.sis.referencing.CommonCRS;
import org.apache.sis.internal.referencing.j2d.AffineTransform2D;
import org.apache.sis.test.TestCase;
import org.junit.Test;

import static org.junit.Assert.*;


/**
 * Tests {@link JTS} implementation.
 *
 * @author  Johann Sorel (Geomatys)
 * @version 1.0
 * @since   1.0
 * @module
 */
public final strictfp class JTSTest extends TestCase {
    /**
     * Tests {@link JTS#getCoordinateReferenceSystem(Geometry)}.
     *
     * @throws FactoryException if an EPSG code can not be resolved.
     */
    @Test
    public void testGetCoordinateReferenceSystem() throws FactoryException {
        final GeometryFactory gf = new GeometryFactory();
        final Geometry geometry = gf.createPoint(new Coordinate(5, 6));

        CoordinateReferenceSystem crs = JTS.getCoordinateReferenceSystem(geometry);
        assertNull(crs);

        // Test CRS as user data.
        geometry.setUserData(CommonCRS.ED50.geographic());
        assertEquals(CommonCRS.ED50.geographic(), JTS.getCoordinateReferenceSystem(geometry));

        // Test CRS as map value.
        geometry.setUserData(Collections.singletonMap(JTS.CRS_KEY, CommonCRS.NAD83.geographic()));
        assertEquals(CommonCRS.NAD83.geographic(), JTS.getCoordinateReferenceSystem(geometry));

        // Test CRS as srid.
        geometry.setUserData(null);
        geometry.setSRID(4326);
        assertEquals(CommonCRS.WGS84.geographic(), JTS.getCoordinateReferenceSystem(geometry));
    }

    /**
     * Tests {@link JTS#transform(org.locationtech.jts.geom.Geometry, org.opengis.referencing.crs.CoordinateReferenceSystem) }.
     * Tests {@link JTS#transform(org.locationtech.jts.geom.Geometry, org.opengis.referencing.operation.CoordinateOperation) }.
     * Tests {@link JTS#transform(org.locationtech.jts.geom.Geometry, org.opengis.referencing.operation.MathTransform) }.
     */
    @Test
    public void testTransform() throws FactoryException, TransformException {
        final GeometryFactory gf = new GeometryFactory();
        final Geometry in = gf.createPoint(new Coordinate(5, 6));

        // test exception when transforming geometry without CRS.
        try {
            JTS.transform(in, CommonCRS.WGS84.geographic());
            fail("Geometry has no CRS, transform should have failed");
        } catch (TransformException ex) {
            //ok
        }

        // test axes inversion transform
        in.setUserData(CommonCRS.WGS84.normalizedGeographic());
        Geometry out = JTS.transform(in, CommonCRS.WGS84.geographic());
        assertTrue(out instanceof Point);
        assertEquals(6.0, ((Point) out).getX(), 0.0);
        assertEquals(5.0, ((Point) out).getY(), 0.0);
        assertEquals(CommonCRS.WGS84.geographic(), out.getUserData());

        // test affine transform, user data must be preserved
        final AffineTransform2D trs = new AffineTransform2D(1,0,0,1,10,20);
        out = JTS.transform(in, trs);
        assertTrue(out instanceof Point);
        assertEquals(15.0, ((Point) out).getX(), 0.0);
        assertEquals(26.0, ((Point) out).getY(), 0.0);
    }
}