package org.example;

import org.example.gui.components.CategoryForest;
import org.example.gui.components.ToolbarIcons;
import org.example.gui.components.checkBoxTree.CheckBoxTreeNode;
import org.semanticweb.owlapi.model.OWLClass;

import javax.swing.JButton;
import java.awt.Color;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The three categories of the foods of a {@link DietUser}, as they are shown in a {@link CategoryForest}: the
 * permitted and the forbidden foods.
 */
final class FoodCategories {

	static final int PERMITTED = 0;
	static final int FORBIDDEN = 1;

	static final Color PERMITTED_TINT = new Color(46, 160, 67, 70);
	static final Color FORBIDDEN_TINT = new Color(220, 53, 69, 70);

	private FoodCategories() {
	}

	/**
	 * @param taxonomy
	 * 		Supplies the root of the class tree, which gives the structure of the foods below each root
	 * @param leadingButtons
	 * 		Additional toolbar buttons, in front of the buttons to move and remove foods
	 *
	 * @return A forest with a root for the permitted and the forbidden foods. A food is not both permitted and
	 * forbidden.
	 */
	static CategoryForest<OWLClass> createForest(
			final Supplier<CheckBoxTreeNode<OWLClass>> taxonomy,
			final Function<OWLClass, String> renderClass,
			final JButton... leadingButtons
	) {
		return new CategoryForest<>(
				taxonomy,
				renderClass,
				"Search foods…",
				List.of(
						new CategoryForest.Category(
								"Permitted foods",
								new ToolbarIcons.CheckIcon(),
								"Permit the selected foods and the foods below them",
								PERMITTED_TINT,
								"Any food, as long as it is not forbidden"
						),
						new CategoryForest.Category(
								"Forbidden foods",
								new ToolbarIcons.BanIcon(),
								"Forbid the selected foods and the foods below them",
								FORBIDDEN_TINT,
								"No food is forbidden"
						)
				),
				(a, b) -> a != b,
				leadingButtons
		);
	}

	/**
	 * Shows the foods of the user in the forest, without notifying the change callback of the forest.
	 */
	static void show(final CategoryForest<OWLClass> forest, final DietUser user) {
		forest.setItems(List.of(user.permitted(), user.forbidden()));
	}

	/**
	 * @return The user with the given name and the foods of the forest
	 */
	static DietUser userOf(final String name, final CategoryForest<OWLClass> forest) {
		return new DietUser(
				name,
				forest.getItems(PERMITTED),
				forest.getItems(FORBIDDEN)
		);
	}
}
