package org.example.gui.components;

import java.awt.*;
import java.util.List;

/**
 * Loading indicator with a skeleton of a result list and its buttons.
 */
public class LoadingListPanel extends LoadingPanel {

    private static final int ROW_HEIGHT = 24;
    private static final int LIST_PADDING = 8;

    private static final int MIN_NAME_BAR_WIDTH = 70;
    private static final int MAX_NAME_BAR_WIDTH = 170;
    private static final int NAME_IRI_GAP = 24;

    private static final int BUTTON_HEIGHT = 28;
    private static final int BUTTON_GAP = 8;
    private static final int[] BUTTON_WIDTHS = {110, 70};

    public LoadingListPanel(String title, List<String> stepLabels) {
        super(title, stepLabels);
    }

    @Override
    protected void paintSkeleton(Graphics2D g2, int startY, int contentWidth, int availableHeight) {
        Color border = skeletonBorderColor();
        Color base = skeletonBaseColor();
        Color shimmer = shimmerColor();

        int listHeight = Math.max(ROW_HEIGHT + 2 * LIST_PADDING, availableHeight - BUTTON_HEIGHT - 10);

        paintPaneFrame(g2, 0, startY, contentWidth, listHeight, border);

        int totalTravel = contentWidth + SHIMMER_WIDTH;
        int globalShimmerX = (int) ((long) getFrame() * 5 % totalTravel) - SHIMMER_WIDTH;

        int rowCount = (listHeight - 2 * LIST_PADDING) / ROW_HEIGHT;
        int rowsY = startY + LIST_PADDING;

        for (int i = 0; i < rowCount; i++) {
            int rowY = rowsY + i * ROW_HEIGHT + 6;
            int shimmerX = globalShimmerX + i * DIAGONAL_OFFSET;

            int nameWidth = stableRandomWidth(i, MIN_NAME_BAR_WIDTH, MAX_NAME_BAR_WIDTH);
            paintSkeletonBar(g2, LIST_PADDING, rowY, nameWidth, 11, 6, shimmerX, base, shimmer);

            // the IRI is rendered next to the name
            int iriX = LIST_PADDING + nameWidth + NAME_IRI_GAP;
            int iriWidth = Math.min(
                    stableRandomWidth(i + 50, 180, 300),
                    contentWidth - iriX - LIST_PADDING
            );

            if (iriWidth > 20) {
                paintSkeletonBar(g2, iriX, rowY, iriWidth, 11, 6, shimmerX, withAlpha(base, 150), shimmer);
            }
        }

        int buttonY = startY + listHeight + 10;
        int buttonX = contentWidth;

        for (int i = BUTTON_WIDTHS.length - 1; i >= 0; i--) {
            buttonX -= BUTTON_WIDTHS[i];
            paintSkeletonBar(g2, buttonX, buttonY, BUTTON_WIDTHS[i], BUTTON_HEIGHT, 8, shimmerPositionFor(contentWidth), base, shimmer);
            buttonX -= BUTTON_GAP;
        }
    }
}
