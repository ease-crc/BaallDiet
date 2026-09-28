package org.example.gui.components;

import org.example.gui.components.checkBoxTree.CheckBoxTreeNode;
import org.example.gui.components.checkBoxTree.FilterableTree;
import org.example.gui.components.checkBoxTree.NodeStyle;

import javax.swing.AbstractAction;
import javax.swing.DropMode;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.TransferHandler;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A forest with one root for each category, holding the values the user assigned to that category, e.g., the
 * permitted and forbidden foods of a guest.
 * <p>
 * Below each root, the values keep the structure of a taxonomy: a value is shown below its nearest ancestor in the
 * taxonomy that is in the same category. Values are moved between categories by drag and drop (hold Ctrl to copy) or
 * with the toolbar; values from a taxonomy tree can be dropped, too (see {@link ClassTransfer}). Moving or removing a
 * value includes the values below it.
 * <p>
 * The forest is a view of its items: change them with {@link #setItems} (silently) and {@link #addItems} or by the
 * user (both of which run {@link #setOnChanged the change callback}), and read them with {@link #getItems}.
 */
public class CategoryForest<T> extends JPanel {

    /**
     * A category, i.e., a root of the forest.
     *
     * @param name
     *         The name of the root
     * @param icon
     *         The icon of the toolbar button that moves the selected values to this category
     * @param moveTooltip
     *         The tooltip of that button
     * @param tint
     *         The tint of the root
     * @param emptyHint
     *         What is shown below the root as long as the category has no values
     */
    public record Category(String name, Icon icon, String moveTooltip, Color tint, String emptyHint) {
    }

    private final Supplier<CheckBoxTreeNode<T>> taxonomy;
    private final Function<T, String> label;
    private final List<Category> categories;

    /**
     * Whether a value must not be in the two categories, given by their indices, at the same time.
     */
    private final BiPredicate<Integer, Integer> exclusive;

    private final FilterableTree<T> tree;
    private final List<Set<T>> items = new ArrayList<>();
    private final List<JButton> moveButtons = new ArrayList<>();
    private final JButton removeButton;

    private Runnable onChanged = () -> {
    };

    /**
     * @param taxonomy
     *         Supplies the root of the taxonomy that gives the structure of the values, which is asked for again
     *         whenever the forest is built
     * @param exclusive
     *         Whether a value must not be in the two categories, given by their indices, at the same time. Adding a
     *         value to a category removes it from the categories it is exclusive with.
     * @param leadingButtons
     *         Additional toolbar buttons, in front of the buttons to move and remove values
     */
    public CategoryForest(
            Supplier<CheckBoxTreeNode<T>> taxonomy,
            Function<T, String> label,
            String searchPlaceholder,
            List<Category> categories,
            BiPredicate<Integer, Integer> exclusive,
            JButton... leadingButtons
    ) {
        super(new BorderLayout());

        this.taxonomy = taxonomy;
        this.label = label;
        this.categories = List.copyOf(categories);
        this.exclusive = exclusive;

        categories.forEach(category -> items.add(new LinkedHashSet<>()));

        tree = new FilterableTree<>(CheckBoxTreeNode.placeholder("Foods"), label, searchPlaceholder);
        tree.setRootVisible(false);
        tree.setMultipleSelection(true);
        tree.addSelectionListener(this::updateButtons);

        for (JButton button : leadingButtons) {
            tree.addToolbarButton(button);
        }

        for (int i = 0; i < categories.size(); i++) {
            int category = i;
            Category spec = categories.get(i);
            JButton button = ToolbarIcons.createToolbarButton(
                    spec.icon(), spec.moveTooltip(), () -> moveSelectedTo(category));
            moveButtons.add(button);
            tree.addToolbarButton(button);
        }

        removeButton = ToolbarIcons.createToolbarButton(
                new ToolbarIcons.RemoveIcon(),
                "Remove the selected foods and the foods below them",
                this::removeSelected
        );
        tree.addToolbarButton(removeButton);

        installKeyboardAndDragAndDrop();
        add(tree, BorderLayout.CENTER);

        rebuild();
    }

    /**
     * Runs the given callback whenever the user or {@link #addItems} changed the items.
     */
    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    /**
     * Replaces the items of all categories, without running the change callback.
     *
     * @param itemsByCategory
     *         The items of each category, in the order of the categories
     */
    public void setItems(List<? extends Collection<T>> itemsByCategory) {
        for (int i = 0; i < items.size(); i++) {
            items.get(i).clear();
            items.get(i).addAll(itemsByCategory.get(i));
        }

        rebuild();
    }

    public List<T> getItems(int category) {
        return List.copyOf(items.get(category));
    }

    public boolean contains(int category, T value) {
        return items.get(category).contains(value);
    }

    /**
     * Adds the values to the category, removing them from the categories that are exclusive with it, and runs the
     * change callback if that changed anything.
     */
    public void addItems(int category, Collection<T> values) {
        List<ClassTransfer.Entry<T>> entries =
                values.stream().map(value -> new ClassTransfer.Entry<>(value, -1)).toList();

        if (apply(category, entries, false)) {
            onChanged.run();
        }
    }

    private void installKeyboardAndDragAndDrop() {
        JTree swingTree = tree.getTree();

        swingTree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("DELETE"), "removeFoods");
        swingTree.getActionMap().put("removeFoods", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeSelected();
            }
        });

        swingTree.setDragEnabled(true);
        swingTree.setDropMode(DropMode.ON);
        swingTree.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY_OR_MOVE;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                List<ClassTransfer.Entry<T>> entries = selectedEntries();

                return entries.isEmpty() ? null : ClassTransfer.transferable(new ClassTransfer.Payload<>(entries));
            }

            @Override
            public boolean canImport(TransferSupport support) {
                if (!support.isDrop() || !support.isDataFlavorSupported(ClassTransfer.FLAVOR)) {
                    return false;
                }

                int target = dropCategory(support);

                if (target < 0) {
                    return false;
                }

                // dropping onto the own category would not change anything
                try {
                    @SuppressWarnings("unchecked")
                    ClassTransfer.Payload<T> payload =
                            (ClassTransfer.Payload<T>) support.getTransferable().getTransferData(ClassTransfer.FLAVOR);

                    return payload.entries().stream().anyMatch(entry -> entry.sourceCategory() != target);
                } catch (Exception e) {
                    // the data may not be available before the drop; the drop is then checked when it happens
                    return true;
                }
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }

                try {
                    @SuppressWarnings("unchecked")
                    ClassTransfer.Payload<T> payload =
                            (ClassTransfer.Payload<T>) support.getTransferable().getTransferData(ClassTransfer.FLAVOR);

                    if (apply(dropCategory(support), payload.entries(), support.getDropAction() == MOVE)) {
                        onChanged.run();
                    }

                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
    }

    /**
     * @return The index of the category of the node the user drops onto, or {@code -1}
     */
    private int dropCategory(TransferHandler.TransferSupport support) {
        TreePath path = ((JTree.DropLocation) support.getDropLocation()).getPath();

        if (path == null || !(path.getLastPathComponent() instanceof CheckBoxTreeNode<?> node)) {
            return -1;
        }

        return categoryOf(original(node));
    }

    @SuppressWarnings("unchecked")
    private CheckBoxTreeNode<T> original(CheckBoxTreeNode<?> shown) {
        CheckBoxTreeNode<T> node = (CheckBoxTreeNode<T>) shown;

        return node.getOriginalRef() == null ? node : node.getOriginalRef();
    }

    /**
     * @return The index of the category a node of the forest belongs to, or {@code -1}
     */
    private static int categoryOf(TreeNode node) {
        for (TreeNode current = node; current != null; current = current.getParent()) {
            if (current instanceof CheckBoxTreeNode<?> checkBoxNode
                    && checkBoxNode.getTag() instanceof Integer category) {
                return category;
            }
        }

        return -1;
    }

    /**
     * @return The selected values with their categories, including the values below them
     */
    private List<ClassTransfer.Entry<T>> selectedEntries() {
        Map<T, ClassTransfer.Entry<T>> entries = new LinkedHashMap<>();

        for (CheckBoxTreeNode<T> shown : tree.getSelectedNodes()) {
            if (shown.isPlaceholder()) {
                continue;
            }

            CheckBoxTreeNode<T> selected = original(shown);
            int category = categoryOf(selected);

            for (Enumeration<TreeNode> nodes = selected.preorderEnumeration(); nodes.hasMoreElements(); ) {
                @SuppressWarnings("unchecked")
                CheckBoxTreeNode<T> node = (CheckBoxTreeNode<T>) nodes.nextElement();

                if (!node.isPlaceholder()) {
                    entries.putIfAbsent(node.getValue(), new ClassTransfer.Entry<>(node.getValue(), category));
                }
            }
        }

        return List.copyOf(entries.values());
    }

    private void moveSelectedTo(int category) {
        if (apply(category, selectedEntries(), true)) {
            onChanged.run();
        }
    }

    private void removeSelected() {
        boolean changed = false;

        for (ClassTransfer.Entry<T> entry : selectedEntries()) {
            changed |= entry.sourceCategory() >= 0 && items.get(entry.sourceCategory()).remove(entry.value());
        }

        if (changed) {
            rebuild();
            onChanged.run();
        }
    }

    /**
     * Adds the entries to the target category.
     *
     * @param move
     *         Whether to remove the entries from the category they come from
     *
     * @return Whether anything changed
     */
    private boolean apply(int target, List<ClassTransfer.Entry<T>> entries, boolean move) {
        boolean changed = false;

        for (ClassTransfer.Entry<T> entry : entries) {
            if (move && entry.sourceCategory() >= 0 && entry.sourceCategory() != target) {
                changed |= items.get(entry.sourceCategory()).remove(entry.value());
            }

            for (int other = 0; other < items.size(); other++) {
                if (other != target && exclusive.test(target, other)) {
                    changed |= items.get(other).remove(entry.value());
                }
            }

            changed |= items.get(target).add(entry.value());
        }

        if (changed) {
            rebuild();
        }

        return changed;
    }

    private void updateButtons() {
        boolean selected = !tree.getSelectedValues().isEmpty();

        moveButtons.forEach(button -> button.setEnabled(selected));
        removeButton.setEnabled(selected);
    }

    private void rebuild() {
        Map<T, List<CheckBoxTreeNode<T>>> taxonomyNodes = new HashMap<>();

        for (Enumeration<TreeNode> nodes = taxonomy.get().depthFirstEnumeration(); nodes.hasMoreElements(); ) {
            @SuppressWarnings("unchecked")
            CheckBoxTreeNode<T> node = (CheckBoxTreeNode<T>) nodes.nextElement();

            if (!node.isPlaceholder()) {
                taxonomyNodes.computeIfAbsent(node.getValue(), value -> new ArrayList<>()).add(node);
            }
        }

        CheckBoxTreeNode<T> root = CheckBoxTreeNode.placeholder("Foods");

        for (int i = 0; i < categories.size(); i++) {
            root.add(buildCategory(i, taxonomyNodes));
        }

        tree.setRootNode(root);
        updateButtons();
    }

    private CheckBoxTreeNode<T> buildCategory(int category, Map<T, List<CheckBoxTreeNode<T>>> taxonomyNodes) {
        Category spec = categories.get(category);
        Set<T> values = items.get(category);

        CheckBoxTreeNode<T> categoryNode = CheckBoxTreeNode.placeholder(spec.name() + " (" + values.size() + ")");
        categoryNode.setTag(category);
        categoryNode.setStyle(new NodeStyle(spec.tint(), true, false, true));

        if (values.isEmpty()) {
            CheckBoxTreeNode<T> hint = CheckBoxTreeNode.placeholder(spec.emptyHint());
            hint.setStyle(new NodeStyle(null, false, true, false));
            categoryNode.add(hint);

            return categoryNode;
        }

        Map<T, CheckBoxTreeNode<T>> nodes = new LinkedHashMap<>();
        Map<T, List<T>> children = new HashMap<>();
        List<T> roots = new ArrayList<>();

        values.forEach(value -> nodes.put(value, new CheckBoxTreeNode<>(value)));

        for (T value : values) {
            T parent = nearestAncestor(value, values, taxonomyNodes);

            if (parent == null) {
                roots.add(value);
            } else {
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(value);
            }
        }

        children.forEach((parent, sub) -> {
            sub.sort((a, b) -> label.apply(a).compareToIgnoreCase(label.apply(b)));
            sub.forEach(child -> nodes.get(parent).add(nodes.get(child)));
        });

        roots.sort((a, b) -> label.apply(a).compareToIgnoreCase(label.apply(b)));
        roots.forEach(value -> categoryNode.add(nodes.get(value)));

        return categoryNode;
    }

    /**
     * @return The value that is the nearest ancestor of the given value in the taxonomy and one of the given values,
     * or {@code null}. If the value occurs several times in the taxonomy (multiple inheritance), the occurrence with
     * the nearest such ancestor counts.
     */
    private T nearestAncestor(T value, Set<T> values, Map<T, List<CheckBoxTreeNode<T>>> taxonomyNodes) {
        T nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (CheckBoxTreeNode<T> occurrence : taxonomyNodes.getOrDefault(value, List.of())) {
            int distance = 0;

            for (TreeNode ancestor = occurrence.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                distance++;

                if (ancestor instanceof CheckBoxTreeNode<?> checkBoxNode && !checkBoxNode.isPlaceholder()) {
                    @SuppressWarnings("unchecked")
                    T candidate = (T) checkBoxNode.getValue();

                    if (!candidate.equals(value) && values.contains(candidate)) {
                        if (distance < nearestDistance) {
                            nearest = candidate;
                            nearestDistance = distance;
                        }

                        break;
                    }
                }
            }
        }

        return nearest;
    }
}
