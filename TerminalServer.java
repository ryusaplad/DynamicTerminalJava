import java.io.*;
import java.net.*;
import java.util.*;
import javax.swing.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.io.RandomAccessFile;

public class TerminalServer {
    private static final List<ClientInfo> clients = Collections.synchronizedList(new ArrayList<ClientInfo>());
    private static final String CONFIG_FILE = "server_config.properties";
    private static Properties config = new Properties();
    private static ServerWindow serverWindow;
    private static final String LOCK_FILE = "server.lock";
    private static FileLock lock;
    private static FileChannel lockChannel;
    private static RandomAccessFile lockFileStream;

    public static void main(String[] args) {
        if (!acquireLock()) {
            String message = "Another instance of TerminalServer may already be running.";
            System.err.println(message);
            JOptionPane.showMessageDialog(null, message, "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            releaseLock();
            System.out.println("Server shut down.");
        }));
        
        // Initialize and show the server window
        SwingUtilities.invokeLater(() -> {
            serverWindow = new ServerWindow();
            serverWindow.setVisible(true);
            log("INFO", "Server starting...");
        });
        
        ServerSocket serverSocket = null;
        try {
            while (true) {
                loadConfig(); // Reload config before each connection
                int port = getConfigInt("port", 8080);

                if (serverSocket == null || serverSocket.isClosed() || serverSocket.getLocalPort() != port) {
                    if (serverSocket != null && !serverSocket.isClosed()) {
                        serverSocket.close();
                    }
                    serverSocket = new ServerSocket(port);
                    log("INFO", "Server is listening on port " + port);
                }

                Socket socket = serverSocket.accept();
                log("INFO", "New connection from: " + socket.getInetAddress().getHostAddress());
                new ServerThread(socket).start();
            }
        } catch (IOException e) {
            log("ERROR", "Server error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static boolean acquireLock() {
        try {
            File file = new File(LOCK_FILE);
            lockFileStream = new RandomAccessFile(file, "rw");
            lockChannel = lockFileStream.getChannel();
            lock = lockChannel.tryLock();
            if (lock == null) {
                // Lock is held by another process
                lockChannel.close();
                lockFileStream.close();
                return false;
            }
            // Lock acquired
            return true;
        } catch (OverlappingFileLockException e) {
            return false;
        } catch (IOException e) {
            System.err.println("Could not acquire lock on " + LOCK_FILE + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static void releaseLock() {
        try {
            if (lock != null) {
                lock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
            if (lockFileStream != null) {
                lockFileStream.close();
            }
            new File(LOCK_FILE).delete();
        } catch (IOException e) {
            System.err.println("Failed to release file lock: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static class ServerThread extends Thread {
        private Socket socket;

        public ServerThread(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            BufferedReader reader = null;
            PrintWriter writer = null;
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new PrintWriter(socket.getOutputStream(), true);

                String clientName = reader.readLine();
                String clientIp = socket.getInetAddress().getHostAddress();
                ClientInfo clientInfo = new ClientInfo(clientName, clientIp);
                synchronized (clients) { 
                    clients.add(clientInfo);
                    // Update GUI
                    if (serverWindow != null) {
                        serverWindow.updateConnectionCount(clients.size());
                        serverWindow.addUser(clientName, clientIp);
                    }
                }

                log("INFO", "Client connected: " + clientName);

                String command;
                while ((command = reader.readLine()) != null) {
                    log("INFO", "Received from " + clientName + ": " + command);
                    clientInfo.addCommand(command);

                    if ("exit".equalsIgnoreCase(command.trim())) {
                        log("INFO", "Exit command received from " + clientName);
                        writer.println("Goodbye!");
                        break; // Exit loop to stop reading further commands
                    }

                    try {
                        if (command.startsWith("-i ")) {
                            handleInfoCommand(command.substring(3).trim(), writer);
                        } else if ("-h".equals(command)) {
                            handleHelpCommand(writer);
                        } else {
                            writer.println(executeCommand(command));
                        }
                    } catch (Exception e) {
                        writer.println("Error processing command: " + e.getMessage()); 
                    }
                }
            } catch (IOException e) {
                log("ERROR", "Error: " + e.getMessage());
            } finally {
                cleanup();
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        log("ERROR", "Error closing reader: " + e.getMessage());
                    }
                }
                if (writer != null) {
                    writer.close();
                }
            }
        }

        private void handleInfoCommand(String targetName, PrintWriter writer) {
            synchronized (clients) {
                boolean clientFound = false;
                for (ClientInfo ci : clients) {
                    if (ci.name.equals(targetName)) {
                        writer.println(ci);
                        clientFound = true;
                        break;
                    }
                }
                if (!clientFound) {
                    writer.println("No client found with name: " + targetName);
                }
            }
            writer.println("END_OF_INFO");
        }

        private void handleHelpCommand(PrintWriter writer) {
            StringBuilder clientNames = new StringBuilder("Client names: ");
            synchronized (clients) {
                for (ClientInfo ci : clients) {
                    clientNames.append(ci.name).append(", ");
                }
            }
            writer.println(clientNames.substring(0, clientNames.length() - 2));
        }

        private String executeCommand(String command) throws IOException {
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                if (command.endsWith(".sh")) {
                    return "Shell scripts (.sh) are not supported on Windows.";
                }
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                if (new File(command).exists() && command.endsWith(".sh")) {
                    if (new File("/usr/bin/gnome-terminal").exists()) {
                        pb = new ProcessBuilder("gnome-terminal", "--", "/bin/bash", command);
                    } else {
                        return "gnome-terminal not found.";
                    }
                } else {
                    pb = new ProcessBuilder("/bin/sh", "-c", command);
                }
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();
            return readProcessOutput(process);
        }

        private String readProcessOutput(Process process) throws IOException {
            StringBuilder output = new StringBuilder();
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } finally {
                if (reader != null) {
                    reader.close();
                }
            }
            return output.toString();
        }

        private void cleanup() {
            synchronized (clients) {
                Iterator<ClientInfo> iterator = clients.iterator();
                while (iterator.hasNext()) {
                    ClientInfo ci = iterator.next();
                    if (ci.ip.equals(socket.getInetAddress().getHostAddress())) {
                        iterator.remove();
                        log("INFO", "Client disconnected: " + ci.name);
                        // Update GUI
                        if (serverWindow != null) {
                            serverWindow.updateConnectionCount(clients.size());
                            serverWindow.removeUser(ci.name, ci.ip);
                        }
                        break;
                    }
                }
            }
            try {
                socket.close();
            } catch (IOException e) {
                log("ERROR", "Error closing socket: " + e.getMessage());
            }
        }
    }

    private static class ClientInfo implements Serializable {
        String name;
        String ip;
        List<String> commands = new ArrayList<String>();

        public ClientInfo(String name, String ip) {
            this.name = name;
            this.ip = ip;
        }

        public void addCommand(String command) {
            commands.add(command);
        }

        @Override
        public String toString() {
            return "Client[name=" + name + ", ip=" + ip + ", commands=" + commands + "]";
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
            config.clear(); // Clear existing properties
            config.load(in);
        } catch (IOException e) {
            log("ERROR", "Error loading config file: " + e.getMessage());
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    log("ERROR", "Error closing config file: " + e.getMessage());
                }
            }
        }
    }

    private static void createDefaultConfig() {
        config.setProperty("port", "8080");
        saveConfig();
    }

    private static void saveConfig() {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(CONFIG_FILE);
            config.store(out, "Server Configuration");
        } catch (IOException e) {
            log("ERROR", "Error saving config file: " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    log("ERROR", "Error closing config file: " + e.getMessage());
                }
            }
        }
    }

    private static String getConfigString(String key, String defaultValue) {
        return config.getProperty(key, defaultValue);
    }

    private static int getConfigInt(String key, int defaultValue) {
        return Integer.parseInt(config.getProperty(key, String.valueOf(defaultValue)));
    }

    // Add this helper method for logging
    private static void log(String level, String message) {
        System.out.println(message);
        if (serverWindow != null) {
            serverWindow.log(level, message);
        }
    }
}
