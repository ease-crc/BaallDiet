package org.example.gui.components.checkBoxTree;

import lombok.Getter;
import lombok.Setter;
import lombok.val;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Getter
@Setter
public class CheckBoxTreeNode<T> extends DefaultMutableTreeNode {

    private final T value;
    private final boolean placeholder;
    private final String placeholderText;

    private CheckBoxTreeNode<T> originalRef;

    /**
     * An arbitrary marker of a node for its owner, e.g., the index of the category of a forest.
     */
    private Object tag;

    /**
     * How a placeholder node is shown, or {@code null} for the default look.
     */
    private NodeStyle style;

    public CheckBoxTreeNode(T value) {
        this.value = value;
        this.placeholder = false;
        this.placeholderText = null;
        setUserObject(value);
    }

    private CheckBoxTreeNode(String placeholderText) {
        this.value = null;
        this.placeholder = true;
        this.placeholderText = placeholderText;
        setUserObject(placeholderText);
    }

    public static <T> CheckBoxTreeNode<T> placeholder(String text) {
        return new CheckBoxTreeNode<>(text);
    }


    private Stream<CheckBoxTreeNode<T>> childrenStream() {
        return IntStream.range(0, getChildCount())
                .mapToObj(this::getChildAtTyped);
    }

    public CheckBoxTreeNode<T> getChildAtTyped(int index) {
        return (CheckBoxTreeNode<T>) getChildAt(index);
    }

    public CheckBoxTreeNode<T> getParentNode() {
        return (CheckBoxTreeNode<T>) getParent();
    }

    public CheckBoxTreeNode<T> copyWithoutChildren() {
        var copy = placeholder
                ? CheckBoxTreeNode.<T>placeholder(placeholderText)
                : new CheckBoxTreeNode<>(value);
        copy.originalRef = originalRef;
        copy.tag = tag;
        copy.style = style;

        return copy;
    }
}