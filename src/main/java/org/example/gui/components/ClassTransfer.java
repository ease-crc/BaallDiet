package org.example.gui.components;

import org.example.gui.components.checkBoxTree.FilterableTree;

import javax.swing.JComponent;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.List;

/**
 * What is dragged between a {@link FilterableTree} of classes and a {@link CategoryForest}, within one JVM.
 */
public final class ClassTransfer {

    /**
     * A dragged value.
     *
     * @param value
     *         The dragged value
     * @param sourceCategory
     *         The index of the category of a {@link CategoryForest} that the value is dragged from, or {@code -1} if
     *         it does not come from a forest, e.g., from a taxonomy
     */
    public record Entry<T>(T value, int sourceCategory) {
    }

    /**
     * The values that are dragged.
     */
    public record Payload<T>(List<Entry<T>> entries) {
    }

    static final DataFlavor FLAVOR = new DataFlavor(Payload.class, "Classes");

    private ClassTransfer() {
    }

    static <T> Transferable transferable(Payload<T> payload) {
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{FLAVOR};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return FLAVOR.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(flavor)) {
                    throw new UnsupportedFlavorException(flavor);
                }

                return payload;
            }
        };
    }

    /**
     * Lets the user drag the selected value of the tree onto a {@link CategoryForest}. Nothing can be dropped on the
     * tree.
     */
    public static <T> void installDragSource(FilterableTree<T> tree) {
        tree.getTree().setDragEnabled(true);
        tree.getTree().setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                T value = tree.getSelectedValue();

                return value == null ? null : transferable(new Payload<>(List.of(new Entry<>(value, -1))));
            }
        });
    }
}
