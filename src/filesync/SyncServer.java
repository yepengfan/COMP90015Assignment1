package filesync;

/**
 * Created by yepengfan on 28/03/15.
 */

import java.net.*;
import java.io.*;
import java.util.*;

import org.json.simple.JSONArray;
import org.json.simple.parser.ContainerFactory;
import org.json.simple.parser.JSONParser;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.ExampleMode;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Implement TCP Protocol - Server Side
 */

public class SyncServer {
    private static ServerSocket listenSocket = null;
    private static Map<String, SynchronisedFile> instanceMapper = new
            HashMap<>();
    private static int NUMBER = 0;

    @Option(name = "-f", usage = "synchronise this folder", required = true)
    private static String toDirectory;

    @Option(name = "-p", usage = "port number")
    private static int serverPort = 4444;

    @Argument
    private List<String> arguments = new ArrayList<>();

    private void processConnections(Socket clientSocket) {
        new Connection(clientSocket);
    }

    public static void main(String[] args) throws IOException {
        SyncServer syncServer = new SyncServer(args);
        syncServer.registerDir();

        try {
            listenSocket = new ServerSocket(serverPort);
            while (true) {
                System.out.println("Server listening for a connection");
                Socket clientSocket = listenSocket.accept();
                NUMBER++;
                System.out.println("Received connection: " + NUMBER);
                syncServer.processConnections
                        (clientSocket);
            }
        } catch (IOException e) {
            System.out.println("Listen socket: " + e.getMessage());
        }
    }

    public void doMain(String[] args) throws IOException {
        CmdLineParser parser = new CmdLineParser(this);

        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println("java SampleMain [options] arguments...");
            parser.printUsage(System.err);
            System.err.println();

            System.err.println("Example: java SampleMain" + parser.printExample(ExampleMode.ALL));
            System.exit(0);
        }
    }

    public SyncServer(String[] args) throws IOException {
        doMain(args);
    }

    private void registerDir() {
        // destination directory
        File dir = new File(toDirectory);
        if (!dir.exists()) {
            // if directory doesnt exist, then
            // create it.
            dir.mkdir();
        }

        // register files to instanceMapper
        File[] files = dir.listFiles();
        for (File file : files) {
            try {
                SynchronisedFile tf = new SynchronisedFile(file.getAbsolutePath()
                        .toString());
                instanceMapper.put(file.getName(), tf);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    protected class Connection extends Thread {
        DataInputStream in;
        DataOutputStream out;
        Socket clientSocket;

        public Connection(Socket aClientSocket) {
            try {
                clientSocket = aClientSocket;
                in = new DataInputStream(clientSocket.getInputStream());
                out = new DataOutputStream(clientSocket.getOutputStream());
                this.start();
            } catch (IOException e) {
                System.out.println("Connection: " + e.getMessage());
            }
        }

        private Map parseJSON(String data) {
            JSONParser parser = new JSONParser();
            ContainerFactory containerFactory = new ContainerFactory() {
                @Override
                public Map createObjectContainer() {
                    return null;
                }

                @Override
                public List creatArrayContainer() {
                    return null;
                }
            };

            Map json = null;
            try {
                json = (Map) parser.parse(data, containerFactory);
            } catch (org.json.simple.parser.ParseException pe) {
                System.out.println(pe.getMessage());
            }
            return json;
        }

        private String[] parseDir(String jsonIndex) {
            String[] file_list = jsonIndex.split(",");
            return file_list;
        }

        public void run() {
            InstructionFactory instFact = new InstructionFactory();
            try { // an echo server
                String fileName = null;
                System.out.println("Server reading data");
                while (true) {
                    synchronized (this) {
                        String data = in.readUTF();
                        //parse input stream JSON
                        Map json = parseJSON(data);

                        if (json.get("Type").equals("Index")) {
                            // parse directory index
                            JSONArray file_list = (JSONArray) json.get("Index");
                            for (int i = 0; i < file_list.size(); i++) {
                                if (instanceMapper.get(file_list.get(i)) == null) {
                                    // destination directory
                                    File dir = new File(toDirectory);
                                    // create files
                                    File file = new File(dir.getAbsoluteFile
                                            ().toString() + File.separator +
                                            file_list.get(i));
                                    if (!file.exists()) {
                                        file.createNewFile();
                                    }
                                    SynchronisedFile tf = new
                                            SynchronisedFile(file
                                            .getAbsolutePath().toString());
                                    // add new instance to instance mapper
                                    instanceMapper.put(file.getName(), tf);
                                }
                            }
                            System.err.println(json.toString());
                            out.writeUTF("Success");
                        } else if (json.get("Type").equals("CreateFile")) {
                            File dir = new File(toDirectory);
                            File file = new File(dir.getAbsoluteFile()
                                    .toString() + File.separator + json.get
                                    ("FileName"));

                            if (!file.exists()) {
                                file.createNewFile();
                            }
                            SynchronisedFile tf = new SynchronisedFile(file
                                    .getAbsolutePath().toString());
                            instanceMapper.put(file.getName(), tf);
                            out.writeUTF("Success");
                        } else if (json.get("Type").equals("DeleteFile")) {
                            File dir = new File(toDirectory);
                            File file = new File(dir.getAbsoluteFile().toString() + File.separator + json.get("FileName"));

                            if (file.exists()) {
                                file.delete();
                            }

                            instanceMapper.remove(file.getName());
                            out.writeUTF("Success");
                        } else if (json.get("Type").equals("StartUpdate")) {
                            fileName = json.get("FileName").toString();
                            Instruction receivedInst = instFact.FromJSON(json
                                    .toString());
                            try {
                                instanceMapper.get(fileName).ProcessInstruction(receivedInst);
                            } catch (BlockUnavailableException e) {
//                                e.printStackTrace();
                            }
                            System.err.println(json.toString());
                            out.writeUTF("Success");
                        } else {
                            Instruction receivedInst = instFact.FromJSON(json
                                    .toString());
                            try {
                                instanceMapper.get(fileName)
                                        .ProcessInstruction(receivedInst);
                                System.err.println(json.toString());
                                out.writeUTF("Success");
                            } catch (BlockUnavailableException e) {
                                System.err.println(json.toString());
                                out.writeUTF("BlockUnavailableException");
                                data = in.readUTF();
                                json = parseJSON(data);
                                receivedInst = instFact.FromJSON(json
                                        .toString());
                                try {
                                    instanceMapper.get(fileName)
                                            .ProcessInstruction(receivedInst);
                                    System.err.println(json.toString());
                                    out.writeUTF("Success");
                                } catch (BlockUnavailableException e1) {
                                    assert (false);
                                }
                            }
                        }
                    }
                }
            } catch (EOFException e) {
                System.out.println("EOF: " + e.getMessage());
            } catch (IOException e) {
                System.out.println("Readline: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.out.println("Socket closed failed.");
                }
            }
        }
    }
}
