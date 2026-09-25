package org.example.gui.components;

import lombok.RequiredArgsConstructor;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

@RequiredArgsConstructor(staticName = "of")
public class SimpleDocumentListener implements DocumentListener {

    private final Runnable callback;

    @Override
    public void insertUpdate(DocumentEvent e) {
        callback.run();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        callback.run();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        callback.run();
    }
}