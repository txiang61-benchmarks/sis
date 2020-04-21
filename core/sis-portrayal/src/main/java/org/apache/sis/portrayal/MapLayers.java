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
package org.apache.sis.portrayal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.opengis.geometry.Envelope;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.apache.sis.geometry.ImmutableEnvelope;
import org.apache.sis.storage.DataSet;


/**
 * A group of layers to display together.
 * {@code MapLayers} can be used for grouping related layers under a same node.
 * This allows global actions, like {@linkplain #setVisible(boolean) hiding} background layers in one call.
 * A {@code MapLayers} can also contain nested {@code MapLayers}, thus forming a tree.
 * Since {@link MapLayer} and {@code MapLayers} are the only {@link MapItem} subclasses,
 * all leaves in this tree can only be {@link MapLayer} instances (assuming no {@code MapLayers} is empty).
 *
 * <p>A {@code MapLayers} is the root node of the tree of all layers to draw on the map,
 * unless there is only one layer to draw.
 * The {@link MapItem} children are listed by {@link #getComponents()} in <var>z</var> order.
 * In addition, {@code MapLayers} may define an {@linkplain #getAreaOfInterest() area of interest}
 * which should be zoomed by default when the map is rendered.</p>
 *
 * @author  Johann Sorel (Geomatys)
 * @version 1.1
 * @since   1.1
 * @module
 */
public class MapLayers extends MapItem {
    /**
     * The {@value} property name, used for notifications about changes in area of interest.
     * Associated values are instances of {@link Envelope}.
     *
     * @see #getAreaOfInterest()
     * @see #setAreaOfInterest(Envelope)
     */
    public static final String AREA_OF_INTEREST_PROPERTY = "areaOfInterest";

    /**
     * The components in this group, or an empty list if none.
     *
     * @todo Should be an observable list with event sent when an element is added/removed/modified.
     */
    private final List<MapItem> components;

    /**
     * The area of interest, or {@code null} is unspecified.
     */
    private ImmutableEnvelope areaOfInterest;

    /**
     * Creates an initially empty group of layers.
     */
    public MapLayers() {
        components = new ArrayList<>();
    }

    /**
     * Gets the modifiable list of children contained in this group.
     * The elements in the list are sorted in rendering order.
     * This means that the first rendered element, which will be below
     * all other elements on the rendered map, is located at index zero.
     *
     * <p>The returned list is modifiable: changes in the returned list will
     * be immediately reflected in this {@code MapLayers}, and conversely.</p>
     *
     * @return modifiable list of children in this group of layers.
     */
    @SuppressWarnings("ReturnOfCollectionOrArrayField")
    public List<MapItem> getComponents() {
        return components;
    }

    /**
     * Returns the map area to show by default.
     * This is not necessarily the {@linkplain DataSet#getEnvelope() envelope of data}
     * since one may want to zoom in a different spatiotemporal area.
     *
     * <p>The {@linkplain org.apache.sis.geometry.GeneralEnvelope#getCoordinateReferenceSystem() envelope CRS}
     * provides the reference system to use by default for rendering the map. It may be different than the CRS
     * of data. The returned envelope may have {@linkplain org.apache.sis.geometry.GeneralEnvelope#isAllNaN()
     * all its coordinates set to NaN} if only the {@link CoordinateReferenceSystem} is specified.</p>
     *
     * @return map area to show by default, or {@code null} is unspecified.
     *
     * @see DataSet#getEnvelope()
     */
    public Envelope getAreaOfInterest() {
        return areaOfInterest;
    }

    /**
     * Sets the map area to show by default.
     * The given envelope is not necessarily related to the data contained in this group.
     * It may be wider, or smaller, and in a different {@link CoordinateReferenceSystem}.
     *
     * @param  newValue  new map area to show by default, or {@code null} is unspecified.
     */
    public void setAreaOfInterest(final Envelope newValue) {
        final ImmutableEnvelope imenv = ImmutableEnvelope.castOrCopy(newValue);
        final Envelope oldValue = areaOfInterest;
        if (!Objects.equals(oldValue, imenv)) {
            areaOfInterest = imenv;
            firePropertyChange(AREA_OF_INTEREST_PROPERTY, oldValue, imenv);
        }
    }
}