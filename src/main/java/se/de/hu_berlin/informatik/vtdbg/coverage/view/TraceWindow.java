package se.de.hu_berlin.informatik.vtdbg.coverage.view;

import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.rt.coverage.traces.SequiturUtils;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.messages.MessageBus;
import de.unisb.cs.st.sequitur.input.InputSequence;
import org.jetbrains.annotations.NotNull;
import se.de.hu_berlin.informatik.vtdbg.coverage.Score;
import se.de.hu_berlin.informatik.vtdbg.coverage.tracedata.TraceDataManager;
import se.de.hu_berlin.informatik.vtdbg.coverage.tracedata.TraceIterator;
import se.de.hu_berlin.informatik.vtdbg.utils.EditorUtils;
import se.de.hu_berlin.informatik.vtdbg.utils.VirtualHelper;

import javax.swing.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the execution Traces of selected tests
 *
 * @author Dorottya Kregl
 * @author kregldor@hu-berlin.de
 * @version 1.0
 * @since 1.0
 */

public class TraceWindow {

    public static final String TAB_TITLE_PREFIX = "Thread ";
    private JPanel content;
    private JTabbedPane tabs;
    private JButton button1;
    private JButton colorButton;
    private JButton buttonLeft;
    private JButton buttonRight;


    List<? extends SMTestProxy> testResults;

    private final Project project;

    private Map<Integer, String> idToClassNameMap;
    private Map<Long, InputSequence<Long>> sequences = new HashMap<>();
    private Map<Long, TraceIterator> iterators = new HashMap<>();

    private boolean coloring = false;

    private static final Logger LOG = Logger.getInstance(TraceWindow.class);

    public TraceWindow(Project project, TraceDataManager data, String displayName) {
        this.project = project;
        readData(data, displayName);
        fillForm();
        showButtonDemo();
        showColorButtonDemo();

        registerNextButton();
        registerPreviousButton();
    }

    /**
     * @return everything (that is visible) from the TraceWindow
     */
    public JPanel getContent() {
        return content;
    }

    private void registerNextButton() {
        buttonRight.addActionListener(e -> {
            if (DumbService.isDumb(project)) {
                // ignore when not ready
                showNoIndexWarning();
                return;
            }

            try {
                TraceIterator iterator = getTraceIterator(false);
//                Map<String, List<Score>> map = new HashMap<>();
//                map.put("com.company.Main", Arrays.asList(new Score(13, 0.5), new Score(15, 0.2)));
//                map.put("com.company.TestClass", Arrays.asList(new Score(13, 0.1), new Score(21, 0.7)));
//                for (Map.Entry<String, List<Score>> item : map.entrySet()) {
//                    navigateToClass(project, item.getKey(), item.getValue());
//                }
                if (iterator.hasNext()) {
                    Pair<String, Integer> next = iterator.next();
                    EditorUtils.navigateToClass(project, next.first, next.second);
                    EditorUtils.colorClassSBFL(project, next.second, true);
                }
            } catch (NumberFormatException x) {
                LOG.error("Can't parse thread ID from tab title: " + tabs.getTitleAt(tabs.getSelectedIndex()));
            }
        });
    }

    private void showNoIndexWarning() {
        Messages.showMessageDialog(project,
                "please wait for indices to be generated",
                "Error", Messages.getWarningIcon());
    }

    private void registerPreviousButton() {
        buttonLeft.addActionListener(e -> {
            if (DumbService.isDumb(project)) {
                // ignore when not ready
                showNoIndexWarning();
                return;
            }

            try {
                TraceIterator iterator = getTraceIterator(true);

                if (iterator.hasPrevious()) {
                    Pair<String, Integer> previous = iterator.previous();
                    EditorUtils.navigateToClass(project, previous.first, previous.second);
                    EditorUtils.colorClassSBFL(project, previous.second, false);
                }
            } catch (NumberFormatException x) {
                LOG.error("Can't parse thread ID from tab title: " + tabs.getTitleAt(tabs.getSelectedIndex()));
            }
        });
    }

    @NotNull
    private TraceIterator getTraceIterator(boolean reverse) {
        String title = tabs.getTitleAt(tabs.getSelectedIndex());
        long threadId = Long.valueOf(title.substring(TAB_TITLE_PREFIX.length()));

        TraceIterator iterator = iterators.get(threadId);
        if (iterator == null) {
            iterator = new TraceIterator(sequences.get(threadId), idToClassNameMap,
                    reverse ? sequences.get(threadId).getLength() : 0);
            iterators.put(threadId, iterator);
        }
        return iterator;
    }


    private void showButtonDemo() {
        button1.addActionListener(e -> EditorUtils.navigateToClass(project, "com.company.Main", 13));

    }

    private void showColorButtonDemo() {
        colorButton.addActionListener(e -> {
            Map<String, List<Score>> map = new HashMap<>();
            map.put("com/company/Main", Arrays.asList(new Score(11, 0.5), new Score(12, 0.9),
                    new Score(13, 0.2), new Score(14, 0.5), new Score(15, 0.2)));
            map.put("com/company/TestClass", Arrays.asList(new Score(13, 0.1), new Score(21, 0.7)));

            EditorUtils.colorAllOpenClassSBFL(project, map, false);
            colorOpenedClass(map);
        });
    }

    /**
     * colors every newly opened editor window as long as SBFL scores are available in the map
     * after the color button has been clicked on at least once
     *
     * @param map includes the coverage data for all the executed tests
     */
    private void colorOpenedClass(Map<String, List<Score>> map) {
        if (!coloring) {
            MessageBus messageBus = project.getMessageBus();
            messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                @Override
                public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                    EditorUtils.colorClassSBFL(project, VirtualHelper.getShortPath(project, file), map, false);
                }
            });
        }
        coloring = true;
    }

    /**
     * collects and processes trace data
     *
     * @param dataManager the trace data manager which provides the data
     * @param displayName the respective data id
     */
    private void readData(TraceDataManager dataManager, String displayName) {
        // sample processing of provided execution trace
        Pair<Map<Long, byte[]>, Map<Integer, String>> traceData = dataManager.getTraceData(displayName);
        if (traceData != null) {
            idToClassNameMap = traceData.getSecond();
            Map<Long, byte[]> indexedTraces = traceData.getFirst();
            for (Map.Entry<Long, byte[]> entry : indexedTraces.entrySet()) {
                try {
                    sequences.put(entry.getKey(), // <- this is the thread ID
                            SequiturUtils.getInputSequenceFromByteArray(entry.getValue(), Long.class));
                } catch (IOException | ClassNotFoundException e) {
                    LOG.error("Could not read trace!", e);
                }
            }
        } else {
            LOG.warn("No trace data...");
        }
    }

    private void fillForm() {
        // this is only for testing and should be replaced by a chart view or something like that @Enrico
        if (sequences != null) {
            for (Map.Entry<Long, InputSequence<Long>> sequence : sequences.entrySet()) {
                StringBuilder sb = new StringBuilder();
                TraceIterator iterator = new TraceIterator(sequence.getValue(), idToClassNameMap, 0);
                while (iterator.hasNext()) {
                    Pair<String, Integer> next = iterator.next();
                    sb.append(next.first).append(": ").append(next.second).append(System.lineSeparator());
                }

                //printing the traces here only for testing / showing purposes
                JTextPane textPane = new JTextPane();
                textPane.setText(sb.toString());
                JBScrollPane jbScrollPane = new JBScrollPane(textPane);
                tabs.add(TAB_TITLE_PREFIX + sequence.getKey(), jbScrollPane);
            }
        }
    }

}
