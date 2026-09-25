package org.example.gui.components.checkBoxTree;

import org.example.gui.components.FilterableList;
import org.example.gui.components.ToolbarIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link FilterableTree} of classes next to a {@link FilterableList} of the classes the user picked from it, with
 * "add"/"remove" actions wiring the two together.
 */
public class CheckBoxTree<T> extends JPanel {

    private final FilterableTree<T> tree;
    private final FilterableList<T> selectedList;

    private final JButton addButton = ToolbarIcons.createToolbarButton(
            new ToolbarIcons.AddIcon(), "Add selected class", this::addSelectedTreeNode);
    private final JButton removeButton = ToolbarIcons.createToolbarButton(
            new ToolbarIcons.RemoveIcon(), "Remove selected classes", this::removeSelectedListItems);

    public CheckBoxTree(CheckBoxTreeNode<T> rootNode, Function<T, String> labelProvider) {
        super(new BorderLayout());

        tree = new FilterableTree<>(rootNode, labelProvider, "Search classes…");

        selectedList = new FilterableList<>(labelProvider, "Search selected classes…");
        selectedList.getList().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        selectedList.getList().setVisibleRowCount(12);
        selectedList.addToolbarButton(removeButton);

        tree.setOnActivate(this::addValueIfAbsent);
        tree.addToolbarButton(addButton);

        addButton.setEnabled(false);
        tree.addSelectionListener(() -> addButton.setEnabled(tree.getSelectedValue() != null));

        removeButton.setEnabled(false);
        selectedList.getList().addListSelectionListener(
                e -> removeButton.setEnabled(!selectedList.getList().getSelectedValuesList().isEmpty())
        );

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tree, selectedList);
        splitPane.setResizeWeight(0.62);
        splitPane.setBorder(ToolbarIcons.outlineBorder());
        splitPane.setContinuousLayout(true);
        ToolbarIcons.styleSplitPane(splitPane);

        add(splitPane, BorderLayout.CENTER);
    }

    /**
     * Runs the given callback whenever the selected values (via {@link #setSelectedValues} or by the user
     * adding/removing classes) change, e.g. to enable/disable a button that acts on {@link #getSelectedValues()}.
     */
    public void setOnSelectedValuesChanged(Runnable onSelectedValuesChanged) {
        selectedList.setOnItemsChanged(onSelectedValuesChanged);
    }

    private void addSelectedTreeNode() {
        T value = tree.getSelectedValue();

        if (value != null) {
            addValueIfAbsent(value);
        }
    }

    private void addValueIfAbsent(T value) {
        if (!selectedList.containsItem(value)) {
            selectedList.addItem(value);
        }
    }

    private void removeSelectedListItems() {
        selectedList.removeItems(selectedList.getList().getSelectedValuesList());
    }

    public void setRootNode(CheckBoxTreeNode<T> rootNode) {
        tree.setRootNode(rootNode);
    }

    public void setFilterText(String filterText) {
        tree.setFilterText(filterText);
    }

    public void setTreeFilterText(String filterText) {
        tree.setFilterText(filterText);
    }

    public void setSelectedFilterText(String filterText) {
        // the document listener installed by FilterableList normalizes the text and refreshes the list
        selectedList.getSearchField().setText(Objects.toString(filterText, ""));
    }

    public List<T> getSelectedValues() {
        return selectedList.getItems();
    }

    public void setSelectedValues(List<T> values) {
        selectedList.setItems(values.stream().distinct().toList());
    }

    public void refresh() {
        tree.refresh();
    }
}
