package org.example.gui.components;

import java.awt.*;
import java.util.List;

/**
 * Loading indicator for the class hierarchy, with a skeleton of the class tree and the list of
 * selected classes.
 */
public class LoadingTreePanel extends LoadingPanel {

    private static final int LEFT_ROW_COUNT = 12;
    private static final int RIGHT_ROW_COUNT = 7;

    private static final int ROW_HEIGHT = 26;
    private static final int INDENT_STEP = 20;

    private static final int MIN_ROW_BAR_WIDTH = 45;
    private static final int MAX_ROW_BAR_WIDTH = 170;

    private static final int TOOLBAR_HEIGHT = 40;
    private static final int SEARCH_HEIGHT = 24;
    private static final int BUTTON_SIZE = 30;
    private static final int SPLIT_GAP = 14;

    private static final List<String> STEP_LABELS = List.of("Loading ontology", "Starting reasoner", "Precomputing class hierarchy", "Building class tree");

    public LoadingTreePanel() {
        super("Loading Food Class Hierarchy", STEP_LABELS);
    }

    public void setStatus(String status) {
        if (status == null) return;

        String lower = status.toLowerCase();

        if (lower.contains("loading")) {
            setCurrentStep(0);
        } else if (lower.contains("reasoner")) {
            setCurrentStep(1);
        } else if (lower.contains("precomputing") || lower.contains("hierarchy")) {
            setCurrentStep(2);
        } else if (lower.contains("building")) {
            setCurrentStep(3);
        }
    }

    @Override
    protected void paintSkeleton(Graphics2D g2, int startY, int contentWidth, int availableHeight) {
        Color border = skeletonBorderColor();
        Color base = skeletonBaseColor();
        Color shimmer = shimmerColor();

        int leftW = Math.max(260, (int) ((contentWidth - SPLIT_GAP) * 0.62));
        int rightW = Math.max(180, contentWidth - leftW - SPLIT_GAP);

        if (leftW + SPLIT_GAP + rightW > contentWidth) {
            rightW = Math.max(120, contentWidth - leftW - SPLIT_GAP);
        }

        int leftX = 0;
        int rightX = leftX + leftW + SPLIT_GAP;

        int paneHeight = Math.max(TOOLBAR_HEIGHT + LEFT_ROW_COUNT * ROW_HEIGHT + 10, TOOLBAR_HEIGHT + RIGHT_ROW_COUNT * ROW_HEIGHT + 10);

        paintPaneFrame(g2, leftX, startY, leftW, paneHeight, border);
        paintPaneFrame(g2, rightX, startY, rightW, paneHeight, border);

        paintToolbarSkeleton(g2, leftX, startY, leftW, true, base, shimmer);
        paintToolbarSkeleton(g2, rightX, startY, rightW, false, base, shimmer);

        int rowsY = startY + TOOLBAR_HEIGHT + 8;

        int totalTravel = contentWidth + SHIMMER_WIDTH;
        int globalShimmerX = (int) ((long) getFrame() * 5 % totalTravel) - SHIMMER_WIDTH;

        paintTreeRowsSkeleton(g2, leftX + 8, rowsY, leftW - 16, globalShimmerX, base, shimmer);

        paintListRowsSkeleton(g2, rightX + 8, rowsY, rightW - 16, globalShimmerX + 60, base, shimmer);
    }

    private void paintToolbarSkeleton(Graphics2D g2, int x, int y, int width, boolean addButton, Color base, Color shimmer) {
        int pad = 6;
        int searchX = x + pad;
        int searchY = y + 6;
        int searchW = width - pad * 3 - BUTTON_SIZE;

        paintSkeletonBar(g2, searchX, searchY + 3, searchW, SEARCH_HEIGHT, 8, shimmerPositionFor(searchW), base, shimmer);

        int buttonX = x + width - pad - BUTTON_SIZE;
        int buttonY = y + 5;

        g2.setColor(base);
        g2.fillRoundRect(buttonX, buttonY, BUTTON_SIZE, BUTTON_SIZE, 8, 8);

        g2.setColor(withAlpha(shimmer, 130));
        g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        int cx = buttonX + BUTTON_SIZE / 2;
        int cy = buttonY + BUTTON_SIZE / 2;

        if (addButton) {
            g2.drawLine(cx, cy - 5, cx, cy + 5);
            g2.drawLine(cx - 5, cy, cx + 5, cy);
        } else {
            g2.drawLine(cx - 5, cy, cx + 5, cy);
        }

        g2.setStroke(new BasicStroke(1f));
    }

    private void paintTreeRowsSkeleton(Graphics2D g2, int x, int y, int width, int globalShimmerX, Color base, Color shimmer) {
        for (int i = 0; i < LoadingTreePanel.LEFT_ROW_COUNT; i++) {
            int depth = Math.min(i % 5, 3);

            int rowX = x + depth * INDENT_STEP;
            int rowY = y + i * ROW_HEIGHT;

            int maxAllowedWidth = Math.max(MIN_ROW_BAR_WIDTH, width - depth * INDENT_STEP - 24);
            int randomWidth = stableRandomWidth(i, MIN_ROW_BAR_WIDTH, MAX_ROW_BAR_WIDTH);
            int rowWidth = Math.min(randomWidth, maxAllowedWidth);

            paintTreeRowSkeleton(g2, rowX, rowY, rowWidth, globalShimmerX + i * DIAGONAL_OFFSET, base, shimmer);
        }
    }

    private void paintListRowsSkeleton(Graphics2D g2, int x, int y, int width, int globalShimmerX, Color base, Color shimmer) {
        for (int i = 0; i < LoadingTreePanel.RIGHT_ROW_COUNT; i++) {
            int rowY = y + i * ROW_HEIGHT;

            int randomWidth = stableRandomWidth(i + 100, MIN_ROW_BAR_WIDTH + 20, MAX_ROW_BAR_WIDTH + 40);
            int rowWidth = Math.clamp(width - 10, MIN_ROW_BAR_WIDTH, randomWidth);

            paintSkeletonBar(g2, x, rowY + 5, rowWidth, 11, 6, globalShimmerX + i * DIAGONAL_OFFSET, base, shimmer);
        }
    }

    private void paintTreeRowSkeleton(Graphics2D g2, int x, int y, int width, int shimmerX, Color base, Color shimmer) {
        int disclosureSize = 7;
        int barX = x + disclosureSize + 8;
        int barY = y + 5;
        int barH = 11;

        g2.setColor(withAlpha(base, 180));

        int[] xs = {x, x, x + disclosureSize};

        int[] ys = {y + 5, y + 5 + disclosureSize, y + 5 + disclosureSize / 2};

        g2.fillPolygon(xs, ys, 3);

        paintSkeletonBar(g2, barX, barY, width, barH, 6, shimmerX, base, shimmer);
    }
}
