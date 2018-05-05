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
package org.apache.sis.storage;

import java.util.stream.Stream;
import org.apache.sis.util.ArgumentChecks;

// Branch-dependent imports
import org.opengis.feature.Feature;
import org.opengis.feature.FeatureType;


/**
 * A dataset providing access to a stream of features.
 * All features share a common set of properties described by {@link #getType()}.
 * The common set of properties does not need to enumerate all possible properties since additional properties
 * can be defined in subtypes. In many cases at least one property is a geometry, but features without geometry
 * are also allowed.
 *
 * @author  Johann Sorel (Geomatys)
 * @version 1.0
 * @since   0.8
 * @module
 */
public interface FeatureSet extends DataSet {
    /**
     * Returns a description of properties that are common to all features in this dataset.
     * The feature type contains the definition of all properties, including but not only:
     * <ul>
     *   <li>Name to use for accessing the property</li>
     *   <li>Human-readable description</li>
     *   <li>Type of values</li>
     *   <li>Cardinality (minimum and maximum number of occurrences)</li>
     *   <li>{@linkplain org.opengis.referencing.crs.CoordinateReferenceSystem Coordinate Reference System}.</li>
     * </ul>
     *
     * All features returned by {@link #features(boolean)} will be either of that type, or a sub-type of it.
     *
     * <div class="note"><b>Relationship with metadata:</b>
     * if subtypes exist, their list may be obtained from the {@linkplain #getMetadata() metadata} like below
     * (if the {@code FeatureSet} implementation provides that information):
     *
     * {@preformat java
     *     for (ContentInformation content : metadata.getContentInfo()) {
     *         if (content instanceof FeatureCatalogueDescription) {
     *             for (FeatureTypeInfo info : ((FeatureCatalogueDescription) content).getFeatureTypeInfo()) {
     *                 GenericName name = info.getFeatureTypeName();
     *                 // ... add the name to some list ...
     *             }
     *         }
     *     }
     * }
     * </div>
     *
     * @return description of common properties (never {@code null}).
     * @throws DataStoreException if an error occurred while reading definitions from the underlying data store.
     */
    FeatureType getType() throws DataStoreException;

    /**
     * Requests a subset of features and/or feature properties from this resource.
     * The filtering can be applied in two domains:
     *
     * <ul>
     *   <li>The returned {@code FeatureSet} may contain a smaller number of {@link Feature} instances.</li>
     *   <li>In each {@code Feature} instance of the returned set, the number of
     *       {@linkplain org.apache.sis.feature.DefaultFeatureType#getProperty properties} may be smaller.</li>
     * </ul>
     *
     * While it is technically possible to return a <em>transformed</em> feature set (i.e. containing feature
     * properties not found in this original {@code FeatureSet}, for example as a result of some computation),
     * such usages should be rare. Transformations should be the topic of a separated processing package.
     * This {@code subset(Query)} method is rather for allowing {@link DataStore} implementations to optimize
     * the overall filtering by using the tools available with their format (for example an R-tree).
     * {@code BoundingBox} filters are the most common case of optimization implemented by {@link DataStore}.
     *
     * <p>The returned subset may be a <em>view</em> of this set, i.e. changes in this {@code FeatureSet}
     * may be reflected immediately on the returned subset (and conversely), but not necessarily.
     * However the returned subset may not have the same capabilities as this {@link FeatureSet}.
     * In particular, write operations may become unsupported after complex queries.</p>
     *
     * <p>The default implementation throws {@link UnsupportedQueryException}.</p>
     *
     * @param  query  definition of feature and feature properties filtering applied at reading time.
     * @return resulting subset of features (never {@code null}).
     * @throws UnsupportedQueryException if this {@code FeatureSet} can not execute the given query.
     *         This includes query validation errors.
     * @throws DataStoreException if another error occurred while processing the query.
     */
    default FeatureSet subset(Query query) throws UnsupportedQueryException, DataStoreException {
        ArgumentChecks.ensureNonNull("query", query);
        throw new UnsupportedQueryException();
    }

    /**
     * Returns a stream of all features contained in this dataset.
     * For all features, the following condition shall be true:
     *
     * <blockquote><code>{@linkplain #getType()}.{@linkplain org.apache.sis.feature.DefaultFeatureType#isAssignableFrom
     * isAssignableFrom}(feature.{@linkplain org.apache.sis.feature.AbstractFeature#getType() getType()})</code></blockquote>
     *
     * Most implementations will create {@code Feature} instances on-the-fly when the stream terminal operation is executed.
     * A {@code try} … {@code finally} block should be used for releasing {@link DataStore} resources used by the operation.
     * If a checked exception happens during stream execution, that exception will be wrapped in an unchecked
     * {@link org.apache.sis.util.collection.BackingStoreException}.
     * The following code shows how this stream can be used:
     *
     * {@preformat java
     *     void myReadOperation() throws DataStoreException {
     *         try (Stream<Feature> features = myDataStore.features(false)) {
     *             // Use the stream here.
     *         } catch (BackingStoreException e) {
     *             throw e.unwrapOrRethrow(DataStoreException.class);
     *         }
     *     }
     * }
     *
     * The {@code parallel} argument specifies whether a parallelized stream is desired. If {@code false}, the stream
     * is guaranteed to be sequential. If {@code true}, the stream may or may not be parallel; implementations are free
     * to ignore this argument if they do not support parallelism.
     *
     * @param  parallel  {@code true} for a parallel stream (if supported), or {@code false} for a sequential stream.
     * @return all features contained in this dataset.
     * @throws DataStoreException if an error occurred while creating the stream.
     */
    Stream<Feature> features(boolean parallel) throws DataStoreException;
}
