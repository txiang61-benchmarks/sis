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
package org.apache.sis.internal.filter.sqlmm;

import java.util.Objects;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import org.opengis.feature.FeatureType;
import org.opengis.filter.expression.Expression;
import org.opengis.filter.expression.Literal;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;
import org.opengis.util.FactoryException;
import org.apache.sis.feature.builder.PropertyTypeBuilder;
import org.apache.sis.feature.builder.FeatureTypeBuilder;
import org.apache.sis.internal.filter.NamedFunction;
import org.apache.sis.internal.feature.FeatureExpression;
import org.apache.sis.internal.feature.GeometryWrapper;
import org.apache.sis.internal.feature.Geometries;
import org.apache.sis.internal.util.Constants;
import org.apache.sis.referencing.CRS;
import org.apache.sis.referencing.factory.InvalidGeodeticParameterException;
import org.apache.sis.util.collection.BackingStoreException;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.util.resources.Errors;


/**
 * SQL/MM, ISO/IEC 13249-3:2011, ST_Transform. <br>
 * <p>
 * Return an ST_Geometry value transformed to the specified spatial reference system, considering z and m
 * coordinate values in the calculations and including them in the resultant geometry.
 * </p>
 *
 * <p>
 * An expression which transforms a geometry from one CRS to another CRS.
 * This expression expects two arguments:
 * </p>
 *
 * <ol class="verbose">
 *   <li>An expression returning a geometry object. The evaluated value shall be an instance of
 *       one of the implementations enumerated in {@link org.apache.sis.setup.GeometryLibrary}.</li>
 *   <li>An expression returning the target CRS. This is typically a {@link Literal}, i.e. a constant for all geometries,
 *       but this implementation allows the expression to return different CRS for different geometries
 *       for example depending on the number of dimensions. This CRS can be specified in different ways:
 *     <ul>
 *       <li>As a {@link CoordinateReferenceSystem} instance.</li>
 *       <li>As a {@link String} instance of the form {@code "EPSG:xxxx"}, a URL or a URN.</li>
 *       <li>As an {@link Integer} instance specifying an EPSG code.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * @author  Johann Sorel (Geomatys)
 * @author  Martin Desruisseaux (Geomatys)
 * @version 1.1
 * @since   1.1
 * @module
 */
final class ST_Transform extends NamedFunction implements FeatureExpression {
    /**
     * For cross-version compatibility.
     */
    private static final long serialVersionUID = -5769818355081378907L;

    /**
     * Name of this function as defined by SQL/MM standard.
     */
    static final String NAME = "ST_Transform";

    /**
     * Identifier of the coordinate reference system in which to transform the geometry.
     * This identifier is specified by the second expression and is stored in order to
     * avoid computing {@link #targetCRS} many times when the SRID does not change.
     */
    private transient Object srid;

    /**
     * The coordinate reference system in which to transform the geometry, or {@code null}
     * if not yet determined. This field is recomputed when the {@link #srid} change.
     */
    private transient CoordinateReferenceSystem targetCRS;

    /**
     * Whether the {@link #targetCRS} is defined by a literal.
     * If {@code true}, then {@link #targetCRS} shall be effectively final.
     */
    private final boolean literalCRS;

    /**
     * Creates a new function with the given parameters. It is caller's responsibility to ensure
     * that the given array is non-null, has been cloned and does not contain null elements.
     *
     * @throws IllegalArgumentException if the number of arguments is not equal to 2.
     * @throws FactoryException if CRS can not be constructed from the second expression.
     */
    ST_Transform(final Expression... parameters) throws FactoryException {
        super(parameters);
        ArgumentChecks.ensureExpectedCount("parameters", 2, parameters.length);
        final Expression crs = parameters[1];
        literalCRS = (crs instanceof Literal);
        if (literalCRS) {
            setTargetCRS(((Literal) crs).getValue());
        }
    }

    /**
     * Returns the name of this function, which is {@value #NAME}.
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Invoked on deserialization for restoring the {@link #targetCRS} field.
     *
     * @param  in  the input stream from which to deserialize an attribute.
     * @throws IOException if an I/O error occurred while reading or if the stream contains invalid data.
     * @throws ClassNotFoundException if the class serialized on the stream is not on the classpath.
     */
    private void readObject(final ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (literalCRS) try {
            setTargetCRS(((Literal) parameters.get(1)).getValue());
        } catch (FactoryException e) {
            throw (IOException) new InvalidObjectException(e.getLocalizedMessage()).initCause(e);
        }
    }

    /**
     * Sets {@link #targetCRS} to a coordinate reference system inferred from the given value.
     * The CRS argument shall be the result of {@code parameters.get(1).evaluate(object)}.
     *
     * @throws FactoryException if no CRS can be created from the given object.
     */
    private void setTargetCRS(final Object crs) throws FactoryException {
        if (crs instanceof CoordinateReferenceSystem) {
            targetCRS = (CoordinateReferenceSystem) crs;
        } else {
            final String code;
            if (crs instanceof String) {
                code = (String) crs;
            } else if (crs instanceof Integer) {
                code = Constants.EPSG + ':' + crs;
            } else {
                throw new InvalidGeodeticParameterException(crs == null
                        ? Errors.format(Errors.Keys.UnspecifiedCRS)
                        : Errors.format(Errors.Keys.IllegalCRSType_1, crs.getClass()));
            }
            targetCRS = CRS.forCode(code);
        }
        srid = crs;
    }

    /**
     * Evaluates the first expression as a geometry object, transforms that geometry to the CRS given
     * by the second expression and returns the result.
     *
     * @param  value  the object from which to get a geometry.
     * @return the transformed geometry, or {@code null} if the given object is not an instance of
     *         a supported geometry library (JTS, ERSI, Java2D…).
     */
    @Override
    public Object evaluate(final Object value) {
        final Object geometry = parameters.get(0).evaluate(value);
        final GeometryWrapper<?> wrapper = Geometries.wrap(geometry).orElse(null);
        if (wrapper != null) try {
            final CoordinateReferenceSystem targetCRS;
            if (literalCRS) {
                targetCRS = this.targetCRS;             // No need to synchronize because effectively final.
            } else {
                final Object crs = parameters.get(1).evaluate(value);
                synchronized (this) {
                    if (!Objects.equals(crs, srid)) {
                        setTargetCRS(crs);
                    }
                    targetCRS = this.targetCRS;         // Must be inside synchronized block.
                }
            }
            final GeometryWrapper<?> result = wrapper.transform(targetCRS);
            return (geometry == wrapper) ? result : result.implementation();
        } catch (BackingStoreException e) {
            final Throwable cause = e.getCause();
            warning((cause instanceof Exception) ? (Exception) cause : e);
        } catch (UnsupportedOperationException | FactoryException | TransformException e) {
            warning(e);
        }
        return null;
    }

    /**
     * Provides the type of values produced by this expression when a feature of the given type is evaluated.
     *
     * @param  valueType  the type of features on which to apply this expression.
     * @param  addTo      where to add the type of properties evaluated by this expression.
     * @return builder of type resulting from expression evaluation (never null).
     * @throws IllegalArgumentException if the given feature type does not contain the expected properties.
     */
    @Override
    public PropertyTypeBuilder expectedType(final FeatureType valueType, final FeatureTypeBuilder addTo) {
        return copyGeometryType(valueType, addTo).setCRS(literalCRS ? targetCRS : null);
    }
}
