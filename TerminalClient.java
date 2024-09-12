import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;

import java.net.Socket;
import java.net.UnknownHostException;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import java.awt.BorderLayout;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;


public class TerminalClient {
    private static BufferedReader consoleReader;
    private static Socket socket;
    private static PrintWriter writer;
    private static BufferedReader reader;
    private static final String CONFIG_FILE = "client_config.properties";
    private static Properties config = new Properties();
    private static boolean isSilent;
    private static boolean isAutomatic;
    private static JDialog statusDialog;
    private static JLabel statusLabel;
    private static final String LOCK_FILE = "terminal_client.lock";
    private static File lockFile;

    private static boolean isAlreadyRunning() {
        try {
            return !lockFile.createNewFile();
        } catch (IOException e) {
            return true;
        }
    }

    public static void main(String[] args) {
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
        isAutomatic = Boolean.parseBoolean(getConfigString("isAutomatic", ""));

        consoleReader = new BufferedReader(new InputStreamReader(System.in));
        socket = null;
        writer = null;
        reader = null;

        if (!isAutomatic && !isRunningInCommandPrompt()) {
            showError("Manual mode must be run in a command prompt environment and cannot be started with 'start'.");
            System.exit(1);
        }

        if (isAutomatic && isRunningInCommandPrompt()) {
            showError("automatic mode cannot be run in a command prompt environment.");
            System.exit(1);
        }
        
        if (isAutomatic && !isSilent) {
            createAndShowStatusMessage();
        }

        while (true) {
            try {
                String hostname = getConfigString("serverIP", "");
                if (hostname.isEmpty()) {
                    System.out.print("Enter server IP: ");
                    hostname = consoleReader.readLine();
                    config.setProperty("serverIP", hostname);
                    saveConfig();
                }

                int port = getConfigInt("port", -1);
                if (port < 0 || port > 65535) {
                    System.out.print("Enter port: ");
                    port = Integer.parseInt(consoleReader.readLine());
                    if (port < 0 || port > 65535) {
                        throw new IllegalArgumentException("Port number must be between 0 and 65535.");
                    }
                    config.setProperty("port", String.valueOf(port));
                    saveConfig();
                }

                String clientName = getConfigString("clientName", "");
                if (clientName.isEmpty()) {
                    System.out.print("Enter your name: ");
                    clientName = consoleReader.readLine();
                    config.setProperty("clientName", clientName);
                    saveConfig();
                }

                if (isAutomatic && !isSilent) {
                    updateStatusLabel("Connecting to server...");
                }

                socket = new Socket(hostname, port);
                System.out.println("Connected successfully to " + hostname + " on port " + port);

                OutputStream output = socket.getOutputStream();
                writer = new PrintWriter(output, true);
                InputStream input = socket.getInputStream();
                reader = new BufferedReader(new InputStreamReader(input));

                writer.println(clientName); // Send client name to the server
                writer.flush();
                Thread.sleep(1000); // Delay after sending client name

                // Execute the auto commands if specified
                if (isAutomatic) {
                    String autoCommands = getConfigString("autoCommand", "");
                    if (!autoCommands.isEmpty()) {
                        String[] commands = autoCommands.split(";");
                        for (int i = 0; i < commands.length; i++) {
                            String command = commands[i].trim();
                            if (!command.isEmpty()) {
                                writer.println(command);
                                writer.flush();
                                if (!isSilent) {
                                    updateStatusLabel("Sending Commands....");
                                }
                                Thread.sleep(1000); // Delay after each command
                            }
                        }
                    }
                    closeResources();
                    if (!isSilent) {
                        disposeStatusMessage();
                    }
                    System.exit(0);
                } else {
                    // Thread to handle incoming messages from the server
                    Thread serverListener = new Thread(new Runnable() {
                        public void run() {
                            try {
                                String response;
                                while ((response = reader.readLine()) != null) {
                                    System.out.println("Server response: " + response);
                                    if ("Goodbye!".equalsIgnoreCase(response.trim())) {
                                        break;
                                    }
                                }
                            } catch (IOException e) {
                                showError("Connection lost: " + e.getMessage());
                            } finally {
                                closeResources();
                                System.exit(0);
                            }
                        }
                    });
                    serverListener.start();

                    // Main thread to handle user input
                    String command;
                    while (true) {
                        System.out.print(">>> ");
                        command = consoleReader.readLine();
                        if ("exit".equalsIgnoreCase(command.trim())) {
                            writer.println("exit");
                            writer.flush();
                            break;
                        }
                        writer.println(command);
                        writer.flush();
                        Thread.sleep(1000); // Delay after each user command
                    }
                }

                break; // Exit the loop if connection is successful

            } catch (UnknownHostException ex) {
                if (isAutomatic) {
                    if (!isSilent) {
                        updateStatusLabel("Server not found. Retrying in 5 seconds...");
                    }
                    logError("Server not found: " + ex.getMessage() + ". Retrying...");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    showError("Server not found: " + ex.getMessage());
                    break;
                }
            } catch (IOException ex) {
                if (isAutomatic) {
                    if (ex.getMessage().contains("refused")) {
                        if (!isSilent) {
                            updateStatusLabel("Connection refused. Retrying in 5 seconds...");
                        }
                        logError("Connection refused: " + ex.getMessage() + ". Retrying...");
                    } else {
                        if (!isSilent) {
                            updateStatusLabel("I/O error. Retrying in 5 seconds...");
                        }
                        logError("I/O error: " + ex.getMessage() + ". Retrying...");
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    if (ex.getMessage().contains("refused")) {
                        showError("I/O error: " + ex.getMessage()
                                + "\n Please check the config and ensure the server is running and the port is correct.");
                    } else {
                        showError("I/O error: " + ex.getMessage());
                    }
                    break;
                }
            } catch (IllegalArgumentException ex) {
                showError("Invalid input: " + ex.getMessage());
                break;
            } catch (InterruptedException ex) {
                showError("Thread interrupted: " + ex.getMessage());
                break;
            }

            if (!isAutomatic) {
                break; // Exit the loop if not in automatic mode
            }
        }

        closeResources();
        if (isAutomatic && !isSilent) {
            disposeStatusMessage();
        }
        System.exit(0);
    }

    private static boolean isRunningInCommandPrompt() {
        return System.console() != null;
    }

    private static void createAndShowStatusMessage() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                JFrame frame = new JFrame();
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                statusDialog = new JDialog(frame, "Status", true);
                statusDialog.setSize(300, 150);
                statusDialog.setLocationRelativeTo(frame);
                statusDialog.setLayout(new BorderLayout());
                statusDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
                statusDialog.setUndecorated(true);

                JPanel contentPanel = new JPanel(new BorderLayout());
                statusLabel = new JLabel("Terminal client is running. Please wait...",
                        SwingConstants.CENTER);
                JProgressBar progressBar = new JProgressBar();
                progressBar.setIndeterminate(true);

                contentPanel.add(statusLabel, BorderLayout.CENTER);
                contentPanel.add(progressBar, BorderLayout.SOUTH);

                statusDialog.getContentPane().add(contentPanel);
                statusDialog.setVisible(true);
            }
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

    private static void closeResources() {
        try {
            if (reader != null) {
                reader.close();
                reader = null;
            }
        } catch (IOException ex) {
            showError("Error closing reader: " + ex.getMessage());
        }

        if (writer != null) {
            writer.close();
            writer = null;
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                socket = null;
            }
        } catch (IOException ex) {
            showError("Error closing socket: " + ex.getMessage());
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
        config.setProperty("serverIP", "192.168.0.66");
        config.setProperty("port", "8887");
        config.setProperty("clientName", "ryu");
        config.setProperty("autoCommand",
                "ps aux | grep '[j]ava' | grep -v 'TerminalServer.jar' | awk '{print $2}' | xargs -r kill; /home/user1/PsJPOS_TouchScreen/bin/linux/PsJPOS.sh; exit");
        config.setProperty("silentMode", "false");
        config.setProperty("isAutomatic", "true");
        saveConfig();
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

            if (lines.size() > 100) {
                lines = lines.subList(lines.size() - 100, lines.size());
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
}