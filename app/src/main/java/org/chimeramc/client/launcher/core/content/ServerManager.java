package org.chimeramc.client.core.content;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ServerManager {

    /**
     * The outcome of adding a server, so the caller can say which one happened.
     *
     * <p>A boolean could not: "the server is already in your list" and "the file could not be
     * written" both collapsed to {@code false}, so a disk or permission failure told the user their
     * server was already there — a message that sends them looking for a duplicate that does not
     * exist instead of at the storage problem that does.
     */
    public enum AddResult {
        /** The server was written to the list. */
        ADDED,
        /** A server with the same name, address and port is already in the list. */
        DUPLICATE,
        /** The list could not be written: no target, an uncreatable directory, or an IO failure. */
        FAILED,
    }

    private File minecraftPeDir;

    public void setMinecraftPeDirectory(File directory) {
        this.minecraftPeDir = directory;
    }

    public File getServerListFile() {
        if (minecraftPeDir == null) return null;
        return new File(minecraftPeDir, "external_servers.txt");
    }

    /**
     * Writes a server straight into the given {@code external_servers.txt}.
     *
     * <p>Static so a caller that knows the game data directory but does not own a ContentManager
     * (the Quick Launch "add server" action) can persist a server without first wiring one up.
     * The format is the one the game itself reads: {@code id:name:ip:port:timestamp}, with the id
     * one past the current maximum. The result distinguishes a duplicate from a write failure, so
     * the caller can report the right one.
     */
    public static AddResult writeServerToFile(File file, ServerItem serverItem) {
        if (file == null || serverItem == null) return AddResult.FAILED;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return AddResult.FAILED;

        List<String> lines = new ArrayList<>();
        int maxIndex = 0;
        boolean exists = false;
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                    String[] parts = line.split(":");
                    if (parts.length >= 5) {
                        if (parts[1].equals(serverItem.name)
                                && parts[2].equals(serverItem.ip)
                                && parts[3].equals(String.valueOf(serverItem.port))) {
                            exists = true;
                        }
                        try {
                            int idx = Integer.parseInt(parts[0]);
                            if (idx > maxIndex) maxIndex = idx;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (exists) return AddResult.DUPLICATE;

        long timestamp = System.currentTimeMillis() / 1000L;
        lines.add((maxIndex + 1) + ":" + serverItem.name + ":" + serverItem.ip + ":"
                + serverItem.port + ":" + timestamp);
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            for (String l : lines) {
                bw.write(l);
                bw.newLine();
            }
            return AddResult.ADDED;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return AddResult.FAILED;
    }

    public List<ServerItem> getServers() {
        List<ServerItem> items = new ArrayList<>();
        File serverFile = getServerListFile();
        if (serverFile == null || !serverFile.exists()) return items;

        try (BufferedReader br = new BufferedReader(new FileReader(serverFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(":");
                try {
                    if (parts.length >= 5) {
                        String name = parts[1];
                        String ip = parts[2];
                        int port = Integer.parseInt(parts[3]);
                        items.add(new ServerItem(name, ip, port));
                    }
                } catch (Exception e) {
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return items;
    }

    public boolean deleteServer(ServerItem serverItem) {
        File serverFile = getServerListFile();
        if (serverFile == null || !serverFile.exists()) return false;

        List<String> lines = new ArrayList<>();
        boolean deleted = false;
        try (BufferedReader br = new BufferedReader(new FileReader(serverFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length >= 5) {
                    String name = parts[1];
                    String ip = parts[2];
                    String portStr = parts[3];
                    if (name.equals(serverItem.name) && ip.equals(serverItem.ip) && portStr.equals(String.valueOf(serverItem.port))) {
                        deleted = true;
                        continue;
                    }
                }
                lines.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        if (deleted) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(serverFile))) {
                for (String l : lines) {
                    bw.write(l);
                    bw.newLine();
                }
                return true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return false;
    }

    public AddResult addServer(ServerItem serverItem) {
        File file = getServerListFile();
        if (file == null) return AddResult.FAILED;

        List<String> lines = new ArrayList<>();
        int maxIndex = 0;
        boolean exists = false;
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                    String[] parts = line.split(":");
                    if (parts.length >= 5) {
                        String name = parts[1];
                        String ip = parts[2];
                        String portStr = parts[3];
                        try {
                            int idx = Integer.parseInt(parts[0]);
                            if (idx > maxIndex) maxIndex = idx;
                        } catch (NumberFormatException ignored) {}

                        if (name.equals(serverItem.name) && ip.equals(serverItem.ip) && portStr.equals(String.valueOf(serverItem.port))) {
                            exists = true;
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (exists) return AddResult.DUPLICATE;

        long timestamp = System.currentTimeMillis() / 1000L;
        String newLine = (maxIndex + 1) + ":" + serverItem.name + ":" + serverItem.ip + ":" + serverItem.port + ":" + timestamp;
        lines.add(newLine);

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            for (String l : lines) {
                bw.write(l);
                bw.newLine();
            }
            return AddResult.ADDED;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return AddResult.FAILED;
    }
}
