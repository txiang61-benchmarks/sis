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
package org.apache.sis.internal.feature;

import org.opengis.referencing.crs.CoordinateReferenceSystem;


/**
 * A geometry wrapper with a field for CRS information. This base class is used when the geometry implementation
 * to wrap does not store CRS information by itself. See {@link GeometryWrapper} for more information.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @version 1.1
 *
 * @param  <G>  root class of geometry instances of the underlying library.
 *
 * @since 1.1
 * @module
 */
public abstract class GeometryWithCRS<G> extends GeometryWrapper<G> {
    /**
     * The coordinate reference system, or {@code null} if unspecified.
     */
    private CoordinateReferenceSystem crs;

    /**
     * Creates a new instance initialized with null CRS.
     */
    protected GeometryWithCRS() {
    }

    /**
     * Gets the Coordinate Reference System (CRS) of this geometry.
     *
     * @return the geometry CRS, or {@code null} if unknown.
     */
    @Override
    public final CoordinateReferenceSystem getCoordinateReferenceSystem() {
        return crs;
    }

    /**
     * Sets the coordinate reference system, which is assumed two-dimensional (this method does not verify).
     *
     * @param  crs  the coordinate reference system to set.
     */
    @Override
    public final void setCoordinateReferenceSystem(final CoordinateReferenceSystem crs) {
        this.crs = crs;
    }
}
