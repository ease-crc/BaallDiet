package org.example.gui.components.checkBoxTree;

import com.formdev.flatlaf.ui.FlatTreeUI;

import javax.swing.Icon;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.TreePath;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link FlatTreeUI} whose expand/collapse arrow highlights in the accent color on hover, and animates a 90°
 * rotation (pointing right when collapsed, down when expanded) instead of instantly swapping between two static
 * icons.
 */
class ExpandArrowTreeUI extends FlatTreeUI {

    private static final int ANIMATION_DURATION_MS = 150;
    private static final int ANIMATION_TICK_MS = 15;

    /**
     * How far, in pixels, the mouse may be from the arrow's center and still count as hovering it.
     */
    private static final int HOVER_HIT_RADIUS = 8;

    private static final float ANGLE_COLLAPSED = 0f;
    private static final float ANGLE_EXPANDED = 90f;

    private final Map<TreePath, Animation> animations = new HashMap<>();
    private final ArrowIcon arrowIcon = new ArrowIcon();

    private final TreeExpansionListener expansionListener = new TreeExpansionListener() {
        @Override
        public void treeExpanded(TreeExpansionEvent event) {
            onToggled(event.getPath(), true);
        }

        @Override
        public void treeCollapsed(TreeExpansionEvent event) {
            onToggled(event.getPath(), false);
        }
    };

    private final HoverTracker hoverTracker = new HoverTracker();

    private Timer animationTimer;
    private TreePath hoveredPath;

    @Override
    protected void installListeners() {
        super.installListeners();

        tree.addTreeExpansionListener(expansionListener);
        tree.addMouseMotionListener(hoverTracker);
        tree.addMouseListener(hoverTracker);
    }

    /*
     * A live theme switch discards and recreates every component's UI delegate (see FilterableTree), so this runs
     * far more often than a typical Swing app; cleaning up properly avoids piling up listeners and timers.
     */
    @Override
    protected void uninstallListeners() {
        tree.removeTreeExpansionListener(expansionListener);
        tree.removeMouseMotionListener(hoverTracker);
        tree.removeMouseListener(hoverTracker);

        if (animationTimer != null) {
            animationTimer.stop();
            animationTimer = null;
        }

        super.uninstallListeners();
    }

    @Override
    protected void paintExpandControl(
            Graphics g,
            Rectangle clipBounds,
            Insets insets,
            Rectangle bounds,
            TreePath path,
            int row,
            boolean isExpanded,
            boolean hasBeenExpanded,
            boolean isLeaf
    ) {
        if (isLeaf) {
            return;
        }

        arrowIcon.angle = angleFor(path, isExpanded);
        arrowIcon.color = arrowColor(path.equals(hoveredPath), tree.isPathSelected(path));

        drawCentered(tree, g, arrowIcon, knobX(bounds), knobY(bounds));
    }

    /**
     * The accent color used for a hovered arrow reads poorly on a selected row, whose background is already an
     * accent-derived blue. Use the selection's own foreground color there instead, since that is specifically
     * chosen by the look and feel to stay readable on top of it.
     */
    private Color arrowColor(boolean hovered, boolean selected) {
        if (hovered) {
            if (selected && selectionForeground != null) {
                return selectionForeground;
            }

            Color accent = UIManager.getColor("Component.focusColor");

            if (accent == null) {
                accent = UIManager.getColor("Component.accentColor");
            }

            if (accent != null) {
                return accent;
            }
        }

        Color normal = UIManager.getColor("Tree.icon.collapsedColor");
        return normal != null ? normal : UIManager.getColor("Tree.foreground");
    }

    private int knobX(Rectangle bounds) {
        return tree.getComponentOrientation().isLeftToRight()
                ? bounds.x - getRightChildIndent() + 1
                : bounds.x + bounds.width + getRightChildIndent() - 1;
    }

    private int knobY(Rectangle bounds) {
        return bounds.y + bounds.height / 2;
    }

    /**
     * @return The arrow's current angle: mid-animation if one is running for this path, or resting at its target
     * angle otherwise.
     */
    private float angleFor(TreePath path, boolean isExpanded) {
        Animation animation = animations.get(path);

        if (animation == null) {
            return isExpanded ? ANGLE_EXPANDED : ANGLE_COLLAPSED;
        }

        float progress = Math.min(1f, (System.currentTimeMillis() - animation.startTimeMs) / (float) ANIMATION_DURATION_MS);
        return animation.startAngle + (animation.targetAngle - animation.startAngle) * progress;
    }

    /**
     * Starts animating the given path's arrow to its new resting angle, continuing smoothly from wherever it
     * currently is if it was already mid-animation (e.g. rapid toggling).
     */
    private void onToggled(TreePath path, boolean expanded) {
        float startAngle = angleFor(path, !expanded);
        float targetAngle = expanded ? ANGLE_EXPANDED : ANGLE_COLLAPSED;

        animations.put(path, new Animation(startAngle, targetAngle, System.currentTimeMillis()));

        if (animationTimer == null) {
            animationTimer = new Timer(ANIMATION_TICK_MS, e -> {
                long now = System.currentTimeMillis();
                animations.values().removeIf(a -> now - a.startTimeMs >= ANIMATION_DURATION_MS);

                tree.repaint();

                if (animations.isEmpty()) {
                    animationTimer.stop();
                    animationTimer = null;
                }
            });
            animationTimer.start();
        }
    }

    private void setHovered(TreePath path) {
        if (Objects.equals(hoveredPath, path)) {
            return;
        }

        TreePath previous = hoveredPath;
        hoveredPath = path;

        repaintKnobArea(previous);
        repaintKnobArea(path);
    }

    private void repaintKnobArea(TreePath path) {
        if (path == null) {
            return;
        }

        Rectangle bounds = tree.getPathBounds(path);

        if (bounds != null) {
            int knobX = knobX(bounds);
            tree.repaint(knobX - HOVER_HIT_RADIUS, bounds.y, HOVER_HIT_RADIUS * 2, bounds.height);
        }
    }

    private static final class Animation {

        private final float startAngle;
        private final float targetAngle;
        private final long startTimeMs;

        private Animation(float startAngle, float targetAngle, long startTimeMs) {
            this.startAngle = startAngle;
            this.targetAngle = targetAngle;
            this.startTimeMs = startTimeMs;
        }
    }

    private static final class ArrowIcon implements Icon {

        private static final int SIZE = 11;

        private float angle;
        private Color color;

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();

            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(color);
                g2.rotate(Math.toRadians(angle), x + SIZE / 2.0, y + SIZE / 2.0);

                // a right-pointing chevron; rotating 90° clockwise turns it to point down
                Path2D path = new Path2D.Float();
                path.moveTo(x + 3.5, y + 1.5);
                path.lineTo(x + 7.5, y + 5.5);
                path.lineTo(x + 3.5, y + 9.5);
                g2.draw(path);
            } finally {
                g2.dispose();
            }
        }
    }

    private final class HoverTracker extends MouseAdapter {

        @Override
        public void mouseMoved(MouseEvent e) {
            updateHover(e.getX(), e.getY());
        }

        @Override
        public void mouseExited(MouseEvent e) {
            setHovered(null);
        }

        private void updateHover(int x, int y) {
            int row = tree.getClosestRowForLocation(x, y);
            TreePath path = row >= 0 ? tree.getPathForRow(row) : null;

            if (path != null) {
                Rectangle bounds = tree.getPathBounds(path);
                boolean isLeaf = tree.getModel().isLeaf(path.getLastPathComponent());

                if (bounds != null && !isLeaf
                        && Math.abs(x - knobX(bounds)) <= HOVER_HIT_RADIUS
                        && Math.abs(y - knobY(bounds)) <= HOVER_HIT_RADIUS) {
                    setHovered(path);
                    return;
                }
            }

            setHovered(null);
        }
    }
}
