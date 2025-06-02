# TerminalClient and TerminalServer

A fun and easy way to run your Java program from anywhere in your network!

## Why I built this?

I built this because in my job I use Linux to run my compiled jar files and I was having a problem running over and over again like clicking the so I made this program to keep it easy to send command and run the program.

## What is it?

The TerminalClient and TerminalServer are two Java programs that allow you to run your Java program from anywhere in your network. You can use the TerminalClient to send commands to the TerminalServer and the TerminalServer will run your program and send the output back to the TerminalClient.

## Technology Stack

- **Java**: Built with Java 1.8 compatibility for wide platform support
- **Socket Programming**: Uses Java's socket communication for network connectivity
- **Swing GUI**: The TerminalClient includes a Swing-based user interface for easy interaction
- **Multi-threading**: Handles multiple client connections simultaneously
- **Cross-platform**: Works on both Windows and Linux environments
- **Configuration Management**: Uses Properties files for easy configuration

### TerminalClient

The TerminalClient is a Java program that you can run from anywhere in your network. You can use it to send commands to the TerminalServer and the TerminalServer will run your program and send the output back to the TerminalClient.

#### Features:
- Automatic and manual execution modes
- Connection state management
- Configurable host connections
- Error logging and handling

### TerminalServer

The TerminalServer is a Java program that you can run on a server. You can use it to run your Java program and send the output back to the TerminalClient.

#### Features:
- Multi-client support
- Command history tracking
- Platform-specific command execution (Windows/Linux)
- Dynamic configuration reloading

## System Requirements

- JDK 1.8 or higher
- Network connectivity between client and server machines
- Windows or Linux operating system

## How to use it?

Here are the steps to use the TerminalClient and TerminalServer:

1. Run the TerminalServer on a server.
2. Run the TerminalClient on your computer.
3. Type in the command you want to run and press enter.
4. The TerminalServer will run your program and send the output back to the TerminalClient.

### Example

Let's say you want to run the command "java -jar myprogram.jar" on the server. You can type in the command in the TerminalClient and press enter. The TerminalServer will run the program and send the output back to the TerminalClient.

## Features

Here are some of the features of the TerminalClient and TerminalServer:

* Easy to use
* Can be used from anywhere in your network
* Can run multiple programs at the same time
* Can send commands to the TerminalServer and the TerminalServer will run your program and send the output back to the TerminalClient

## How to get it?

You can get the TerminalClient and TerminalServer from cloning the repository.

## How to build it?

You can build the TerminalClient and TerminalServer by running the following command in the terminal:

```bash
./compile.bat
```

This will create the necessary JAR files in the `dist` directory.

## Configuration

Both the client and server use properties files for configuration:
- `client_config.properties`: Contains client connection settings
- `server_config.properties`: Contains server port and other settings
