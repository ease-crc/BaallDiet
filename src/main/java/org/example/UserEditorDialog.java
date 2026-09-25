package org.example;

import org.example.gui.components.CategoryForest;
import org.example.gui.components.ClassTransfer;
import org.example.gui.components.ToolbarIcons;
import org.example.gui.components.checkBoxTree.CheckBoxTreeNode;
import org.example.gui.components.checkBoxTree.FilterableTree;
import org.semanticweb.owlapi.model.OWLClass;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Modal dialog to create or edit a {@link DietUser}.
 */
final class UserEditorDialog extends JDialog {

	private final JTextField nameField = new JTextField();
	private final FilterableTree<OWLClass> classTree;
	private final CategoryForest<OWLClass> foods;

	/**
	 * Whether a name is already taken by another user.
	 */
	private final Predicate<String> nameTaken;

	private DietUser result;

	/**
	 * @param user
	 * 		The user to edit, or {@code null} to create a new user
	 * @param nameTaken
	 * 		Tests whether a name is already taken by another user
	 */
	private UserEditorDialog(
			final Window owner,
			final DietUser user,
			final CheckBoxTreeNode<OWLClass> classRoot,
			final Function<OWLClass, String> renderClass,
			final Predicate<String> nameTaken
	) {
		super(owner, user == null ? "Add user" : "Edit user", ModalityType.APPLICATION_MODAL);
		this.nameTaken = nameTaken;

		classTree = new FilterableTree<>(classRoot, renderClass, "Search classes…");
		foods = FoodCategories.createForest(() -> classRoot, renderClass);
		foods.setOnChanged(classTree::refreshHighlights);

		// a forbidden food is neither permitted nor favorite; a food can be both permitted and favorite
		classTree.setHighlightProvider(value -> {
			if (foods.contains(FoodCategories.FAVORITE, value)) {
				return FoodCategories.FAVORITE_TINT;
			}
			if (foods.contains(FoodCategories.PERMITTED, value)) {
				return FoodCategories.PERMITTED_TINT;
			}
			if (foods.contains(FoodCategories.FORBIDDEN, value)) {
				return FoodCategories.FORBIDDEN_TINT;
			}
			return null;
		});
		classTree.setOnActivate(value -> foods.addItems(FoodCategories.PERMITTED, List.of(value)));
		classTree.addToolbarButton(ToolbarIcons.createToolbarButton(
				new ToolbarIcons.CheckIcon(), "Permit selected class", () -> addSelected(FoodCategories.PERMITTED)));
		classTree.addToolbarButton(ToolbarIcons.createToolbarButton(
				new ToolbarIcons.StarIcon(), "Make selected class a favorite", () -> addSelected(FoodCategories.FAVORITE)));
		classTree.addToolbarButton(ToolbarIcons.createToolbarButton(
				new ToolbarIcons.BanIcon(), "Forbid selected class", () -> addSelected(FoodCategories.FORBIDDEN)));
		ClassTransfer.installDragSource(classTree);

		if (user != null) {
			nameField.setText(user.name());
			FoodCategories.show(foods, user);
		}

		nameField.putClientProperty("JTextField.placeholderText", "e.g. Alex");

		JPanel namePanel = new JPanel(new BorderLayout(8, 0));
		JLabel nameLabel = new JLabel("Name:");
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
		namePanel.add(nameLabel, BorderLayout.WEST);
		namePanel.add(nameField, BorderLayout.CENTER);

		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, classTree, foods);
		splitPane.setResizeWeight(0.5);
		splitPane.setBorder(BorderFactory.createEmptyBorder());
		splitPane.setContinuousLayout(true);
		ToolbarIcons.styleSplitPane(splitPane);

		JLabel help = ToolbarIcons.createHelpIcon(
				"Select a class in the tree, then permit, favorite or forbid it, or drag it onto the roots on the right"
						+ " (double-click permits it). Drag the foods on the right between the roots to move them; hold Ctrl to"
						+ " copy them. Foods keep the structure of the taxonomy below each root, and moving or removing a food"
						+ " includes the foods below it. The user wants the foods that are permitted and favorite, minus the"
						+ " forbidden foods; an empty permitted or favorite root does not restrict the foods. Foods processed only"
						+ " from permitted (favorite) foods are permitted (favorite), too; foods processed from forbidden foods are"
						+ " forbidden, too. A forbidden class is neither permitted nor favorite."
		);

		JButton ok = new JButton(user == null ? "Add user" : "Save");
		ok.setFont(ok.getFont().deriveFont(Font.BOLD));
		ok.addActionListener(e -> accept());

		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dispose());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(cancel);
		buttons.add(ok);

		JPanel south = new JPanel(new BorderLayout());
		south.add(help, BorderLayout.WEST);
		south.add(buttons, BorderLayout.EAST);

		JPanel content = new JPanel(new BorderLayout(8, 12));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(namePanel, BorderLayout.NORTH);
		content.add(splitPane, BorderLayout.CENTER);
		content.add(south, BorderLayout.SOUTH);

		setContentPane(content);
		getRootPane().setDefaultButton(ok);
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		setSize(1000, 650);
		setLocationRelativeTo(owner);
	}

	/**
	 * Shows the dialog and blocks until it is closed.
	 *
	 * @param user
	 * 		The user to edit, or {@code null} to create a new user
	 * @param nameTaken
	 * 		Tests whether a name is already taken by another user
	 *
	 * @return The created or edited user, or empty if the dialog was cancelled
	 */
	static Optional<DietUser> show(
			final Window owner,
			final DietUser user,
			final CheckBoxTreeNode<OWLClass> classRoot,
			final Function<OWLClass, String> renderClass,
			final Predicate<String> nameTaken
	) {
		UserEditorDialog dialog = new UserEditorDialog(owner, user, classRoot, renderClass, nameTaken);
		dialog.setVisible(true);
		return Optional.ofNullable(dialog.result);
	}

	/**
	 * Adds the class selected in the tree to the category, removing it from the categories it is exclusive with.
	 */
	private void addSelected(final int category) {
		OWLClass value = classTree.getSelectedValue();

		if (value != null) {
			foods.addItems(category, List.of(value));
		}
	}

	private void accept() {
		String name = nameField.getText().trim();

		if (DinnerPartyAxioms.sanitize(name).isEmpty()) {
			showError("Please enter a name containing letters or digits.");
			return;
		}

		if (nameTaken.test(name)) {
			showError("There already is a user named \"" + name + "\".");
			return;
		}

		result = FoodCategories.userOf(name, foods);
		dispose();
	}

	private void showError(String message) {
		JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.WARNING_MESSAGE);
		nameField.requestFocusInWindow();
	}
}
