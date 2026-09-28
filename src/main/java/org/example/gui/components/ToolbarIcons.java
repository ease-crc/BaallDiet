package org.example.gui.components;

import com.formdev.flatlaf.FlatClientProperties;
import lombok.val;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;

/**
 * Small monochrome toolbar icons and matching button/search-field styling, shared by every panel that offers
 * add/edit/remove/search actions (the class tree, the dinner party panel, and the result popups), so that all of
 * them look and behave the same way.
 */
public final class ToolbarIcons {

    private ToolbarIcons() {
    }

    public static JButton createToolbarButton(Icon icon, String tooltip, Runnable action) {
        val button = new JButton(icon);

        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setPreferredSize(new Dimension(30, 30));
        button.setMinimumSize(new Dimension(30, 30));
        button.setMaximumSize(new Dimension(30, 30));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.putClientProperty("JComponent.arc", 8);
        button.addActionListener(e -> action.run());

        return button;
    }

    public static void configureSearchField(JTextField field, String placeholder) {
        field.putClientProperty("JTextField.placeholderText", placeholder);
        field.putClientProperty("JTextField.leadingIcon", new SearchIcon());
        field.putClientProperty("JTextField.showClearButton", true);
        field.putClientProperty("JComponent.arc", 8);
        field.setBackground(searchFieldBackground());
    }

    /**
     * A row with a search field on the left and a trailing component (typically a {@link JPanel} of toolbar
     * buttons) on the right, as used by every search bar in the application.
     */
    public static JPanel buildSearchToolbar(JTextField searchField, JComponent trailing) {
        JPanel toolbar = new JPanel(new BorderLayout(6, 0));
        toolbar.add(searchField, BorderLayout.CENTER);
        toolbar.add(trailing, BorderLayout.EAST);

        return toolbar;
    }

    /**
     * A bold title label above the given content, as used by every titled list/tree section in the application.
     */
    public static JPanel wrapWithTitle(String title, Component content) {
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);

        return panel;
    }

    /**
     * A bold title on the background of lists and trees, spaced above and to the left like the search toolbar below
     * it, so that title and content look like one surface. Use it above a {@link FilterableList} or a tree instead of
     * {@link #wrapWithTitle}, which puts the title on the background of the panel.
     */
    public static JLabel createHeaderLabel(String text) {
        return new HeaderLabel(text);
    }

    /**
     * The given seamless content (see {@link #createHeaderLabel}) below a title.
     */
    public static JPanel wrapWithHeader(String title, Component content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(createHeaderLabel(title), BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Sets its look in {@link #updateUI()}, so that it follows a switch of the theme.
     */
    private static final class HeaderLabel extends JLabel {

        private HeaderLabel(String text) {
            super(text);
        }

        @Override
        public void updateUI() {
            super.updateUI();

            setOpaque(true);
            setBackground(UIManager.getColor("List.background"));
            setFont(getFont().deriveFont(Font.BOLD));
            // the same spacing as the search toolbar of a list or tree: 6 to the left and about 6 above
            setBorder(BorderFactory.createEmptyBorder(8, 6, 2, 6));
        }
    }

    /**
     * The FlatLaf style of the dividers: the panel background mixed with gray, so that they are visible next to the
     * seamless white lists and trees in both themes. The color is opaque on purpose. The border color of the themes is
     * translucent, and a translucent color that is painted again on top of itself, e.g., while a split pane is
     * dragged, gets darker and darker.
     */
    private static final String DIVIDER_STYLE = "background: mix($Panel.background,$Label.disabledForeground,75%)";

    /**
     * Makes the divider of the split pane easier to see: a bit wider than the default and darker than the panel, with a grip.
     */
    public static void styleSplitPane(JSplitPane splitPane) {
        splitPane.putClientProperty(
                FlatClientProperties.STYLE,
                "dividerSize: 6; style: grip; gripColor: $Label.disabledForeground; " + DIVIDER_STYLE
        );
    }

    /**
     * A one pixel outline in the border color of the current theme, which is drawn opaque: the border color of the
     * themes is translucent, and repainting a translucent line on top of itself makes it darker and darker.
     */
    public static Border outlineBorder() {
        return new AbstractBorder() {
            @Override
            public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
                Color background = UIManager.getColor("Panel.background");
                Color line = UIManager.getColor("Component.borderColor");

                if (background == null || line == null) {
                    return;
                }

                float alpha = line.getAlpha() / 255f;
                g.setColor(new Color(
                        Math.round(line.getRed() * alpha + background.getRed() * (1 - alpha)),
                        Math.round(line.getGreen() * alpha + background.getGreen() * (1 - alpha)),
                        Math.round(line.getBlue() * alpha + background.getBlue() * (1 - alpha))
                ));
                g.drawRect(x, y, width - 1, height - 1);
            }

            @Override
            public Insets getBorderInsets(Component c) {
                return new Insets(1, 1, 1, 1);
            }

            @Override
            public Insets getBorderInsets(Component c, Insets insets) {
                insets.set(1, 1, 1, 1);
                return insets;
            }
        };
    }

    /**
     * A small "(?)" icon that shows the given text in a tooltip on hover, used wherever an explanation would
     * otherwise take up permanent space as a label.
     */
    public static JLabel createHelpIcon(String tooltipText) {
        JLabel label = new JLabel(new HelpIcon());
        label.setToolTipText(wrapTooltipHtml(tooltipText));
        return label;
    }

    /**
     * Tooltips render plain text on one line; wrapping it in HTML lets it wrap onto multiple lines instead.
     */
    private static String wrapTooltipHtml(String text) {
        String escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        return "<html><div style='width:260px;'>" + escaped + "</div></html>";
    }

    public static Color panelBackground() {
        Color c = UIManager.getColor("Panel.background");
        return c != null ? c : Color.LIGHT_GRAY;
    }

    public static Color searchFieldBackground() {
        Color c = UIManager.getColor("TextField.background");

        if (c == null) {
            c = UIManager.getColor("TextField.inactiveBackground");
        }

        return c != null ? c : panelBackground();
    }

    private static Color iconColor() {
        Color c = UIManager.getColor("TextField.placeholderForeground");

        if (c == null) {
            c = UIManager.getColor("Component.iconColor");
        }

        if (c == null) {
            c = UIManager.getColor("Label.disabledForeground");
        }

        if (c == null) {
            c = UIManager.getColor("Label.foreground");
        }

        return c != null ? c : new Color(120, 120, 120);
    }

    private static Color iconColor(Component c) {
        if (c instanceof JButton button) {
            val model = button.getModel();

            if (model.isPressed() || model.isRollover()) {
                Color hover = UIManager.getColor("Button.hoverForeground");

                if (hover == null) {
                    hover = UIManager.getColor("Button.selectedForeground");
                }

                if (hover == null) {
                    hover = UIManager.getColor("Button.default.foreground");
                }

                return hover != null ? hover : Color.WHITE;
            }
        }

        return iconColor();
    }

    public abstract static class AbstractToolbarIcon implements Icon {

        protected static final int SIZE = 16;

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        protected Graphics2D prepare(Component c, Graphics g) {
            val g2 = (Graphics2D) g.create();

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            g2.setColor(iconColor(c));
            g2.setStroke(new BasicStroke(
                    1.15f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
            ));

            return g2;
        }
    }

    public static class SearchIcon extends AbstractToolbarIcon {

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            val g2 = prepare(c, g);

            try {
                g2.drawOval(x + 3, y + 3, 7, 7);
                g2.drawLine(x + 9, y + 9, x + 12, y + 12);
            } finally {
                g2.dispose();
            }
        }
    }

    public static class AddIcon extends AbstractToolbarIcon {

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            val g2 = prepare(c, g);

            try {
                g2.drawRoundRect(x + 3, y + 3, 10, 10, 3, 3);
                g2.drawLine(x + 8, y + 5, x + 8, y + 11);
                g2.drawLine(x + 5, y + 8, x + 11, y + 8);
            } finally {
                g2.dispose();
            }
        }
    }

    public static class RemoveIcon extends AbstractToolbarIcon {

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            val g2 = prepare(c, g);

            try {
                g2.drawRoundRect(x + 3, y + 3, 10, 10, 3, 3);
                g2.drawLine(x + 5, y + 8, x + 11, y + 8);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * A pencil, used for "edit" actions.
     */
    public static class EditIcon extends AbstractToolbarIcon {

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            val g2 = prepare(c, g);

            try {
                g2.drawLine(x + 3, y + 13, x + 4, y + 10);
                g2.drawLine(x + 4, y + 10, x + 10, y + 4);
                g2.drawLine(x + 10, y + 4, x + 12, y + 6);
                g2.drawLine(x + 12, y + 6, x + 6, y + 12);
                g2.drawLine(x + 6, y + 12, x + 3, y + 13);
                g2.drawLine(x + 9, y + 5, x + 11, y + 7);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * A checkmark, used for "permit" actions.
     */
    public static class CheckIcon extends AbstractToolbarIcon {

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            val g2 = prepare(c, g);

            try {
                g2.drawLine(x + 3, y + 8, x + 6, y + 11);
                g2.drawLine(x + 6, y + 11, x + 13, y + 4);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * A circle with a diagonal slash (the universal "no" sign), used for "forbid" actions.
     */
    public static class BanIcon extends AbstractToolbarIcon {

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            val g2 = prepare(c, g);

            try {
                g2.drawOval(x + 2, y + 2, 11, 11);
                g2.drawLine(x + 4, y + 12, x + 12, y + 4);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * A five-pointed star outline, used for "favourite" actions.
     */
    public static class StarIcon extends AbstractToolbarIcon {

        private static final double CENTER = 8;
        private static final double OUTER_RADIUS = 6.5;
        private static final double INNER_RADIUS = 2.7;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            val g2 = prepare(c, g);

            try {
                int[] xs = new int[10];
                int[] ys = new int[10];

                for (int i = 0; i < 10; i++) {
                    double radius = i % 2 == 0 ? OUTER_RADIUS : INNER_RADIUS;
                    double angle = -Math.PI / 2 + i * Math.PI / 5;

                    xs[i] = (int) Math.round(x + CENTER + radius * Math.cos(angle));
                    ys[i] = (int) Math.round(y + CENTER + 0.5 + radius * Math.sin(angle));
                }

                g2.drawPolygon(xs, ys, 10);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * A circled question mark, used to mark hoverable help text.
     */
    public static class HelpIcon extends AbstractToolbarIcon {

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            val g2 = prepare(c, g);

            try {
                g2.drawOval(x + 1, y + 1, 13, 13);

                Font font = g2.getFont().deriveFont(Font.BOLD, 10f);
                String text = "?";

                /*
                 * Centering on font ascent/descent leaves a glyph without a descender (like "?") looking too low,
                 * since that space is reserved but unused. Centering on the glyph's actual visual bounds instead
                 * centers what is actually drawn.
                 */
                GlyphVector glyphVector = font.createGlyphVector(g2.getFontRenderContext(), text);
                Rectangle2D visualBounds = glyphVector.getVisualBounds();

                double textX = x + (SIZE - visualBounds.getWidth()) / 2 - visualBounds.getX();
                double textY = y + (SIZE - visualBounds.getHeight()) / 2 - visualBounds.getY();

                g2.setFont(font);
                g2.drawGlyphVector(glyphVector, (float) textX, (float) textY);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * A gear/cog, used for a "settings" action.
     */
    public static class GearIcon extends AbstractToolbarIcon {

        private static final int TEETH = 8;
        private static final double OUTER_RADIUS = 5;
        private static final double TOOTH_LENGTH = 2;
        private static final double INNER_RADIUS = 2;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            val g2 = prepare(c, g);

            try {
                double cx = x + SIZE / 2.0;
                double cy = y + SIZE / 2.0;

                for (int i = 0; i < TEETH; i++) {
                    double angle = 2 * Math.PI * i / TEETH;
                    double dx = Math.cos(angle);
                    double dy = Math.sin(angle);

                    g2.drawLine(
                            (int) Math.round(cx + OUTER_RADIUS * dx), (int) Math.round(cy + OUTER_RADIUS * dy),
                            (int) Math.round(cx + (OUTER_RADIUS + TOOTH_LENGTH) * dx), (int) Math.round(cy + (OUTER_RADIUS + TOOTH_LENGTH) * dy)
                    );
                }

                int outer = (int) Math.round(OUTER_RADIUS);
                int inner = (int) Math.round(INNER_RADIUS);
                g2.drawOval((int) Math.round(cx - outer), (int) Math.round(cy - outer), outer * 2, outer * 2);
                g2.drawOval((int) Math.round(cx - inner), (int) Math.round(cy - inner), inner * 2, inner * 2);
            } finally {
                g2.dispose();
            }
        }
    }
}
