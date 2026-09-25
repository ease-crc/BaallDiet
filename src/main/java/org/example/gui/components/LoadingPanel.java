package org.example.gui.components;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Animated loading indicator with a spinner, a list of steps, the elapsed time and a skeleton
 * of the content that is being loaded. Subclasses paint the skeleton.
 */
public abstract class LoadingPanel extends JPanel {

    protected static final int MARGIN_X = 6;
    protected static final int MARGIN_Y = 8;

    protected static final int SHIMMER_WIDTH = 200;
    protected static final int DIAGONAL_OFFSET = 7;

    /*
     * Peak opacity of the shimmer highlight that sweeps over the skeleton bars.
     */
    private static final int SHIMMER_PEAK_ALPHA = 130;

    private final String title;
    private final List<String> stepLabels;

    private final javax.swing.Timer animationTimer;
    private final long startTimeMs = System.currentTimeMillis();

    private int currentStep = 0;
    private int frame = 0;

    protected LoadingPanel(String title, List<String> stepLabels) {
        this.title = title;
        this.stepLabels = List.copyOf(stepLabels);

        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(MARGIN_Y, MARGIN_X, MARGIN_Y, MARGIN_X));

        animationTimer = new javax.swing.Timer(30, e -> {
            frame++;
            repaint();
        });
        animationTimer.start();
    }

    protected static int stableRandomWidth(int rowIndex, int min, int max) {
        int n = rowIndex * 1103515245 + 12345;
        n ^= n >>> 16;
        n = Math.abs(n);

        return min + n % (max - min + 1);
    }

    @SafeVarargs
    protected static <T> T coalesce(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }

        return null;
    }

    protected static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.clamp(a, 0, 255));
    }

    /**
     * @return The opaque color that results from painting the given, possibly translucent, color
     * onto the given opaque background
     */
    private static Color composite(Color c, Color background) {
        return mix(background, new Color(c.getRed(), c.getGreen(), c.getBlue()), c.getAlpha() / 255f);
    }

    private static Color mix(Color a, Color b, float ratio) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * ratio),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * ratio),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * ratio)
        );
    }

    /**
     * Marks the step with the given index as active and all previous steps as done.
     */
    public void setCurrentStep(int step) {
        currentStep = Math.clamp(step, 0, stepLabels.size());
        repaint();
    }

    public void stopAnimation() {
        animationTimer.stop();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        stopAnimation();
    }

    protected int getFrame() {
        return frame;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Graphics2D g2 = (Graphics2D) g.create();

        try {
            Insets insets = getInsets();

            g2.translate(insets.left, insets.top);

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int contentWidth = getWidth() - insets.left - insets.right;
            int contentHeight = getHeight() - insets.top - insets.bottom;

            int headerBottom = paintHeader(g2, contentWidth);

            int separatorY = headerBottom + 12;
            paintSeparator(g2, separatorY, contentWidth);

            int skeletonY = separatorY + 14;
            paintSkeleton(g2, skeletonY, contentWidth, contentHeight - skeletonY);

        } finally {
            g2.dispose();
        }
    }

    /**
     * Paints the skeleton of the content that is being loaded.
     */
    protected abstract void paintSkeleton(Graphics2D g2, int startY, int contentWidth, int availableHeight);

    /**
     * Paints the spinner, step list, and elapsed timer.
     *
     * @return the Y coordinate of the bottom edge of the header area
     */
    private int paintHeader(Graphics2D g2, int contentWidth) {
        Color textColor = coalesce(UIManager.getColor("Label.foreground"), new Color(210, 210, 210));
        Color mutedColor = coalesce(UIManager.getColor("Label.disabledForeground"), new Color(120, 120, 120));
        Color doneColor = coalesce(UIManager.getColor("Component.accentColor"), new Color(80, 180, 110));
        Color activeColor = coalesce(UIManager.getColor("Component.focusColor"), new Color(90, 150, 240));
        Color trackColor = coalesce(UIManager.getColor("Component.borderColor"), new Color(70, 70, 70));

        long secs = (System.currentTimeMillis() - startTimeMs) / 1000L;
        String elapsed = String.format("%d:%02d elapsed", secs / 60, secs % 60);

        g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
        FontMetrics fm = g2.getFontMetrics();

        g2.setColor(mutedColor);
        g2.drawString(elapsed, contentWidth - fm.stringWidth(elapsed), 14);

        int spD = 16;
        int spX = 0;
        int spY = 0;
        int spinAngle = (frame * 7) % 360;

        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        g2.setColor(trackColor);
        g2.drawArc(spX, spY, spD, spD, 0, 360);

        g2.setColor(activeColor);
        g2.drawArc(spX, spY, spD, spD, spinAngle, 90);

        g2.setStroke(new BasicStroke(1f));

        g2.setFont(getFont().deriveFont(Font.BOLD, 14f));
        g2.setColor(textColor);
        g2.drawString(title, spX + spD + 10, spY + 13);

        int stepStartY = spY + spD + 14;

        g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
        FontMetrics sfm = g2.getFontMetrics();

        int lineHeight = sfm.getHeight() + 5;
        int dotD = 8;
        int dotX = 6;

        for (int i = 0; i < stepLabels.size(); i++) {
            int dotY = stepStartY + i * lineHeight;

            boolean isDone = i < currentStep;
            boolean isActive = i == currentStep;

            if (isDone) {
                g2.setColor(doneColor);
                g2.fillOval(dotX, dotY + 2, dotD, dotD);

                g2.setColor(getBackground());
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(dotX + 1, dotY + 6, dotX + 3, dotY + 8);
                g2.drawLine(dotX + 3, dotY + 8, dotX + 7, dotY + 4);
                g2.setStroke(new BasicStroke(1f));

            } else if (isActive) {
                float pulse = 0.55f + 0.45f * (float) Math.sin(frame * 0.14);
                g2.setColor(withAlpha(activeColor, (int) (220 * pulse)));
                g2.fillOval(dotX, dotY + 2, dotD, dotD);

            } else {
                g2.setColor(mutedColor);
                g2.drawOval(dotX, dotY + 2, dotD, dotD);
            }

            g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            g2.setColor(isDone ? doneColor : isActive ? textColor : mutedColor);
            g2.drawString(stepLabels.get(i), dotX + dotD + 8, dotY + sfm.getAscent());
        }

        return stepStartY + stepLabels.size() * lineHeight;
    }

    private void paintSeparator(Graphics2D g2, int y, int contentWidth) {
        Color sep = coalesce(UIManager.getColor("Separator.foreground"), new Color(60, 60, 60));

        g2.setColor(withAlpha(sep, 80));
        g2.drawLine(0, y, contentWidth, y);
    }

    protected static Color skeletonBorderColor() {
        return coalesce(UIManager.getColor("Component.borderColor"), new Color(72, 72, 72));
    }

    /**
     * @return The color of the skeleton bars, slightly lighter than the component borders so
     * that the bars stand out from the background
     */
    protected static Color skeletonBaseColor() {
        Color background = coalesce(UIManager.getColor("Panel.background"), new Color(40, 40, 40));
        Color foreground = coalesce(UIManager.getColor("Label.foreground"), new Color(210, 210, 210));

        // border colors may be translucent, e.g., in the macOS themes
        Color border = composite(skeletonBorderColor(), background);

        return mix(border, foreground, 0.10f);
    }

    /**
     * @return The color of the shimmer highlight: the focus color, lightened towards the text
     * color so that it clearly stands out from the skeleton bars
     */
    protected static Color shimmerColor() {
        Color focus = coalesce(UIManager.getColor("Component.focusColor"), new Color(140, 140, 140));
        Color foreground = coalesce(UIManager.getColor("Label.foreground"), new Color(210, 210, 210));

        return mix(focus, foreground, 0.30f);
    }

    protected void paintPaneFrame(Graphics2D g2, int x, int y, int width, int height, Color border) {
        g2.setColor(withAlpha(border, 90));
        g2.drawRoundRect(x, y, width - 1, height - 1, 8, 8);
    }

    protected void paintSkeletonBar(Graphics2D g2, int x, int y, int width, int height, int arc, int shimmerX, Color base, Color shimmerColor) {
        g2.setColor(base);
        g2.fillRoundRect(x, y, width, height, arc, arc);

        Shape prevClip = g2.getClip();
        Paint prevPaint = g2.getPaint();

        g2.clip(new java.awt.geom.RoundRectangle2D.Float(x, y, width, height, arc, arc));

        int mid = shimmerX + SHIMMER_WIDTH / 2;
        int end = shimmerX + SHIMMER_WIDTH;

        /*
         * A GradientPaint keeps its end colors beyond its end points, so each half of the
         * shimmer must only be painted within its own range.
         */
        g2.setPaint(new GradientPaint(shimmerX, y, withAlpha(shimmerColor, 0), mid, y, withAlpha(shimmerColor, SHIMMER_PEAK_ALPHA)));
        g2.fillRect(shimmerX, y, mid - shimmerX, height);

        g2.setPaint(new GradientPaint(mid, y, withAlpha(shimmerColor, SHIMMER_PEAK_ALPHA), end, y, withAlpha(shimmerColor, 0)));
        g2.fillRect(mid, y, end - mid, height);

        g2.setPaint(prevPaint);
        g2.setClip(prevClip);
    }

    /**
     * @return The x coordinate of the shimmer for a sweep over the given width
     */
    protected int shimmerPositionFor(int width) {
        int totalTravel = width + SHIMMER_WIDTH;
        return (int) ((long) frame * 5 % totalTravel) - SHIMMER_WIDTH;
    }
}
