import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import java.net.Socket;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import java.util.Date;
import java.util.Map;
import java.util.HashMap;

import java.awt.Color;
import java.awt.Font;

/**
 * Represents the connection state of a host
 */
enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/**
 * Represents the execution mode of the terminal client
 */
enum ExecutionMode {
    MANUAL,
    AUTOMATIC
}

/**
 * Configuration for a remote host connection
 */
class HostConfig {
    private static final int RETRY_COOLDOWN_MS = 5000;

    final String hostname;
    final int port;
    final String clientName;
    final String autoCommand;
    ConnectionState state;
    long lastAttempt;
    int retryCount;

    public HostConfig(String hostname, int port, String clientName, String autoCommand) {
        this.hostname = hostname;
        this.port = port;
        this.clientName = clientName;
        this.autoCommand = autoCommand;
        this.state = ConnectionState.DISCONNECTED;
        this.lastAttempt = 0;
        this.retryCount = 0;
    }

    public boolean canRetry() {
        return System.currentTimeMillis() - lastAttempt >= RETRY_COOLDOWN_MS;
    }

    @Override
    public String toString() {
        return String.format("Host[%s:%d, client=%s]", hostname, port, clientName);
    }
}

/**
 * Manages network connections to remote hosts
 */
class ConnectionManager {
    private static final int TIMEOUT_MS = 30000;
    private static LogCallback logCallback;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private HostConfig config;

    public static void setLogCallback(LogCallback callback) {
        logCallback = callback;
    }

    interface LogCallback {
        void log(String message);
    }

    public ConnectionManager(HostConfig config) {
        this.config = config;
    }

    public boolean connect() throws IOException {
        try {
            socket = new Socket(config.hostname, config.port);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.setKeepAlive(true);
            writer = new PrintWriter(socket.getOutputStream(), true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            config.state = ConnectionState.CONNECTED;
            return true;
        } catch (IOException e) {
            config.state = ConnectionState.FAILED;
            close(); // Ensure cleanup on connection failure
            throw e;
        }
    }

    public void sendCommand(String command) throws IOException {
        if (!config.state.equals(ConnectionState.CONNECTED) || socket == null || socket.isClosed()) {
            throw new IOException("Not connected to host: " + config.hostname);
        }
        try {
            writer.println(command);
            if (writer.checkError()) { // Check for write errors
                throw new IOException("Write error occurred");
            }
        } catch (Exception e) {
            close(); // Cleanup on error
            throw new IOException("Failed to send command: " + e.getMessage());
        }
    }

    public String readResponse() throws IOException {
        if (!config.state.equals(ConnectionState.CONNECTED) || reader == null) {
            throw new IOException("Not connected to host: " + config.hostname);
        }
        try {
            String response = reader.readLine();
            if (response == null) {
                throw new IOException("Connection closed by server");
            }
            return response;
        } catch (IOException e) {
            close(); // Cleanup on error
            throw e;
        }
    }

    public void close() {
        try {
            if (writer != null) {
                writer.close();
            }
            if (reader != null) {
                reader.close();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            // Log close errors but don't throw
            if (logCallback != null) {
                logCallback.log("Error closing connection: " + e.getMessage());
            }
        } finally {
            config.state = ConnectionState.DISCONNECTED;
            reader = null;
            writer = null;
            socket = null;
        }
    }
}

/**
 * Terminal client application for managing remote host connections
 */
public class TerminalClient {
    private static final int MAX_RETRIES = 3;
    private static final String CONFIG_FILE = "client_config.properties";
    private static final String LOCK_FILE = "terminal_client.lock";
    private static final int LOG_MAX_LINES = 100;

    private static BufferedReader consoleReader;
    private static Properties config = new Properties();
    private static boolean isSilent;
    private static ExecutionMode executionMode;
    private static JDialog statusDialog;
    private static JLabel statusLabel;
    private static File lockFile;
    private static List<HostConfig> hostConfigs = new ArrayList<HostConfig>();
    private static Map<String, ConnectionManager> connections = new HashMap<String, ConnectionManager>();

    private static boolean isAlreadyRunning() {
        try {
            return !lockFile.createNewFile();
        } catch (IOException e) {
            return true;
        }
    }

    public static void main(String[] args) {
        ConnectionManager.setLogCallback(new ConnectionManager.LogCallback() {
            public void log(String message) {
                logError(message);
            }
        });

        lockFile = new File(LOCK_FILE);
        if (isAlreadyRunning()) {
            if (isRunningInCommandPrompt()) {
                System.err.println("Another instance is already running.");
            } else {
                showError("Another instance is already running.");
            }
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                if (lockFile.exists()) {
                    lockFile.delete();
                }
            }
        });

        loadConfig();
        isSilent = Boolean.parseBoolean(getConfigString("silentMode", ""));
        executionMode = ExecutionMode.valueOf(getConfigString("executionMode", "MANUAL"));

        consoleReader = new BufferedReader(new InputStreamReader(System.in));

        // For automatic mode
        if (executionMode.equals(ExecutionMode.AUTOMATIC)) {
            if (!isSilent) {
                createAndShowStatusMessage();
            } else {
                System.out.println("Terminal client is running in silent mode...");
            }
        }

        // For manual mode
        if (executionMode.equals(ExecutionMode.MANUAL) && !isRunningInCommandPrompt()) {
            showError("Manual mode must be run in command prompt environment.");
            System.exit(1);
        }

        if (executionMode.equals(ExecutionMode.AUTOMATIC)) {
            if (!isSilent) {
                createAndShowStatusMessage();
            }

            // Single attempt to connect to all hosts
            connectToHosts();

            // Execute commands on any successfully connected hosts
            for (Map.Entry<String, ConnectionManager> entry : connections.entrySet()) {
                HostConfig hostConfig = null;
                for (HostConfig config : hostConfigs) {
                    if (config.hostname.equals(entry.getKey())) {
                        hostConfig = config;
                        break;
                    }
                }

                if (hostConfig != null && !hostConfig.autoCommand.isEmpty()) {
                    try {
                        String[] commands = hostConfig.autoCommand.split(";");
                        for (String command : commands) {
                            command = command.trim();
                            if (!command.isEmpty()) {
                                entry.getValue().sendCommand(command);
                                if (!isSilent) {
                                    updateStatusLabel("Sending command to " + hostConfig.hostname + ": " + command);
                                    Thread.sleep(1500);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logError("Error executing commands on " + hostConfig.hostname + ": " + e.getMessage());
                    }
                }
            }

            // Close all connections
            for (ConnectionManager connection : connections.values()) {
                connection.close();
            }
            connections.clear();

            if (!isSilent) {
                disposeStatusMessage();
            }
            System.exit(0);
        } else {
            // Manual mode
            System.out.println("Available hosts:");
            for (int i = 0; i < hostConfigs.size(); i++) {
                System.out.println((i + 1) + ". " + hostConfigs.get(i));
            }

            try {
                System.out.print("Select host number (or 'all' for all hosts): ");
                String selection = consoleReader.readLine();
                List<HostConfig> selectedHosts = new ArrayList<HostConfig>();

                if ("all".equalsIgnoreCase(selection)) {
                    selectedHosts.addAll(hostConfigs);
                } else {
                    try {
                        int index = Integer.parseInt(selection) - 1;
                        if (index >= 0 && index < hostConfigs.size()) {
                            selectedHosts.add(hostConfigs.get(index));
                        } else {
                            showError("Invalid host number");
                            System.exit(1);
                        }
                    } catch (NumberFormatException e) {
                        showError("Invalid input. Please enter a number or 'all'");
                        System.exit(1);
                    }
                }

                // Connect to selected hosts
                for (HostConfig hostConfig : selectedHosts) {
                    try {
                        logInfo("Attempting to connect to " + hostConfig);
                        ConnectionManager connection = new ConnectionManager(hostConfig);
                        connection.connect();
                        connections.put(hostConfig.hostname, connection);
                        connection.sendCommand(hostConfig.clientName);
                        System.out.println("Connected to " + hostConfig.hostname);
                        Thread.sleep(1000);
                    } catch (Exception e) {
                        String errorMsg = "Failed to connect to " + hostConfig + ": " + e.getMessage();
                        logError(errorMsg);
                        System.err.println(errorMsg);
                    }
                }

                if (connections.isEmpty()) {
                    showError("No connections established");
                    System.exit(1);
                }

                // Create response handlers for each connection
                for (final Map.Entry<String, ConnectionManager> entry : connections.entrySet()) {
                    Thread responseHandler = new Thread(new Runnable() {
                        public void run() {
                            try {
                                String response;
                                while ((response = entry.getValue().readResponse()) != null) {
                                    System.out.println("[" + entry.getKey() + "] " + response);
                                    if ("Goodbye!".equalsIgnoreCase(response.trim())) {
                                        break;
                                    }
                                }
                            } catch (IOException e) {
                                logError("Connection lost to " + entry.getKey() + ": " + e.getMessage());
                            }
                        }
                    });
                    responseHandler.start();
                }

                // Main command loop
                String command;
                while (true) {
                    System.out.print(">>> ");
                    command = consoleReader.readLine();

                    if ("exit".equalsIgnoreCase(command.trim())) {
                        for (ConnectionManager connection : connections.values()) {
                            try {
                                connection.sendCommand("exit");
                            } catch (IOException e) {
                                // Ignore send errors during exit
                            }
                        }
                        break;
                    }

                    // Send command to all connected hosts
                    for (Map.Entry<String, ConnectionManager> entry : connections.entrySet()) {
                        try {
                            entry.getValue().sendCommand(command);
                        } catch (IOException e) {
                            System.err.println("Failed to send command to " + entry.getKey() + ": " + e.getMessage());
                        }
                    }
                    Thread.sleep(1000);
                }

            } catch (Exception e) {
                showError("Error in manual mode: " + e.getMessage());
            } finally {
                // Close all connections
                for (ConnectionManager connection : connections.values()) {
                    connection.close();
                }
                connections.clear();
            }
        }
    }

    private static boolean isRunningInCommandPrompt() {
        return System.console() != null;
    }

    private static void createAndShowStatusMessage() {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame();
            statusDialog = new JDialog(frame, "Terminal Client Status", true);
            statusDialog.setSize(400, 100);
            statusDialog.setLocationRelativeTo(null);
            statusDialog.setLayout(new BorderLayout());
            statusDialog.setUndecorated(true);

            JPanel panel = new JPanel(new BorderLayout(5, 5));
            panel.setBackground(new Color(44, 62, 80));
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            statusLabel = new JLabel("Initializing...");
            statusLabel.setForeground(Color.WHITE);
            statusLabel.setFont(new Font("Arial", Font.BOLD, 12));

            JProgressBar progressBar = new JProgressBar();
            progressBar.setIndeterminate(true);
            progressBar.setStringPainted(false);
            progressBar.setPreferredSize(new Dimension(progressBar.getPreferredSize().width, 5));

            panel.add(statusLabel, BorderLayout.CENTER);
            panel.add(progressBar, BorderLayout.SOUTH);
            statusDialog.add(panel);
            statusDialog.setVisible(true);
        });
    }

    private static void updateStatusLabel(final String message) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (statusLabel != null) {
                    statusLabel.setText(message);
                }
            }
        });
    }

    private static void disposeStatusMessage() {
        if (statusDialog != null) {
            updateStatusLabel("Operation completed. closing.");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            statusDialog.dispose();
        }
    }

    private static void loadConfig() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            createDefaultConfig();
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(configFile);
            config.load(in);

            // Check if this is an old format config
            if (!config.containsKey("hostCount")) {
                String oldIp = config.getProperty("serverIP", "");
                String oldPort = config.getProperty("port", "");
                String oldClientName = config.getProperty("clientName", "");
                String oldAutoCommand = config.getProperty("autoCommand", "");

                if (!oldIp.isEmpty() && !oldPort.isEmpty()) {
                    // Set up new format
                    config.setProperty("hostCount", "1");
                    config.setProperty("host.1.ip", oldIp);
                    config.setProperty("host.1.port", oldPort);
                    config.setProperty("host.1.clientName", oldClientName);
                    config.setProperty("host.1.autoCommand", oldAutoCommand);

                    // Remove old properties
                    config.remove("serverIP");
                    config.remove("port");
                    config.remove("clientName");
                    config.remove("autoCommand");

                    // Save the new format
                    saveConfig();
                }
            }

            // Load host configurations
            hostConfigs.clear();
            int hostCount = getConfigInt("hostCount", 1);
            for (int i = 1; i <= hostCount; i++) {
                String hostname = getConfigString("host." + i + ".ip", "");
                int port = getConfigInt("host." + i + ".port", -1);
                String clientName = getConfigString("host." + i + ".clientName", "");
                String autoCommand = getConfigString("host." + i + ".autoCommand", "");

                if (!hostname.isEmpty() && port > 0) {
                    hostConfigs.add(new HostConfig(hostname, port, clientName, autoCommand));
                }
            }

            if (hostConfigs.isEmpty()) {
                createDefaultConfig();
                loadConfig(); // Reload with default config
            }

        } catch (IOException e) {
            showError("Error loading config file: " + e.getMessage());
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    showError("Error closing config file input stream: " + e.getMessage());
                }
            }
        }
    }

    private static void createDefaultConfig() {
        // Clear existing properties
        config.clear();

        config.setProperty("hostCount", "2");

        // First host
        config.setProperty("host.1.ip", "192.168.0.103");
        config.setProperty("host.1.port", "8887");
        config.setProperty("host.1.clientName", "Ryu 103");
        config.setProperty("host.1.autoCommand", "ps aux | grep '[j]ava' | grep -v 'TerminalServer.jar' | awk '{print $2}' | xargs -r kill; /home/user1/PsJPOS_TouchScreen/bin/linux/PsJPOS.sh; exit");

        // Second host
        config.setProperty("host.2.ip", "192.168.0.105");
        config.setProperty("host.2.port", "8887");
        config.setProperty("host.2.clientName", "Ryu 105");
        config.setProperty("host.2.autoCommand", "ps aux | grep '[j]ava' | grep -v 'TerminalServer.jar' | awk '{print $2}' | xargs -r kill; /home/user1/PsJPOS_TouchScreen_Ryu/bin/linux/PsJPOS.sh; exit");

        // Global settings
        config.setProperty("silentMode", "false");
        config.setProperty("executionMode", "AUTOMATIC");

        saveConfig();

        // Log the creation of default config
        logInfo("Created default configuration with " + config.getProperty("hostCount") + " hosts");
    }

    private static void saveConfig() {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(CONFIG_FILE);
            config.store(out, "Client Configuration");
        } catch (IOException e) {
            showError("Error saving config file: " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    showError("Error closing config file output stream: " + e.getMessage());
                }
            }
        }
    }

    private static String getConfigString(String key, String defaultValue) {
        return config.getProperty(key, defaultValue);
    }

    private static int getConfigInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(config.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static void showError(String message) {
        if (!isSilent) {
            JOptionPane.showMessageDialog(null, message, "Error", JOptionPane.ERROR_MESSAGE);
        } else {
            logError(message);
        }
        System.err.println("Error: " + message);
    }

    private static void logError(String e) {
        try {
            File logDir = new File("logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            File logFile = new File(logDir, "terminal_client.log");
            BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true));
            writer.write(e);
            writer.newLine();
            writer.close();

            manageLogFileSize(logFile);
        } catch (IOException ioException) {
            System.err.println("Logging error: " + ioException.getMessage());
        }
    }

    private static void manageLogFileSize(File logFile) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(logFile));
            List<String> lines = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            reader.close();

            if (lines.size() > LOG_MAX_LINES) {
                lines = lines.subList(lines.size() - LOG_MAX_LINES, lines.size());
                BufferedWriter writer = new BufferedWriter(new FileWriter(logFile));
                for (String l : lines) {
                    writer.write(l);
                    writer.newLine();
                }
                writer.close();
            }
        } catch (IOException e) {
            System.err.println("Error managing log file size: " + e.getMessage());
        }
    }

    private static void connectToHosts() {
        for (HostConfig hostConfig : hostConfigs) {
            if (hostConfig.retryCount >= MAX_RETRIES) continue;
            if (!hostConfig.canRetry()) continue;

            try {
                updateStatusLabel("[*] Connecting to " + hostConfig.hostname);
                ConnectionManager connection = new ConnectionManager(hostConfig);
                connection.connect();
                connections.put(hostConfig.hostname, connection);

                updateStatusLabel("[>] Sending client name: " + hostConfig.clientName);
                connection.sendCommand(hostConfig.clientName);

                if (executionMode.equals(ExecutionMode.AUTOMATIC) && !hostConfig.autoCommand.isEmpty()) {
                    String[] commands = hostConfig.autoCommand.split(";");
                    for (String command : commands.length > 3 ?
                         new String[]{commands[0], "...", commands[commands.length-1]} : commands) {

                        if (!command.trim().isEmpty()) {
                            updateStatusLabel("[+] " + hostConfig.hostname + ": " +
                                (command.length() > 40 ? command.substring(0, 37) + "..." : command));
                            connection.sendCommand(command.trim());
                            Thread.sleep(500);
                        }
                    }
                }

                hostConfig.state = ConnectionState.CONNECTED;
                hostConfig.retryCount = 0;

            } catch (Exception e) {
                logError("[!] " + hostConfig.hostname + ": " + e.getMessage());
                ConnectionManager connection = connections.remove(hostConfig.hostname);
                if (connection != null) {
                    connection.close();
                }
                hostConfig.state = ConnectionState.FAILED;
                hostConfig.retryCount++;
            }
        }

        if (connections.isEmpty() && allHostsMaxedRetries()) {
            updateStatusLabel("[X] All connections failed");
            try { Thread.sleep(1000); } catch (InterruptedException e) {}
            System.exit(1);
        }
    }

    private static boolean allHostsMaxedRetries() {
        for (HostConfig hostConfig : hostConfigs) {
            if (hostConfig.retryCount < MAX_RETRIES) {
                return false;
            }
        }
        return true;
    }

    private static void logInfo(String message) {
        try {
            File logDir = new File("logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            File logFile = new File(logDir, "terminal_client.log");
            BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true));
            writer.write("[INFO] " + new Date() + " - " + message);
            writer.newLine();
            writer.close();

            manageLogFileSize(logFile);
        } catch (IOException ioException) {
            System.err.println("Logging error: " + ioException.getMessage());
        }
    }
}