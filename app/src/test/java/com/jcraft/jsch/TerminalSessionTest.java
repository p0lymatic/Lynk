package com.jcraft.jsch;

import com.p0lymatic.lynk.ssh.TerminalSession;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
            await(() -> shell.sent.size() == "echo Привет\r\u0003\u001b[A".getBytes(StandardCharsets.UTF_8).length);
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

    /** A stalled network must not stall the UI, resize, or disposal. */
    @Test public void queuesInputDsrResizeAndCloseOnOneWorker() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<Thread> writeThread = new AtomicReference<>();
        AtomicReference<Thread> resizeThread = new AtomicReference<>();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        Shell shell = new Shell() {
            @Override public OutputStream getOutputStream() {
                outputStreams++;
                return new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        writeThread.set(Thread.currentThread());
                        writing.countDown();
                        try {
                            if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("write blocked");
                        } catch (InterruptedException e) { throw new IOException(e); }
                        sent.write(value);
                    }
                };
            }
            @Override public void setPtySize(int cols, int rows, int width, int height) {
                // The initial PTY configuration happens before connect, on the IO constructor path.
                if (cols == 42) resizeThread.set(Thread.currentThread());
            }
            @Override public void disconnect() {
                closeThread.set(Thread.currentThread());
                super.disconnect();
                disconnected.countDown();
            }
        };
        TerminalSession terminal = terminal(shell);
        Thread caller = new Thread(() -> {
            terminal.sendBytes(new byte[] {'A'});
            returned.countDown();
        }, "test-ui");
        try {
            caller.start();
            assertTrue("sendBytes blocked its caller", returned.await(1, TimeUnit.SECONDS));
            assertTrue(writing.await(1, TimeUnit.SECONDS));
            assertNotSame(caller, writeThread.get());

            // Feed on the actual reader; its DSR reply must queue behind A and before B.
            await(() -> terminal.getRevision().getValue() > 0);
            long before = terminal.getRevision().getValue();
            shell.receive("\u001b[5n");
            await(() -> terminal.getRevision().getValue() > before);
            byte[] bytes = new byte[] {'B'};
            terminal.sendBytes(bytes);
            bytes[0] = 'X'; // queued input must be owned by the session
            terminal.resize(42, 10);
            terminal.close();
            terminal.close();
            terminal.send("ignored after close");
            assertNull("resize ran before queued input", resizeThread.get());
            assertNull("close ran before queued input", closeThread.get());
        } finally {
            release.countDown();
            terminal.close();
            caller.join(2000);
        }
        assertTrue(disconnected.await(2, TimeUnit.SECONDS));
        await(() -> terminal.getStatus().getValue() == TerminalSession.Status.CLOSED);
        assertEquals("A\u001b[0nB", shell.sent.toString("UTF-8"));
        assertSame(writeThread.get(), resizeThread.get());
        assertSame(writeThread.get(), closeThread.get());
        assertEquals(1, shell.outputStreams);
        assertNull(terminal.getError().getValue());
    }

    @Test public void reportsWriterFailureAndClosesOnce() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        CountDownLatch notified = new CountDownLatch(1);
        Shell shell = new Shell() {
            @Override public OutputStream getOutputStream() {
                return new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        throw new IOException("writer failed");
                    }
                };
            }
            @Override public void disconnect() {
                closed.incrementAndGet();
                super.disconnect();
                throw new IllegalStateException("teardown failed after write");
            }
        };
        TerminalSession terminal = new TerminalSession(sessionFor(shell), 80, 24, () -> {
            notified.countDown();
            return Unit.INSTANCE;
        });
        terminal.send("hello");
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        assertEquals(TerminalSession.Status.ERROR, terminal.getStatus().getValue());
        assertEquals("writer failed", terminal.getError().getValue());
        terminal.close();
        assertEquals(1, closed.get());
    }

    @Test public void rejectsInputOverflowWithVisibleError() throws Exception {
        try (TerminalSession terminal = terminal(new Shell())) {
            terminal.sendBytes(new byte[1024 * 1024 + 1]);
            assertEquals(TerminalSession.Status.ERROR, terminal.getStatus().getValue());
            assertEquals("Переполнена очередь ввода терминала", terminal.getError().getValue());
        }
    }

    @Test public void reportsFlushFailureEvenWhenCloseIsAlreadyQueued() throws Exception {
        CountDownLatch flushing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);
        Shell shell = new Shell() {
            @Override public OutputStream getOutputStream() {
                return new OutputStream() {
                    @Override public void write(int value) { }
                    @Override public void flush() throws IOException {
                        flushing.countDown();
                        try { release.await(2, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { throw new IOException(e); }
                        throw new IOException("flush failed");
                    }
                };
            }
        };
        TerminalSession terminal = new TerminalSession(sessionFor(shell), 80, 24, () -> {
            notified.countDown();
            return Unit.INSTANCE;
        });
        try {
            terminal.send("hello");
            assertTrue(flushing.await(1, TimeUnit.SECONDS));
            terminal.close();
        } finally {
            release.countDown();
            terminal.close();
        }
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        assertEquals(TerminalSession.Status.ERROR, terminal.getStatus().getValue());
        assertEquals("flush failed", terminal.getError().getValue());
    }

    @Test public void reportsResizeFailure() throws Exception {
        Shell shell = new Shell() {
            @Override public void setPtySize(int cols, int rows, int width, int height) {
                if (cols == 42) throw new IllegalStateException("resize failed");
            }
        };
        try (TerminalSession terminal = terminal(shell)) {
            terminal.resize(42, 10);
            await(() -> terminal.getStatus().getValue() == TerminalSession.Status.ERROR);
            assertEquals("resize failed", terminal.getError().getValue());
        }
    }

    @Test public void reportsDisconnectFailure() throws Exception {
        Shell shell = new Shell() {
            @Override public void disconnect() {
                super.disconnect();
                throw new IllegalStateException("disconnect failed");
            }
        };
        TerminalSession terminal = terminal(shell);
        terminal.close();
        await(() -> terminal.getStatus().getValue() == TerminalSession.Status.ERROR);
        assertEquals("disconnect failed", terminal.getError().getValue());
    }

    @Test public void boundsQueuedAndInFlightInputTogether() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);
        Shell shell = new Shell() {
            @Override public OutputStream getOutputStream() {
                return new OutputStream() {
                    @Override public void write(int value) { }
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                        writing.countDown();
                        try {
                            if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("write blocked");
                        } catch (InterruptedException e) { throw new IOException(e); }
                    }
                };
            }
        };
        TerminalSession terminal = new TerminalSession(sessionFor(shell), 80, 24, () -> {
            notified.countDown();
            return Unit.INSTANCE;
        });
        try {
            terminal.sendBytes(new byte[1024 * 1024 - 1]);
            assertTrue(writing.await(1, TimeUnit.SECONDS));
            terminal.send("x"); // active write plus this queued byte fills the budget
            assertEquals(TerminalSession.Status.CONNECTED, terminal.getStatus().getValue());
            terminal.send("y");
            assertEquals(TerminalSession.Status.ERROR, terminal.getStatus().getValue());
            assertEquals("Переполнена очередь ввода терминала", terminal.getError().getValue());
        } finally {
            release.countDown();
            terminal.close();
        }
        assertTrue(notified.await(2, TimeUnit.SECONDS));
    }

    @Test public void remoteScrollFallsBackToArrows() throws Exception {
        Shell shell = new Shell();
        try (TerminalSession terminal = terminal(shell)) {
            terminal.scrollRemote(2, 3, 4);
            terminal.scrollRemote(-1, 3, 4);
            terminal.scrollRemote(0, 3, 4);
            await(() -> shell.sent.size() == 9);
            assertEquals("\u001b[A\u001b[A\u001b[B", shell.sent.toString("UTF-8"));
        }
    }

    @Test public void remoteScrollUsesSgrWheelWhenMouseTrackingEnabled() throws Exception {
        Shell shell = new Shell();
        try (TerminalSession terminal = terminal(shell)) {
            await(() -> terminal.getRevision().getValue() > 0);
            shell.receive("\u001b[?1000h");
            await(() -> terminal.getScreen().getMouseReporting());
            terminal.scrollRemote(2, 3, 4);
            terminal.scrollRemote(-1, 3, 4);
            terminal.scrollRemote(1, -5, 999);
            String expected = "\u001b[<64;4;5M\u001b[<64;4;5M\u001b[<65;4;5M\u001b[<64;1;24M";
            await(() -> shell.sent.size() == expected.length());
            assertEquals(expected, shell.sent.toString("UTF-8"));
            shell.receive("\u001b[?1000l");
            await(() -> !terminal.getScreen().getMouseReporting());
            terminal.scrollRemote(-1, 0, 0);
            await(() -> shell.sent.size() == expected.length() + 3);
            assertEquals(expected + "\u001b[B", shell.sent.toString("UTF-8"));
        }
    }

    @Test public void dsrDoesNotCountAsUserInput() throws Exception {
        Shell shell = new Shell();
        try (TerminalSession terminal = terminal(shell)) {
            shell.receive("\u001b[5n");
            await(() -> shell.sent.size() == 4);
            assertEquals(0L, terminal.getInputRevision().getValue().longValue());
            terminal.send("x");
            assertEquals(1L, terminal.getInputRevision().getValue().longValue());
        }
    }

}
