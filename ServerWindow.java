import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Timer;
import java.util.TimerTask;

public class ServerWindow extends JFrame {
    private Point initialClick;
    private JTextArea logArea;
    private JButton closeButton, minimizeButton;
    private JLabel statusLabel, timeLabel, connectionLabel;
    private Timer statusTimer;
    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    
    // Modern color scheme
    private static final Color DARK_BG = new Color(18, 18, 18);
    private static final Color SUCCESS_COLOR = new Color(52, 199, 89);
    private static final Color ERROR_COLOR = new Color(255, 69, 58);
    private static final Color TEXT_PRIMARY = new Color(255, 255, 255);
    private static final Color TEXT_SECONDARY = new Color(174, 174, 178);
    private static final Color BORDER_COLOR = new Color(44, 44, 46);
    
    public ServerWindow() {
        setupWindow();
        createComponents();
        startStatusUpdater();
        
        // Add some initial logs
        SwingUtilities.invokeLater(() -> {
            log("INFO", "Server console initialized");
            log("SUCCESS", "Server started on port 8080");
            log("INFO", "Ready to accept connections");
        });
    }
    
    private TrayIcon trayIcon;
    private SystemTray systemTray;
    private boolean isTraySupported = false;

    private void setupWindow() {
        setUndecorated(true);
        setSize(500, 340); // Increased width for button visibility
        setLocationRelativeTo(null);
        setBackground(new Color(0, 0, 0, 0));
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); // Custom close handling

        // Linux-specific optimizations
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        // Additional Linux font settings
        System.setProperty("swing.defaultlaf", UIManager.getSystemLookAndFeelClassName());
        
        // Rocky Linux 8 / GNOME system tray compatibility
        System.setProperty("java.awt.headless", "false");
        System.setProperty("awt.toolkit", "sun.awt.X11.XToolkit");

        // System tray support (Java 7+)
        isTraySupported = SystemTray.isSupported();
        if (isTraySupported) {
            try {
                systemTray = SystemTray.getSystemTray();
                // Create a simple tray icon programmatically for Linux compatibility
                Image image = createTrayIcon();
                PopupMenu popup = new PopupMenu();
                MenuItem restoreItem = new MenuItem("Restore");
                MenuItem exitItem = new MenuItem("Exit");
                restoreItem.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        setVisible(true);
                        setExtendedState(JFrame.NORMAL);
                        systemTray.remove(trayIcon);
                    }
                });
                exitItem.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        systemTray.remove(trayIcon);
                        dispose();
                        System.exit(0);
                    }
                });
                popup.add(restoreItem);
                popup.addSeparator();
                popup.add(exitItem);
                trayIcon = new TrayIcon(image, "TerminalServer", popup);
                trayIcon.setImageAutoSize(true);
                
                // Single left-click action - restore window
                trayIcon.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        setVisible(true);
                        setExtendedState(JFrame.NORMAL);
                        systemTray.remove(trayIcon);
                    }
                });
                
                // Double-click also restores window
                trayIcon.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() == 2) {
                            setVisible(true);
                            setExtendedState(JFrame.NORMAL);
                            systemTray.remove(trayIcon);
                        }
                    }
                });
            } catch (Exception e) {
                isTraySupported = false;
            }
        }

        // Window close/minimize event handling
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeOrSystemTray();
            }
        });
        addWindowStateListener(new WindowStateListener() {
            @Override
            public void windowStateChanged(WindowEvent e) {
                if (e.getNewState() == JFrame.ICONIFIED) {
                    // Window is minimized to taskbar - no action needed
                }
            }
        });
    }

    // Create a simple tray icon programmatically for Linux compatibility
    private Image createTrayIcon() {
        int size = 16; // Standard tray icon size
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        
        // Enable antialiasing for smoother icon
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Create a simple server icon (green circle with "S")
        g2d.setColor(SUCCESS_COLOR);
        g2d.fillOval(1, 1, size-2, size-2);
        
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        FontMetrics fm = g2d.getFontMetrics();
        String text = "S";
        int x = (size - fm.stringWidth(text)) / 2;
        int y = (size - fm.getHeight()) / 2 + fm.getAscent();
        g2d.drawString(text, x, y);
        
        g2d.dispose();
        return image;
    }

    private void closeOrSystemTray() {
        if (isTraySupported) {
            String[] options = {"Minimize to Tray", "Exit", "Cancel"};
            int result = JOptionPane.showOptionDialog(this, 
                "What would you like to do?", 
                "Close Application", 
                JOptionPane.YES_NO_CANCEL_OPTION, 
                JOptionPane.QUESTION_MESSAGE, 
                null, 
                options, 
                options[2]); // Default to Cancel
            
            if (result == 0) { // Minimize to Tray
                try {
                    systemTray.add(trayIcon);
                    setVisible(false);
                } catch (Exception ex) {
                    log("ERROR", "Failed to add to tray: " + ex.getMessage());
                    dispose();
                    System.exit(0);
                }
            } else if (result == 1) { // Exit
                dispose();
                System.exit(0);
            }
            // result == 2 or JOptionPane.CLOSED_OPTION means Cancel - do nothing
        } else {
            String[] options = {"Exit", "Cancel"};
            int result = JOptionPane.showOptionDialog(this, 
                "Exit application?", 
                "Exit", 
                JOptionPane.YES_NO_OPTION, 
                JOptionPane.QUESTION_MESSAGE, 
                null, 
                options, 
                options[1]); // Default to Cancel
            
            if (result == 0) { // Exit
                dispose();
                System.exit(0);
            }
            // result == 1 or JOptionPane.CLOSED_OPTION means Cancel - do nothing
        }
    }
    
    private void createComponents() {
        JPanel mainPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                
                // Draw subtle shadow
                for (int i = 0; i < 6; i++) {
                    g2d.setColor(new Color(0, 0, 0, 20 - i * 3));
                    g2d.drawRoundRect(i, i, getWidth() - 2 * i - 1, getHeight() - 2 * i - 1, 10, 10);
                }
                
                // Draw main background
                g2d.setColor(DARK_BG);
                g2d.fillRoundRect(6, 6, getWidth() - 12, getHeight() - 12, 10, 10);
                
                // Draw border
                g2d.setColor(BORDER_COLOR);
                g2d.drawRoundRect(6, 6, getWidth() - 12, getHeight() - 12, 10, 10);
                
                g2d.dispose();
            }
        };
        mainPanel.setLayout(new BorderLayout());
        mainPanel.setOpaque(false);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        
        // Create header
        JPanel headerPanel = createHeaderPanel();
        
        // Create tabbed pane
        JTabbedPane tabbedPane = createTabbedPane();
        
        // Create footer
        JPanel footerPanel = createFooterPanel();
        
        // Assemble main panel
        mainPanel.add(headerPanel, BorderLayout.NORTH);
        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        mainPanel.add(footerPanel, BorderLayout.SOUTH);
        
        setContentPane(mainPanel);
        makeDraggable(headerPanel);
    }
    
    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        headerPanel.setPreferredSize(new Dimension(0, 45));
        
        // Left side - title and status

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        leftPanel.setOpaque(false);
        
        JLabel titleLabel = new JLabel("Server Console");
        titleLabel.setForeground(TEXT_PRIMARY);
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        
        statusLabel = new JLabel("* ONLINE");  // Changed from Unicode bullet
        statusLabel.setForeground(SUCCESS_COLOR);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        
        leftPanel.add(titleLabel);
        leftPanel.add(statusLabel);
        
        // Right side - time and controls
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.X_AXIS));
        rightPanel.setOpaque(false);
        
        timeLabel = new JLabel();
        timeLabel.setForeground(TEXT_SECONDARY);
        timeLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        updateTime();
        
        // Minimize button with ASCII character
        minimizeButton = new JButton("Minimize");  // Changed to dash for better visibility
        minimizeButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        minimizeButton.setContentAreaFilled(false);
        minimizeButton.setBorderPainted(false);
        minimizeButton.setFocusPainted(false);
        minimizeButton.setForeground(TEXT_SECONDARY);
        minimizeButton.addActionListener(e -> setState(JFrame.ICONIFIED));
        
        // Close button with ASCII character
        closeButton = new JButton("Close");  // Changed from Unicode ×
        closeButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.setForeground(TEXT_SECONDARY);
        
        closeButton.addActionListener(e -> {
            if (statusTimer != null) statusTimer.cancel();
            closeOrSystemTray();
        });
        
        // Add hover effects
        addButtonHoverEffect(minimizeButton, false);
        addButtonHoverEffect(closeButton, true);
        
        rightPanel.add(Box.createHorizontalGlue());
        rightPanel.add(timeLabel);
        rightPanel.add(Box.createHorizontalStrut(8));
        rightPanel.add(minimizeButton);
        rightPanel.add(Box.createHorizontalStrut(2));
        rightPanel.add(closeButton);
        
        headerPanel.add(leftPanel, BorderLayout.WEST);
        headerPanel.add(rightPanel, BorderLayout.EAST);
        
        return headerPanel;
    }
    
    private void addButtonHoverEffect(JButton button, boolean isClose) {
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setForeground(isClose ? ERROR_COLOR : TEXT_PRIMARY);
                button.setContentAreaFilled(true);
                button.setBackground(new Color(44, 44, 46));
            }
            @Override
            public void mouseExited(MouseEvent e) {
                button.setForeground(TEXT_SECONDARY);
                button.setContentAreaFilled(false);
            }
        });
    }
    
    private JScrollPane createLogArea() {
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setForeground(TEXT_PRIMARY);
        logArea.setBackground(new Color(15, 15, 15));
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        logArea.setLineWrap(false);
        
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getViewport().setBackground(new Color(15, 15, 15));
        scrollPane.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(4, 10, 4, 10),
            BorderFactory.createLineBorder(BORDER_COLOR, 1)
        ));
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);
        
        // Style scrollbar
        scrollPane.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                this.thumbColor = new Color(60, 60, 60);
                this.trackColor = new Color(25, 25, 25);
            }
        });
        
        return scrollPane;
    }
    
    private JTabbedPane createTabbedPane() {
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setOpaque(false);
        tabbedPane.setBackground(DARK_BG);
        tabbedPane.setForeground(TEXT_PRIMARY);
        tabbedPane.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        
        // Console tab
        JScrollPane logScrollPane = createLogArea();
        tabbedPane.addTab("Console", logScrollPane);
        
        // Users tab
        JScrollPane usersScrollPane = createUsersArea();
        tabbedPane.addTab("Users", usersScrollPane);
        
        // Style the tabs
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabbedPane.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        
        return tabbedPane;
    }
    
    private JScrollPane createUsersArea() {
        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setForeground(TEXT_PRIMARY);
        userList.setBackground(new Color(15, 15, 15));
        userList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        userList.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        userList.setSelectionBackground(new Color(0, 122, 255, 50));
        userList.setSelectionForeground(TEXT_PRIMARY);
        
        // Add some padding and styling
        JPanel userPanel = new JPanel(new BorderLayout());
        userPanel.setOpaque(false);
        userPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        userPanel.add(userList, BorderLayout.CENTER);
        
        JScrollPane scrollPane = new JScrollPane(userPanel);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getViewport().setBackground(new Color(15, 15, 15));
        scrollPane.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(4, 10, 4, 10),
            BorderFactory.createLineBorder(BORDER_COLOR, 1)
        ));
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);
        
        // Style scrollbar
        scrollPane.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                this.thumbColor = new Color(60, 60, 60);
                this.trackColor = new Color(25, 25, 25);
            }
        });
        
        return scrollPane;
    }
    
    private JPanel createFooterPanel() {
        JPanel footerPanel = new JPanel(new BorderLayout());
        footerPanel.setOpaque(false);
        footerPanel.setBorder(BorderFactory.createEmptyBorder(6, 12, 8, 12));
        footerPanel.setPreferredSize(new Dimension(0, 35));
        
        // Left side - clear and kill buttons
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftPanel.setOpaque(false);

        JButton clearButton = new JButton("Clear");
        clearButton.setForeground(TEXT_SECONDARY);
        clearButton.setBackground(new Color(44, 44, 46));
        clearButton.setBorderPainted(false);
        clearButton.setFocusPainted(false);
        clearButton.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        clearButton.setPreferredSize(new Dimension(70, 25));
        clearButton.addActionListener(e -> logArea.setText(""));
        clearButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                clearButton.setBackground(new Color(54, 54, 56));
            }
            @Override
            public void mouseExited(MouseEvent e) {
                clearButton.setBackground(new Color(44, 44, 46));
            }
        });

        JButton killJavaButton = new JButton("Kill Java Processes");
        killJavaButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        killJavaButton.setContentAreaFilled(false);
        killJavaButton.setBorderPainted(false);
        killJavaButton.setFocusPainted(false);
        killJavaButton.setForeground(ERROR_COLOR);
        killJavaButton.setToolTipText("Kill all running Java processes except TerminalServer");
        killJavaButton.setPreferredSize(new Dimension(170, 25));
        killJavaButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                killJavaProcessesExceptTerminalServer();
            }
        });
        killJavaButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                killJavaButton.setForeground(new Color(200, 0, 0));
                killJavaButton.setContentAreaFilled(true);
                killJavaButton.setBackground(new Color(44, 44, 46));
            }
            @Override
            public void mouseExited(MouseEvent e) {
                killJavaButton.setForeground(ERROR_COLOR);
                killJavaButton.setContentAreaFilled(false);
            }
        });

        leftPanel.add(clearButton);
        leftPanel.add(Box.createHorizontalStrut(8));
        leftPanel.add(killJavaButton);

        // Right side - connection count
        connectionLabel = new JLabel("Connections: 0");
        connectionLabel.setForeground(TEXT_SECONDARY);
        connectionLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));

        footerPanel.add(leftPanel, BorderLayout.WEST);
        footerPanel.add(connectionLabel, BorderLayout.EAST);

        return footerPanel;
    }
    
    private void makeDraggable(JPanel panel) {
        panel.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                initialClick = e.getPoint();
            }
        });
        
        panel.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (initialClick == null) {
                    return; // Prevent null pointer exception
                }
                int thisX = getLocation().x;
                int thisY = getLocation().y;
                int xMoved = e.getX() - initialClick.x;
                int yMoved = e.getY() - initialClick.y;
                setLocation(thisX + xMoved, thisY + yMoved);
            }
        });
    }
    
    private void startStatusUpdater() {
        statusTimer = new Timer();
        statusTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(() -> updateTime());
            }
        }, 0, 1000);
    }
    
    private void updateTime() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        timeLabel.setText(LocalDateTime.now().format(formatter));
    }
    
    public void log(String level, String message) {
        SwingUtilities.invokeLater(() -> {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
            String timestamp = LocalDateTime.now().format(formatter);
            String formattedMessage = String.format("[%s] %s: %s", timestamp, level, message);
            
            logArea.append(formattedMessage + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
    
    public void updateConnectionCount(int count) {
        SwingUtilities.invokeLater(() -> {
            connectionLabel.setText("Connections: " + count);
        });
    }
    
    public void addUser(String clientName, String clientIp) {
        SwingUtilities.invokeLater(() -> {
            String userInfo = String.format("%s (%s)", clientName, clientIp);
            userListModel.addElement(userInfo);
        });
    }
    
    public void removeUser(String clientName, String clientIp) {
        SwingUtilities.invokeLater(() -> {
            String userInfo = String.format("%s (%s)", clientName, clientIp);
            userListModel.removeElement(userInfo);
        });
    }
    
    public void clearUsers() {
        SwingUtilities.invokeLater(() -> {
            userListModel.clear();
        });
    }

    /**
     * Kills all running Java processes except the TerminalServer itself.
     * Uses 'ps' and 'grep' on Linux, and logs results in the UI.
     */
    private void killJavaProcessesExceptTerminalServer() {
        log("INFO", "Attempting to kill all Java processes except TerminalServer...");
        try {
            // Command to list all Java PIDs except TerminalServer (Linux/Unix only)
            String[] cmd = { "/bin/sh", "-c", "ps aux | grep '[j]ava' | grep -v 'TerminalServer.jar' | awk '{print $2}'" };
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process process = pb.start();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String line;
            java.util.List<String> pids = new java.util.ArrayList<String>();
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    pids.add(line);
                }
            }
            reader.close();
            process.waitFor();
            if (pids.isEmpty()) {
                log("INFO", "No Java processes found to kill (except TerminalServer).");
                javax.swing.JOptionPane.showMessageDialog(this, "No Java processes found (except TerminalServer).", "Info", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            // Kill each PID
            int killed = 0;
            for (String pid : pids) {
                try {
                    String[] killCmd = { "/bin/sh", "-c", "kill -9 " + pid };
                    ProcessBuilder killPb = new ProcessBuilder(killCmd);
                    Process killProcess = killPb.start();
                    killProcess.waitFor();
                    killed++;
                    log("SUCCESS", "Killed Java process PID: " + pid);
                } catch (Exception ex) {
                    log("ERROR", "Failed to kill PID: " + pid + " - " + ex.getMessage());
                }
            }
            javax.swing.JOptionPane.showMessageDialog(this, "Killed " + killed + " Java process(es) (except TerminalServer).", "Success", javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            log("ERROR", "Failed to enumerate or kill Java processes: " + ex.getMessage());
            javax.swing.JOptionPane.showMessageDialog(this, "Error killing Java processes: " + ex.getMessage(), "Error", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void main(String[] args) {
        // Set system look and feel for better Linux integration
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            
            // Additional Linux font settings
            UIManager.put("Button.font", new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            UIManager.put("Label.font", new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            UIManager.put("TextArea.font", new Font(Font.MONOSPACED, Font.PLAIN, 11));
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        SwingUtilities.invokeLater(() -> {
            ServerWindow console = new ServerWindow();
            console.setVisible(true);
            
            // Simulate some server activity
            Timer logTimer = new Timer();
            logTimer.scheduleAtFixedRate(new TimerTask() {
                private int counter = 1;
                @Override
                public void run() {
                    String[] levels = {"INFO", "SUCCESS", "WARNING", "ERROR"};
                    String[] messages = {
                        "Client connected",
                        "Query executed",
                        "High CPU usage",
                        "Connection failed",
                        "Backup completed"
                    };
                    
                    String level = levels[(int)(Math.random() * levels.length)];
                    String message = messages[(int)(Math.random() * messages.length)];
                    console.log(level, message + " #" + counter++);
                }
            }, 3000, 4000);
        });
    }
}