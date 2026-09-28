/*
 * Copyright (c) 2010-2026. Institut Pasteur.
 *
 * This file is part of Icy.
 * Icy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Icy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Icy. If not, see <https://www.gnu.org/licenses/>.
 */

package plugins.adufour.viewers;

import fr.icy.common.geom.point.Point5D;
import fr.icy.extension.plugin.abstract_.Plugin;
import fr.icy.extension.plugin.annotation_.IcyPluginName;
import fr.icy.extension.plugin.interface_.PluginCanvas;
import fr.icy.gui.GraphicsUtil;
import fr.icy.gui.canvas.IcyCanvas2D;
import fr.icy.gui.canvas.IcyCanvasEvent;
import fr.icy.gui.canvas.Layer;
import fr.icy.gui.component.slider.IcySlider;
import fr.icy.gui.viewer.Viewer;
import fr.icy.model.image.IcyBufferedImage;
import fr.icy.model.image.IcyBufferedImageUtil;
import fr.icy.model.roi.ROI;
import fr.icy.model.roi.ROI2D;
import fr.icy.model.sequence.DimensionId;
import fr.icy.model.sequence.Sequence;
import fr.icy.system.preferences.CanvasPreferences;
import fr.icy.system.thread.SingleProcessor;
import fr.icy.system.thread.ThreadUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@IcyPluginName("Ortho Viewer")
public class OrthoViewer extends Plugin implements PluginCanvas<OrthoViewer.OrthoCanvas> {
    @Override
    public String getCanvasClassName() {
        return OrthoViewer.class.getName();
    }

    @Override
    public OrthoCanvas createCanvas(final Viewer viewer) {
        return new OrthoCanvas(viewer);
    }

    public static class OrthoCanvas extends IcyCanvas2D {
        final JPanel orthoViewPanel;

        final OrthoView xy, zy, xz;

        double xScale; // = 1;
        double yScale; // = 1;

        final IcySlider zoomSlider = new IcySlider(IcySlider.HORIZONTAL, 1, 1000, 100);

        double zoom = 1;

        boolean crossHairVisible = true;

        public OrthoCanvas(final Viewer viewer) {
            super(viewer);

            orthoViewPanel = new JPanel(null);

            xy = new OrthoView(DimensionId.Z);
            xz = new OrthoView(DimensionId.Y);
            zy = new OrthoView(DimensionId.X);

            xScale = getSequence().getPixelSizeZ() / getSequence().getPixelSizeX();
            if (Double.isNaN(xScale) || xScale == 0) xScale = 1.0;

            yScale = getSequence().getPixelSizeZ() / getSequence().getPixelSizeY();
            if (Double.isNaN(yScale) || yScale == 0) yScale = 1.0;

            posX = getSequence().getSizeX() / 2;
            posY = getSequence().getSizeY() / 2;
            posZ = getSequence().getSizeZ() / 2;

            orthoViewPanel.add(xy);
            orthoViewPanel.add(zy);
            orthoViewPanel.add(xz);

            setZoom(1.0);

            final JScrollPane scroll = new JScrollPane(orthoViewPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            add(scroll, BorderLayout.CENTER);

            updateTNav();
            updateZNav();

            getMouseImageInfosPanel().setInfoColorVisible(false);
            getMouseImageInfosPanel().setInfoCVisible(false);
            getMouseImageInfosPanel().setInfoXVisible(false);
            getMouseImageInfosPanel().setInfoYVisible(false);
            getMouseImageInfosPanel().setInfoDataVisible(false);

            xy.imageChanged();
            xz.imageChanged();
            zy.imageChanged();

            invalidate();
        }

        /**
         * @return the XY view
         */
        public OrthoView getXYView() {
            return xy;
        }

        /**
         * @return the XZ view
         */
        public OrthoView getXZView() {
            return xz;
        }

        /**
         * @return the ZY view
         */
        public OrthoView getZYView() {
            return zy;
        }

        @Override
        public void customizeToolbar(final @NotNull JToolBar toolBar) {
            final JCheckBox showCrossHair = new JCheckBox("Crosshair", true);
            showCrossHair.setFocusable(false);
            showCrossHair.addActionListener(e -> {
                crossHairVisible = showCrossHair.isSelected();
                refresh();
            });
            toolBar.add(showCrossHair);

            final JLabel sizeLabel = new JLabel("  Zoom:");
            final JLabel zoomValueLabel = new JLabel(zoomSlider.getValue() + "%");

            zoomSlider.setFocusable(false);
            zoomSlider.addChangeListener(e -> {
                setZoom(zoomSlider.getValue() / 100.0);
                zoomValueLabel.setText(zoomSlider.getValue() + "%");
            });

            toolBar.add(sizeLabel);
            toolBar.add(zoomSlider);
            toolBar.add(zoomValueLabel);
        }

        private void setZoom(final double newZoom) {
            this.zoom = newZoom;

            // adjust the main (XY) panel size
            final int xyWidth = (int) Math.round(newZoom * getSequence().getSizeX());
            final int xyHeight = (int) Math.round(newZoom * getSequence().getSizeY());

            xy.setBounds(0, 0, xyWidth, xyHeight);
            zy.setBounds(xyWidth + 5, 0, (int) Math.round(newZoom * getSequence().getSizeZ() * xScale), xyHeight);
            xz.setBounds(0, xyHeight + 5, xyWidth, (int) Math.round(newZoom * getSequence().getSizeZ() * yScale));
            orthoViewPanel.setPreferredSize(new Dimension(xy.getWidth() + 5 + zy.getWidth(), xy.getHeight() + 5 + xz.getHeight()));
        }

        @Override
        public java.awt.geom.Point2D.Double canvasToImage(final Point point) {
            return super.canvasToImage(point);
        }

        @Override
        public void changed(final IcyCanvasEvent event) {
            super.changed(event);

            switch (event.getType()) {
                case POSITION_CHANGED: {
                    if (event.getDim() == DimensionId.Z) {
                        xy.imageChanged();
                        refresh();
                    }
                    else if (event.getDim() == DimensionId.T) {
                        xy.imageChanged();
                        zy.imageChanged();
                        xz.imageChanged();
                        refresh();
                    }
                    break;
                }
                case OFFSET_CHANGED, ROTATION_CHANGED, SCALE_CHANGED, MOUSE_IMAGE_POSITION_CHANGED, SYNC_CHANGED:
                    break;
                default:
            }
        }

        @Override
        protected void lutChanged(final int component) {
            super.lutChanged(component);

            try {
                if (xy != null) xy.imageChanged();
                if (zy != null) zy.imageChanged();
                if (xz != null) xz.imageChanged();
            }
            catch (final NullPointerException npE) {
                // as silly as it seems, this may happen...
            }

            refresh();
        }

        protected void mousePositionChanged(@NotNull final DimensionId dim, int x, int y) {
            x = (int) Math.max(0, x / zoom);
            y = (int) Math.max(0, y / zoom);

            final int maxWidth = getSequence().getSizeX() - 1;
            final int maxHeight = getSequence().getSizeY() - 1;
            final int maxDepth = getSequence().getSizeZ() - 1;

            switch (dim) {
                case Z: {

                    // adjust X
                    if (x > maxWidth) x = maxWidth;
                    if (x != posX) {
                        setPositionXInternal(x);
                        zy.imageChanged();
                    }

                    // adjust Y
                    if (y > maxHeight) y = maxHeight;
                    if (y != posY) {
                        setPositionYInternal(y);
                        xz.imageChanged();
                    }

                    break;
                }
                case Y: {

                    // adjust X
                    if (x > maxWidth) x = maxWidth;
                    if (x != posX) {
                        setPositionXInternal(x);
                        zy.imageChanged();
                    }

                    // adjust Z
                    double scale = getSequence().getPixelSizeZ() / getSequence().getPixelSizeX();
                    if (Double.isNaN(scale) || scale == 0) scale = 1.0;

                    y /= (int) scale;
                    if (y > maxDepth) y = maxDepth;
                    if (y != posZ) {
                        setPositionZInternal(y);
                        xy.imageChanged();
                    }

                    break;
                }
                case X: {

                    // adjust Y
                    if (y > maxHeight) y = maxHeight;
                    if (y != posY) {
                        setPositionYInternal(y);
                        xz.imageChanged();
                    }

                    // adjust Z
                    double scale = getSequence().getPixelSizeZ() / getSequence().getPixelSizeY();
                    if (Double.isNaN(scale) || scale == 0) scale = 1.0;

                    x /= (int) scale;
                    if (x > maxDepth) x = maxDepth;
                    if (x != posZ) {
                        setPositionZInternal(x);
                        xy.imageChanged();
                    }

                    break;
                }
                default:
            }

            refresh();
        }

        @Override
        public void refresh() {
            getMouseImageInfosPanel().updateInfos(this);
            repaint();
        }

        /**
         * Snapshot
         */
        @Override
        public BufferedImage getRenderedImage(final int t, final int z, final int c, final boolean canvasView) {
            final Dimension size = orthoViewPanel.getPreferredSize();
            final BufferedImage snap = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = snap.createGraphics();

            final int oldZ = getPositionZ();
            final int oldT = getPositionT();

            setPositionZInternal(z);
            setPositionTInternal(t);
            xy.imageCache.run();

            orthoViewPanel.paintAll(g);

            setPositionZInternal(oldZ);
            setPositionZInternal(oldT);

            return snap;
        }

        @SuppressWarnings("SuspiciousSystemArraycopy")
        public IcyBufferedImage getCurrentImage(final DimensionId fixedDim) {
            final Sequence seq = getSequence();
            final int sizeX = seq.getSizeX();
            final int sizeY = seq.getSizeY();
            final int sizeZ = seq.getSizeZ(posT);
            final int sizeC = seq.getSizeC();

            if (sizeZ == 0) return null;

            switch (fixedDim) {
                case Z:
                    return super.getImage(posT, posZ, -1);

                case Y: {
                    if (posY == -1) return null;

                    // create the XZ side view
                    final IcyBufferedImage xzImage = new IcyBufferedImage(sizeX, sizeZ, sizeC, seq.getDataType());

                    final int inY = sizeX * posY;
                    int out_offset = 0;

                    // field only used for debugging purposes
                    int inSize = 0, outSize = 0;

                    try {
                        final Object in_z_c_xy = seq.getDataXYCZ(posT);
                        final Object out_c_xy = xzImage.getDataXYC();

                        for (int z = 0; z < sizeZ; z++) {
                            final Object in_c_xy = Array.get(in_z_c_xy, z);

                            // handle missing slices
                            if (in_c_xy == null) continue;

                            out_offset = z * sizeX;

                            for (int c = 0; c < sizeC; c++) {
                                final Object in_xy = Array.get(in_c_xy, c);
                                inSize = Array.getLength(in_xy);
                                final Object out_xy = Array.get(out_c_xy, c);
                                outSize = Array.getLength(out_xy);
                                System.arraycopy(in_xy, inY, out_xy, out_offset, sizeX);
                            }
                        }
                        return xzImage;
                    }
                    catch (final ArrayIndexOutOfBoundsException e) {
                        throw new ArrayIndexOutOfBoundsException(
                                "cannot copy from [size=" + inSize + ",off=" + inY + "] to [size=" + outSize + ",off=" + out_offset + "] with size " + sizeX);
                    }
                }
                case X: {
                    // create the ZY side view
                    final IcyBufferedImage zyImage = new IcyBufferedImage(sizeZ, sizeY, sizeC, seq.getDataType());

                    final Object in_z_c_xy = seq.getDataXYCZ(posT);
                    final Object out_c_xy = zyImage.getDataXYC();

                    // wait for buffers
                    ThreadUtil.sleep(20);

                    for (int z = 0; z < sizeZ; z++) {
                        final Object in_c_xy = Array.get(in_z_c_xy, z);

                        // handle missing slices
                        if (in_c_xy == null) continue;

                        for (int c = 0; c < sizeC; c++) {
                            final Object in_xy = Array.get(in_c_xy, c);
                            final Object out_xy = Array.get(out_c_xy, c);

                            for (int y = 0, in_offset = posX, out_off = z; y < sizeY; y++, in_offset += sizeX, out_off += sizeZ) {
                                final Object pixelIN = Array.get(in_xy, in_offset);
                                Array.set(out_xy, out_off, pixelIN);
                            }
                        }
                    }

                    return zyImage;
                }

                default:
                    throw new UnsupportedOperationException();
            }
        }

        @Override
        public int getPositionC() {
            return -1;
        }

        @Override
        public void repaint() {
            super.repaint();
            if (xy != null) xy.repaint();
            if (zy != null) zy.repaint();
            if (xz != null) xz.repaint();
        }

        @Override
        public void keyReleased(final KeyEvent e) {
            super.keyReleased(e);
            repaint();
        }

        @SuppressWarnings("unused")
        public class OrthoView extends JPanel implements MouseWheelListener, MouseListener, MouseMotionListener {
            private Timer refreshTimer;

            public class ImageCache implements Runnable {
                /**
                 * image cache
                 */
                private BufferedImage imageCache;

                /**
                 * processor
                 */
                private final SingleProcessor processor;
                /**
                 * internals
                 */
                private boolean needRebuild;

                public ImageCache() {
                    super();

                    processor = new SingleProcessor(true, "OrthoView renderer");
                    // we want the processor to stay alive for sometime
                    processor.setKeepAliveTime(5, TimeUnit.MINUTES);

                    imageCache = null;
                    needRebuild = true;
                    // build cache
                    processor.submit(this);
                }

                public void invalidCache() {
                    needRebuild = true;
                }

                public boolean isValid() {
                    return !needRebuild;
                }

                public boolean isProcessing() {
                    return processor.isProcessing();
                }

                public void refresh() {
                    if (needRebuild)
                        // rebuild cache
                        processor.submit(this);

                    // just repaint
                    repaint();
                }

                public BufferedImage getImage() {
                    return imageCache;
                }

                @Override
                public void run() {
                    // important to set it to false at beginning
                    needRebuild = false;

                    final IcyBufferedImage img = getCurrentImage(currentDimension);

                    if (img != null) {
                        try {
                            imageCache = IcyBufferedImageUtil.toBufferedImage(img, imageCache, getLut());
                        }
                        catch (final InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    else imageCache = null;

                    // repaint now
                    repaint();
                }
            }

            /**
             * Image cache
             */
            final ImageCache imageCache;

            /**
             * internals
             */
            private final Font font;

            public final DimensionId currentDimension;

            private final Point5D.Double mousePosition = new Point5D.Double(getPositionX(), getPositionY(), getPositionZ(), getPositionT(), getPositionC());

            public OrthoView(final DimensionId dim) {
                super();

                this.currentDimension = dim;

                imageCache = new ImageCache();

                font = new Font("Arial", Font.BOLD, 16);

                addMouseListener(this);
                addMouseMotionListener(this);
                addMouseWheelListener(this);
            }

            @Override
            public void mouseClicked(final MouseEvent e) {
                // send mouse event to painters first
                for (final Layer layer : getVisibleLayers())
                    layer.getOverlay().mouseClick(e, mousePosition, OrthoCanvas.this);
            }

            @Override
            public void mousePressed(final MouseEvent e) {
                // send mouse event to painters first
                for (final Layer layer : getVisibleLayers())
                    layer.getOverlay().mousePressed(e, mousePosition, OrthoCanvas.this);

                mousePositionChanged(currentDimension, e.getX(), e.getY());
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                // send mouse event to painters after
                for (final Layer layer : getVisibleLayers())
                    layer.getOverlay().mouseReleased(e, mousePosition, OrthoCanvas.this);
            }

            @Override
            public void mouseEntered(final MouseEvent e) {
            }

            @Override
            public void mouseExited(final MouseEvent e) {
            }

            @Override
            public void mouseMoved(@NotNull final MouseEvent e) {
                final Point2D.Double p = canvasToImage(e.getPoint());
                mousePosition.x = p.x / zoom;
                mousePosition.y = p.y / zoom;

                // send mouse event to painters first
                for (final Layer layer : getVisibleLayers())
                    layer.getOverlay().mouseMove(e, mousePosition, OrthoCanvas.this);

                repaint();
            }

            @Override
            public void mouseDragged(@NotNull final MouseEvent e) {
                final Point2D.Double p = canvasToImage(e.getPoint());
                final Point5D.Double p5 = new Point5D.Double(p.x / zoom, p.y / zoom, mousePosition.z, mousePosition.t, mousePosition.c);
                // mousePosition.y = p.y;

                // send mouse event to painters after
                for (final Layer layer : getVisibleLayers())
                    layer.getOverlay().mouseDrag(e, p5, OrthoCanvas.this);

                mousePositionChanged(currentDimension, e.getX(), e.getY());
            }

            @Override
            public void mouseWheelMoved(final MouseWheelEvent e) {
            }

            @Override
            protected void paintComponent(final Graphics g) {
                // pre-paint
                super.paintComponent(g);

                // check if the image data exists

                final Sequence seq = getSequence();

                final double sizeZ = seq.getSizeZ(posT);
                double scaleX = seq.getPixelSizeZ() / seq.getPixelSizeX();
                if (Double.isNaN(scaleX) || scaleX == 0) scaleX = 1.0;
                double scaleY = seq.getPixelSizeZ() / seq.getPixelSizeY();
                if (Double.isNaN(scaleY) || scaleY == 0) scaleY = 1.0;

                final BufferedImage img = imageCache.getImage();

                if (img != null) {
                    // paint the image data

                    final Graphics2D g2 = (Graphics2D) g.create();

                    g2.scale(zoom, zoom);

                    if (CanvasPreferences.getFiltering()) {
                        if (getScaleX() < 4d && getScaleY() < 4d) {
                            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        }
                        else {
                            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                        }
                    }

                    final AffineTransform trans = new AffineTransform();
                    if (currentDimension == DimensionId.X) {
                        trans.scale(scaleX, 1.0);
                    }
                    else if (currentDimension == DimensionId.Y) {
                        trans.scale(1.0, scaleY);
                    }

                    g2.drawImage(img, trans, null);

                    // paint the layers

                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    final ArrayList<Layer> layers = getVisibleLayers();

                    // draw them in inverse order to have first painter event at top
                    // every layer but the first (i.e. no image, we draw it ourselves)
                    for (int i = layers.size() - 2; i >= 0; i--) {
                        final Layer layer = layers.get(i);

                        if (!layer.isVisible()) continue;

                        final float alpha = layer.getOpacity();

                        if (alpha != 1f) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                        else g2.setComposite(AlphaComposite.SrcOver);

                        if (currentDimension == DimensionId.Z) {
                            layer.getOverlay().paint(g2, seq, OrthoCanvas.this);
                        }
                        else {
                            // on side views, check if ROI can be painted
                            final ROI roi = layer.getAttachedROI();

                            if (roi instanceof ROI2D) {
                                final Color color = ((ROI.ROIPainter) layer.getOverlay()).getDisplayColor();
                                final double stroke = ((ROI.ROIPainter) layer.getOverlay()).getStroke();

                                final Rectangle rect = ((ROI2D) roi).getBounds();
                                g2.setColor(color);
                                g2.setStroke(new BasicStroke((float) ROI.getAdjustedStroke(OrthoCanvas.this, stroke + 2d)));

                                if (currentDimension == DimensionId.X) {
                                    // YZ view
                                    rect.x = 0;
                                    rect.width = getWidth();
                                    rect.width /= (int) zoom;
                                }
                                else {
                                    // XZ view
                                    rect.y = 0;
                                    rect.height = getHeight();
                                    rect.height /= (int) zoom;
                                }

                                // draw border black line
                                g2.setStroke(new BasicStroke((float) ROI.getAdjustedStroke(OrthoCanvas.this, stroke + (roi.isSelected() ? 2d : 1d))));

                                g2.setColor(Color.black);
                                g2.draw(rect);

                                // draw internal border
                                g2.setColor(color);
                                g2.setStroke(new BasicStroke((float) ROI.getAdjustedStroke(OrthoCanvas.this, stroke + (roi.isSelected() ? 1d : 0d))));
                                g2.draw(rect);
                            }
                        }
                    }

                    // draw cross hair

                    if (crossHairVisible) {
                        g2.setStroke(new BasicStroke((float) (zoom > 1 ? 1.0 / zoom : zoom)));
                        g2.setColor(Color.white);
                        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));

                        switch (currentDimension) {
                            case Z:
                                if (zoom > 1) {
                                    g2.draw(new Rectangle2D.Double(posX, 0, 1, seq.getHeight()));
                                    g2.draw(new Rectangle2D.Double(0, posY, seq.getWidth(), 1));
                                }
                                else {
                                    g2.drawLine(posX, 0, posX, seq.getHeight());
                                    g2.drawLine(0, posY, seq.getWidth(), posY);
                                }
                                break;

                            case X:
                                if (zoom > 1) {
                                    final int x = (int) Math.round(posZ * scaleX);
                                    g2.draw(new Rectangle2D.Double(0, posY, (int) (seq.getSizeZ() * scaleX), 1));
                                    g2.draw(new Rectangle2D.Double(x, 0, scaleX, seq.getHeight()));
                                }
                                else {
                                    g2.drawLine(0, posY, (int) (seq.getSizeZ() * scaleX), posY);
                                    final int x = (int) Math.round(posZ * scaleX + scaleX * 0.5);
                                    g2.setStroke(new BasicStroke((float) (scaleX * zoom)));
                                    g2.drawLine(x, 0, x, seq.getHeight());
                                }
                                break;

                            case Y:
                                if (zoom > 1) {
                                    final int y = (int) Math.round(posZ * scaleY);
                                    g2.draw(new Rectangle2D.Double(0, y, seq.getWidth(), scaleY));
                                    g2.draw(new Rectangle2D.Double(posX, 0, 1, seq.getHeight()));
                                }
                                else {
                                    g2.drawLine(posX, 0, posX, (int) (seq.getSizeZ() * scaleX));
                                    final int y = (int) Math.round(posZ * scaleY + scaleY * 0.5);
                                    g2.setStroke(new BasicStroke((float) (scaleY * zoom)));
                                    g2.drawLine(0, y, seq.getWidth(), y);
                                }
                                break;

                            default:
                                break;
                        }
                    }

                    g2.dispose();
                }
                else {
                    final Graphics2D g2 = (Graphics2D) g.create();

                    g2.setFont(font);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    if (OrthoCanvas.this.getCurrentImage() != null)
                        // cache not yet built
                        drawTextCenter(g2, "Loading...", 0.8f);
                    else
                        // no image
                        drawTextCenter(g2, " No image ", 0.8f);

                    g2.dispose();
                }

                // image or layers changed during repaint --> refresh again

                if (!isCacheValid()) refresh();

                // if (!isCacheValid())
                // refresh();
                // cache is being rebuild --> refresh to show progression
                // else if (imageCache.isProcessing()) refreshLater(100);
            }

            public void drawTextBottomRight(final Graphics2D g, final String text, final float alpha) {
                final Rectangle2D rect = GraphicsUtil.getStringBounds(g, text);
                final int w = (int) rect.getWidth();
                final int h = (int) rect.getHeight();
                final int x = getWidth() - (w + 8 + 2);
                final int y = getHeight() - (h + 8 + 2);

                g.setColor(Color.gray);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.fillRoundRect(x, y, w + 8, h + 8, 8, 8);

                g.setColor(Color.white);
                g.drawString(text, x + 4, y + 2 + h);
            }

            public void drawTextTopRight(final Graphics2D g, final String text, final float alpha) {
                final Rectangle2D rect = GraphicsUtil.getStringBounds(g, text);
                final int w = (int) rect.getWidth();
                final int h = (int) rect.getHeight();
                final int x = getWidth() - (w + 8 + 2);
                final int y = 2;

                g.setColor(Color.gray);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.fillRoundRect(x, y, w + 8, h + 8, 8, 8);

                g.setColor(Color.white);
                g.drawString(text, x + 4, y + 2 + h);
            }

            public void drawTextCenter(final Graphics2D g, final String text, final float alpha) {
                final Rectangle2D rect = GraphicsUtil.getStringBounds(g, text);
                final int w = (int) rect.getWidth();
                final int h = (int) rect.getHeight();
                final int x = (getWidth() - (w + 8 + 2)) / 2;
                final int y = (getHeight() - (h + 8 + 2)) / 2;

                g.setColor(Color.gray);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.fillRoundRect(x, y, w + 8, h + 8, 8, 8);

                g.setColor(Color.white);
                g.drawString(text, x + 4, y + 2 + h);
            }

            @Override
            public void repaint() {
                super.repaint();
            }

            public void refresh() {
                imageCache.refresh();
            }

            /**
             * Refresh in sometime
             */
            public void refreshLater(final int milli) {
                refreshTimer.setInitialDelay(milli);
                refreshTimer.start();
            }

            public void imageChanged() {
                imageCache.invalidCache();
            }

            public void layersChanged() {

            }

            public boolean isCacheValid() {
                return imageCache.isValid();
            }
        }

        @Override
        public Component getViewComponent() {
            return null;
        }
    }
}
