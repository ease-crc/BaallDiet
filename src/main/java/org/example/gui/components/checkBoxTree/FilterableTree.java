package org.example.gui.components.checkBoxTree;

import lombok.Getter;
import lombok.val;
import org.example.gui.components.SimpleDocumentListener;
import org.example.gui.components.ToolbarIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A searchable {@link JTree} of {@link CheckBoxTreeNode}s, with a search toolbar (search field plus any
 * caller-supplied buttons) and an optional per-value highlight, e.g. to mark values that already have some
 * significance elsewhere in the UI (such as a food already being permitted or forbidden).
 * <p>
 * Large trees are filtered asynchronously and cancellably, since copying/filtering the whole ontology tree on
 * every keystroke would otherwise block the EDT.
 */
public class FilterableTree<T> extends JPanel {

    private static final int FILTER_DEBOUNCE_MS = 180;

    /*
     * Expanding huge filtered trees is surprisingly expensive.
     * Below this threshold we expand everything.
     * Above it we expand only a small useful prefix.
     */
    private static final int MAX_FULL_EXPAND_ROWS = 800;
    private static final int MAX_PARTIAL_EXPAND_ROWS = 120;

    private final Function<T, String> labelProvider;

    @Getter
    private CheckBoxTreeNode<T> rootNode;

    private final JTree swingTree;
    private final DefaultTreeModel treeModel;

    private final JTextField searchField = new JTextField();
    private final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));

    /*
     * IdentityHashMap is intentional:
     * tree nodes are mutable objects and should be cached by object identity,
     * not by equals/hashCode semantics.
     */
    private final Map<CheckBoxTreeNode<T>, String> renderedNodeCache = new IdentityHashMap<>();

    private String filterText = "";

    /*
     * The filter of the tree content that is currently shown. It differs from
     * filterText while a search is still running, and is used to highlight
     * the matches in the shown tree.
     */
    private String displayedFilterText = "";

    private Timer filterDebounceTimer;

    private SwingWorker<CheckBoxTreeNode<T>, Void> filterWorker;

    private Consumer<T> onActivate = value -> {
    };

    private Function<T, Color> highlightProvider = value -> null;

    public FilterableTree(CheckBoxTreeNode<T> rootNode, Function<T, String> labelProvider, String searchPlaceholder) {
        super(new BorderLayout());

        this.rootNode = Objects.requireNonNull(rootNode, "rootNode must not be null");
        this.labelProvider = Objects.requireNonNull(labelProvider, "labelProvider must not be null");

        this.treeModel = new DefaultTreeModel(rootNode);
        this.swingTree = new JTree(treeModel);

        configureTree();
        configureSearchField(searchPlaceholder);
        buttonPanel.setOpaque(false);

        val scrollPane = new JScrollPane(swingTree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setColumnHeaderView(buildToolbar());

        add(scrollPane, BorderLayout.CENTER);

        refreshImmediately("");
    }

    private void configureTree() {
        swingTree.setUI(new ExpandArrowTreeUI());

        /*
         * A live theme switch (see SettingsDialog) calls SwingUtilities.updateComponentTreeUI(), which resets
         * every component's UI delegate to the look and feel's plain default, discarding our custom one. Put it
         * straight back.
         */
        swingTree.addPropertyChangeListener("UI", e -> {
            if (!(swingTree.getUI() instanceof ExpandArrowTreeUI)) {
                swingTree.setUI(new ExpandArrowTreeUI());
            }
        });

        swingTree.setRootVisible(true);
        swingTree.setShowsRootHandles(true);
        swingTree.setEditable(false);
        swingTree.setBorder(new EmptyBorder(2, 4, 4, 4));
        swingTree.setCellRenderer(new OntologyTreeCellRenderer());

        swingTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    T value = getSelectedValue();

                    if (value != null) {
                        onActivate.accept(value);
                    }
                }
            }
        });
    }

    private Component buildToolbar() {
        val toolbar = ToolbarIcons.buildSearchToolbar(searchField, buttonPanel);
        toolbar.setOpaque(true);
        toolbar.setBackground(treeBackground());
        toolbar.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));

        return toolbar;
    }

    private void configureSearchField(String placeholder) {
        ToolbarIcons.configureSearchField(searchField, placeholder);

        filterDebounceTimer = new Timer(FILTER_DEBOUNCE_MS, e -> setFilterTextNow(searchField.getText()));
        filterDebounceTimer.setRepeats(false);

        searchField.getDocument().addDocumentListener(
                SimpleDocumentListener.of(() -> filterDebounceTimer.restart())
        );
    }

    /**
     * Adds a button (typically created with {@link ToolbarIcons#createToolbarButton}) to the search toolbar,
     * after any previously added button.
     */
    public void addToolbarButton(JButton button) {
        buttonPanel.add(button);
    }

    /**
     * Called when the user double-clicks a class in the tree. Defaults to doing nothing.
     */
    public void setOnActivate(Consumer<T> onActivate) {
        this.onActivate = Objects.requireNonNull(onActivate, "onActivate must not be null");
    }

    /**
     * Runs the given callback whenever the tree's selection changes, e.g. to enable/disable a toolbar button that
     * acts on {@link #getSelectedValue()}.
     */
    public void addSelectionListener(Runnable onSelectionChanged) {
        swingTree.addTreeSelectionListener(e -> onSelectionChanged.run());
    }

    /**
     * Tints the background of each class's row with the color the given function returns for it (or leaves it
     * untinted for {@code null}). The provider is consulted again on every repaint, so callers must call
     * {@link #refreshHighlights()} whenever the highlighted state changes.
     */
    public void setHighlightProvider(Function<T, Color> highlightProvider) {
        this.highlightProvider = Objects.requireNonNull(highlightProvider, "highlightProvider must not be null");
        swingTree.repaint();
    }

    /**
     * Repaints the tree so it picks up changes in whatever state {@link #setHighlightProvider} depends on.
     */
    public void refreshHighlights() {
        swingTree.repaint();
    }

    /**
     * @return The value of the currently selected class, or {@code null} if none is selected or it is a
     * placeholder node
     */
    public T getSelectedValue() {
        Object selected = swingTree.getLastSelectedPathComponent();

        if (!(selected instanceof CheckBoxTreeNode<?> rawNode)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        CheckBoxTreeNode<T> node = (CheckBoxTreeNode<T>) rawNode;

        return node.isPlaceholder() ? null : node.getValue();
    }

    /**
     * @return The underlying {@link JTree}, e.g., to install a {@link javax.swing.TransferHandler}. Its nodes are
     * copies of the nodes of the root node; use {@link CheckBoxTreeNode#getOriginalRef()} to get the original.
     */
    public JTree getTree() {
        return swingTree;
    }

    /**
     * Whether the root node is shown. If not, its children are the top level of the tree.
     */
    public void setRootVisible(boolean rootVisible) {
        swingTree.setRootVisible(rootVisible);
    }

    /**
     * Allows to select several nodes, which must then be requested with {@link #getSelectedNodes()} or
     * {@link #getSelectedValues()}.
     */
    public void setMultipleSelection(boolean multiple) {
        swingTree.getSelectionModel().setSelectionMode(
                multiple ? TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION : TreeSelectionModel.SINGLE_TREE_SELECTION
        );
    }

    /**
     * @return The selected nodes, which are copies of the nodes of the root node (see
     * {@link CheckBoxTreeNode#getOriginalRef()}), including placeholder nodes
     */
    public List<CheckBoxTreeNode<T>> getSelectedNodes() {
        TreePath[] paths = swingTree.getSelectionPaths();
        List<CheckBoxTreeNode<T>> nodes = new ArrayList<>();

        if (paths == null) {
            return nodes;
        }

        for (TreePath path : paths) {
            if (path.getLastPathComponent() instanceof CheckBoxTreeNode<?> raw) {
                @SuppressWarnings("unchecked")
                CheckBoxTreeNode<T> node = (CheckBoxTreeNode<T>) raw;
                nodes.add(node);
            }
        }

        return nodes;
    }

    /**
     * @return The values of the selected nodes, without placeholder nodes
     */
    public List<T> getSelectedValues() {
        return getSelectedNodes().stream()
                .filter(node -> !node.isPlaceholder())
                .map(CheckBoxTreeNode::getValue)
                .toList();
    }

    public void setRootNode(CheckBoxTreeNode<T> rootNode) {
        this.rootNode = Objects.requireNonNull(rootNode, "rootNode must not be null");
        renderedNodeCache.clear();
        refreshImmediately(filterText);
    }

    public void setFilterText(String filterText) {
        searchField.setText(Objects.toString(filterText, ""));
        setFilterTextNow(filterText);
    }

    private void setFilterTextNow(String filterText) {
        this.filterText = normalizeFilterText(filterText);
        refreshAsync(this.filterText);
    }

    public void refresh() {
        refreshAsync(filterText);
    }

    private void refreshImmediately(String filterText) {
        CheckBoxTreeNode<T> visibleRoot = copyFiltered(rootNode, normalizeFilterText(filterText));

        if (visibleRoot == null) {
            visibleRoot = CheckBoxTreeNode.placeholder("No matching entries");
        }

        displayedFilterText = normalizeFilterText(filterText);
        treeModel.setRoot(visibleRoot);
        treeModel.reload();
        expandReasonably();
    }

    private void refreshAsync(String filterText) {
        if (filterWorker != null && !filterWorker.isDone()) {
            filterWorker.cancel(true);
        }

        final String expectedFilter = normalizeFilterText(filterText);

        searchField.putClientProperty("JTextField.placeholderText", "Searching…");

        filterWorker = new SwingWorker<>() {
            @Override
            protected CheckBoxTreeNode<T> doInBackground() {
                CheckBoxTreeNode<T> visibleRoot = copyFilteredCancellable(rootNode, expectedFilter);

                if (isCancelled()) {
                    return null;
                }

                if (visibleRoot == null) {
                    visibleRoot = CheckBoxTreeNode.placeholder("No matching entries");
                }

                return visibleRoot;
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }

                try {
                    CheckBoxTreeNode<T> visibleRoot = get();

                    if (visibleRoot == null) {
                        return;
                    }

                    /*
                     * Another search may have started after this worker.
                     * In that case, ignore this stale result.
                     */
                    if (!Objects.equals(FilterableTree.this.filterText, expectedFilter)) {
                        return;
                    }

                    displayedFilterText = expectedFilter;
                    treeModel.setRoot(visibleRoot);
                    treeModel.reload();
                    expandReasonably();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    treeModel.setRoot(CheckBoxTreeNode.placeholder("Search failed"));
                    treeModel.reload();
                } finally {
                    searchField.putClientProperty("JTextField.placeholderText", "Search classes…");
                }
            }
        };

        filterWorker.execute();
    }

    private CheckBoxTreeNode<T> copyFiltered(CheckBoxTreeNode<T> source, String filter) {
        val matches = matches(source, filter);

        val copy = source.copyWithoutChildren();
        copy.setOriginalRef(source.getOriginalRef() == null ? source : source.getOriginalRef());

        for (int i = 0; i < source.getChildCount(); i++) {
            val child = source.getChildAtTyped(i);
            val childCopy = copyFiltered(child, filter);

            if (childCopy != null) {
                copy.add(childCopy);
            }
        }

        return matches || copy.getChildCount() > 0 ? copy : null;
    }

    private CheckBoxTreeNode<T> copyFilteredCancellable(CheckBoxTreeNode<T> source, String filter) {
        if (filterWorker != null && filterWorker.isCancelled()) {
            return null;
        }

        val matches = matches(source, filter);

        val copy = source.copyWithoutChildren();
        copy.setOriginalRef(source.getOriginalRef() == null ? source : source.getOriginalRef());

        for (int i = 0; i < source.getChildCount(); i++) {
            if (filterWorker != null && filterWorker.isCancelled()) {
                return null;
            }

            val child = source.getChildAtTyped(i);
            val childCopy = copyFilteredCancellable(child, filter);

            if (childCopy != null) {
                copy.add(childCopy);
            }
        }

        return matches || copy.getChildCount() > 0 ? copy : null;
    }

    private boolean matches(CheckBoxTreeNode<T> node, String filter) {
        return filter.isBlank()
                || (node.getStyle() != null && node.getStyle().alwaysVisible())
                || renderNodeCached(node).toLowerCase(Locale.ROOT).contains(filter);
    }

    private String renderNode(CheckBoxTreeNode<T> node) {
        if (node.isPlaceholder()) {
            return node.getPlaceholderText();
        }

        return labelProvider.apply(node.getValue());
    }

    private String renderNodeCached(CheckBoxTreeNode<T> node) {
        return renderedNodeCache.computeIfAbsent(node, this::renderNode);
    }

    private void expandReasonably() {
        int rowCount = swingTree.getRowCount();

        if (rowCount <= MAX_FULL_EXPAND_ROWS) {
            expandRows(MAX_FULL_EXPAND_ROWS);
            return;
        }

        expandRows(MAX_PARTIAL_EXPAND_ROWS);
    }

    private void expandRows(int maxRows) {
        for (int i = 0; i < swingTree.getRowCount() && i < maxRows; i++) {
            swingTree.expandRow(i);
        }
    }

    private static String normalizeFilterText(String filterText) {
        return Objects.toString(filterText, "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private Color treeBackground() {
        Color c = UIManager.getColor("Tree.background");

        if (c == null) {
            c = UIManager.getColor("Panel.background");
        }

        return c != null ? c : getBackground();
    }

    private static Color searchHighlightColor() {
        Color c = UIManager.getColor("Component.focusColor");

        if (c == null) {
            c = UIManager.getColor("Component.accentColor");
        }

        if (c == null) {
            c = new Color(255, 170, 0);
        }

        return new Color(c.getRed(), c.getGreen(), c.getBlue(), 140);
    }

    private class OntologyTreeCellRenderer extends DefaultTreeCellRenderer {

        /*
         * Start and end indices of the search matches in the text of the node that
         * is currently rendered.
         */
        private final List<int[]> searchMatches = new ArrayList<>();
        private Color highlightColor;

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus
        ) {
            JLabel label = (JLabel) super.getTreeCellRendererComponent(
                    tree,
                    value,
                    selected,
                    expanded,
                    leaf,
                    row,
                    hasFocus
            );

            searchMatches.clear();
            highlightColor = null;

            if (value instanceof CheckBoxTreeNode<?> rawNode) {
                @SuppressWarnings("unchecked")
                CheckBoxTreeNode<T> node = (CheckBoxTreeNode<T>) rawNode;
                String text = renderNode(node);
                label.setText(text);

                NodeStyle style = node.getStyle();

                label.setFont(style != null && style.bold() ? tree.getFont().deriveFont(Font.BOLD) : tree.getFont());

                if (style != null && style.muted() && !selected) {
                    label.setForeground(UIManager.getColor("Label.disabledForeground"));
                }

                if (node.isPlaceholder()) {
                    highlightColor = style == null ? null : style.background();
                } else {
                    collectSearchMatches(text);
                    highlightColor = highlightProvider.apply(node.getValue());
                }
            }

            return label;
        }

        private void collectSearchMatches(String text) {
            String filter = displayedFilterText;

            if (filter.isBlank()) {
                return;
            }

            String lowerText = text.toLowerCase(Locale.ROOT);

            /*
             * Lowercasing may change the length of some characters. Highlighting
             * those would be misplaced, so skip it in that rare case.
             */
            if (lowerText.length() != text.length()) {
                return;
            }

            int index = lowerText.indexOf(filter);

            while (index >= 0) {
                searchMatches.add(new int[]{index, index + filter.length()});
                index = lowerText.indexOf(filter, index + filter.length());
            }
        }

        /*
         * Paints the permitted/forbidden highlight and the search highlights after the background, which
         * DefaultTreeCellRenderer paints in paint(), but before the text.
         */
        @Override
        protected void paintComponent(Graphics g) {
            if (highlightColor != null) {
                paintHighlightBackground(g, highlightColor);
            }

            if (!searchMatches.isEmpty()) {
                paintSearchHighlights(g);
            }

            super.paintComponent(g);
        }

        private void paintHighlightBackground(Graphics g, Color color) {
            val g2 = (Graphics2D) g.create();

            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
            } finally {
                g2.dispose();
            }
        }

        private void paintSearchHighlights(Graphics g) {
            String text = getText();
            FontMetrics fm = getFontMetrics(getFont());

            Insets insets = getInsets();
            Rectangle viewR = new Rectangle(
                    insets.left,
                    insets.top,
                    getWidth() - insets.left - insets.right,
                    getHeight() - insets.top - insets.bottom
            );
            Rectangle iconR = new Rectangle();
            Rectangle textR = new Rectangle();

            SwingUtilities.layoutCompoundLabel(
                    this,
                    fm,
                    text,
                    getIcon(),
                    getVerticalAlignment(),
                    getHorizontalAlignment(),
                    getVerticalTextPosition(),
                    getHorizontalTextPosition(),
                    viewR,
                    iconR,
                    textR,
                    getIconTextGap()
            );

            val g2 = (Graphics2D) g.create();

            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(searchHighlightColor());

                for (int[] match : searchMatches) {
                    int x = textR.x + fm.stringWidth(text.substring(0, match[0]));
                    int width = fm.stringWidth(text.substring(match[0], match[1]));

                    g2.fillRoundRect(x - 1, textR.y, width + 2, textR.height, 6, 6);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
