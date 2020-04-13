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
package org.apache.sis.gui.map;

import java.util.Locale;
import java.util.Objects;
import java.awt.geom.AffineTransform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.input.RotateEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.GestureEvent;
import javafx.scene.Cursor;
import javafx.event.EventType;
import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.concurrent.Task;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Affine;
import javafx.scene.transform.NonInvertibleTransformException;
import org.opengis.geometry.Envelope;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.apache.sis.referencing.operation.matrix.Matrices;
import org.apache.sis.referencing.operation.matrix.MatrixSIS;
import org.apache.sis.referencing.operation.transform.MathTransforms;
import org.apache.sis.referencing.operation.transform.LinearTransform;
import org.apache.sis.geometry.Envelope2D;
import org.apache.sis.geometry.ImmutableEnvelope;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.util.logging.Logging;
import org.apache.sis.internal.util.Numerics;
import org.apache.sis.internal.gui.BackgroundThreads;
import org.apache.sis.internal.map.PlanarCanvas;
import org.apache.sis.internal.map.RenderException;
import org.apache.sis.internal.system.Modules;


/**
 * A canvas for maps to be rendered on screen in a JavaFX application.
 * The map may be an arbitrary JavaFX node, typically an {@link javafx.scene.image.ImageView}
 * or {@link javafx.scene.canvas.Canvas}, which must be supplied by subclasses.
 * This base class provides handlers for keyboard, mouse, track pad or touch screen events
 * such as pans, zooms and rotations. The keyboard actions are:
 *
 * <table class="sis">
 *   <caption>Keyboard actions</caption>
 *   <tr><th>Key</th>          <th>Action</th></tr>
 *   <tr><td>⇨</td>            <td>Move view to the right</td></tr>
 *   <tr><td>⇦</td>            <td>Move view to the left</td></tr>
 *   <tr><td>⇧</td>            <td>Move view to the top</td></tr>
 *   <tr><td>⇩</td>            <td>Move view to the bottom</td></tr>
 *   <tr><td>⎇ + ⇨</td>        <td>Rotate clockwise</td></tr>
 *   <tr><td>⎇ + ⇦</td>        <td>Rotate anticlockwise</td></tr>
 *   <tr><td>Page down</td>    <td>Zoom in</td></tr>
 *   <tr><td>Page up</td>      <td>Zoom out</td></tr>
 *   <tr><td>Home</td>         <td>{@linkplain #reset() Reset}</td></tr>
 *   <tr><td>Ctrl + above</td> <td>Above actions as a smaller translation, zoom or rotation</td></tr>
 * </table>
 *
 * <h2>Subclassing</h2>
 * Implementations need to add at least one JavaFX node in the {@link #floatingPane} list of children.
 * Map rendering involves the following steps:
 *
 * <ol>
 *   <li>{@link #createRenderer()} is invoked in the JavaFX thread. That method shall take a snapshot
 *     of every information needed for performing the rendering in background.</li>
 *   <li>{@link Renderer#render()} is invoked in a background thread. That method creates or updates
 *     the nodes to show in this {@code MapCanvas} but without interacting with the canvas yet.</li>
 *   <li>{@link Renderer#commit()} is invoked in the JavaFX thread. The nodes prepared by {@code render()}
 *     can be transferred to {@link #floatingPane} in that method.</li>
 * </ol>
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @version 1.1
 * @since   1.1
 * @module
 */
public abstract class MapCanvas extends PlanarCanvas {
    /**
     * Size in pixels of a scroll or translation event. This value should be close to the
     * {@linkplain ScrollEvent#getDeltaY() delta of a scroll event done with mouse wheel}.
     */
    private static final double SCROLL_EVENT_SIZE = 40;

    /**
     * The zoom factor to apply on scroll event. A value of 0.1 means that a zoom of 10%
     * is applied.
     */
    private static final double ZOOM_FACTOR = 0.1;

    /**
     * Division factor to apply on translations and zooms when the control key is down.
     */
    private static final double CONTROL_KEY_FACTOR = 10;

    /**
     * Number of milliseconds to wait before to repaint after gesture events (zooms, rotations, pans).
     * This delay allows to collect more events before to run a potentially costly {@link #repaint()}.
     * It does not apply to the immediate feedback that the user gets from JavaFX affine transforms
     * (an image with lower quality used until the higher quality image become ready).
     */
    private static final long REPAINT_DELAY = 500;

    /**
     * The pane showing the map and any other JavaFX nodes to scale and translate together with the map.
     * This pane is initially empty; subclasses should add nodes (canvas, images, shapes, texts, <i>etc.</i>)
     * into the {@link Pane#getChildren()} list.
     * All children must specify their coordinates in units relative to the pane (absolute layout).
     * Those coordinates can be computed from real world coordinates by {@link #objectiveToDisplay}.
     *
     * <p>This pane contains an {@link Affine} transform which is updated by user gestures such as pans,
     * zooms or rotations. Visual positions of all children move together in response to user's gesture,
     * thus giving an appearance of pane floating around. Changes in {@code floatingPane} affine transform
     * are temporary; they are applied for producing immediate visual feedback while the map is recomputed
     * in a background thread. Once calculation is completed and the content of this pane has been updated,
     * the {@code floatingPane} {@link Affine} transform is reset to identity.</p>
     */
    protected final Pane floatingPane;

    /**
     * The pane showing the map and other JavaFX nodes to keep at fixed position regardless pans, zooms or rotations
     * applied on the map. This pane contains at least the {@linkplain #floatingPane} (which itself contains the map),
     * but more children (shapes, texts, controls, <i>etc.</i>) can be added by subclasses into the
     * {@link StackPane#getChildren()} list.
     */
    protected final StackPane fixedPane;

    /**
     * The data bounds to use for computing the initial value of {@link #objectiveToDisplay}.
     * We differ this recomputation until all parameters are known.
     *
     * @see #setObjectiveBounds(Envelope)
     * @see #invalidObjectiveToDisplay
     */
    private Envelope objectiveBounds;

    /**
     * Incremented when the map needs to be rendered again.
     *
     * @see #renderedContentStamp
     * @see #contentsChanged()
     */
    private int contentChangeCount;

    /**
     * Value of {@link #contentChangeCount} last time the data have been rendered. This is used for deciding
     * if a call to {@link #repaint()} should be done with the next layout operation. We need this check for
     * avoiding never-ending repaint events caused by calls to {@code ImageView.setImage(Image)} causing
     * themselves new layout events. It is okay if this value overflows.
     */
    private int renderedContentStamp;

    /**
     * Non-null if a rendering task is in progress. Used for avoiding to send too many {@link #repaint()}
     * requests; we will wait for current repaint event to finish before to send another painting request.
     */
    private Task<?> renderingInProgress;

    /**
     * Whether the size of this canvas changed.
     */
    private boolean sizeChanged;

    /**
     * Whether {@link #objectiveToDisplay} needs to be recomputed.
     * We differ this recomputation until all parameters are known.
     *
     * @see #objectiveBounds
     */
    private boolean invalidObjectiveToDisplay;

    /**
     * The zooms, pans and rotations applied on {@link #floatingPane} since last time the map has been painted.
     * This is the identity transform except during the short time between a gesture (zoom, pan, <i>etc.</i>)
     * and the completion of latest {@link #repaint()} event. This is used for giving immediate feedback to user
     * while waiting for the new rendering to be ready. Since this transform is a member of {@link #floatingPane}
     * {@linkplain Pane#getTransforms() transform list}, changes in this transform are immediately visible to user.
     */
    private final Affine transform;

    /**
     * The {@link #transform} values at the time the {@link #repaint()} method has been invoked.
     * This is a change applied on {@link #objectiveToDisplay} but not yet visible in the map.
     * After the map has been updated, this transform is reset to identity.
     */
    private final Affine changeInProgress;

    /**
     * The value to assign to {@link #transform} after the {@link #floatingPane} has been updated
     * with transformed content.
     */
    private final Affine transformOnNewImage;

    /**
     * Cursor position at the time pan event started.
     * This is used for computing the {@linkplain #floatingPane} translation to apply during drag events.
     *
     * @see #onDrag(MouseEvent)
     */
    private double xPanStart, yPanStart;

    /**
     * Whether a rendering is in progress. This property is set to {@code true} when {@code MapCanvas}
     * is about to start a background thread for performing a rendering, and is reset to {@code false}
     * after the {@code MapCanvas} has been updated with new rendering result.
     *
     * @see #renderingProperty()
     */
    private final ReadOnlyBooleanWrapper isRendering;

    /**
     * The exception or error that occurred during last rendering operation.
     * This is reset to {@code null} when a rendering operation completes successfully.
     *
     * @see #errorProperty()
     */
    private final ReadOnlyObjectWrapper<Throwable> error;

    /**
     * Creates a new canvas for JavaFX application.
     *
     * @param  locale  the locale to use for labels and some messages, or {@code null} for default.
     */
    public MapCanvas(final Locale locale) {
        super(locale);
        transform           = new Affine();
        changeInProgress    = new Affine();
        transformOnNewImage = new Affine();
        final Pane view = new Pane() {
            @Override protected void layoutChildren() {
                super.layoutChildren();
                if (contentsChanged()) {
                    repaint();
                }
            }
        };
        view.getTransforms().add(transform);
        view.setOnZoom(this::onZoom);
        view.setOnRotate(this::onRotate);
        view.setOnScroll(this::onScroll);
        view.setOnMousePressed(this::onDrag);
        view.setOnMouseDragged(this::onDrag);
        view.setOnMouseReleased(this::onDrag);
        view.setFocusTraversable(true);
        view.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyTyped);
        /*
         * Do not set a preferred size, otherwise `repaint()` is invoked twice: once with the preferred size
         * and once with the actual size of the parent window. Actually the `repaint()` method appears to be
         * invoked twice anyway, but without preferred size the width appears to be 0, in which case nothing
         * is repainted.
         */
        view.layoutBoundsProperty().addListener(this::onSizeChanged);
        view.setCursor(Cursor.CROSSHAIR);
        floatingPane = view;
        fixedPane = new StackPane(view);
        final Rectangle clip = new Rectangle();
        clip.widthProperty() .bind(fixedPane.widthProperty());
        clip.heightProperty().bind(fixedPane.heightProperty());
        fixedPane.setClip(clip);
        isRendering = new ReadOnlyBooleanWrapper(this, "isRendering");
        error = new ReadOnlyObjectWrapper<>(this, "exception");
    }

    /**
     * Invoked when the size of the {@linkplain #floatingPane} has changed.
     * This method requests a new repaint after a short wait, in order to collect more resize events.
     */
    private void onSizeChanged(final Observable property) {
        sizeChanged = true;
        repaintLater();
    }

    /**
     * Invoked when the user presses the button, drags the map and releases the button.
     * This is interpreted as a translation applied in pixel units on the map.
     */
    private void onDrag(final MouseEvent event) {
        final double x = event.getX();
        final double y = event.getY();
        final EventType<? extends MouseEvent> type = event.getEventType();
        if (type == MouseEvent.MOUSE_PRESSED) {
            floatingPane.setCursor(Cursor.CLOSED_HAND);
            floatingPane.requestFocus();
            xPanStart = x;
            yPanStart = y;
        } else {
            if (type != MouseEvent.MOUSE_DRAGGED) {
                floatingPane.setCursor(renderingInProgress != null ? Cursor.WAIT : Cursor.CROSSHAIR);
            }
            applyTranslation(x - xPanStart, y - yPanStart, type == MouseEvent.MOUSE_RELEASED);
        }
        event.consume();
    }

    /**
     * Translates the map in response to user event (keyboard, mouse, track pad, touch screen).
     *
     * @param  tx       horizontal translation in pixel units.
     * @param  ty       vertical translation in pixel units.
     * @param  isFinal  {@code false} if more translations are expected soon, or
     *                  {@code true} if this is the last translation for now.
     *
     * @see #applyZoomOrRotate(GestureEvent, double, double)
     */
    private void applyTranslation(final double tx, final double ty, final boolean isFinal) {
        if (tx != 0 || ty != 0) {
            transform.appendTranslation(tx, ty);
            final Point2D p = changeInProgress.deltaTransform(tx, ty);
            transformOnNewImage.appendTranslation(p.getX(), p.getY());
            if (!isFinal) {
                repaintLater();
            }
        }
        if (isFinal && !transform.isIdentity()) {
            repaint();
        }
    }

    /**
     * Invoked when the user rotates the mouse wheel.
     * This method performs a zoom-in or zoom-out event.
     */
    private void onScroll(final ScrollEvent event) {
        if (event.getTouchCount() != 0) {
            // Do not interpret scroll events on touch pad as a zoom.
            return;
        }
        final double delta = event.getDeltaY();
        double zoom = Math.abs(delta) / SCROLL_EVENT_SIZE * ZOOM_FACTOR;
        if (event.isControlDown()) {
            zoom /= CONTROL_KEY_FACTOR;
        }
        zoom++;
        if (delta < 0) {
            zoom = 1/zoom;
        }
        applyZoomOrRotate(event, zoom, 0);
    }

    /**
     * Invoked when the user performs a zoom on track pad or touch screen.
     */
    private void onZoom(final ZoomEvent event) {
        applyZoomOrRotate(event, event.getZoomFactor(), 0);
    }

    /**
     * Invoked when the user performs a rotation on track pad or touch screen.
     */
    private void onRotate(final RotateEvent event) {
        applyZoomOrRotate(event, 1, event.getAngle());
    }

    /**
     * Zooms or rotates the map in response to user event (keyboard, mouse, track pad, touch screen).
     * If the given event is non-null, it will be consumed.
     *
     * @param  event  the mouse, track pad or touch screen event, or {@code null} if the event was a keyboard event.
     * @param  zoom   the zoom factor to apply, or 1 if none.
     * @param  angle  the rotation angle in degrees, or 0 if nine.
     *
     * @see #applyTranslation(double, double, boolean)
     */
    private void applyZoomOrRotate(final GestureEvent event, final double zoom, final double angle) {
        if (zoom != 1 || angle != 0) {
            double x, y;
            if (event != null) {
                x = event.getX();
                y = event.getY();
            } else {
                final Bounds bounds = floatingPane.getLayoutBounds();
                x = bounds.getCenterX();
                y = bounds.getCenterY();
                try {
                    final Point2D p = transform.inverseTransform(x, y);
                    x = p.getX();
                    y = p.getY();
                } catch (NonInvertibleTransformException e) {
                    /*
                     * `event` is null only when this method is invoked from `onKeyTyped(…)`.
                     * Keep old coordinates. The map may appear shifted, but its location will
                     * be fixed when `repaint()` completes its work.
                     */
                    unexpectedException("onKeyTyped", e);
                }
            }
            final Point2D p = changeInProgress.transform(x, y);
            if (zoom != 1) {
                transform.appendScale(zoom, zoom, x, y);
                transformOnNewImage.appendScale(zoom, zoom, p.getX(), p.getY());
            }
            if (angle != 0) {
                transform.appendRotation(angle, x, y);
                transformOnNewImage.appendRotation(angle, p.getX(), p.getY());
            }
            repaintLater();
        }
        if (event != null) {
            event.consume();
        }
    }

    /**
     * Invoked when the user presses a key. This handler provides navigation in the direction of arrow keys,
     * or zoom-in / zoom-out with page-down / page-up keys. If the control key is down, navigation is finer.
     */
    private void onKeyTyped(final KeyEvent event) {
        double tx = 0, ty = 0, zoom = 1, angle = 0;
        if (event.isAltDown()) {
            switch (event.getCode()) {
                case RIGHT: case KP_RIGHT: angle = +15; break;
                case LEFT:  case KP_LEFT:  angle = -15; break;
                default:                   return;
            }
        } else {
            switch (event.getCode()) {
                case RIGHT: case KP_RIGHT: tx   = -SCROLL_EVENT_SIZE;  break;
                case LEFT:  case KP_LEFT:  tx   = +SCROLL_EVENT_SIZE;  break;
                case DOWN:  case KP_DOWN:  ty   = -SCROLL_EVENT_SIZE;  break;
                case UP:    case KP_UP:    ty   = +SCROLL_EVENT_SIZE;  break;
                case PAGE_UP:              zoom = 1/(1 + ZOOM_FACTOR); break;
                case PAGE_DOWN:            zoom =   (1 + ZOOM_FACTOR); break;
                case HOME:                 reset(); break;
                default:                   return;
            }
        }
        if (event.isControlDown()) {
            tx    /= CONTROL_KEY_FACTOR;
            ty    /= CONTROL_KEY_FACTOR;
            angle /= CONTROL_KEY_FACTOR;
            zoom   = (zoom - 1) / CONTROL_KEY_FACTOR + 1;
        }
        try {
            final Point2D p = transform.inverseDeltaTransform(tx, ty);
            tx = p.getX();
            ty = p.getY();
        } catch (NonInvertibleTransformException e) {
            /*
             * Should never happen. If happen anyway, keep old coordinates. The map may appear
             * shifted, but its location will be fixed when `repaint()` completes its work.
             */
            unexpectedException("onKeyTyped", e);
        }
        applyZoomOrRotate(null, zoom, angle);
        applyTranslation(tx, ty, false);
        event.consume();
    }

    /**
     * Resets the map view to its default zoom level and default position with no rotation.
     * Contrarily to {@link #clear()}, this method does not remove the map content.
     */
    public void reset() {
        invalidObjectiveToDisplay = true;
        requestRepaint();
    }

    /**
     * Returns {@code true} if content changed since the last {@link #repaint()} execution.
     */
    final boolean contentsChanged() {
        return contentChangeCount != renderedContentStamp;
    }

    /**
     * Sets the data bounds to use for computing the initial value of {@link #objectiveToDisplay}.
     * This method should be invoked only when new data have been loaded, or when the caller wants
     * to discard any zoom or translation and reset the view to the given bounds.
     *
     * @param  visibleArea  bounding box in objective CRS of the initial area to show,
     *         or {@code null} if unknown (in which case an identity transform will be set).
     *
     * @see #setObjectiveCRS(CoordinateReferenceSystem)
     */
    protected void setObjectiveBounds(final Envelope visibleArea) {
        ArgumentChecks.ensureDimensionMatches("bounds", BIDIMENSIONAL, visibleArea);
        objectiveBounds = ImmutableEnvelope.castOrCopy(visibleArea);
        invalidObjectiveToDisplay = true;
    }

    /**
     * Invoked in JavaFX thread for creating a renderer to be executed in a background thread.
     * Subclasses shall copy in this method all {@code MapCanvas} properties that the background thread
     * will need for performing the rendering process.
     *
     * @return rendering process to be executed in background thread,
     *         or {@code null} if there is nothing to paint.
     */
    protected abstract Renderer createRenderer();

    /**
     * A snapshot of {@link MapCanvas} state to render as a map, together with rendering code.
     * This class is instantiated and used as below:
     *
     * <ol>
     *   <li>{@link MapCanvas} invokes {@link MapCanvas#createRenderer()} in the JavaFX thread.
     *     That method shall take a snapshot of every information needed for performing the rendering
     *     in a background thread.</li>
     *   <li>{@link MapCanvas} invokes {@link #render()} in a background thread. That method creates or
     *     updates the nodes to show in the canvas but without reading or writing any canvas property;
     *     that method should use only the snapshot taken in step 1.</li>
     *   <li>{@link MapCanvas} invokes {@link #commit()} in the JavaFX thread. The nodes prepared at
     *     step 2 can be transferred to {@link MapCanvas#floatingPane} in that method.</li>
     * </ol>
     *
     * @author  Martin Desruisseaux (Geomatys)
     * @version 1.1
     * @since   1.1
     * @module
     */
    protected abstract static class Renderer {
        /**
         * The canvas size.
         */
        private int width, height;

        /**
         * Creates a new renderer. The {@linkplain #getWidth() width} and {@linkplain #getHeight() height}
         * are initially zero; they will get a non-zero values before {@link #render()} is invoked.
         */
        protected Renderer() {
        }

        /**
         * Sets the width and height to the size of the given view,
         * then returns {@code true} if the view is non-empty.
         */
        final boolean initialize(final Pane view) {
            width  = Numerics.clamp(Math.round(view.getWidth()));
            height = Numerics.clamp(Math.round(view.getHeight()));
            return width > 0 && height > 0;
        }

        /**
         * Returns the width (number of columns) of the view, in pixels.
         *
         * @return number of pixels to render horizontally.
         */
        public int getWidth() {
            return width;
        }

        /**
         * Returns the height (number of rows) of the view, in pixels.
         *
         * @return number of pixels to render vertically.
         */
        public int getHeight() {
            return height;
        }

        /**
         * Invoked in a background thread for rendering the map. This method should not access any
         * {@link MapCanvas} property; if some canvas properties are needed, they should have been
         * copied at construction time.
         */
        protected abstract void render();

        /**
         * Invoked in JavaFX thread after {@link #render()} completion. This method can update the
         * {@link #floatingPane} children with the nodes (images, shaped, <i>etc.</i>) created by
         * {@link #render()}.
         *
         * @return {@code true} on success, or {@code false} if the rendering should be redone
         *         (for example because a change has been detected in the data).
         */
        protected abstract boolean commit();
    }

    /**
     * Requests the map to be rendered again, possibly with new data. Invoking this
     * method does not necessarily causes the repaint process to start immediately.
     * The request will be queued and executed at an arbitrary time.
     */
    protected void requestRepaint() {
        contentChangeCount++;
        floatingPane.requestLayout();
    }

    /**
     * Invokes {@link #repaint()} after a short delay. This method is used when the
     * repaint event is caused by some gesture like pan, zoom or resizing the window.
     */
    private void repaintLater() {
        contentChangeCount++;
        if (renderingInProgress == null) {
            executeRendering(new Delayed());
        }
    }

    /**
     * Executes a rendering task in a background thread. This method applies configurations
     * specific to the rendering process before and after delegating to the overrideable method.
     */
    private void executeRendering(final Task<?> worker) {
        assert renderingInProgress == null;
        floatingPane.setCursor(Cursor.WAIT);
        execute(worker);
        renderingInProgress = worker;       // Set last after we know that the task has been scheduled.
    }

    /**
     * Invoked when the map content needs to be rendered again.
     * It may be because the map has new content, or because the viewed region moved or has been zoomed.
     *
     * @see #requestRepaint()
     */
    final void repaint() {
        /*
         * If a rendering is already in progress, do not send a new request now.
         * Wait for current rendering to finish; a new one will be automatically
         * requested if content changes are detected after the rendering.
         */
        if (renderingInProgress != null) {
            if (renderingInProgress instanceof Delayed) {
                renderingInProgress.cancel(true);
                renderingInProgress = null;
            } else {
                contentChangeCount++;
                return;
            }
        }
        renderedContentStamp = contentChangeCount;
        /*
         * If a new canvas size is known, inform the parent `PlanarCanvas` about that.
         * It may cause a recomputation of the "objective to display" transform.
         */
        try {
            if (sizeChanged) {
                sizeChanged = false;
                final Pane view = floatingPane;
                Envelope2D bounds = new Envelope2D(null, view.getLayoutX(), view.getLayoutY(), view.getWidth(), view.getHeight());
                if (bounds.isEmpty()) return;
                setDisplayBounds(bounds);
            }
            /*
             * Compute the `objectiveToDisplay` only before the first rendering, because the display
             * bounds may not be known before (it may be zero at the time `MapCanvas` is initialized).
             * This code is executed only once for a new map.
             */
            if (invalidObjectiveToDisplay) {
                invalidObjectiveToDisplay = false;
                LinearTransform tr;
                final CoordinateReferenceSystem crs;
                if (objectiveBounds != null) {
                    crs = objectiveBounds.getCoordinateReferenceSystem();
                    final Envelope2D target = getDisplayBounds();
                    final MatrixSIS m = Matrices.createTransform(objectiveBounds, target);
                    Matrices.forceUniformScale(m, 0, new double[] {target.width / 2, target.height / 2});
                    tr = MathTransforms.linear(m);
                } else {
                    tr = MathTransforms.identity(BIDIMENSIONAL);
                    crs = null;
                }
                setObjectiveCRS(crs);
                setObjectiveToDisplay(tr);
                transform.setToIdentity();
            }
        } catch (RenderException ex) {
            errorOccurred(ex);
            return;
        }
        /*
         * If a temporary zoom, rotation or translation has been applied using JavaFX transform API,
         * replaced that temporary transform by a "permanent" adjustment of the `objectiveToDisplay`
         * transform. It allows SIS to get new data for the new visible area and resolution.
         */
        assert changeInProgress.isIdentity() : changeInProgress;
        changeInProgress.setToTransform(transform);
        transformOnNewImage.setToIdentity();
        isRendering.set(true);
        if (!transform.isIdentity()) {
            transformDisplayCoordinates(new AffineTransform(
                    transform.getMxx(), transform.getMyx(),
                    transform.getMxy(), transform.getMyy(),
                    transform.getTx(),  transform.getTy()));
        }
        /*
         * Invoke `createRenderer()` only after we finished above configuration, because that method
         * may take a snapshot of current canvas state in preparation for use in background threads.
         */
        final Renderer context = createRenderer();
        if (context != null && context.initialize(floatingPane)) {
            executeRendering(createWorker(context));
        } else {
            error.set(null);
            isRendering.set(false);
        }
    }

    /**
     * Creates the background task which will invoke {@link Renderer#render()} in a background thread.
     * The tasks must invoke {@link #renderingCompleted(Task)} in JavaFX thread after completion,
     * either successful or not.
     */
    Task<?> createWorker(final Renderer renderer) {
        return new Task<Void>() {
            /** Invoked in background thread. */
            @Override protected Void call() {
                renderer.render();
                return null;
            }

            /** Invoked in JavaFX thread on success. */
            @Override protected void succeeded() {
                final boolean done = renderer.commit();
                renderingCompleted(this);
                if (!done || contentsChanged()) {
                    repaint();
                }
            }

            /** Invoked in JavaFX thread on failure. */
            @Override protected void failed()    {renderingCompleted(this);}
            @Override protected void cancelled() {renderingCompleted(this);}
        };
    }

    /**
     * Invoked after the background thread created by {@link #repaint()} finished to update map content.
     * The {@link #changeInProgress} is the JavaFX transform at the time the repaint event was trigged and
     * which is now integrated in the map. That transform will be removed from {@link #floatingPane} transforms.
     * It may be identity if no zoom, rotation or pan gesture has been applied since last rendering.
     */
    final void renderingCompleted(final Task<?> task) {
        renderingInProgress = null;
        floatingPane.setCursor(Cursor.CROSSHAIR);
        final Point2D p = changeInProgress.transform(xPanStart, yPanStart);
        xPanStart = p.getX();
        yPanStart = p.getY();
        changeInProgress.setToIdentity();
        transform.setToTransform(transformOnNewImage);
        error.set(task.getException());
        isRendering.set(false);
    }

    /**
     * A pseudo-rendering task which wait for some delay before to perform the real repaint.
     * The intent is to collect some more gesture events (pans, zooms, <i>etc.</i>) before consuming CPU time.
     * This is especially useful when the first gesture event is a tiny change because the user just started
     * panning or zooming.
     *
     * <div class="note"><b>Design note:</b>
     * using a thread for waiting seems a waste of resources, but a thread (likely this one) is going to be used
     * for real after the waiting time is elapsed. That thread usually exists anyway in {@link BackgroundThreads}
     * as an idle thread, and it is unlikely that other parts of this JavaFX application need that thread in same
     * time (if it happens, other threads will be created).</div>
     *
     * @see #repaintLater()
     */
    private final class Delayed extends Task<Void> {
        @Override protected Void call() {
            try {
                Thread.sleep(REPAINT_DELAY);
            } catch (InterruptedException e) {
                // Task.cancel(true) has been invoked: do nothing and terminate now.
            }
            return null;
        }

        @Override protected void succeeded() {paintAfterDelay();}
        @Override protected void failed()    {paintAfterDelay();}
        // Do not override `cancelled()` because a repaint is already in progress.
    }

    /**
     * Invoked after {@link #REPAINT_DELAY} has been elapsed for performing the real repaint request.
     */
    private void paintAfterDelay() {
        renderingInProgress = null;
        repaint();
    }

    /**
     * Starts a background task for any process loading or rendering the map.
     * This {@code MapCanvas} class invokes this method for rendering the map,
     * but subclasses can also invoke this method for other purposes.
     *
     * <p>Tasks need to be careful to not access any {@code MapCanvas} property in their {@link Task#call()} method.
     * If a canvas property is needed by the task, its value should be copied before the background thread is started.
     * However {@link Task#succeeded()} and similar methods can safety read and write those properties.</p>
     *
     * <h4>Overriding</h4>
     * Subclasses can override this method for configuring the task before execution.
     * For example the following methods may be invoked before to call {@code super.execute(task)}:
     * <ul>
     *   <li><code>{@linkplain Task#runningProperty()}.addListener(…)</code></li>
     *   <li><code>{@linkplain Task#setOnFailed Task.setOnFailed}(…)</code></li>
     * </ul>
     *
     * @param  task  the task to execute in a background thread for loading or rendering the map.
     */
    protected void execute(final Task<?> task) {
        BackgroundThreads.execute(task);
    }

    /**
     * Returns a property telling whether a rendering is in progress. This property become {@code true}
     * when this {@code MapCanvas} is about to start a background thread for performing a rendering, and
     * is reset to {@code false} after this {@code MapCanvas} has been updated with new rendering result.
     *
     * @return a property telling whether a rendering is in progress.
     */
    public final ReadOnlyBooleanProperty renderingProperty() {
        return isRendering.getReadOnlyProperty();
    }

    /**
     * Returns a property giving the exception or error that occurred during last rendering operation.
     * The property value is reset to {@code null} when a rendering operation completed successfully.
     *
     * @return a property giving the exception or error that occurred during last rendering operation.
     */
    public final ReadOnlyObjectProperty<Throwable> errorProperty() {
        return error.getReadOnlyProperty();
    }

    /**
     * Sets the error property to the given value. This method is provided for subclasses that perform
     * processing outside the {@link Renderer}. It does not need to be invoked if the error occurred
     * during the rendering process.
     *
     * @param  ex  the exception that occurred (can not be null).
     */
    protected void errorOccurred(final Throwable ex) {
        error.set(Objects.requireNonNull(ex));
    }

    /**
     * Invoked when an unexpected exception occurred but it is okay to continue despite it.
     */
    private static void unexpectedException(final String method, final NonInvertibleTransformException e) {
        Logging.unexpectedException(Logging.getLogger(Modules.APPLICATION), MapCanvas.class, method, e);
    }

    /**
     * Removes map content and clears all properties of this canvas.
     *
     * @see #reset()
     */
    protected void clear() {
        transform.setToIdentity();
        changeInProgress.setToIdentity();
        invalidObjectiveToDisplay = true;
        objectiveBounds = null;
        error.set(null);
        isRendering.set(false);
    }
}