package org.example;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import org.example.gui.components.ToolbarIcons;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Dialog mirroring the settings in {@code application.yaml}, plus a light/dark theme toggle that is not itself a
 * Spring property. Only the theme applies immediately; every other setting requires an application restart, since
 * they are already bound into other beans (the ontology, the reasoner, ...) by the time this dialog can be opened.
 */
final class SettingsDialog extends JDialog {

    private final JTextField serverPortField = new JTextField();
    private final JTextField ontologyFileField = new JTextField();
    private final JTextField koncludeOwlLinkServerField = new JTextField();
    private final JTextField koncludeFolderField = new JTextField();
    private final JTextField koncludeWorkersField = new JTextField();
    private final JCheckBox koncludeKeepRunningCheckBox = new JCheckBox();
    private final JTextField securityUsernameField = new JTextField();
    private final JPasswordField securityPasswordField = new JPasswordField();
    private final JComboBox<String> logLevelRootCombo = logLevelCombo();
    private final JComboBox<String> logLevelSpringFrameworkCombo = logLevelCombo();
    private final JComboBox<String> logLevelSpringSecurityWebCombo = logLevelCombo();
    private final JComboBox<String> logLevelApacheCombo = logLevelCombo();
    private final JTextField logFilePathField = new JTextField();
    private final JRadioButton lightThemeRadio = new JRadioButton("Light");
    private final JRadioButton darkThemeRadio = new JRadioButton("Dark");

    private final Path yamlPath;

    private SettingsDialog(final Window owner, final Path yamlPath, final AppSettings settings) {
        super(owner, "Settings", ModalityType.APPLICATION_MODAL);
        this.yamlPath = yamlPath;

        populate(settings);

        ButtonGroup themeGroup = new ButtonGroup();
        themeGroup.add(lightThemeRadio);
        themeGroup.add(darkThemeRadio);
        lightThemeRadio.addActionListener(e -> applyThemePreview());
        darkThemeRadio.addActionListener(e -> applyThemePreview());

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(ToolbarIcons.wrapWithTitle("Appearance", section("Theme:", themeRow())));
        form.add(spacer());
        form.add(ToolbarIcons.wrapWithTitle("Server", section("Port:", serverPortField)));
        form.add(spacer());
        form.add(ToolbarIcons.wrapWithTitle("Ontology", section("File:", withBrowseButton(ontologyFileField, false))));
        form.add(spacer());
        form.add(ToolbarIcons.wrapWithTitle("Reasoner (Konclude)", section(
                "OWLlink server:", koncludeOwlLinkServerField,
                "Folder:", withBrowseButton(koncludeFolderField, true),
                "Workers:", koncludeWorkersField,
                "Keep running on exit:", koncludeKeepRunningCheckBox
        )));
        form.add(spacer());
        form.add(ToolbarIcons.wrapWithTitle("Security", section(
                "Username:", securityUsernameField,
                "Password:", securityPasswordField
        )));
        form.add(spacer());
        form.add(ToolbarIcons.wrapWithTitle("Logging", section(
                "Root level:", logLevelRootCombo,
                "org.springframework level:", logLevelSpringFrameworkCombo,
                "o.s.s.web level:", logLevelSpringSecurityWebCombo,
                "org.apache level:", logLevelApacheCombo,
                "File directory:", withBrowseButton(logFilePathField, true)
        )));

        JLabel help = ToolbarIcons.createHelpIcon(
                "The theme applies immediately. Every other setting only takes effect the next time the"
                        + " application is started, since the ontology, the reasoner and the web server are"
                        + " already running with the current settings by the time this dialog can be opened."
        );

        JButton save = new JButton("Save");
        save.setFont(save.getFont().deriveFont(Font.BOLD));
        save.addActionListener(e -> save());

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(save);

        JPanel south = new JPanel(new BorderLayout());
        south.add(help, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);

        JScrollPane scrollPane = new JScrollPane(form);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        JPanel content = new JPanel(new BorderLayout(8, 12));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
        getRootPane().setDefaultButton(save);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(560, 640);
        setLocationRelativeTo(owner);
    }

    static void show(final Window owner) {
        Path path = AppSettingsFile.locate();
        AppSettings settings = AppSettingsFile.load(path);
        new SettingsDialog(owner, path, settings).setVisible(true);
    }

    private static JComboBox<String> logLevelCombo() {
        JComboBox<String> combo = new JComboBox<>(new String[]{"trace", "debug", "info", "warn", "error"});
        combo.setEditable(true);
        return combo;
    }

    /**
     * A simple two-column label/field grid, as used by every settings section.
     */
    private static JPanel section(Object... labelsAndFields) {
        JPanel panel = new JPanel(new GridLayout(labelsAndFields.length / 2, 2, 8, 6));

        for (Object labelOrField : labelsAndFields) {
            panel.add(labelOrField instanceof String text ? new JLabel(text) : (Component) labelOrField);
        }

        return panel;
    }

    private static Component spacer() {
        return javax.swing.Box.createVerticalStrut(12);
    }

    private Component themeRow() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        panel.add(lightThemeRadio);
        panel.add(darkThemeRadio);
        return panel;
    }

    private JComponent withBrowseButton(final JTextField field, final boolean directory) {
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);

            if (!field.getText().isBlank()) {
                chooser.setSelectedFile(new File(field.getText()));
            }

            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.add(field, BorderLayout.CENTER);
        panel.add(browse, BorderLayout.EAST);

        return panel;
    }

    private void populate(final AppSettings settings) {
        serverPortField.setText(Integer.toString(settings.serverPort()));
        ontologyFileField.setText(settings.ontologyFile());
        koncludeOwlLinkServerField.setText(settings.koncludeOwlLinkServer());
        koncludeFolderField.setText(settings.koncludeFolder());
        koncludeWorkersField.setText(settings.koncludeWorkers());
        koncludeKeepRunningCheckBox.setSelected(settings.koncludeKeepRunning());
        securityUsernameField.setText(settings.securityUsername());
        securityPasswordField.setText(settings.securityPassword());
        logLevelRootCombo.setSelectedItem(settings.logLevelRoot());
        logLevelSpringFrameworkCombo.setSelectedItem(settings.logLevelSpringFramework());
        logLevelSpringSecurityWebCombo.setSelectedItem(settings.logLevelSpringSecurityWeb());
        logLevelApacheCombo.setSelectedItem(settings.logLevelApache());
        logFilePathField.setText(settings.logFilePath());

        if (settings.theme() == AppSettings.Theme.LIGHT) {
            lightThemeRadio.setSelected(true);
        } else {
            darkThemeRadio.setSelected(true);
        }
    }

    /**
     * Switches the running application's theme immediately, so the choice can be previewed before saving.
     */
    private void applyThemePreview() {
        try {
            FlatLaf.setup(lightThemeRadio.isSelected() ? new FlatMacLightLaf() : new FlatMacDarkLaf());
            FlatLaf.updateUI();
        } catch (RuntimeException e) {
            // best-effort live preview only; the choice is saved regardless of whether this succeeded
        }
    }

    private void save() {
        int port;

        try {
            port = Integer.parseInt(serverPortField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Port must be a number.", "Settings", JOptionPane.WARNING_MESSAGE);
            return;
        }

        AppSettings settings = new AppSettings(
                port,
                ontologyFileField.getText().trim(),
                koncludeOwlLinkServerField.getText().trim(),
                koncludeFolderField.getText().trim(),
                koncludeWorkersField.getText().trim(),
                koncludeKeepRunningCheckBox.isSelected(),
                securityUsernameField.getText().trim(),
                new String(securityPasswordField.getPassword()),
                (String) logLevelRootCombo.getSelectedItem(),
                (String) logLevelSpringFrameworkCombo.getSelectedItem(),
                (String) logLevelSpringSecurityWebCombo.getSelectedItem(),
                (String) logLevelApacheCombo.getSelectedItem(),
                logFilePathField.getText().trim(),
                lightThemeRadio.isSelected() ? AppSettings.Theme.LIGHT : AppSettings.Theme.DARK
        );

        try {
            AppSettingsFile.save(yamlPath, settings);
            dispose();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    this,
                    "Could not save settings: " + e.getMessage(),
                    "Settings",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }
}
