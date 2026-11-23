package com.jcraft.jsch;

import com.p0lymatic.lynk.ssh.TerminalSession;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import kotlin.Unit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uses JSch's actual receive pipe, with shell output delivered during connect(). */
public class TerminalSessionTest {
    private static class Shell extends ChannelShell {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        int outputStreams;

        @Override public void connect(int timeout) throws JSchException {
            try { receive("MOTD\r\nuser@host:~$ "); }
            catch (IOException e) { throw new JSchException("receive failed", e); }
        }

        void receive(String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            // This is the same path Session uses for SSH_MSG_CHANNEL_DATA.
            write(bytes, 0, bytes.length);
        }

        @Override public OutputStream getOutputStream() {
            outputStreams++;
            // Like JSch, each call returns a separate buffer requiring its own flush().
            return new BufferedOutputStream(sent);
        }

        @Override public void disconnect() {
            try { io.out_close(); } catch (Exception ignored) { }
        }
    }

    private static Session sessionFor(Shell shell) throws JSchException {
        return new Session(new JSch(), "test", "localhost", 22) {
            @Override public Channel openChannel(String type) { return shell; }
        };
    }

    private static TerminalSession terminal(Shell shell) throws JSchException {
        return new TerminalSession(sessionFor(shell), 80, 24, () -> Unit.INSTANCE);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("terminal update timed out", condition.getAsBoolean());
    }

    @Test public void publishesInitialPromptAndLaterChunksWhileShellStaysOpen() throws Exception {
        Shell shell = new Shell();
        try (TerminalSession terminal = terminal(shell)) {
            await(() -> terminal.getRevision().getValue() > 0);
            assertTrue(terminal.getScreen().snapshot().get(0).getText().contains("MOTD"));
            assertTrue(terminal.getScreen().snapshot().get(1).getText().contains("user@host"));
            long before = terminal.getRevision().getValue();
            shell.receive("echo OK\r\nOK\r\n");
            await(() -> terminal.getRevision().getValue() > before);
            assertEquals(TerminalSession.Status.CONNECTED, terminal.getStatus().getValue());
        }
    }

    @Test public void flushesTypedBytesAndControlKeysOnTheSameStream() throws Exception {
        Shell shell = new Shell();
        try (TerminalSession terminal = terminal(shell)) {
            terminal.send("echo Привет\n");
            terminal.sendBytes(new byte[] {3});
            terminal.sendKey(TerminalSession.KEY_UP);
            assertEquals("echo Привет\r\u0003\u001b[A", shell.sent.toString("UTF-8"));
            assertEquals(1, shell.outputStreams);
        }
    }

    @Test public void publishesResizeWithoutWaitingForRemoteOutput() throws Exception {
        Shell shell = new Shell();
        try (TerminalSession terminal = terminal(shell)) {
            await(() -> terminal.getRevision().getValue() > 0);
            long before = terminal.getRevision().getValue();
            terminal.resize(42, 10);
            assertTrue(terminal.getRevision().getValue() > before);
            assertEquals(10, terminal.getScreen().snapshot().size());
            assertEquals(42, terminal.getScreen().getCols());
        }
    }

    @Test public void reportsReaderFailureInsteadOfSilentlyClosing() throws Exception {
        Shell shell = new Shell() {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    @Override public int read() throws IOException {
                        throw new IOException("reader failed");
                    }
                };
            }
        };
        try (TerminalSession terminal = terminal(shell)) {
            await(() -> terminal.getStatus().getValue() == TerminalSession.Status.ERROR);
            assertEquals("reader failed", terminal.getError().getValue());
        }
    }

    @Test public void publishesFinalOutputOnEof() throws Exception {
        Shell shell = new Shell();
        try (TerminalSession terminal = terminal(shell)) {
            shell.receive("final output");
            shell.io.out_close();
            await(() -> terminal.getStatus().getValue() == TerminalSession.Status.CLOSED);
            assertEquals(terminal.getScreen().getRevision(), terminal.getRevision().getValue().longValue());
            assertTrue(terminal.getScreen().snapshot().get(1).getText().contains("final output"));
        }
    }
}
