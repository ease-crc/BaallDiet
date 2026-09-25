package org.example.gui.components.checkBoxTree;

import lombok.RequiredArgsConstructor;
import lombok.val;

import javax.swing.*;
import javax.swing.tree.TreeCellEditor;
import java.awt.Component;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class CheckBoxTreeCellEditor<T> extends AbstractCellEditor implements TreeCellEditor {

    private final JTree tree;
    private final Consumer<CheckBoxTreeNode<T>> toggleHandler;

    @Override
    public Object getCellEditorValue() {
        return null;
    }

    @Override
    public Component getTreeCellEditorComponent(
            JTree tree,
            Object value,
            boolean selected,
            boolean expanded,
            boolean leaf,
            int row
    ) {
        val node = (CheckBoxTreeNode<T>) value;
        toggleHandler.accept(node);
        SwingUtilities.invokeLater(this::fireEditingStopped);

        return tree.getCellRenderer().getTreeCellRendererComponent(
                tree,
                value,
                selected,
                expanded,
                leaf,
                row,
                true
        );
    }
}