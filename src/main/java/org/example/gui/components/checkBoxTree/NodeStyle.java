package org.example.gui.components.checkBoxTree;

import java.awt.Color;

/**
 * How a {@link FilterableTree} shows a placeholder node, e.g., the category of a forest.
 *
 * @param background
 * 		The tint of the row, or {@code null} for none
 * @param bold
 * 		Whether the text is bold
 * @param muted
 * 		Whether the text is shown like a disabled text, e.g., for a hint
 * @param alwaysVisible
 * 		Whether the node stays visible when the tree is filtered, even if nothing below it matches
 */
public record NodeStyle(Color background, boolean bold, boolean muted, boolean alwaysVisible) {
}
