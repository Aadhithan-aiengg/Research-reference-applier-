import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import javax.swing.text.rtf.RTFEditorKit;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.text.DecimalFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.net.URI;

public class WordEditor extends JFrame {
    // The main text component supporting styled (rich) text
    private final JTextPane editor = new JTextPane();

    // We use RTFEditorKit to read/write RTF files (keeps formatting)
    private final RTFEditorKit rtfKit = new RTFEditorKit();

    // Undo manager to support undo/redo operations
    private final UndoManager undoManager = new UndoManager();

    // File currently opened or saved
    private File currentFile = null;

    // Tracks whether the document has unsaved changes
    private boolean isDirty = false;

    // Zoom factor (1.0 means 100%) used for display scaling
    private float zoomFactor = 1.0f;

    // Status bar labels
    private final JLabel statusWords = new JLabel("Words: 0");
    private final JLabel statusChars = new JLabel("Chars: 0");
    private final JLabel statusZoom = new JLabel("Zoom: 100%");

    // Font selectors in toolbar
    private final JComboBox<String> fontFamilyCombo = new JComboBox<>(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    private final JSpinner fontSizeSpinner = new JSpinner(new SpinnerNumberModel(14, 6, 200, 1));

    // Simple formatter for zoom display
    private final DecimalFormat zoomFormat = new DecimalFormat("#%");

    public WordEditor() {
        super("Word Editor");

        // Tell Swing to honor display properties for font scaling
        editor.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        editor.setEditorKit(rtfKit); // enable RTF handling
        editor.setFont(new Font("Serif", Font.PLAIN, 14));

        // Wrap the editor in a scroll pane
        JScrollPane scrollPane = new JScrollPane(editor);

        // Build the UI: menu bar, toolbar, status bar
        setJMenuBar(buildMenuBar());
        JToolBar toolBar = buildToolBar();
        JPanel statusBar = buildStatusBar();

        // Use BorderLayout: toolbar at top, editor center, status bar bottom
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(toolBar, BorderLayout.NORTH);
        getContentPane().add(scrollPane, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);

        // Wire up undo/redo
        editor.getDocument().addUndoableEditListener(undoManager);

        // Track changes for status updates and dirty flag
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { markDirtyAndUpdateStatus(); }
            @Override public void removeUpdate(DocumentEvent e) { markDirtyAndUpdateStatus(); }
            @Override public void changedUpdate(DocumentEvent e) { markDirtyAndUpdateStatus(); }
        });

        // Update counts when caret moves (e.g., after selection changes)
        editor.addCaretListener(e -> updateStatus());

        // Window close handler to prompt for unsaved changes
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { 
                if (confirmLoseUnsavedChanges()) {
                    dispose();
                }
            }
        });

        // Initial status update
        updateStatus();

        installPasteHook();
        installLinkHandler();

        // Set sensible window size and center on screen
        setSize(1000, 700);
        setLocationRelativeTo(null);
    }

    /**
     * Build the application menu bar (File, Edit, Format, Insert, View, Help).
     */
    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        fileMenu.add(menuItem("New", KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), e -> doNew()));
        fileMenu.add(menuItem("Open...", KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), e -> doOpen()));
        fileMenu.add(menuItem("Save", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), e -> doSave(false)));
        fileMenu.add(menuItem("Save As...", null, e -> doSave(true)));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Print...", KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK), e -> doPrint()));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Exit", null, e -> {
            if (confirmLoseUnsavedChanges()) dispose();
        }));

        JMenu editMenu = new JMenu("Edit");
        editMenu.add(menuItem("Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), e -> doUndo()));
        editMenu.add(menuItem("Redo", KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), e -> doRedo()));
        editMenu.addSeparator();
        editMenu.add(menuItem("Cut", KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), e -> editor.cut()));
        editMenu.add(menuItem("Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), e -> editor.copy()));
        editMenu.add(menuItem("Paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), e -> performPasteAction()));
        editMenu.add(menuItem("Select All", KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), e -> editor.selectAll()));
        editMenu.addSeparator();
        editMenu.add(menuItem("Find...", KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), e -> openFindReplace(true)));
        editMenu.add(menuItem("Replace...", KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), e -> openFindReplace(false)));

        JMenu formatMenu = new JMenu("Format");
        formatMenu.add(menuItem("Bold", KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK), e -> toggleBold()));
        formatMenu.add(menuItem("Italic", KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK), e -> toggleItalic()));
        formatMenu.add(menuItem("Underline", KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK), e -> toggleUnderline()));
        formatMenu.addSeparator();
        formatMenu.add(menuItem("Text Color...", null, e -> pickTextColor()));
        formatMenu.add(menuItem("Highlight Color...", null, e -> pickHighlightColor()));
        formatMenu.addSeparator();
        formatMenu.add(menuItem("Align Left", null, e -> setAlignment(StyleConstants.ALIGN_LEFT)));
        formatMenu.add(menuItem("Align Center", null, e -> setAlignment(StyleConstants.ALIGN_CENTER)));
        formatMenu.add(menuItem("Align Right", null, e -> setAlignment(StyleConstants.ALIGN_RIGHT)));

        JMenu insertMenu = new JMenu("Insert");
        insertMenu.add(menuItem("Image...", null, e -> insertImage()));

        JMenu viewMenu = new JMenu("View");
        viewMenu.add(menuItem("Zoom In", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK), e -> zoom(1.1f)));
        viewMenu.add(menuItem("Zoom Out", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), e -> zoom(1/1.1f)));
        viewMenu.add(menuItem("Reset Zoom", null, e -> resetZoom()));

        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(menuItem("About", null, e -> showAbout()));

        JMenu toolsMenu = new JMenu("Tools");
        toolsMenu.add(menuItem("Citation Capture...", null, e -> runCitationTool()));

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(formatMenu);
        menuBar.add(insertMenu);
        menuBar.add(viewMenu);
        menuBar.add(helpMenu);
        menuBar.add(toolsMenu);
        return menuBar;
    }

    /**
     * Build the toolbar with font selectors and common formatting actions.
     */
    private JToolBar buildToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        // Font family combo box: applies to current selection
        fontFamilyCombo.setSelectedItem("Serif");
        fontFamilyCombo.setMaximumSize(new Dimension(200, 28));
        fontFamilyCombo.addActionListener(e -> applyFontFamily());

        // Font size spinner: applies to current selection
        fontSizeSpinner.addChangeListener(e -> applyFontSize());

        JButton boldBtn = new JButton("B");
        boldBtn.setFont(boldBtn.getFont().deriveFont(Font.BOLD));
        boldBtn.addActionListener(e -> toggleBold());

        JButton italicBtn = new JButton("I");
        italicBtn.setFont(italicBtn.getFont().deriveFont(Font.ITALIC));
        italicBtn.addActionListener(e -> toggleItalic());

        JButton underlineBtn = new JButton("U");
        underlineBtn.addActionListener(e -> toggleUnderline());

        JButton colorBtn = new JButton("Text Color");
        colorBtn.addActionListener(e -> pickTextColor());

        JButton highlightBtn = new JButton("Highlight");
        highlightBtn.addActionListener(e -> pickHighlightColor());

        JButton leftBtn = new JButton("Left");
        leftBtn.addActionListener(e -> setAlignment(StyleConstants.ALIGN_LEFT));

        JButton centerBtn = new JButton("Center");
        centerBtn.addActionListener(e -> setAlignment(StyleConstants.ALIGN_CENTER));

        JButton rightBtn = new JButton("Right");
        rightBtn.addActionListener(e -> setAlignment(StyleConstants.ALIGN_RIGHT));

        JButton imageBtn = new JButton("Image");
        imageBtn.addActionListener(e -> insertImage());

        JButton zoomInBtn = new JButton("Zoom +");
        zoomInBtn.addActionListener(e -> zoom(1.1f));

        JButton zoomOutBtn = new JButton("Zoom -");
        zoomOutBtn.addActionListener(e -> zoom(1/1.1f));

        JButton zoomResetBtn = new JButton("Reset");
        zoomResetBtn.addActionListener(e -> resetZoom());

        tb.add(new JLabel("Font:"));
        tb.add(fontFamilyCombo);
        tb.add(new JLabel("Size:"));
        tb.add(fontSizeSpinner);
        tb.addSeparator();
        tb.add(boldBtn);
        tb.add(italicBtn);
        tb.add(underlineBtn);
        tb.addSeparator();
        tb.add(colorBtn);
        tb.add(highlightBtn);
        tb.addSeparator();
        tb.add(leftBtn);
        tb.add(centerBtn);
        tb.add(rightBtn);
        tb.addSeparator();
        tb.add(imageBtn);
        tb.addSeparator();
        tb.add(zoomInBtn);
        tb.add(zoomOutBtn);
        tb.add(zoomResetBtn);

        return tb;
    }

    /**
     * Build the status bar showing words, characters, and zoom.
     */
    private JPanel buildStatusBar() {
        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT));
        status.setBorder(new EmptyBorder(4, 8, 4, 8));
        status.add(statusWords);
        status.add(new JSeparator(SwingConstants.VERTICAL));
        status.add(statusChars);
        status.add(new JSeparator(SwingConstants.VERTICAL));
        status.add(statusZoom);
        return status;
    }

    /** Utility to create a menu item with optional accelerator and handler. */
    private JMenuItem menuItem(String title, KeyStroke accel, ActionListener handler) {
        JMenuItem item = new JMenuItem(title);
        if (accel != null) item.setAccelerator(accel);
        item.addActionListener(handler);
        return item;
    }

    /** Mark document as dirty and update status bar. */
    private void markDirtyAndUpdateStatus() {
        isDirty = true;
        updateStatus();
    }

    /** Update word count, char count, and zoom labels. */
    private void updateStatus() {
        String text;
        try {
            Document d = editor.getDocument();
            text = d.getText(0, d.getLength());
        } catch (BadLocationException e) {
            text = editor.getText();
        }
        if (text == null) text = "";
        int chars = text.length();
        String trimmed = text.trim();
        int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        statusChars.setText("Chars: " + chars);
        statusWords.setText("Words: " + words);
        statusZoom.setText("Zoom: " + (int)(zoomFactor * 100) + "%");
    }

    /** Show About dialog. */
    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "Word Editor\nJava Swing rich text editor\n© Your Name",
                "About",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** Create a new document (prompt if unsaved changes). */
    private void doNew() {
        if (!confirmLoseUnsavedChanges()) return;
        editor.setDocument(new DefaultStyledDocument());
        editor.setEditorKit(rtfKit);
        currentFile = null;
        isDirty = false;
        updateStatus();
    }

    /** Open an existing TXT or RTF file. */
    private void doOpen() {
        if (!confirmLoseUnsavedChanges()) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text/RTF", "txt", "rtf"));
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try (FileInputStream fis = new FileInputStream(file)) {
                if (file.getName().toLowerCase().endsWith(".rtf")) {
                    // Read RTF keeping styles
                    StyledDocument doc = new DefaultStyledDocument();
                    rtfKit.read(fis, doc, 0);
                    editor.setDocument(doc);
                    editor.setEditorKit(rtfKit);
                } else {
                    // Read plain text
                    String content = new String(fis.readAllBytes());
                    StyledDocument doc = new DefaultStyledDocument();
                    try {
                        doc.insertString(0, content, null);
                    } catch (BadLocationException ignored) {}
                    editor.setDocument(doc);
                    editor.setEditorKit(rtfKit); // keep RTF kit for later saves
                }
                currentFile = file;
                isDirty = false;
                updateStatus();
            } catch (Exception ex) {
                showError("Failed to open file: " + ex.getMessage());
            }
        }
    }

    /** Save the document. If saveAs is true, always prompt for location. */
    private void doSave(boolean saveAs) {
        try {
            if (currentFile == null || saveAs) {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Save As");
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("RTF Document (*.rtf)", "rtf"));
                int result = chooser.showSaveDialog(this);
                if (result != JFileChooser.APPROVE_OPTION) return;
                currentFile = chooser.getSelectedFile();
                // Ensure .rtf extension so styles are preserved
                if (!currentFile.getName().toLowerCase().endsWith(".rtf")) {
                    currentFile = new File(currentFile.getAbsolutePath() + ".rtf");
                }
            }
            try (FileOutputStream fos = new FileOutputStream(currentFile)) {
                rtfKit.write(fos, editor.getDocument(), 0, editor.getDocument().getLength());
                isDirty = false;
            }
        } catch (Exception ex) {
            showError("Failed to save file: " + ex.getMessage());
        }
    }

    /** Print the document using the standard print dialog. */
    private void doPrint() {
        try {
            boolean success = editor.print();
            if (!success) showError("Printing was canceled or failed.");
        } catch (Exception ex) {
            showError("Failed to print: " + ex.getMessage());
        }
    }

    /** Undo last edit. */
    private void doUndo() {
        try { undoManager.undo(); } catch (CannotUndoException ignored) {}
    }

    /** Redo last undone edit. */
    private void doRedo() {
        try { undoManager.redo(); } catch (CannotRedoException ignored) {}
    }

    /** Toggle bold on selection. */
    private void toggleBold() {
        StyledEditorKit.BoldAction action = new StyledEditorKit.BoldAction();
        action.actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, null));
    }

    /** Toggle italic on selection. */
    private void toggleItalic() {
        StyledEditorKit.ItalicAction action = new StyledEditorKit.ItalicAction();
        action.actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, null));
    }

    /** Toggle underline on selection. */
    private void toggleUnderline() {
        StyledEditorKit.UnderlineAction action = new StyledEditorKit.UnderlineAction();
        action.actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, null));
    }

    /** Apply selected font family to current selection. */
    private void applyFontFamily() {
        String family = (String) fontFamilyCombo.getSelectedItem();
        if (family == null) return;
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attrs, family);
        editor.setCharacterAttributes(attrs, false);
    }

    /** Apply selected font size to current selection. */
    private void applyFontSize() {
        int size = (Integer) fontSizeSpinner.getValue();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setFontSize(attrs, size);
        editor.setCharacterAttributes(attrs, false);
    }

    /** Pick a text color and apply to selection. */
    private void pickTextColor() {
        Color chosen = JColorChooser.showDialog(this, "Text Color", Color.BLACK);
        if (chosen == null) return;
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, chosen);
        editor.setCharacterAttributes(attrs, false);
    }

    /** Pick a highlight color and apply to selection. */
    private void pickHighlightColor() {
        Color chosen = JColorChooser.showDialog(this, "Highlight Color", Color.YELLOW);
        if (chosen == null) return;
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setBackground(attrs, chosen);
        editor.setCharacterAttributes(attrs, false);
    }

    /** Set paragraph alignment for the current selection. */
    private void setAlignment(int alignment) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setAlignment(attrs, alignment);
        int start = editor.getSelectionStart();
        int end = editor.getSelectionEnd();
        StyledDocument doc = editor.getStyledDocument();
        doc.setParagraphAttributes(start, end - start, attrs, false);
    }

    /** Insert an image at the current caret position. */
    private void insertImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Insert Image");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif"));
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            ImageIcon icon = new ImageIcon(file.getAbsolutePath());
            // Scale down very large images for reasonable display
            int maxW = 600, maxH = 400;
            if (icon.getIconWidth() > maxW || icon.getIconHeight() > maxH) {
                float scale = Math.min((float)maxW / icon.getIconWidth(), (float)maxH / icon.getIconHeight());
                Image scaled = icon.getImage().getScaledInstance((int)(icon.getIconWidth() * scale), (int)(icon.getIconHeight() * scale), Image.SCALE_SMOOTH);
                icon = new ImageIcon(scaled);
            }
            editor.insertIcon(icon);
        }
    }

    /** Open the find/replace dialog. */
    private void openFindReplace(boolean findOnly) {
        new FindReplaceDialog(this, editor, findOnly).setVisible(true);
    }

    /** Adjust zoom by multiplying zoomFactor. */
    private void zoom(float factor) {
        zoomFactor *= factor;
        // Update the base font used by the editor. Note: styled runs with explicit sizes won't scale with this.
        Font base = editor.getFont();
        float newSize = Math.max(8f, base.getSize2D() * factor);
        editor.setFont(base.deriveFont(newSize));
        updateStatus();
    }

    /** Reset zoom to 100%. */
    private void resetZoom() {
        zoomFactor = 1.0f;
        editor.setFont(editor.getFont().deriveFont(14f));
        updateStatus();
    }

    /** Show an error message dialog. */
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    /** Ask user to confirm losing unsaved changes. */
    private boolean confirmLoseUnsavedChanges() {
        if (!isDirty) return true;
        int choice = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes. Continue?",
                "Unsaved Changes",
                JOptionPane.YES_NO_OPTION);
        return choice == JOptionPane.YES_OPTION;
    }

    public static void main(String[] args) {
        // Launch on the Swing event dispatch thread
        SwingUtilities.invokeLater(() -> new WordEditor().setVisible(true));
    }

    private void runCitationTool() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select PDF directory for citation capture");
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;
        File dir = chooser.getSelectedFile();
        clearPendingCitation();
        String scriptPath = new File("C:\\Users\\user\\OneDrive\\Desktop\\wordeditor\\Word\\src\\CitationMain.py").getAbsolutePath();
        String[] candidates = new String[] {"py", "python", "python3"};

        ProcessConsoleDialog console = new ProcessConsoleDialog(this, res -> {
            if (res != null) {
                SwingUtilities.invokeLater(() -> processCitationResult(res));
            }
        }, this::clearPendingCitation);
        console.setVisible(true);

        new Thread(() -> {
            for (String interp : candidates) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(interp, scriptPath, dir.getAbsolutePath());
                    pb.environment().put("PYTHONIOENCODING", "utf-8");
                    pb.directory(new File("."));
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    console.attachProcess(p);
                    console.readOutput();
                    return;
                } catch (IOException ignored) {
                }
            }
            console.append("Failed to start Python. Ensure Python is installed and available in PATH.\n");
        }).start();
    }

    private String pendingCsv;
    private String pendingLinkUrl;
    private int pendingUrlOffset;

    private void insertCitation(CitationResult res) {
        String text;
        if (res.csv != null && !res.csv.isBlank()) {
            text = res.csv + "\n";
        } else {
            String title = res.title != null ? res.title : "";
            String year = res.year != null ? res.year : "";
            String authors = res.authors != null ? res.authors : "";
            text = "Title: " + title + "\n" +
                    "Year: " + year + "\n" +
                    "Authors: " + authors + "\n";
        }
        try {
            editor.getDocument().insertString(editor.getCaretPosition(), text, null);
        } catch (BadLocationException ignored) {}
    }

    private void enqueueCitation(CitationResult res) {
        if (res.csv != null && !res.csv.isBlank()) {
            pendingCsv = res.csv;
        } else {
            String title = res.title != null ? res.title : "";
            String year = res.year != null ? res.year : "";
            String authors = res.authors != null ? res.authors : "";
            pendingCsv = title + "," + year + "," + authors;
        }
    }

    private void installPasteHook() {
        Action original = editor.getActionMap().get(DefaultEditorKit.pasteAction);
        Action wrapped = new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (original != null) original.actionPerformed(e);
                insertPendingCsvIfAny();
            }
        };
        editor.getActionMap().put(DefaultEditorKit.pasteAction, wrapped);
    }

    private void performPasteAction() {
        Action a = editor.getActionMap().get(DefaultEditorKit.pasteAction);
        if (a != null) {
            a.actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, null));
        } else {
            editor.paste();
            insertPendingCsvIfAny();
        }
    }

    private void insertPendingCsvIfAny() {
        String toInsert = pendingCsv;
        if (toInsert != null && !toInsert.isBlank()) {
            try {
                int pos = editor.getCaretPosition();
                editor.getDocument().insertString(pos, "\n" + toInsert + "\n", null);
                if (pendingLinkUrl != null && !pendingLinkUrl.isBlank()) {
                    int linkStart = pos + 1 + pendingUrlOffset;
                    int linkLen = toInsert.length() - pendingUrlOffset;
                    SimpleAttributeSet attrs = new SimpleAttributeSet();
                    StyleConstants.setForeground(attrs, Color.BLUE);
                    StyleConstants.setUnderline(attrs, true);
                    attrs.addAttribute("link", pendingLinkUrl);
                    ((StyledDocument) editor.getDocument()).setCharacterAttributes(linkStart, linkLen, attrs, false);
                }
            } catch (BadLocationException ignored) {}
            pendingCsv = null;
            pendingLinkUrl = null;
            pendingUrlOffset = 0;
        }
    }

    private void processCitationResult(CitationResult res) {
        String title;
        String year;
        String authors;
        if (res.csv != null && !res.csv.isBlank()) {
            String csv = res.csv.trim();
            int i1 = csv.indexOf(',');
            int i2 = i1 == -1 ? -1 : csv.indexOf(',', i1 + 1);
            if (i1 != -1 && i2 != -1) {
                title = csv.substring(0, i1).trim();
                year = csv.substring(i1 + 1, i2).trim();
                authors = csv.substring(i2 + 1).trim();
            } else {
                title = res.title != null ? res.title : "";
                year = res.year != null ? res.year : "";
                authors = res.authors != null ? res.authors : "";
            }
        } else {
            title = res.title != null ? res.title : "";
            year = res.year != null ? res.year : "";
            authors = res.authors != null ? res.authors : "";
        }

        String[] pubs = {"IEEE", "ArXiV", "ResearchGate"};
        String publication = (String) JOptionPane.showInputDialog(this, "Select publication", "Publication", JOptionPane.PLAIN_MESSAGE, null, pubs, pubs[0]);
        if (publication == null || publication.isBlank()) publication = pubs[0];

        int linkChoice = JOptionPane.showConfirmDialog(this, "Do you have a web link?", "Web Link", JOptionPane.YES_NO_OPTION);
        String url = null;
        if (linkChoice == JOptionPane.YES_OPTION) {
            String entered = JOptionPane.showInputDialog(this, "Enter URL");
            if (entered != null) {
                entered = entered.trim();
                if (!entered.isBlank()) url = entered;
            }
        }

        String fTitle = csvEscape(title);
        String fYear = csvEscape(year);
        String fAuthors = csvEscape(authors);
        String fPublication = csvEscape(publication);
        String fUrl = url == null ? "" : url;

        String beforeLink = fTitle + "," + fYear + "," + fAuthors + "," + fPublication + ",";
        String finalLine = beforeLink + fUrl;

        pendingCsv = finalLine;
        pendingLinkUrl = url;
        pendingUrlOffset = beforeLink.length();
    }

    private void clearPendingCitation() {
        pendingCsv = null;
        pendingLinkUrl = null;
        pendingUrlOffset = 0;
    }

    private String csvEscape(String s) {
        if (s == null) return "";
        boolean needs = s.contains(",") || s.contains("\n") || s.contains("\r") || s.contains("\"");
        String r = s.replace("\"", "\"\"");
        return needs ? "\"" + r + "\"" : r;
    }

    private void installLinkHandler() {
        editor.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int pos = editor.viewToModel2D(e.getPoint());
                if (pos >= 0) {
                    StyledDocument doc = (StyledDocument) editor.getDocument();
                    Element el = doc.getCharacterElement(pos);
                    AttributeSet as = el.getAttributes();
                    Object lk = as.getAttribute("link");
                    if (lk instanceof String) {
                        try {
                            Desktop.getDesktop().browse(new URI((String) lk));
                        } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    static class ProcessConsoleDialog extends JDialog {
        private final JTextArea output = new JTextArea(20, 80);
        private final JButton stopBtn = new JButton("Stop");
        private Process process;
        private Thread readerThread;
        private volatile boolean stopping = false;
        private volatile boolean autoStopTriggered = false;
        private volatile boolean captureStarted = false;
        private final Consumer<CitationResult> onStopped;
        private final Runnable onCaptureStart;
        private String capturedTitle;
        private String capturedYear;
        private String capturedAuthors;
        private String capturedCsv;

        ProcessConsoleDialog(Frame owner, Consumer<CitationResult> onStopped, Runnable onCaptureStart) {
            super(owner, "Citation Capture", false);
            this.onStopped = onStopped;
            this.onCaptureStart = onCaptureStart;
            output.setEditable(false);
            JScrollPane sp = new JScrollPane(output);
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(sp, BorderLayout.CENTER);
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            controls.add(stopBtn);
            panel.add(controls, BorderLayout.SOUTH);
            setContentPane(panel);
            pack();
            setLocationRelativeTo(owner);

            stopBtn.addActionListener(e -> {
                stopProcess();
            });
        }

        void attachProcess(Process p) {
            this.process = p;
        }

        void readOutput() {
            if (process == null) return;
            readerThread = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (!stopping && (line = br.readLine()) != null) {
                        String ln = line + "\n";
                        SwingUtilities.invokeLater(() -> output.append(ln));
                        if (line.startsWith("Title: ")) capturedTitle = line.substring(7).trim();
                        else if (line.startsWith("Year: ")) capturedYear = line.substring(6).trim();
                        else if (line.startsWith("Authors: ")) capturedAuthors = line.substring(9).trim();
                        else {
                            String[] parts = line.split(",", 3);
                            if (parts.length == 3) {
                                String mid = parts[1].trim();
                                if (mid.matches("\\d{4}|Year not found|Error extracting year")) {
                                    capturedCsv = line.trim();
                                    if (!stopping && !autoStopTriggered && capturedCsv != null && !capturedCsv.isBlank()) {
                                        autoStopTriggered = true;
                                        SwingUtilities.invokeLater(() -> stopProcess());
                                    }
                                }
                            }
                        }
                        if (!captureStarted && (line.startsWith("Title: ") || line.startsWith("CONTENT:") || line.contains(","))) {
                            captureStarted = true;
                            if (onCaptureStart != null) SwingUtilities.invokeLater(onCaptureStart);
                        }
                    }
                } catch (IOException e) {
                    SwingUtilities.invokeLater(() -> output.append("\n[Error reading process output]\n"));
                }
                SwingUtilities.invokeLater(() -> stopBtn.setEnabled(false));
            });
            readerThread.setDaemon(true);
            readerThread.start();
        }

        void append(String s) {
            SwingUtilities.invokeLater(() -> output.append(s));
        }

        private void stopProcess() {
            stopping = true;
            stopBtn.setEnabled(false);
            new Thread(() -> {
                try { if (process != null) process.getInputStream().close(); } catch (IOException ignored) {}
                try { if (process != null) process.getErrorStream().close(); } catch (IOException ignored) {}
                try { if (process != null) process.getOutputStream().close(); } catch (IOException ignored) {}

                if (process != null) {
                    try {
                        ProcessHandle.of(process.pid()).ifPresent(h -> {
                            h.descendants().forEach(ph -> { ph.destroy(); ph.destroyForcibly(); });
                            h.destroy();
                        });
                        process.waitFor(800, TimeUnit.MILLISECONDS);
                        if (process.isAlive()) {
                            process.destroyForcibly();
                        }
                    } catch (Throwable t) {
                        try { process.destroy(); } catch (Throwable ignored) {}
                        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                        if (process.isAlive()) {
                            try { process.destroyForcibly(); } catch (Throwable ignored) {}
                        }
                    }
                }
                SwingUtilities.invokeLater(() -> { append("\n[Process terminated]\n"); setVisible(false); dispose(); });
                if (onStopped != null) {
                    CitationResult res = new CitationResult();
                    res.title = capturedTitle;
                    res.year = capturedYear;
                    res.authors = capturedAuthors;
                    res.csv = capturedCsv;
                    try {
                        onStopped.accept(res);
                    } catch (Exception ignored) {}
                }
            }, "ProcessStopper").start();
        }
    }

    static class CitationResult {
        String title;
        String year;
        String authors;
        String csv;
    }
    /**
     * Simple Find/Replace dialog for the editor.
     * It searches the editor's document text and selects matches; replace will modify the document.
     */
    static class FindReplaceDialog extends JDialog {
        private final JTextField findField = new JTextField(20);
        private final JTextField replaceField = new JTextField(20);
        private final JCheckBox caseSensitive = new JCheckBox("Case sensitive");
        private final JButton findNextBtn = new JButton("Find Next");
        private final JButton replaceBtn = new JButton("Replace");
        private final JButton replaceAllBtn = new JButton("Replace All");

        private final JTextPane editor;
        private int lastIndex = -1; // where the last match ended

        public FindReplaceDialog(Frame owner, JTextPane editor, boolean findOnly) {
            super(owner, findOnly ? "Find" : "Find/Replace", true);
            this.editor = editor;

            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4,4,4,4);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            gbc.gridx = 0; gbc.gridy = 0; panel.add(new JLabel("Find:"), gbc);
            gbc.gridx = 1; gbc.gridy = 0; panel.add(findField, gbc);
            gbc.gridx = 0; gbc.gridy = 1; panel.add(new JLabel("Replace:"), gbc);
            gbc.gridx = 1; gbc.gridy = 1; panel.add(replaceField, gbc);
            gbc.gridx = 1; gbc.gridy = 2; panel.add(caseSensitive, gbc);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(findNextBtn);
            if (!findOnly) {
                buttons.add(replaceBtn);
                buttons.add(replaceAllBtn);
            }

            gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; panel.add(buttons, gbc);

            setContentPane(panel);
            pack();
            setLocationRelativeTo(owner);

            // Button handlers
            findNextBtn.addActionListener(e -> findNext());
            replaceBtn.addActionListener(e -> replaceOne());
            replaceAllBtn.addActionListener(e -> replaceAll());
        }

        private String docText() {
            try {
                Document d = editor.getDocument();
                return d.getText(0, d.getLength());
            } catch (BadLocationException e) {
                return editor.getText();
            }
        }

        private void findNext() {
            String needle = findField.getText();
            if (needle.isEmpty()) return;
            String hay = docText();
            String src = caseSensitive.isSelected() ? hay : hay.toLowerCase();
            String ndl = caseSensitive.isSelected() ? needle : needle.toLowerCase();

            int start = editor.getSelectionEnd();
            int idx = src.indexOf(ndl, Math.max(start, lastIndex));
            if (idx == -1) {
                // Wrap search to the beginning
                idx = src.indexOf(ndl, 0);
                if (idx == -1) return; // not found
            }
            editor.requestFocusInWindow();
            editor.select(idx, idx + needle.length());
            lastIndex = idx + needle.length();
        }

        private void replaceOne() {
            String needle = findField.getText();
            String repl = replaceField.getText();
            if (needle.isEmpty()) return;
            if (editor.getSelectedText() != null && matchSelected(editor.getSelectedText(), needle)) {
                try {
                    int start = editor.getSelectionStart();
                    editor.getDocument().remove(start, needle.length());
                    editor.getDocument().insertString(start, repl, null);
                    editor.select(start, start + repl.length());
                } catch (BadLocationException ignored) {}
            } else {
                findNext();
            }
        }

        private void replaceAll() {
            String needle = findField.getText();
            String repl = replaceField.getText();
            if (needle.isEmpty()) return;
            String hay = docText();
            String src = caseSensitive.isSelected() ? hay : hay.toLowerCase();
            String ndl = caseSensitive.isSelected() ? needle : needle.toLowerCase();

            int idx = src.indexOf(ndl);
            int offset = 0; // track changes in length
            while (idx != -1) {
                try {
                    editor.getDocument().remove(idx + offset, needle.length());
                    editor.getDocument().insertString(idx + offset, repl, null);
                    offset += (repl.length() - needle.length());
                    src = docText();
                    src = caseSensitive.isSelected() ? src : src.toLowerCase();
                    idx = src.indexOf(ndl, idx + repl.length());
                } catch (BadLocationException ignored) {
                    break;
                }
            }
        }

        private boolean matchSelected(String selected, String needle) {
            if (caseSensitive.isSelected()) return selected.equals(needle);
            return selected.equalsIgnoreCase(needle);
        }
    }
}