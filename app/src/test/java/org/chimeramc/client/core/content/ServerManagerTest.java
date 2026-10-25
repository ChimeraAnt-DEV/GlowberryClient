package org.chimeramc.client.core.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Covers {@link ServerManager}'s file format. The "add server" path used to depend on a deep link
 * the running game never read; the file writer is now the durable path, so its format and its
 * de-duplication are worth pinning.
 */
public class ServerManagerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void writesTheGameExternalServersFormat() throws Exception {
        File dir = folder.newFolder("minecraftpe");
        File file = new File(dir, "external_servers.txt");

        assertEquals(ServerManager.AddResult.ADDED,
                ServerManager.writeServerToFile(file, new ServerItem("My Server", "1.2.3.4", 19132)));

        String contents = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(contents.startsWith("1:My Server:1.2.3.4:19132:"));
        assertEquals(5, contents.trim().split(":").length);
    }

    @Test
    public void idsAreOnePastTheCurrentMaximum() throws Exception {
        File file = new File(folder.newFolder("mp"), "external_servers.txt");
        assertEquals(ServerManager.AddResult.ADDED,
                ServerManager.writeServerToFile(file, new ServerItem("A", "1.1.1.1", 19132)));

        // Re-read the id the first write chose, then add a second and confirm it increments.
        String first = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        String firstId = first.split(":")[0];

        assertEquals(ServerManager.AddResult.ADDED,
                ServerManager.writeServerToFile(file, new ServerItem("B", "2.2.2.2", 19133)));

        List<String> lines = Files.readAllLines(file.toPath());
        assertEquals(2, lines.size());
        assertEquals(Integer.parseInt(firstId) + 1, Integer.parseInt(lines.get(1).split(":")[0]));
    }

    @Test
    public void rejectsADuplicateServer() throws Exception {
        File file = new File(folder.newFolder("dup"), "external_servers.txt");
        assertEquals(ServerManager.AddResult.ADDED,
                ServerManager.writeServerToFile(file, new ServerItem("Same", "9.9.9.9", 19132)));
        assertEquals(ServerManager.AddResult.DUPLICATE,
                ServerManager.writeServerToFile(file, new ServerItem("Same", "9.9.9.9", 19132)));
        assertEquals(1, Files.readAllLines(file.toPath()).size());
    }

    @Test
    public void createsTheParentDirectory() throws Exception {
        File file = new File(folder.getRoot(), "made/up/path/external_servers.txt");
        assertEquals(ServerManager.AddResult.ADDED,
                ServerManager.writeServerToFile(file, new ServerItem("N", "3.3.3.3", 19132)));
        assertTrue(file.exists());
    }

    /**
     * A write failure must not be reported as a duplicate.
     *
     * <p>The boolean this replaced collapsed both to {@code false}, so the Quick Launch path told
     * the user "that server is already in your list" when the file could not be written at all -
     * sending them to look for a duplicate that does not exist. The parent is a regular file here,
     * so the directory cannot be created.
     */
    @Test
    public void aWriteFailureIsReportedAsFailedNotAsADuplicate() throws Exception {
        File blocker = folder.newFile("not-a-directory");
        File file = new File(blocker, "external_servers.txt");

        assertEquals(ServerManager.AddResult.FAILED,
                ServerManager.writeServerToFile(file, new ServerItem("S", "1.1.1.1", 19132)));
    }

    @Test
    public void aManagerWithNoDirectoryReportsNoFileRatherThanThrowing() {
        ServerManager manager = new ServerManager();
        org.junit.Assert.assertNull(manager.getServerListFile());
        assertTrue(manager.getServers().isEmpty());
        assertEquals(ServerManager.AddResult.FAILED,
                manager.addServer(new ServerItem("x", "1.1.1.1", 19132)));
    }

    @Test
    public void readsBackWhatTheStaticWriterWrote() throws Exception {
        File file = new File(folder.newFolder("round"), "external_servers.txt");
        assertEquals(ServerManager.AddResult.ADDED,
                ServerManager.writeServerToFile(file, new ServerItem("Round", "5.5.5.5", 19132)));

        ServerManager manager = new ServerManager();
        manager.setMinecraftPeDirectory(file.getParentFile());
        assertNotNull(manager.getServerListFile());
        List<ServerItem> servers = manager.getServers();
        assertEquals(1, servers.size());
        assertEquals("Round", servers.get(0).name);
        assertEquals("5.5.5.5", servers.get(0).ip);
        assertEquals(19132, servers.get(0).port);
    }
}
