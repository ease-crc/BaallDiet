package org.example.gui.components;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link JList} with a search field above it that filters the list, case-insensitively, by a substring of the
 * label {@link #FilterableList(Function, String) rendered} for each item.
 * <p>
 * This is the one place that implements "type to filter a list of items", used by the class tree's selected-values
 * list, the dinner party panel's user/permitted/forbidden lists, and the result popups. Every caller keeps its own
 * items in sync via {@link #setItems}, {@link #addItem}, {@link #removeItem}, {@link #removeItems} and
 * {@link #replaceItem}; the currently displayed, filtered subset is purely a view over that list.
 */
public class FilterableList<T> extends JPanel {

    private final Function<T, String> labelProvider;

    /**
     * All items, independently of the current search filter. The single source of truth; the list model is
     * always derived from this.
     */
    private final List<T> items = new ArrayList<>();

    private final DefaultListModel<T> model = new DefaultListModel<>();
    private final JList<T> list = new JList<>(model);
    private final JTextField searchField = new JTextField();
    private final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    private final JScrollPane scrollPane;

    private String filterText = "";
    private Runnable onItemsChanged = () -> {
    };

    public FilterableList(Function<T, String> labelProvider, String searchPlaceholder) {
        super(new BorderLayout());

        this.labelProvider = Objects.requireNonNull(labelProvider, "labelProvider must not be null");

        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> l, Object value, int index, boolean selected, boolean focus
            ) {
                super.getListCellRendererComponent(l, value, index, selected, focus);

                @SuppressWarnings("unchecked")
                T typedValue = (T) value;
                setText(labelProvider.apply(typedValue));

                return this;
            }
        });

        buttonPanel.setOpaque(false);

        ToolbarIcons.configureSearchField(searchField, searchPlaceholder);
        searchField.getDocument().addDocumentListener(
                SimpleDocumentListener.of(() -> {
                    filterText = normalizeFilterText(searchField.getText());
                    refresh();
                })
        );

        scrollPane = new JScrollPane(list);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        add(buildSeamlessToolbar(), BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * The search toolbar, padded and with the background of the list, so that toolbar and list look like one surface,
     * like the search bar and taxonomy of {@link org.example.gui.components.checkBoxTree.FilterableTree}.
     */
    private JPanel buildSeamlessToolbar() {
        JPanel toolbar = ToolbarIcons.buildSearchToolbar(searchField, buttonPanel);
        toolbar.setOpaque(true);
        toolbar.setBackground(list.getBackground());
        toolbar.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));

        // a theme switch resets the colors of the UI, but not those that were set explicitly
        list.addPropertyChangeListener("background", e -> toolbar.setBackground(list.getBackground()));

        return toolbar;
    }

    public JList<T> getList() {
        return list;
    }

    public JTextField getSearchField() {
        return searchField;
    }

    /**
     * Sets the border drawn around the list itself (not the search toolbar above it). Defaults to no border, so that
     * the list looks like one surface with the toolbar.
     */
    public void setListBorder(Border border) {
        scrollPane.setBorder(border);
    }

    /**
     * Adds a button (typically created with {@link ToolbarIcons#createToolbarButton}) to the toolbar, after any
     * previously added button.
     */
    public void addToolbarButton(JButton button) {
        buttonPanel.add(button);
    }

    /**
     * Runs the given callback whenever the items (via {@link #setItems}, {@link #addItem}, {@link #removeItem},
     * {@link #removeItems} or {@link #replaceItem}) or the search filter change, e.g. to enable/disable a toolbar
     * button that depends on whether the list is empty.
     */
    public void setOnItemsChanged(Runnable onItemsChanged) {
        this.onItemsChanged = Objects.requireNonNull(onItemsChanged, "onItemsChanged must not be null");
    }

    /**
     * @return An unmodifiable snapshot of all items, independently of the current search filter
     */
    public List<T> getItems() {
        return List.copyOf(items);
    }

    /**
     * @return An unmodifiable snapshot of the items currently shown, i.e. after applying the current search filter
     */
    public List<T> getVisibleItems() {
        List<T> visible = new ArrayList<>(model.size());

        for (int i = 0; i < model.size(); i++) {
            visible.add(model.get(i));
        }

        return List.copyOf(visible);
    }

    public void setItems(List<T> newItems) {
        items.clear();
        items.addAll(newItems);
        refresh();
    }

    /**
     * Membership check that avoids the defensive copy {@link #getItems()} makes, for callers (e.g. tree highlight
     * providers) that run on every repaint.
     */
    public boolean containsItem(T item) {
        return items.contains(item);
    }

    public void addItem(T item) {
        items.add(item);
        refresh();
    }

    public boolean removeItem(T item) {
        boolean removed = items.remove(item);

        if (removed) {
            refresh();
        }

        return removed;
    }

    public void removeItems(Collection<T> itemsToRemove) {
        if (items.removeAll(itemsToRemove)) {
            refresh();
        }
    }

    /**
     * Replaces the first occurrence of {@code oldItem} with {@code newItem}, keeping its position. Does nothing if
     * {@code oldItem} is not currently an item.
     */
    public void replaceItem(T oldItem, T newItem) {
        int index = items.indexOf(oldItem);

        if (index >= 0) {
            items.set(index, newItem);
            refresh();
        }
    }

    /**
     * Selects the given item, if it is currently visible under the search filter.
     */
    public void selectItem(T item) {
        list.setSelectedValue(item, true);
    }

    private void refresh() {
        model.clear();

        for (T item : items) {
            if (filterText.isBlank() || labelProvider.apply(item).toLowerCase(Locale.ROOT).contains(filterText)) {
                model.addElement(item);
            }
        }

        onItemsChanged.run();
    }

    private static String normalizeFilterText(String filterText) {
        return Objects.toString(filterText, "").trim().toLowerCase(Locale.ROOT);
    }
}
