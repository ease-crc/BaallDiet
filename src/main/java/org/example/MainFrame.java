package org.example;

import com.formdev.flatlaf.FlatClientProperties;
import jakarta.annotation.PostConstruct;
import lombok.val;
import org.example.gui.components.FilterableList;
import org.example.gui.components.LoadingListPanel;
import org.example.gui.components.LoadingTreePanel;
import org.example.gui.components.ToolbarIcons;
import org.example.gui.components.checkBoxTree.CheckBoxTree;
import org.example.gui.components.checkBoxTree.CheckBoxTreeNode;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.owllink.OWLlinkHTTPXMLReasoner;
import org.semanticweb.owlapi.owllink.builtin.requests.GetSubClassHierarchy;
import org.semanticweb.owlapi.owllink.builtin.response.ClassHierarchy;
import org.semanticweb.owlapi.owllink.builtin.response.HierarchyPair;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class MainFrame extends JFrame {

    private static final Logger LOGGER = LoggerFactory.getLogger(MainFrame.class);

    private static final List<String> SELECTION_STEP_LABELS = List.of(
            "Adding dietary concept",
            "Creating knowledge base",
            "Classifying ontology",
            "Querying permitted ingredients"
    );

    /*
     * Hard-coded concept root.
     * Change this IRI to your target class.
     */
    private static final String ROOT_CLASS_IRI = "http://ontologies.baall.de/FOOD#Food";

    private static final String FOOD_NS = "http://ontologies.baall.de/FOOD#";

    private static final String TAB_CUSTOM_DIET = "Custom Diet";

    private static final String TAB_DINNER_PARTY = "Dinner party";

    private static final String DIET_IRI = FOOD_NS + "Diet";

    private static final String PERMITS_IRI = FOOD_NS + "permits";

    private static final String PROCESSED_FROM_IRI =
            "http://ontologies.baall.de/FOOD#processed_from";
    private final OntologyManager ontologyManager;
    private final KoncludeManager koncludeManager;

    private OWLOntology ontology;
    /*
     * Volatile, as the reasoner is replaced by background workers and read on the EDT.
     */
    private volatile OWLReasoner reasoner;

    private CheckBoxTree<OWLClass> classTree;
    /*
     * The root of the taxonomy that classTree and the dinner party dialogs show.
     */
    private CheckBoxTreeNode<OWLClass> taxonomyRoot;

    private JPanel rootPanel;
    private JPanel centerPanel;
    private JPanel southPanel;
    private LoadingTreePanel loadingTreePanel;
    private JButton runButton;
    private JButton saveButton;
    private boolean computingPermittedIngredients;
    private boolean savingOntology;
    /*
     * Whether the ontology contains custom diets that are not saved to its file yet.
     */
    private boolean unsavedChanges;
    private DinnerPartyPanel dinnerPartyPanel;
    private FoodResultCache foodCache;
    private JMenuBar menuBar;

    @Autowired
    public MainFrame(
            final OntologyManager ontologyManager,
            @Lazy final KoncludeManager koncludeManager
    ) {
        super("Diet Ingredient Calculator");

        LOGGER.info("Creating MainFrame.");

        this.ontologyManager = ontologyManager;
        this.koncludeManager = koncludeManager;
    }

    private static void showFatal(Exception e) {
        LOGGER.error("Fatal error in MainFrame.", e);

        JTextArea area = new JTextArea(e.toString());
        area.setEditable(false);

        JOptionPane.showMessageDialog(
                null,
                new JScrollPane(area),
                "Fatal error",
                JOptionPane.ERROR_MESSAGE
        );
    }

    @PostConstruct
    private void start() {
        LOGGER.info("MainFrame PostConstruct called. Scheduling GUI creation on EDT.");

        SwingUtilities.invokeLater(() -> {
            LOGGER.info("Building loading GUI on EDT.");
            buildLoadingGui();

            LOGGER.info("Starting asynchronous ontology loading and tree generation.");
            loadOntologyAndBuildTreeAsync();
        });
    }

    private void buildLoadingGui() {
        long startNs = System.nanoTime();

        LOGGER.info("Configuring loading window.");

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 650));
        setJMenuBar(buildMenuBar());

        rootPanel = new JPanel(new BorderLayout(12, 12));
        rootPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 220, 220)),
                BorderFactory.createEmptyBorder()
        ));

        loadingTreePanel = new LoadingTreePanel();
        centerPanel.add(loadingTreePanel, BorderLayout.CENTER);

        southPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton disabledRunButton = new JButton("Compute permitted ingredients");
        disabledRunButton.setEnabled(false);
        disabledRunButton.setFont(disabledRunButton.getFont().deriveFont(Font.BOLD));

        southPanel.add(disabledRunButton);

        rootPanel.add(centerPanel, BorderLayout.CENTER);
        rootPanel.add(southPanel, BorderLayout.SOUTH);

        /*
         * Konclude is shut down by KoncludeServer when the application exits. The reasoner is
         * deliberately not disposed here: Manager's methods are synchronized, so doing so on
         * the EDT would block while a reasoner is being created, which can take minutes.
         */

        setContentPane(rootPanel);
        setLocationRelativeTo(null);
        setVisible(true);

        LOGGER.info("Loading GUI built in {}.", formatDuration(startNs));
    }

    private void loadOntologyAndBuildTreeAsync() {
        LOGGER.info("Creating ontology-loading SwingWorker.");

        SwingWorker<CheckBoxTreeNode<OWLClass>, String> worker = new SwingWorker<>() {

            @Override
            protected CheckBoxTreeNode<OWLClass> doInBackground() {
                long totalStartNs = System.nanoTime();

                LOGGER.info("Background ontology initialization started.");

                publishStatus("Loading ontology...");
                long ontologyStartNs = System.nanoTime();
                ontology = ontologyManager.getOntology();
                LOGGER.info(
                        "Ontology loaded in {}. Ontology ID: {}. Axiom count: {}. Class signature count: {}.",
                        formatDuration(ontologyStartNs),
                        ontology.getOntologyID(),
                        ontology.getAxiomCount(),
                        ontology.classesInSignature().count()
                );

                publishStatus("Starting reasoner...");
                long reasonerStartNs = System.nanoTime();
                reasoner = koncludeManager.getReasoner();
                LOGGER.info(
                        "Reasoner obtained in {}. Reasoner implementation: {}.",
                        formatDuration(reasonerStartNs),
                        reasoner == null ? "<null>" : reasoner.getClass().getName()
                );

                publishStatus("Precomputing class hierarchy...");
                long precomputeStartNs = System.nanoTime();
                reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
                LOGGER.info(
                        "Reasoner class hierarchy precomputation finished in {}.",
                        formatDuration(precomputeStartNs)
                );

                publishStatus("Building class tree...");
                long treeStartNs = System.nanoTime();
                CheckBoxTreeNode<OWLClass> rootNode = buildOntologyTree();
                LOGGER.info(
                        "Class tree built in {}. Total background initialization took {}.",
                        formatDuration(treeStartNs),
                        formatDuration(totalStartNs)
                );

                return rootNode;
            }

            @Override
            protected void process(List<String> chunks) {
                if (chunks.isEmpty()) {
                    return;
                }

                String latestStatus = chunks.get(chunks.size() - 1);
                LOGGER.debug("Updating loading panel status: {}", latestStatus);

                if (loadingTreePanel != null) {
                    loadingTreePanel.setStatus(latestStatus);
                }
            }

            @Override
            protected void done() {
                LOGGER.info("Ontology-loading SwingWorker finished. Switching to loaded tree view.");

                try {
                    CheckBoxTreeNode<OWLClass> rootNode = get();
                    showLoadedTree(rootNode);
                    LOGGER.info("Loaded tree successfully shown.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.error("Ontology-loading SwingWorker was interrupted.", e);
                    showFatal(e);
                } catch (ExecutionException e) {
                    LOGGER.error("Ontology-loading SwingWorker failed.", e);
                    showFatal(e);
                } catch (Exception e) {
                    LOGGER.error("Unexpected error while finalizing loaded tree.", e);
                    showFatal(e);
                }
            }

            private void publishStatus(String status) {
                LOGGER.info(status);
                publish(status);
            }
        };

        worker.execute();

        LOGGER.info("Ontology-loading SwingWorker submitted.");
    }

    private CheckBoxTreeNode<OWLClass> buildOntologyTree() {
        long startNs = System.nanoTime();

        LOGGER.info("Building ontology tree. Root class IRI: {}", ROOT_CLASS_IRI);

        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLClass rootClass = df.getOWLClass(IRI.create(ROOT_CLASS_IRI));

        LOGGER.info("Collecting named unsatisfiable classes.");
        Set<OWLClass> unsatisfiableClasses = getNamedUnsatisfiableClasses(df);

        LOGGER.info(
                "Found {} named unsatisfiable classes that are present in the ontology signature.",
                unsatisfiableClasses.size()
        );

        Map<OWLClass, List<OWLClass>> directSubclassCache = new HashMap<>();

        prefillDirectSubclassCache(unsatisfiableClasses, directSubclassCache);

        LOGGER.info("Starting visible subclass tree generation from {}.", renderClassWithIri(rootClass));

        CheckBoxTreeNode<OWLClass> conceptRootNode = buildSubclassTreeIterative(
                rootClass,
                unsatisfiableClasses,
                directSubclassCache
        );

        LOGGER.info(
                "Visible subclass tree generation completed. Direct subclass cache contains {} entries.",
                directSubclassCache.size()
        );

        if (unsatisfiableClasses.isEmpty()) {
            LOGGER.info("No unsatisfiable-class root needed. Ontology tree built in {}.", formatDuration(startNs));
            return conceptRootNode;
        }

        LOGGER.info("Adding separate owl:Nothing branch for {} unsatisfiable classes.", unsatisfiableClasses.size());

        CheckBoxTreeNode<OWLClass> visibleRoot =
                CheckBoxTreeNode.placeholder("Ontology classes");

        visibleRoot.add(conceptRootNode);
        visibleRoot.add(buildOwlNothingRoot(df, unsatisfiableClasses));

        LOGGER.info("Ontology tree with owl:Nothing branch built in {}.", formatDuration(startNs));

        return visibleRoot;
    }

    /**
     * Fills the given cache with the direct visible subclasses of all classes, using a single
     * OWLlink GetSubClassHierarchy request. Without this, building the tree sends one request
     * per class. Classes not covered by the hierarchy are still requested individually.
     */
    private void prefillDirectSubclassCache(
            Set<OWLClass> classesToHideFromNormalTree,
            Map<OWLClass, List<OWLClass>> directSubclassCache
    ) {
        if (!(reasoner instanceof OWLlinkHTTPXMLReasoner owllinkReasoner)) {
            return;
        }

        long startNs = System.nanoTime();

        LOGGER.info("Requesting complete class hierarchy from reasoner.");

        ClassHierarchy hierarchy = owllinkReasoner.answer(
                new GetSubClassHierarchy(owllinkReasoner.getDefaultKB())
        );

        Set<OWLClass> subClasses = new HashSet<>();

        for (HierarchyPair<OWLClass> pair : hierarchy.getPairs()) {
            List<OWLClass> children = toVisibleSortedClasses(
                    pair.getSubs().entities(),
                    classesToHideFromNormalTree
            );

            pair.getSuper().entities().forEach(c -> directSubclassCache.put(c, children));
            pair.getSubs().entities().forEach(subClasses::add);
        }

        /*
         * The hierarchy only contains pairs for classes that have subclasses. Classes that
         * only occur as subclasses are leaves.
         */
        subClasses.forEach(c -> directSubclassCache.putIfAbsent(c, List.of()));

        LOGGER.info(
                "Class hierarchy with {} pairs received in {}. Direct subclass cache contains {} entries.",
                hierarchy.getPairs().size(),
                formatDuration(startNs),
                directSubclassCache.size()
        );
    }

    private List<OWLClass> toVisibleSortedClasses(
            Stream<OWLClass> classes,
            Set<OWLClass> classesToHideFromNormalTree
    ) {
        return classes
                .filter(c -> !c.isOWLNothing())
                .filter(c -> !classesToHideFromNormalTree.contains(c))
                .sorted(Comparator.comparing(this::renderClass))
                .toList();
    }

    private List<OWLClass> getDirectVisibleSubclasses(
            OWLClass owlClass,
            Set<OWLClass> classesToHideFromNormalTree
    ) {
        LOGGER.debug("Requesting direct subclasses for {}.", renderClassWithIri(owlClass));

        long startNs = System.nanoTime();

        NodeSet<OWLClass> subClasses = reasoner.getSubClasses(owlClass, true);

        List<OWLClass> children = toVisibleSortedClasses(subClasses.entities(), classesToHideFromNormalTree);

        LOGGER.debug(
                "Found {} visible direct subclasses for {} in {}.",
                children.size(),
                renderClassWithIri(owlClass),
                formatDuration(startNs)
        );

        return children;
    }

    /*
     * This frame deliberately does not store a copied Set<OWLClass> path.
     *
     * Instead, it points to its parent frame. That avoids allocating and copying
     * a LinkedHashSet for every node. Cycle checks walk parent links upward,
     * which is usually cheap because ontology class hierarchy depth is normally
     * far smaller than hierarchy width.
     */
    private record TreeBuildFrame(
            OWLClass owlClass,
            CheckBoxTreeNode<OWLClass> node,
            TreeBuildFrame parent,
            int depth
    ) {
    }

    private CheckBoxTreeNode<OWLClass> buildSubclassTreeIterative(
            OWLClass rootClass,
            Set<OWLClass> classesToHideFromNormalTree,
            Map<OWLClass, List<OWLClass>> directSubclassCache
    ) {
        long startNs = System.nanoTime();

        LOGGER.info("Iterative subclass tree build started at {}.", renderClassWithIri(rootClass));

        CheckBoxTreeNode<OWLClass> rootNode = new CheckBoxTreeNode<>(rootClass);

        Deque<TreeBuildFrame> stack = new ArrayDeque<>();
        Set<OWLClass> expandedClasses = new LinkedHashSet<>();

        /*
         * A class may occur under several parents because OWL class hierarchies
         * are DAGs, not strict trees. We expand each class only once. Later
         * occurrences are still added as visible leaf/reference nodes, but their
         * subtrees are not expanded again.
         */
        expandedClasses.add(rootClass);

        stack.push(new TreeBuildFrame(
                rootClass,
                rootNode,
                null,
                1
        ));

        int expandedNodes = 0;
        int visibleNodes = 1;
        int createdChildLinks = 0;
        int duplicateReferenceLeaves = 0;
        int skippedCycleEdges = 0;
        int maxStackSize = stack.size();
        int maxDepth = 1;

        while (!stack.isEmpty()) {
            maxStackSize = Math.max(maxStackSize, stack.size());

            TreeBuildFrame frame = stack.pop();

            OWLClass currentClass = frame.owlClass();
            CheckBoxTreeNode<OWLClass> currentNode = frame.node();

            expandedNodes++;
            maxDepth = Math.max(maxDepth, frame.depth());

            List<OWLClass> children = directSubclassCache.computeIfAbsent(
                    currentClass,
                    cls -> getDirectVisibleSubclasses(cls, classesToHideFromNormalTree)
            );

            /*
             * Stack is LIFO. Push children in reverse order so that the final tree
             * keeps the same visual ordering as the sorted children list.
             */
            ListIterator<OWLClass> iterator = children.listIterator(children.size());

            while (iterator.hasPrevious()) {
                OWLClass childClass = iterator.previous();

                if (appearsInPath(frame, childClass)) {
                    skippedCycleEdges++;

                    LOGGER.warn(
                            "Skipping cycle edge while building tree: {} -> {}. Current depth: {}.",
                            renderClassWithIri(currentClass),
                            renderClassWithIri(childClass),
                            frame.depth()
                    );

                    continue;
                }

                CheckBoxTreeNode<OWLClass> childNode = new CheckBoxTreeNode<>(childClass);

                currentNode.add(childNode);
                visibleNodes++;
                createdChildLinks++;

                if (!expandedClasses.add(childClass)) {
                    duplicateReferenceLeaves++;

                    LOGGER.debug(
                            "Added duplicate/reference leaf for {} under {}, but will not expand it again.",
                            renderClassWithIri(childClass),
                            renderClassWithIri(currentClass)
                    );

                    continue;
                }

                stack.push(new TreeBuildFrame(
                        childClass,
                        childNode,
                        frame,
                        frame.depth() + 1
                ));
            }
        }

        LOGGER.info(
                "Iterative subclass tree build finished in {}. Expanded unique nodes: {}. Visible nodes: {}. Created child links: {}. Duplicate/reference leaves: {}. Skipped cycle edges: {}. Max stack size: {}. Max depth: {}. Subclass cache entries: {}.",
                formatDuration(startNs),
                expandedNodes,
                visibleNodes,
                createdChildLinks,
                duplicateReferenceLeaves,
                skippedCycleEdges,
                maxStackSize,
                maxDepth,
                directSubclassCache.size()
        );

        if (duplicateReferenceLeaves > 0) {
            LOGGER.info(
                    "Detected {} duplicate class occurrences caused by multiple inheritance. They were shown as leaf/reference nodes instead of expanding their subtrees repeatedly.",
                    duplicateReferenceLeaves
            );
        }

        return rootNode;
    }

    private boolean appearsInPath(TreeBuildFrame frame, OWLClass owlClass) {
        TreeBuildFrame current = frame;

        while (current != null) {
            if (current.owlClass().equals(owlClass)) {
                return true;
            }

            current = current.parent();
        }

        return false;
    }

    private Set<OWLClass> getNamedUnsatisfiableClasses(OWLDataFactory df) {
        long startNs = System.nanoTime();

        LOGGER.info("Requesting unsatisfiable classes from reasoner.");

        OWLClass owlNothing = df.getOWLNothing();

        val classes = reasoner.getUnsatisfiableClasses();

        Set<OWLClass> namedUnsatisfiableClasses = classes.entities()
                .filter(c -> !c.equals(owlNothing))
                .filter(c -> ontology.containsClassInSignature(c.getIRI()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (namedUnsatisfiableClasses.isEmpty()) {
            LOGGER.info("No named unsatisfiable classes found in {}.", formatDuration(startNs));
            return namedUnsatisfiableClasses;
        }

        LOGGER.warn(
                "Found {} named unsatisfiable classes in {}.",
                namedUnsatisfiableClasses.size(),
                formatDuration(startNs)
        );

        namedUnsatisfiableClasses.forEach(c ->
                LOGGER.warn("Unsatisfiable class: {}", c.getIRI())
        );

        return namedUnsatisfiableClasses;
    }

    private CheckBoxTreeNode<OWLClass> buildOwlNothingRoot(
            OWLDataFactory df,
            Set<OWLClass> unsatisfiableClasses
    ) {
        long startNs = System.nanoTime();

        LOGGER.info("Building owl:Nothing tree branch for {} classes.", unsatisfiableClasses.size());

        OWLClass owlNothing = df.getOWLNothing();
        CheckBoxTreeNode<OWLClass> owlNothingNode = new CheckBoxTreeNode<>(owlNothing);

        unsatisfiableClasses.stream()
                .sorted(Comparator.comparing(this::renderClass))
                .map(CheckBoxTreeNode::new)
                .forEach(owlNothingNode::add);

        LOGGER.info("owl:Nothing branch built in {}.", formatDuration(startNs));

        return owlNothingNode;
    }

    private void showLoadedTree(CheckBoxTreeNode<OWLClass> rootNode) {
        long startNs = System.nanoTime();

        LOGGER.info("Replacing loading panel with loaded checkbox tree.");

        if (loadingTreePanel != null) {
            LOGGER.debug("Stopping loading animation.");
            loadingTreePanel.stopAnimation();
        }

        int visibleNodeCount = countTreeNodes(rootNode);

        LOGGER.info("Creating CheckBoxTree. Visible node count: {}.", visibleNodeCount);

        taxonomyRoot = rootNode;
        foodCache = new FoodResultCache(FoodResultCache.defaultFile(), ontology);
        classTree = new CheckBoxTree<>(rootNode, this::renderClass);

        JPanel selectionTab = new JPanel(new BorderLayout(0, 8));
        selectionTab.add(classTree, BorderLayout.CENTER);
        selectionTab.add(buildButtonPanel(), BorderLayout.SOUTH);

        dinnerPartyPanel = new DinnerPartyPanel(
                rootNode,
                this::renderClass,
                this::computeDinnerPartyFoodsAndShowPopup,
                new DinnerPartyStore(
                        DinnerPartyStore.defaultFile(),
                        ontology.getOWLOntologyManager().getOWLDataFactory(),
                        c -> ontology.containsClassInSignature(c.getIRI())
                )
        );

        CardLayout cardLayout = new CardLayout();
        JPanel tabContent = new JPanel(cardLayout);
        tabContent.add(selectionTab, TAB_CUSTOM_DIET);
        tabContent.add(dinnerPartyPanel, TAB_DINNER_PARTY);

        // the tabs are shown in the title bar, in front of the settings button
        ButtonGroup tabGroup = new ButtonGroup();
        addTitleBarTab(
                TAB_CUSTOM_DIET,
                "Select base foods in the tree, name a new dietary concept, and compute the foods that are only"
                        + " processed from your selection: the concept's permitted ingredients. The new diet is added to the"
                        + " ontology and the class tree; save writes the ontology, including new diets, to its file.",
                0,
                tabGroup,
                () -> cardLayout.show(tabContent, TAB_CUSTOM_DIET)
        ).setSelected(true);
        addTitleBarTab(
                TAB_DINNER_PARTY,
                "Manage users with permitted, favorite and forbidden foods, choose who attends the dinner party, and"
                        + " compute the foods everyone attending may eat.",
                1,
                tabGroup,
                () -> cardLayout.show(tabContent, TAB_DINNER_PARTY)
        );

        centerPanel.removeAll();
        centerPanel.setBorder(BorderFactory.createEmptyBorder());
        centerPanel.add(tabContent, BorderLayout.CENTER);

        /*
         * The tabs bring their own buttons: drop the button row of the loading screen and use the space more tightly,
         * as the tab content follows the title bar directly.
         */
        rootPanel.remove(southPanel);
        rootPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));

        rootPanel.revalidate();
        rootPanel.repaint();

        LOGGER.info("Loaded checkbox tree shown in {}.", formatDuration(startNs));
    }

    /**
     * Adds a tab-styled button to the menu bar, which FlatLaf embeds in the title bar of the window.
     *
     * @param index
     *         The position in the menu bar, in front of the settings button
     * @param onSelected
     *         Is run when the tab is selected
     */
    private JToggleButton addTitleBarTab(
            String title,
            String helpText,
            int index,
            ButtonGroup group,
            Runnable onSelected
    ) {
        JToggleButton tab = new JToggleButton(title);
        tab.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TAB);
        tab.setFocusable(false);
        tab.setToolTipText("<html><div style='width:320px'>" + helpText + "</div></html>");
        tab.addActionListener(e -> onSelected.run());

        group.add(tab);
        menuBar.add(tab, index);
        menuBar.revalidate();
        menuBar.repaint();

        return tab;
    }

    private JMenuBar buildMenuBar() {
        menuBar = new JMenuBar();
        // pushes the settings button to the right; the tabs are added in front of the glue
        menuBar.add(Box.createHorizontalGlue());
        menuBar.add(ToolbarIcons.createToolbarButton(new ToolbarIcons.GearIcon(), "Settings", this::openSettings));

        return menuBar;
    }

    private void openSettings() {
        SettingsDialog.show(this);
    }

    private JPanel buildButtonPanel() {
        LOGGER.debug("Building bottom button panel.");

        runButton = new JButton("Compute permitted ingredients");
        runButton.setFont(runButton.getFont().deriveFont(Font.BOLD));
        runButton.addActionListener(e -> runSelectionMethodAndShowPopup());

        classTree.setOnSelectedValuesChanged(this::updateRunButtonState);
        updateRunButtonState();

        saveButton = new JButton("Save ontology");
        saveButton.setToolTipText("Overwrites the ontology file with the ontology including the custom diets.");
        saveButton.addActionListener(e -> saveOntologyAfterConfirmation());
        updateSaveButtonState();

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(saveButton);
        buttons.add(runButton);

        return buttons;
    }

    private void updateRunButtonState() {
        runButton.setEnabled(!computingPermittedIngredients && !classTree.getSelectedValues().isEmpty());
        updateSaveButtonState();
    }

    private void updateSaveButtonState() {
        if (saveButton != null) {
            saveButton.setEnabled(unsavedChanges && !savingOntology && !computingPermittedIngredients);
        }
    }

    /**
     * Overwrites the ontology file with the ontology, which includes the custom diets created so far.
     */
    private void saveOntologyAfterConfirmation() {
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Overwrite " + ontologyManager.getOntologyFile() + "\nwith the ontology including the custom diets?",
                "Save ontology",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        savingOntology = true;
        updateSaveButtonState();

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws OWLOntologyStorageException {
                long startNs = System.nanoTime();
                ontologyManager.saveOntology();
                LOGGER.info("Saved ontology to {} in {}.", ontologyManager.getOntologyFile(), formatDuration(startNs));
                return null;
            }

            @Override
            protected void done() {
                savingOntology = false;

                try {
                    get();
                    unsavedChanges = false;
                    JOptionPane.showMessageDialog(
                            MainFrame.this,
                            "Saved " + ontologyManager.getOntologyFile(),
                            "Save ontology",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    LOGGER.error("Saving the ontology failed.", e);
                    showFatal(e);
                } finally {
                    updateSaveButtonState();
                }
            }
        }.execute();
    }

    /**
     * Adds the given concept to the taxonomy shown in the trees as a child of the root class, unless it is shown
     * already. Must be called on the EDT.
     */
    private void addToTaxonomy(OWLClass concept) {
        CheckBoxTreeNode<OWLClass> parent = findNode(ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLClass(IRI.create(ROOT_CLASS_IRI)));

        if (parent == null || findNode(concept) != null) {
            return;
        }

        int index = 0;

        while (index < parent.getChildCount()
                && renderClass(parent.getChildAtTyped(index).getValue()).compareTo(renderClass(concept)) < 0) {
            index++;
        }

        parent.insert(new CheckBoxTreeNode<>(concept), index);
        classTree.refresh();
    }

    private CheckBoxTreeNode<OWLClass> findNode(OWLClass owlClass) {
        Enumeration<?> nodes = taxonomyRoot.depthFirstEnumeration();

        while (nodes.hasMoreElements()) {
            if (nodes.nextElement() instanceof CheckBoxTreeNode<?> node && owlClass.equals(node.getValue())) {
                @SuppressWarnings("unchecked")
                CheckBoxTreeNode<OWLClass> typed = (CheckBoxTreeNode<OWLClass>) node;
                return typed;
            }
        }

        return null;
    }

    private void runSelectionMethodAndShowPopup() {
        LOGGER.info("Run selection method button clicked.");

        /*
         * This method receives the content of the right-hand selected-class
         * list, not checkbox states from the tree.
         */
        List<OWLClass> selectedClasses = classTree.getSelectedValues();

        LOGGER.info("Collected {} selected classes from tree.", selectedClasses.size());

        if (selectedClasses.isEmpty()) {
            LOGGER.info("No selected classes. Showing empty result.");
            showResultPopup(List.of());
            return;
        }

        if (LOGGER.isDebugEnabled()) {
            selectedClasses.forEach(c ->
                    LOGGER.debug("Selected class: {}", renderClassWithIri(c))
            );
        }

        final String conceptName = askForNewConceptName();

        if (conceptName == null || conceptName.isBlank()) {
            LOGGER.info("No usable concept name provided. Not running the selection method.");
            return;
        }

        computingPermittedIngredients = true;
        updateRunButtonState();

        runInResultPopup(
                "Permitted ingredients",
                "Computing permitted ingredients for " + conceptName.trim(),
                SELECTION_STEP_LABELS,
                stepListener -> computeOtherClassesLater(selectedClasses, conceptName, stepListener),
                () -> {
                    computingPermittedIngredients = false;
                    updateRunButtonState();
                }
        );
    }

    private void computeDinnerPartyFoodsAndShowPopup(List<DietUser> guests) {
        LOGGER.info("Computing dinner party foods for {} guests.", guests.size());

        dinnerPartyPanel.setComputing(true);

        runInResultPopup(
                "Dinner party foods",
                "Computing foods for " + guests.stream().map(DietUser::name).collect(Collectors.joining(", ")),
                DinnerPartyPlanner.STEP_LABELS,
                stepListener -> DinnerPartyPlanner.computeFoods(guests, ontology, koncludeManager, foodCache, stepListener)
                        .stream()
                        .sorted(Comparator.comparing(this::renderClass))
                        .toList(),
                () -> dinnerPartyPanel.setComputing(false)
        );
    }

    /**
     * Shows a result popup with a loading indicator, runs the given computation in the background and replaces the
     * loading indicator with the computed classes. Blocks until the popup is closed.
     *
     * @param computation
     *         Computes the classes to show, reporting the index of the step in {@code stepLabels} that is being
     *         started
     * @param onFinished
     *         Is run on the EDT when the computation has finished, successfully or not
     */
    private void runInResultPopup(
            String dialogTitle,
            String loadingTitle,
            List<String> stepLabels,
            Function<IntConsumer, List<OWLClass>> computation,
            Runnable onFinished
    ) {
        final JDialog dialog = createResultDialog(dialogTitle);
        final LoadingListPanel loadingPanel = new LoadingListPanel(loadingTitle, stepLabels);
        dialog.setContentPane(wrapDialogContent(loadingPanel));

        SwingWorker<List<OWLClass>, Integer> worker = new SwingWorker<>() {

            @Override
            protected List<OWLClass> doInBackground() {
                final long startNs = System.nanoTime();

                List<OWLClass> result = computation.apply(step -> publish(step));

                LOGGER.info(
                        "{}: computed {} result classes in {}.",
                        dialogTitle,
                        result.size(),
                        formatDuration(startNs)
                );

                return result;
            }

            @Override
            protected void process(List<Integer> steps) {
                loadingPanel.setCurrentStep(steps.getLast());
            }

            @Override
            protected void done() {
                onFinished.run();
                loadingPanel.stopAnimation();

                final List<OWLClass> result;

                try {
                    result = get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    dialog.dispose();
                    return;
                } catch (ExecutionException e) {
                    LOGGER.error("{}: computation failed.", dialogTitle, e);
                    dialog.dispose();
                    showFatal(e);
                    return;
                }

                if (!dialog.isDisplayable()) {
                    LOGGER.info("Result popup was closed before the result was available.");
                    return;
                }

                LOGGER.info("Replacing loading indicator with {} result classes.", result.size());

                dialog.setContentPane(buildResultPanel(result, dialog));
                dialog.revalidate();
                dialog.repaint();
            }
        };

        worker.execute();

        LOGGER.info("Displaying result popup with loading indicator.");

        dialog.setVisible(true);
    }

    private String askForNewConceptName() {
        LOGGER.debug("Asking user for new concept name.");

        String conceptName = JOptionPane.showInputDialog(
                this,
                "Name of the new dietary concept:",
                "Compute permitted ingredients",
                JOptionPane.PLAIN_MESSAGE
        );

        if (conceptName == null) {
            LOGGER.info("User cancelled new concept dialog.");
        } else if (conceptName.isBlank()) {
            LOGGER.info("User submitted blank new concept name.");
        } else {
            LOGGER.info("User entered new concept name: {}", conceptName);
        }

        return conceptName;
    }

    private String sanitizeFragment(String value) {
        String sanitized = value.trim()
                .replaceAll("\\s+", "_")
                .replaceAll("[^A-Za-z0-9_\\-]", "");

        if (!value.equals(sanitized)) {
            LOGGER.info("Sanitized concept fragment from '{}' to '{}'.", value, sanitized);
        } else {
            LOGGER.debug("Concept fragment did not need sanitizing: '{}'.", value);
        }

        return sanitized;
    }

    /**
     * Computes the result classes of the selection method. This takes long and must not be called on the EDT.
     *
     * @param stepListener
     *         Receives the index of the step in {@link #SELECTION_STEP_LABELS} that is being started
     */
    private List<OWLClass> computeOtherClassesLater(
            final List<OWLClass> selectedClasses,
            final String conceptName,
            final IntConsumer stepListener
    ) {
        final long startNs = System.nanoTime();

        LOGGER.info("Computing result classes for {} selected classes.", selectedClasses.size());

        stepListener.accept(0);

        final OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        final OWLOntologyManager manager = ontology.getOWLOntologyManager();

        final String sanitizedFragment = sanitizeFragment(conceptName);

        final OWLClass newConcept = df.getOWLClass(
                IRI.create(FOOD_NS + sanitizedFragment)
        );

        LOGGER.info("New concept class will be {}.", renderClassWithIri(newConcept));

        final OWLObjectProperty processedFrom = df.getOWLObjectProperty(
                IRI.create(PROCESSED_FROM_IRI)
        );

        final OWLClassExpression processedFromSomeNewConcept =
                df.getOWLObjectSomeValuesFrom(processedFrom, newConcept);

        final OWLClassExpression processedFromOnlyNewConcept =
                df.getOWLObjectAllValuesFrom(processedFrom, newConcept);

        final OWLClassExpression definition = df.getOWLObjectIntersectionOf(
                processedFromSomeNewConcept,
                processedFromOnlyNewConcept
        );

        final OWLEquivalentClassesAxiom equivalentClassesAxiom =
                df.getOWLEquivalentClassesAxiom(newConcept, definition);

        final List<OWLSubClassOfAxiom> selectedClassSubClassAxioms = selectedClasses.stream()
                .map(selectedClass -> df.getOWLSubClassOfAxiom(selectedClass, newConcept))
                .toList();

        /*
         * Like the ontology's own diets (e.g. Vegan_Diet), the new diet is a Diet that permits the new concept.
         */
        final OWLClass newDiet = df.getOWLClass(IRI.create(FOOD_NS + sanitizedFragment + "_Diet"));

        final List<OWLAxiom> axiomsToAdd = new ArrayList<>();
        axiomsToAdd.add(equivalentClassesAxiom);
        axiomsToAdd.addAll(selectedClassSubClassAxioms);
        axiomsToAdd.add(df.getOWLDeclarationAxiom(newDiet));
        axiomsToAdd.add(df.getOWLSubClassOfAxiom(newDiet, df.getOWLClass(IRI.create(DIET_IRI))));
        axiomsToAdd.add(df.getOWLSubClassOfAxiom(
                newDiet,
                df.getOWLObjectSomeValuesFrom(df.getOWLObjectProperty(IRI.create(PERMITS_IRI)), newConcept)
        ));
        axiomsToAdd.add(df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(),
                newConcept.getIRI(),
                df.getOWLLiteral(conceptName.trim(), "en")
        ));
        axiomsToAdd.add(df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(),
                newDiet.getIRI(),
                df.getOWLLiteral(conceptName.trim() + " diet", "en")
        ));

        final Set<OWLAxiom> axiomSetToAdd = new LinkedHashSet<>(axiomsToAdd);

        LOGGER.info(
                "Adding {} axioms to ontology. These axioms will remain in the ontology.",
                axiomSetToAdd.size()
        );

        final long addStartNs = System.nanoTime();

        /*
         * The permitted foods of the selection only depend on the ontology without the new diet, so a user with
         * the same classes has computed them already, and vice versa. Look them up before the ontology changes.
         */
        final Optional<Set<OWLClass>> knownFoods = foodCache.get(FoodResultCache.Kind.PERMITTED, selectedClasses);

        manager.addAxioms(ontology, axiomSetToAdd);
        foodCache.ontologyChanged();

        LOGGER.info(
                "Added {} axioms to ontology in {}. Ontology axiom count is now {}.",
                axiomSetToAdd.size(),
                formatDuration(addStartNs),
                ontology.getAxiomCount()
        );

        SwingUtilities.invokeLater(() -> {
            unsavedChanges = true;
            updateSaveButtonState();
            addToTaxonomy(newConcept);
        });

        if (knownFoods.isPresent()) {
            LOGGER.info("Using the cached permitted foods of the selection; no reasoning needed.");

            stepListener.accept(3);

            foodCache.put(FoodResultCache.Kind.PERMITTED, selectedClasses, knownFoods.get());
            foodCache.save();

            return knownFoods.get().stream()
                    .filter(c -> !c.equals(newConcept))
                    .sorted(Comparator.comparing(this::renderClass))
                    .toList();
        }

        /*
         * Konclude via OWLlink does not support ontology changes.
         *
         * Therefore, do not call:
         *
         *     reasoner.flush();
         *
         * Instead, create a temporary reasoner over the changed ontology. The managed
         * reasoner, which the class tree was built with, is deliberately kept. The
         * temporary reasoner is disposed afterwards, which releases its knowledge base
         * in Konclude to free memory. Also, there is deliberately no finally block
         * that removes the axioms again, because the changes should be kept.
         */
        final long recreateStartNs = System.nanoTime();

        stepListener.accept(1);

        LOGGER.info("Creating temporary Konclude reasoner for the changed ontology.");

        final OWLReasoner selectionReasoner = koncludeManager.createUnmanagedReasoner(ontology, Set.of());
        final List<OWLClass> result;

        try {
            LOGGER.info(
                    "Temporary Konclude reasoner created in {}.",
                    formatDuration(recreateStartNs)
            );

            final long precomputeStartNs = System.nanoTime();

            stepListener.accept(2);

            LOGGER.info("Precomputing class hierarchy with temporary reasoner.");

            selectionReasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);

            LOGGER.info(
                    "Class hierarchy precomputation with temporary reasoner finished in {}.",
                    formatDuration(precomputeStartNs)
            );

            stepListener.accept(3);

            LOGGER.info("Querying subclasses of new concept {}.", renderClassWithIri(newConcept));

            final long queryStartNs = System.nanoTime();

            result = selectionReasoner.getSubClasses(newConcept, false)
                    .entities()
                    .filter(c -> !c.isOWLNothing())
                    .filter(c -> !c.equals(newConcept))
                    .sorted(Comparator.comparing(this::renderClass))
                    .toList();

            LOGGER.info(
                    "Subclass query for new concept returned {} classes in {}. Total computation time: {}.",
                    result.size(),
                    formatDuration(queryStartNs),
                    formatDuration(startNs)
            );
        } finally {
            selectionReasoner.dispose();
        }

        foodCache.put(FoodResultCache.Kind.PERMITTED, selectedClasses, new LinkedHashSet<>(result));
        foodCache.save();

        if (LOGGER.isDebugEnabled()) {
            result.forEach(c ->
                    LOGGER.debug("Result class: {}", renderClassWithIri(c))
            );
        }

        return result;
    }

    private void showResultPopup(List<OWLClass> classes) {
        LOGGER.info("Showing result popup with {} classes.", classes.size());

        JDialog dialog = createResultDialog("Permitted ingredients");
        dialog.setContentPane(buildResultPanel(classes, dialog));
        dialog.setVisible(true);

        LOGGER.info("Result popup closed.");
    }

    private JDialog createResultDialog(String title) {
        JDialog dialog = new JDialog(this, title, true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(650, 450);
        dialog.setLocationRelativeTo(this);

        return dialog;
    }

    private static JPanel wrapDialogContent(java.awt.Component content) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(content, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildResultPanel(List<OWLClass> classes, JDialog dialog) {
        long startNs = System.nanoTime();

        FilterableList<String> resultList = new FilterableList<>(Function.identity(), "Search results…");
        resultList.getList().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        resultList.setItems(classes.stream().map(this::renderClassWithIri).toList());

        JButton copySelected = new JButton("Copy selected");
        copySelected.addActionListener(e -> {
            List<String> values = resultList.getList().getSelectedValuesList();

            if (values.isEmpty()) {
                LOGGER.info("Copy requested with no explicit list selection. Copying all currently shown result classes.");
                values = resultList.getVisibleItems();
            } else {
                LOGGER.info("Copying {} selected result classes.", values.size());
            }

            String text = String.join(System.lineSeparator(), values);

            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(text), null);

            LOGGER.info("Copied result classes to clipboard.");
        });

        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            LOGGER.info("Closing result popup.");
            dialog.dispose();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(copySelected);
        buttons.add(close);

        JPanel panel = wrapDialogContent(resultList);
        panel.add(buttons, BorderLayout.SOUTH);

        LOGGER.info("Result panel prepared in {}.", formatDuration(startNs));

        return panel;
    }

    private String renderClass(OWLClass owlClass) {
        IRI iri = owlClass.getIRI();
        String fragment = iri.getFragment();

        if (fragment != null && !fragment.isBlank()) {
            return fragment;
        }

        String iriString = iri.toString();
        int slash = Math.max(iriString.lastIndexOf('/'), iriString.lastIndexOf('#'));

        return slash >= 0 ? iriString.substring(slash + 1) : iriString;
    }

    private String renderClassWithIri(OWLClass owlClass) {
        return renderClass(owlClass) + "    <" + owlClass.getIRI() + ">";
    }

    private int countTreeNodes(CheckBoxTreeNode<OWLClass> rootNode) {
        if (rootNode == null) {
            return 0;
        }

        int count = 0;

        Deque<CheckBoxTreeNode<OWLClass>> stack = new ArrayDeque<>();
        stack.push(rootNode);

        while (!stack.isEmpty()) {
            CheckBoxTreeNode<OWLClass> node = stack.pop();
            count++;

            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                stack.push((CheckBoxTreeNode<OWLClass>) node.getChildAt(i));
            }
        }

        return count;
    }

    private static String formatDuration(long startNs) {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        if (elapsedMs < 1_000) {
            return elapsedMs + " ms";
        }

        return String.format("%.2f s", elapsedMs / 1_000.0);
    }
}