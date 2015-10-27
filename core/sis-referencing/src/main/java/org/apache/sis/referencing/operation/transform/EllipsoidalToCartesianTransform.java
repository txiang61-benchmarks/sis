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

import java.util.Arrays;
import java.io.Serializable;
import javax.measure.unit.Unit;
import javax.measure.quantity.Length;
import org.opengis.util.FactoryException;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.parameter.ParameterDescriptorGroup;
import org.opengis.referencing.datum.Ellipsoid;
import org.opengis.referencing.operation.Matrix;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.MathTransformFactory;
import org.apache.sis.util.Debug;
import org.apache.sis.util.ComparisonMode;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.internal.util.Numerics;
import org.apache.sis.internal.util.DoubleDouble;
import org.apache.sis.internal.system.DefaultFactories;
import org.apache.sis.internal.referencing.provider.GeographicToGeocentric;
import org.apache.sis.referencing.operation.matrix.NoninvertibleMatrixException;
import org.apache.sis.referencing.operation.matrix.Matrix3;
import org.apache.sis.referencing.operation.matrix.Matrices;
import org.apache.sis.referencing.operation.matrix.MatrixSIS;
import org.apache.sis.metadata.iso.citation.Citations;
import org.apache.sis.parameter.ParameterBuilder;

import static java.lang.Math.*;
import static org.apache.sis.internal.referencing.provider.MapProjection.SEMI_MAJOR;
import static org.apache.sis.internal.referencing.provider.MapProjection.SEMI_MINOR;
import static org.apache.sis.internal.referencing.provider.MapProjection.EXCENTRICITY;
import static org.apache.sis.internal.referencing.provider.AbridgedMolodensky.DIMENSION;


/**
 * Transform from two- or three- dimensional ellipsoidal coordinates to Cartesian coordinates.
 * This transform is usually part of a conversion from
 * {@linkplain org.apache.sis.referencing.crs.DefaultGeographicCRS geographic} to
 * {@linkplain org.apache.sis.referencing.crs.DefaultGeocentricCRS geocentric} coordinates.
 *
 * <p>Input coordinates are expected to contain:</p>
 * <ol>
 *   <li>longitudes in <strong>radians</strong> relative to the prime meridian (usually Greenwich),</li>
 *   <li>latitudes in <strong>radians</strong>,</li>
 *   <li>optionally heights above the ellipsoid, in units of an ellipsoid having a semi-major axis length of 1.</li>
 * </ol>
 *
 * Output coordinates are as below, in units of an ellipsoid having a semi-major axis length of 1:
 * <ol>
 *   <li>distance from Earth center on the X axis (toward the intersection of prime meridian and equator),</li>
 *   <li>distance from Earth center on the Y axis (toward the intersection of 90°E meridian and equator),</li>
 *   <li>distance from Earth center on the Z axis (toward North pole).</li>
 * </ol>
 *
 * <div class="section">Geographic to geocentric conversions</div>
 * For converting geographic coordinates to geocentric coordinates, {@code EllipsoidalToCartesianTransform} instances
 * need to be concatenated with the following affine transforms:
 *
 * <ul>
 *   <li><cite>Normalization</cite> before {@code EllipsoidalToCartesianTransform}:<ul>
 *     <li>Conversion of (λ,φ) from degrees to radians</li>
 *     <li>Division of (h) by the semi-major axis length</li>
 *   </ul></li>
 *   <li><cite>Denormalization</cite> after {@code EllipsoidalToCartesianTransform}:<ul>
 *     <li>Multiplication of (X,Y,Z) by the semi-major axis length</li>
 *   </ul></li>
 * </ul>
 *
 * The full conversion chain including the above affine transforms
 * can be created by {@link #createGeodeticConversion(MathTransformFactory)}.
 * Alternatively, the {@link #createGeodeticConversion(Ellipsoid, boolean)} convenience method can also be used.
 *
 * @author  Martin Desruisseaux (IRD, Geomatys)
 * @since   0.7
 * @version 0.7
 * @module
 */
public class EllipsoidalToCartesianTransform extends AbstractMathTransform implements Serializable {
    /**
     * Serial number for inter-operability with different versions.
     */
    private static final long serialVersionUID = -3352045463953828140L;

    /**
     * Internal parameter descriptor, used only for debugging purpose.
     * Created only when first needed.
     *
     * @see #getParameterDescriptors()
     */
    @Debug
    private static ParameterDescriptorGroup DESCRIPTOR;

    /**
     * The square of excentricity: ℯ² = (a²-b²)/a² where
     * <var>a</var> is the <cite>semi-major</cite> axis length and
     * <var>b</var> is the <cite>semi-minor</cite> axis length.
     */
    protected final double excentricitySquared;

    /**
     * {@code true} if ellipsoidal coordinates include an ellipsoidal height (i.e. are 3-D).
     * If {@code false}, then the input coordinates are expected to be two-dimensional and
     * the ellipsoidal height is assumed to be 0.
     */
    final boolean withHeight;

    /**
     * The parameters used for creating this conversion.
     * They are used for formatting <cite>Well Known Text</cite> (WKT) and error messages.
     *
     * @see #getContextualParameters()
     */
    private final ContextualParameters context;

    /**
     * The inverse of this transform.
     * Created at construction time because needed soon anyway.
     */
    private final AbstractMathTransform inverse;

    /**
     * Creates a transform from an ellipsoid of semi-major axis length of 1.
     * Angular units of input coordinates are <strong>radians</strong>.
     *
     * <p>For a conversion from angles in degrees and height in metres, see the
     * {@link #createGeodeticConversion(MathTransformFactory)} method.</p>
     *
     * @param semiMajor  The semi-major axis length.
     * @param semiMinor  The semi-minor axis length.
     * @param withHeight {@code true} if geographic coordinates include an ellipsoidal height (i.e. are 3-D),
     *                   or {@code false} if they are only 2-D.
     * @param unit       The unit of measurement for the semi-axes and the ellipsoidal height (if any).
     */
    public EllipsoidalToCartesianTransform(final double semiMajor, final double semiMinor, final boolean withHeight, final Unit<Length> unit) {
        ArgumentChecks.ensureStrictlyPositive("semiMajor", semiMajor);
        ArgumentChecks.ensureStrictlyPositive("semiMinor", semiMinor);
        this.excentricitySquared = 1 - (semiMinor*semiMinor) / (semiMajor*semiMajor);
        this.withHeight = withHeight;
        context = new ContextualParameters(GeographicToGeocentric.PARAMETERS, withHeight ? 4 : 3, 4);
        /*
         * Copy parameters to the ContextualParameter. Those parameters are not used directly
         * by EllipsoidToCartesian, but we need to store them in case the user asks for them.
         */
        context.getOrCreate(SEMI_MAJOR).setValue(semiMajor, unit);
        context.getOrCreate(SEMI_MINOR).setValue(semiMinor, unit);
        if (!withHeight) {
            context.getOrCreate(DIMENSION).setValue(2);
        }
        /*
         * Prepare two affine transforms to be executed before and after this EllipsoidalToCartesianTransform:
         *
         *   - A "normalization" transform for conversing degrees to radians and normalizing the height,
         *   - A "denormalization" transform for scaling (X,Y,Z) to the semi-major axis length.
         */
        context.normalizeGeographicInputs(0);
        final DoubleDouble a = new DoubleDouble(semiMajor);
        final MatrixSIS denormalize = context.getMatrix(false);
        for (int i=0; i<3; i++) {
            denormalize.convertAfter(i, a, null);
        }
        if (withHeight) {
            a.inverseDivide(1, 0);
            context.getMatrix(true).convertBefore(2, a, null);    // Divide ellipsoidal height by a.
        }
        inverse = new Inverse();
    }

    /**
     * Creates a transform from geographic to geocentric coordinates. This convenience method combines the
     * {@code EllipsoidalToCartesianTransform} instance with the steps needed for converting degrees to radians and
     * expressing the results in units of the given ellipsoid.
     *
     * <p>Input coordinates are expected to contain:</p>
     * <ol>
     *   <li>longitudes in degrees relative to the prime meridian (usually Greenwich),</li>
     *   <li>latitudes in degrees,</li>
     *   <li>optionally heights above the ellipsoid, in units of the ellipsoid axis (usually metres).</li>
     * </ol>
     *
     * Output coordinates are as below, in units of the ellipsoid axis (usually metres):
     * <ol>
     *   <li>distance from Earth center on the X axis (toward the intersection of prime meridian and equator),</li>
     *   <li>distance from Earth center on the Y axis (toward the intersection of 90°E meridian and equator),</li>
     *   <li>distance from Earth center on the Z axis (toward North pole).</li>
     * </ol>
     *
     * @param ellipsoid  The ellipsoid of source coordinates.
     * @param withHeight {@code true} if geographic coordinates include an ellipsoidal height (i.e. are 3-D),
     *                   or {@code false} if they are only 2-D.
     * @return The conversion from geographic to geocentric coordinates.
     */
    public static MathTransform createGeodeticConversion(final Ellipsoid ellipsoid, final boolean withHeight) {
        ArgumentChecks.ensureNonNull("ellipsoid", ellipsoid);
        try {
            return new EllipsoidalToCartesianTransform(ellipsoid.getSemiMajorAxis(), ellipsoid.getSemiMinorAxis(), withHeight,
                    ellipsoid.getAxisUnit()).createGeodeticConversion(DefaultFactories.forBuildin(MathTransformFactory.class));
        } catch (FactoryException e) {
            /*
             * Should not happen with SIS factory implementation. If it happen anyway,
             * maybe we got some custom factory implementation with limited functionality.
             */
            throw new IllegalStateException(e.getLocalizedMessage(), e);
        }
    }

    /**
     * Returns the sequence of <cite>normalization</cite> → {@code this} → <cite>denormalization</cite>
     * transforms as a whole. The transform returned by this method expects input coordinate having the
     * following values:
     *
     * <ol>
     *   <li>longitudes in degrees relative to the prime meridian (usually Greenwich),</li>
     *   <li>latitudes in degrees,</li>
     *   <li>optionally heights above the ellipsoid, in the units given to the constructor (usually metres).</li>
     * </ol>
     *
     * The converted coordinates will be lengths in the units given to the constructor (usually metres).
     *
     * @param  factory The factory to use for creating the transform.
     * @return The conversion from geographic to geocentric coordinates.
     * @throws FactoryException if an error occurred while creating a transform.
     *
     * @see ContextualParameters#completeTransform(MathTransformFactory, MathTransform)
     */
    public MathTransform createGeodeticConversion(final MathTransformFactory factory) throws FactoryException {
        return context.completeTransform(factory, this);
    }

    /**
     * Returns the parameters used for creating the complete conversion. Those parameters describe a sequence
     * of <cite>normalize</cite> → {@code this} → <cite>denormalize</cite> transforms, <strong>not</strong>
     * including {@linkplain org.apache.sis.referencing.cs.CoordinateSystems#swapAndScaleAxes axis swapping}.
     * Those parameters are used for formatting <cite>Well Known Text</cite> (WKT) and error messages.
     *
     * @return The parameters values for the sequence of
     *         <cite>normalize</cite> → {@code this} → <cite>denormalize</cite> transforms.
     */
    @Override
    protected final ContextualParameters getContextualParameters() {
        return context;
    }

    /**
     * Returns a copy of internal parameter values of this {@code EllipsoidalToCartesianTransform} transform.
     * The returned group contains parameter values for the number of dimensions and the excentricity.
     *
     * <div class="note"><b>Note:</b>
     * This method is mostly for {@linkplain org.apache.sis.io.wkt.Convention#INTERNAL debugging purposes}
     * since the isolation of non-linear parameters in this class is highly implementation dependent.
     * Most GIS applications will instead be interested in the {@linkplain #getContextualParameters()
     * contextual parameters}.</div>
     *
     * @return A copy of the internal parameter values for this transform.
     */
    @Debug
    @Override
    public ParameterValueGroup getParameterValues() {
        final ParameterValueGroup pg = getParameterDescriptors().createValue();
        pg.parameter("excentricity").setValue(sqrt(excentricitySquared));
        pg.parameter("dim").setValue(getSourceDimensions());
        return pg;
    }

    /**
     * Returns a description of the internal parameters of this {@code EllipsoidalToCartesianTransform} transform.
     * The returned group contains parameter descriptors for the number of dimensions and the excentricity.
     *
     * @return A description of the internal parameters.
     */
    @Debug
    @Override
    public ParameterDescriptorGroup getParameterDescriptors() {
        synchronized (EllipsoidalToCartesianTransform.class) {
            if (DESCRIPTOR == null) {
                DESCRIPTOR = new ParameterBuilder().setCodeSpace(Citations.SIS, "SIS")
                        .addName("Ellipsoidal to Cartesian").createGroup(1, 1, DIMENSION, EXCENTRICITY);
            }
            return DESCRIPTOR;
        }
    }

    /**
     * Gets the dimension of input points, which is 2 or 3.
     *
     * @return 2 or 3.
     */
    @Override
    public final int getSourceDimensions() {
        return withHeight ? 3 : 2;
    }

    /**
     * Gets the dimension of output points, which is 3.
     *
     * @return Always 3.
     */
    @Override
    public final int getTargetDimensions() {
        return 3;
    }

    /**
     * Converts the (λ,φ) or (λ,φ,<var>h</var>) geodetic coordinates to
     * to (<var>X</var>,<var>Y</var>,<var>Z</var>) geocentric coordinates,
     * and optionally returns the derivative at that location.
     *
     * @return {@inheritDoc}
     */
    @Override
    public Matrix transform(final double[] srcPts, final int srcOff,
                            final double[] dstPts, final int dstOff,
                            final boolean derivate)
    {
        final double λ      = srcPts[srcOff  ];                             // Longitude (radians)
        final double φ      = srcPts[srcOff+1];                             // Latitude (radians)
        final double h      = withHeight ? srcPts[srcOff+2] : 0;            // Height above the ellipsoid
        final double cosλ   = cos(λ);
        final double sinλ   = sin(λ);
        final double cosφ   = cos(φ);
        final double sinφ   = sin(φ);
        final double ν2     = 1 / (1 - excentricitySquared*(sinφ*sinφ));    // Square of ν (see below)
        final double ν      = sqrt(ν2);                                     // Prime vertical radius of curvature at latitude φ
        final double r      = ν + h;
        final double νℯ     = ν * (1 - excentricitySquared);
        if (dstPts != null) {
            final double rcosφ = r * cosφ;
            dstPts[dstOff  ] = rcosφ  * cosλ;                               // X: Toward prime meridian
            dstPts[dstOff+1] = rcosφ  * sinλ;                               // Y: Toward 90° east
            dstPts[dstOff+2] = (νℯ+h) * sinφ;                               // Z: Toward north pole
        }
        if (derivate) {
            final double sdφ   = νℯ * ν2 + h;
            final double dX_dh = cosφ * cosλ;
            final double dY_dh = cosφ * sinλ;
            final double dX_dλ = -r * dY_dh;
            final double dY_dλ =  r * dX_dh;
            final double dX_dφ = -sdφ * (sinφ * cosλ);
            final double dY_dφ = -sdφ * (sinφ * sinλ);
            final double dZ_dφ =  sdφ * cosφ;
            if (withHeight) {
                return new Matrix3(dX_dλ, dX_dφ, dX_dh,
                                   dY_dλ, dY_dφ, dY_dh,
                                       0, dZ_dφ, sinφ);
            } else {
                return Matrices.create(3, 2, new double[] {
                    dX_dλ, dX_dφ,
                    dY_dλ, dY_dφ,
                        0, dZ_dφ});
            }
        } else {
            return null;
        }
    }

    /**
     * Converts the (λ,φ) or (λ,φ,<var>h</var>) geodetic coordinates to
     * to (<var>X</var>,<var>Y</var>,<var>Z</var>) geocentric coordinates.
     */
    @Override
    public void transform(double[] srcPts, int srcOff, final double[] dstPts, int dstOff, int numPts) {
        final int dimSource = getSourceDimensions();
        int srcInc = 0;
        int dstInc = 0;
        if (srcPts == dstPts) {
            switch (IterationStrategy.suggest(srcOff, dimSource, dstOff, 3, numPts)) {
                case ASCENDING: {
                    break;
                }
                case DESCENDING: {
                    srcOff += (numPts - 1) * dimSource;
                    dstOff += (numPts - 1) * 3;                 // Target dimension is fixed to 3.
                    srcInc = -2 * dimSource;
                    dstInc = -6;
                    break;
                }
                default: {
                    srcPts = Arrays.copyOfRange(srcPts, srcOff, srcOff + numPts*dimSource);
                    srcOff = 0;
                    break;
                }
            }
        }
        while (--numPts >= 0) {
            final double λ      = srcPts[srcOff++];                                 // Longitude
            final double φ      = srcPts[srcOff++];                                 // Latitude
            final double h      = withHeight ? srcPts[srcOff++] : 0;                // Height above the ellipsoid
            final double sinφ   = sin(φ);
            final double ν      = 1/sqrt(1 - excentricitySquared * (sinφ*sinφ));    // Prime vertical radius of curvature at latitude φ
            final double rcosφ  = (ν + h) * cos(φ);
            dstPts[dstOff++]    = rcosφ * cos(λ);                                   // X: Toward prime meridian
            dstPts[dstOff++]    = rcosφ * sin(λ);                                   // Y: Toward 90° east
            dstPts[dstOff++]    = (ν * (1 - excentricitySquared) + h) * sinφ;       // Z: Toward north pole
            srcOff += srcInc;
            dstOff += dstInc;
        }
    }

    /**
     * Converts the (λ,φ) or (λ,φ,<var>h</var>) geodetic coordinates to
     * (<var>X</var>,<var>Y</var>,<var>Z</var>) geocentric coordinates.
     */
    @Override
    public void transform(float[] srcPts, int srcOff, final float[] dstPts, int dstOff, int numPts) {
        final int dimSource = getSourceDimensions();
        int srcInc = 0;
        int dstInc = 0;
        if (srcPts == dstPts) {
            switch (IterationStrategy.suggest(srcOff, dimSource, dstOff, 3, numPts)) {
                case ASCENDING: {
                    break;
                }
                case DESCENDING: {
                    srcOff += (numPts - 1) * dimSource;
                    dstOff += (numPts - 1) * 3;
                    srcInc = -2 * dimSource;
                    dstInc = -6;
                    break;
                }
                default: {
                    srcPts = Arrays.copyOfRange(srcPts, srcOff, srcOff + numPts*dimSource);
                    srcOff = 0;
                    break;
                }
            }
        }
        // See transform(double[], int, double[], int, int) for in-line comments.
        while (--numPts >= 0) {
            final double λ      = srcPts[srcOff++];
            final double φ      = srcPts[srcOff++];
            final double h      = withHeight ? srcPts[srcOff++] : 0;
            final double sinφ   = sin(φ);
            final double ν      = 1/sqrt(1 - excentricitySquared * (sinφ*sinφ));
            final double rcosφ  = (ν + h) * cos(φ);
            dstPts[dstOff++]    = (float) (rcosφ * cos(λ));
            dstPts[dstOff++]    = (float) (rcosφ * sin(λ));
            dstPts[dstOff++]    = (float) ((ν * (1 - excentricitySquared) + h) * sinφ);
            srcOff += srcInc;
            dstOff += dstInc;
        }
    }

    /**
     * Converts the (λ,φ) or (λ,φ,<var>h</var>) geodetic coordinates to
     * (<var>X</var>,<var>Y</var>,<var>Z</var>) geocentric coordinates.
     */
    @Override
    public void transform(final float[] srcPts, int srcOff, final double[] dstPts, int dstOff, int numPts) {
        // See transform(double[], int, double[], int, int) for in-line comments.
        while (--numPts >= 0) {
            final double λ      = srcPts[srcOff++];
            final double φ      = srcPts[srcOff++];
            final double h      = withHeight ? srcPts[srcOff++] : 0;
            final double sinφ   = sin(φ);
            final double ν      = 1/sqrt(1 - excentricitySquared * (sinφ*sinφ));
            final double rcosφ  = (ν + h) * cos(φ);
            dstPts[dstOff++]    = rcosφ * cos(λ);
            dstPts[dstOff++]    = rcosφ * sin(λ);
            dstPts[dstOff++]    = (ν * (1 - excentricitySquared) + h) * sinφ;
        }
    }

    /**
     * Converts the (λ,φ) or (λ,φ,<var>h</var>) geodetic coordinates to
     * to (<var>X</var>,<var>Y</var>,<var>Z</var>) geocentric coordinates.
     */
    @Override
    public void transform(final double[] srcPts, int srcOff, final float[] dstPts, int dstOff, int numPts) {
        // See transform(double[], int, double[], int, int) for in-line comments.
        while (--numPts >= 0) {
            final double λ      = srcPts[srcOff++];
            final double φ      = srcPts[srcOff++];
            final double h      = withHeight ? srcPts[srcOff++] : 0;
            final double sinφ   = sin(φ);
            final double ν      = 1/sqrt(1 - excentricitySquared * (sinφ*sinφ));
            final double rcosφ  = (ν + h) * cos(φ);
            dstPts[dstOff++]    = (float) (rcosφ * cos(λ));
            dstPts[dstOff++]    = (float) (rcosφ * sin(λ));
            dstPts[dstOff++]    = (float) ((ν * (1 - excentricitySquared) + h) * sinφ);
        }
    }

    /**
     * Converts Cartesian coordinates (<var>X</var>,<var>Y</var>,<var>Z</var>) to ellipsoidal coordinates
     * (λ,φ) or (λ,φ,<var>h</var>).
     *
     * @param srcPts1 The array containing the source point coordinates in simple precision.
     * @param srcPts2 Same than {@code srcPts1} but in double precision. Exactly one of
     *                {@code srcPts1} or {@code srcPts2} can be non-null.
     * @param srcOff  The offset to the first point to be transformed in the source array.
     * @param dstPts1 The array into which the transformed point coordinates are returned.
     *                May be the same than {@code srcPts1} or {@code srcPts2}.
     * @param dstPts2 Same than {@code dstPts1} but in double precision. Exactly one of
     *                {@code dstPts1} or {@code dstPts2} can be non-null.
     * @param dstOff  The offset to the location of the first transformed point that is stored
     *                in the destination array.
     * @param numPts  The number of point objects to be transformed.
     * @param descending {@code true} if points should be iterated in descending order.
     */
    final void inverseTransform(final float[] srcPts1, final double[] srcPts2, int srcOff,
                                final float[] dstPts1, final double[] dstPts2, int dstOff,
                                int numPts, final boolean descending)
    {
        // TODO
    }

    /**
     * Returns the inverse of this transform.
     *
     * @return The conversion from Cartesian to ellipsoidal coordinates.
     */
    @Override
    public MathTransform inverse() {
        return inverse;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    protected int computeHashCode() {
        return super.computeHashCode() + Numerics.hashCode(Double.doubleToLongBits(excentricitySquared));
    }

    /**
     * Compares the specified object with this math transform for equality.
     *
     * @return {@inheritDoc}
     */
    @Override
    public boolean equals(final Object object, final ComparisonMode mode) {
        if (object == this) {
            // Slight optimization
            return true;
        }
        if (super.equals(object, mode)) {
            final EllipsoidalToCartesianTransform that = (EllipsoidalToCartesianTransform) object;
            return (withHeight == that.withHeight) && Numerics.equals(excentricitySquared, that.excentricitySquared);
        }
        return false;
    }




    /**
     * Converts Cartesian coordinates (<var>X</var>,<var>Y</var>,<var>Z</var>)
     * to ellipsoidal coordinates (λ,φ) or (λ,φ,<var>h</var>).
     *
     * @author  Martin Desruisseaux (IRD, Geomatys)
     * @since   0.7
     * @version 0.7
     * @module
     */
    private final class Inverse extends AbstractMathTransform.Inverse implements Serializable {
        /**
         * Serial number for inter-operability with different versions.
         */
        private static final long serialVersionUID = 6942084702259211803L;

        /**
         * Creates the inverse of the enclosing transform.
         */
        Inverse() {
        }

        /**
         * Inverse transforms a single coordinate in a list of ordinal values,
         * and optionally returns the derivative at that location.
         *
         * <p>This method delegates the derivative computation to the enclosing class, then inverses the result.
         * This is much easier than trying to compute the derivative from the formulas of this inverse transform.</p>
         */
        @Override
        public Matrix transform(final double[] srcPts, final int srcOff,
                                final double[] dstPts, final int dstOff,
                                final boolean derivate) throws NoninvertibleMatrixException
        {
            final double[] point;
            final int offset;
            if (derivate && (dstPts == null || !withHeight)) {
                point  = new double[3];
                offset = 0;
            } else {
                point  = dstPts;
                offset = dstOff;
            }
            inverseTransform(null, srcPts, srcOff, null, point, offset, 1, false);
            if (!derivate) {
                return null;
            }
            if (dstPts != point && dstPts != null) {
                dstPts[dstOff  ] = point[0];
                dstPts[dstOff+1] = point[1];
            }
            return Matrices.inverse(EllipsoidalToCartesianTransform.this.transform(point, offset, null, 0, true));
        }

        /**
         * Transforms the given array of points.
         */
        @Override
        public void transform(double[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) {
            boolean descending = false;
            if (srcPts == dstPts) {
                switch (IterationStrategy.suggest(srcOff, 3, dstOff, getTargetDimensions(), numPts)) {
                    case ASCENDING: {
                        break;
                    }
                    case DESCENDING: {
                        descending = true;
                        break;
                    }
                    default: {
                        srcPts = Arrays.copyOfRange(srcPts, srcOff, srcOff + 3*numPts);
                        srcOff = 0;
                        break;
                    }
                }
            }
            inverseTransform(null, srcPts, srcOff, null, dstPts, dstOff, numPts, descending);
        }

        /**
         * Transforms the given array of points.
         */
        @Override
        public void transform(float[] srcPts, int srcOff, float[] dstPts, int dstOff, int numPts) {
            boolean descending = false;
            if (srcPts == dstPts) {
                switch (IterationStrategy.suggest(srcOff, 3, dstOff, getTargetDimensions(), numPts)) {
                    case ASCENDING: {
                        break;
                    }
                    case DESCENDING: {
                        descending = true;
                        break;
                    }
                    default: {
                        srcPts = Arrays.copyOfRange(srcPts, srcOff, srcOff + 3*numPts);
                        srcOff = 0;
                        break;
                    }
                }
            }
            inverseTransform(srcPts, null, srcOff, dstPts, null, dstOff, numPts, descending);
        }

        /**
         * Transforms the given array of points.
         */
        @Override
        public void transform(float [] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) {
            inverseTransform(srcPts, null, srcOff, null, dstPts, dstOff, numPts, false);
        }

        /**
         * Transforms the given array of points.
         */
        @Override
        public void transform(double[] srcPts, int srcOff, float [] dstPts, int dstOff, int numPts) {
            inverseTransform(null, srcPts, srcOff, dstPts, null, dstOff, numPts, false);
        }
    }
}
