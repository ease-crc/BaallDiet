package org.example;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

import javax.swing.*;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
@SpringBootApplication
@ConfigurationPropertiesScan
public class App {

    static void main(String[] args) {
        setupLookAndFeel();

        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(App.class)
                        .headless(false)
                        .web(WebApplicationType.NONE)
                        .run(args);

        SwingUtilities.invokeLater(() -> {
            MainFrame mainFrame = context.getBean(MainFrame.class);
            mainFrame.setVisible(true);
        });
    }

    private static void setupLookAndFeel() {
        AppSettings settings = AppSettingsFile.load(AppSettingsFile.locate());

        if (settings.theme() == AppSettings.Theme.LIGHT) {
            FlatMacLightLaf.setup();
        } else {
            FlatMacDarkLaf.setup();
        }

        // draw the window's title bar ourselves, so the settings button can be embedded in it
        JFrame.setDefaultLookAndFeelDecorated(true);

        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("TitlePane.menuBarEmbedded", true);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 8);
    }
}