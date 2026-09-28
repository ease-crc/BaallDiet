package org.example;

import com.formdev.flatlaf.FlatClientProperties;
import org.example.gui.components.CategoryForest;
import org.example.gui.components.FilterableList;
import org.example.gui.components.ToolbarIcons;
import org.example.gui.components.checkBoxTree.CheckBoxTreeNode;
import org.semanticweb.owlapi.model.OWLClass;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Panel to manage {@link DietUser}s, choose the guests of a dinner party and compute the foods they may all eat.
 * Users and guests are persisted by a {@link DinnerPartyStore} after every change.
 */
final class DinnerPartyPanel extends JPanel {

	/**
	 * Width of the check box area in a user row; clicks there toggle whether the user attends.
	 */
	private static final int CHECK_BOX_AREA_WIDTH = 26;

	private final CheckBoxTreeNode<OWLClass> classRoot;
	private final Function<OWLClass, String> renderClass;
	private final Consumer<List<DietUser>> computeHandler;
	private final DinnerPartyStore store;

	/**
	 * The users that exist. The single source of truth; see {@link FilterableList}.
	 */
	private final FilterableList<DietUser> userSearchList = new FilterableList<>(DietUser::name, "Search users…");

	/**
	 * Names of the users that attend the dinner party.
	 */
	private final Set<String> guestNames = new LinkedHashSet<>();

	private static final String CARD_EMPTY = "empty";
	private static final String CARD_LISTS = "lists";

	private final JLabel detailsTitle = ToolbarIcons.createHeaderLabel(" ");
	private final JLabel noUserSelectedLabel = new JLabel();
	private final CardLayout detailsCardLayout = new CardLayout();
	private final JPanel detailsCards = new JPanel(detailsCardLayout);

	/**
	 * The permitted and forbidden foods of the currently selected user. Constructed in the constructor, since it
	 * needs {@code renderClass}, which is only available there (a field initializer runs before the constructor body
	 * assigns it).
	 */
	private final CategoryForest<OWLClass> foods;

	private final JButton editButton =
			ToolbarIcons.createToolbarButton(new ToolbarIcons.EditIcon(), "Edit user", this::editSelectedUser);
	private final JButton removeButton =
			ToolbarIcons.createToolbarButton(new ToolbarIcons.RemoveIcon(), "Remove user", this::removeSelectedUser);

	private final JButton computeButton = new JButton("Compute dinner party foods");
	private final JLabel guestSummary = new JLabel();

	private boolean computing;

	/**
	 * @param classRoot
	 * 		The root of the class tree to choose permitted and forbidden foods from
	 * @param computeHandler
	 * 		Computes the foods for the given guests
	 * @param store
	 * 		Persists the users and guests. Its state is loaded initially.
	 */
	DinnerPartyPanel(
			final CheckBoxTreeNode<OWLClass> classRoot,
			final Function<OWLClass, String> renderClass,
			final Consumer<List<DietUser>> computeHandler,
			final DinnerPartyStore store
	) {
		super(new BorderLayout(0, 8));

		this.classRoot = classRoot;
		this.renderClass = renderClass;
		this.computeHandler = computeHandler;
		this.store = store;

		this.foods = FoodCategories.createForest(
				() -> classRoot,
				renderClass,
				ToolbarIcons.createToolbarButton(new ToolbarIcons.AddIcon(), "Add foods…", this::editSelectedUser)
		);
		foods.setOnChanged(this::foodsChanged);

		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildUserPanel(), buildDetailsPanel());
		splitPane.setResizeWeight(0.35);
		splitPane.setBorder(ToolbarIcons.outlineBorder());
		splitPane.setContinuousLayout(true);
		ToolbarIcons.styleSplitPane(splitPane);

		computeButton.setFont(computeButton.getFont().deriveFont(Font.BOLD));
		computeButton.addActionListener(e -> computeHandler.accept(getGuests()));

		guestSummary.setForeground(UIManager.getColor("Label.disabledForeground"));

		JButton examplesButton = new JButton("Add example guests");
		examplesButton.setToolTipText("Adds a halal, a kosher, an ovo-lacto-pescetarian and a pure-food guest (with a nut allergy) and a guest without pet food.");
		examplesButton.addActionListener(e -> addExampleGuests());

		JPanel summary = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
		summary.add(examplesButton);
		summary.add(guestSummary);

		JPanel south = new JPanel(new BorderLayout());
		south.add(summary, BorderLayout.WEST);
		south.add(computeButton, BorderLayout.EAST);

		add(splitPane, BorderLayout.CENTER);
		add(south, BorderLayout.SOUTH);

		final DinnerPartyStore.State restored = store.load();
		userSearchList.setItems(restored.users());
		guestNames.addAll(restored.guestNames());

		updateState();
	}

	/**
	 * Disables computing while a computation is running.
	 */
	void setComputing(final boolean computing) {
		this.computing = computing;
		updateState();
	}

	private Component buildUserPanel() {
		JList<DietUser> userList = userSearchList.getList();

		userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		userList.setCellRenderer(new UserCellRenderer());
		userList.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				updateState();
			}
		});
		userList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int index = userList.locationToIndex(e.getPoint());
				Rectangle bounds = index < 0 ? null : userList.getCellBounds(index, index);

				if (bounds == null || !bounds.contains(e.getPoint())) {
					return;
				}

				if (e.getX() - bounds.x < CHECK_BOX_AREA_WIDTH) {
					toggleGuest(userList.getModel().getElementAt(index));
				} else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
					editSelectedUser();
				}
			}
		});
		userList.getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "toggleGuest");
		userList.getActionMap().put("toggleGuest", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				DietUser user = userList.getSelectedValue();
				if (user != null) {
					toggleGuest(user);
				}
			}
		});

		userSearchList.addToolbarButton(ToolbarIcons.createToolbarButton(new ToolbarIcons.AddIcon(), "Add user…", this::addUser));
		userSearchList.addToolbarButton(editButton);
		userSearchList.addToolbarButton(removeButton);

		return ToolbarIcons.wrapWithHeader("Users", userSearchList);
	}

	private Component buildDetailsPanel() {
		noUserSelectedLabel.setHorizontalAlignment(SwingConstants.CENTER);
		noUserSelectedLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

		detailsCards.add(centered(noUserSelectedLabel), CARD_EMPTY);
		detailsCards.add(foods, CARD_LISTS);

		JPanel panel = new JPanel(new BorderLayout());
		panel.add(detailsTitle, BorderLayout.NORTH);
		panel.add(detailsCards, BorderLayout.CENTER);

		return panel;
	}

	private static Component centered(final Component content) {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.putClientProperty(FlatClientProperties.STYLE, "background: $List.background");
		panel.add(content);
		return panel;
	}

	private void addUser() {
		UserEditorDialog.show(SwingUtilities.getWindowAncestor(this), null, classRoot, renderClass, name -> isNameTaken(name, null))
				.ifPresent(user -> {
					userSearchList.addItem(user);
					// new users attend by default
					guestNames.add(user.name());
					userSearchList.selectItem(user);
					updateState();
					persist();
				});
	}

	/**
	 * Adds the example guests of the store that do not exist yet; they attend by default.
	 */
	private void addExampleGuests() {
		DietUser first = null;

		for (DietUser example : store.exampleGuests()) {
			if (isNameTaken(example.name(), null)) {
				continue;
			}

			userSearchList.addItem(example);
			guestNames.add(example.name());
			first = first == null ? example : first;
		}

		if (first != null) {
			userSearchList.selectItem(first);
		}

		updateState();
		persist();
	}

	private void editSelectedUser() {
		DietUser selected = userSearchList.getList().getSelectedValue();

		if (selected == null) {
			return;
		}

		UserEditorDialog.show(
						SwingUtilities.getWindowAncestor(this),
						selected,
						classRoot,
						renderClass,
						name -> isNameTaken(name, selected)
				)
				.ifPresent(edited -> {
					if (guestNames.remove(selected.name())) {
						guestNames.add(edited.name());
					}
					userSearchList.replaceItem(selected, edited);
					userSearchList.selectItem(edited);
					updateState();
					persist();
				});
	}

	private void removeSelectedUser() {
		DietUser selected = userSearchList.getList().getSelectedValue();

		if (selected == null) {
			return;
		}

		int filteredIndex = userSearchList.getList().getSelectedIndex();

		userSearchList.removeItem(selected);
		guestNames.remove(selected.name());

		int size = userSearchList.getList().getModel().getSize();

		if (size > 0) {
			userSearchList.getList().setSelectedIndex(Math.min(filteredIndex, size - 1));
		}

		updateState();
		persist();
	}

	/**
	 * The user changed the foods of the selected user in the forest, e.g., by drag and drop.
	 */
	private void foodsChanged() {
		DietUser selected = userSearchList.getList().getSelectedValue();

		if (selected == null) {
			return;
		}

		DietUser updated = FoodCategories.userOf(selected.name(), foods);
		userSearchList.replaceItem(selected, updated);
		userSearchList.selectItem(updated);
		updateState();
		persist();
	}

	/**
	 * Names are compared after {@link DinnerPartyAxioms#sanitize sanitization}, as they become part of class IRIs.
	 */
	private boolean isNameTaken(final String name, final DietUser except) {
		String sanitized = DinnerPartyAxioms.sanitize(name);

		return userSearchList.getItems().stream()
				.filter(u -> u != except)
				.anyMatch(u -> DinnerPartyAxioms.sanitize(u.name()).equalsIgnoreCase(sanitized));
	}

	private void toggleGuest(final DietUser user) {
		if (!guestNames.remove(user.name())) {
			guestNames.add(user.name());
		}
		userSearchList.getList().repaint();
		updateState();
		persist();
	}

	private void persist() {
		store.save(userSearchList.getItems(), guestNames);
	}

	private List<DietUser> getGuests() {
		List<DietUser> guests = new ArrayList<>();

		for (DietUser user : userSearchList.getItems()) {
			if (guestNames.contains(user.name())) {
				guests.add(user);
			}
		}

		return guests;
	}

	private void updateState() {
		DietUser selected = userSearchList.getList().getSelectedValue();

		editButton.setEnabled(selected != null);
		removeButton.setEnabled(selected != null);

		if (selected != null) {
			FoodCategories.show(foods, selected);
		}

		List<DietUser> allUsers = userSearchList.getItems();

		if (selected == null) {
			// not empty, so that the title keeps its height
			detailsTitle.setText(" ");
			noUserSelectedLabel.setText(allUsers.isEmpty()
					                            ? "Add a user to see their foods."
					                            : "Select a user to see their foods.");
			detailsCardLayout.show(detailsCards, CARD_EMPTY);
		} else {
			detailsTitle.setText(selected.name());
			detailsCardLayout.show(detailsCards, CARD_LISTS);
		}

		int guests = getGuests().size();
		guestSummary.setText(allUsers.isEmpty()
				                     ? "Tick the users who attend the dinner party."
				                     : guests + " of " + allUsers.size() + " users attend.");

		computeButton.setEnabled(guests > 0 && !computing);
		computeButton.setText(computing ? "Computing…" : "Compute dinner party foods");
	}

	private class UserCellRenderer implements javax.swing.ListCellRenderer<DietUser> {

		private final JCheckBox checkBox = new JCheckBox();

		@Override
		public Component getListCellRendererComponent(
				JList<? extends DietUser> list,
				DietUser user,
				int index,
				boolean selected,
				boolean focus
		) {
			checkBox.setSelected(guestNames.contains(user.name()));
			checkBox.setText("<html>" + escape(user.name()) + "&nbsp;&nbsp;<span style='color:" + hex(UIManager.getColor("Label.disabledForeground")) + "'>"
					                 + describe(user) + "</span></html>");
			checkBox.setOpaque(true);
			checkBox.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
			checkBox.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
			checkBox.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			checkBox.setBorderPainted(true);

			return checkBox;
		}

		private static String describe(DietUser user) {
			if (user.permitted().isEmpty()) {
				return "all foods permitted, " + user.forbidden().size() + " forbidden";
			}

			return count(user.permitted()) + " permitted, "
					+ user.forbidden().size() + " forbidden";
		}

		/**
		 * An empty list does not restrict the foods.
		 */
		private static String count(List<OWLClass> classes) {
			return classes.isEmpty() ? "any" : String.valueOf(classes.size());
		}

		private static String escape(String text) {
			return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		}

		private static String hex(Color color) {
			return color == null ? "gray" : String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
		}
	}
}
