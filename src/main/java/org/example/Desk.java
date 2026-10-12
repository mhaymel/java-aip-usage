package org.example;

import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * What the program asks of the desktop for the About view: to show a file in its folder, and to put a text on the
 * clipboard. It is asked for the program's own files only, by the paths the program knows; nothing a request says ever
 * becomes a command or a path here.
 */
interface Desk {

    /**
     * Opens the folder the file is in, with the file marked where the system can do that.
     *
     * @throws IOException if the system's file manager cannot be started
     */
    void reveal(Path file) throws IOException;

    /**
     * Puts the text on the clipboard.
     *
     * @throws IOException if there is no clipboard to put it on, or it does not take it
     */
    void copy(String text) throws IOException;

    /** The desktop of the machine the program runs on. */
    Desk SYSTEM = new Desk() {

        @Override
        public void reveal(Path file) throws IOException {
            List<String> command = revealCommand(System.getProperty("os.name", ""), file, Files.exists(file));
            // The file manager is left to itself: on Windows it ends with a code that says nothing, at once or never.
            try {
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException e) {
                throw new Fault("the file manager could not be started: " + Failure.ofStart(e), e);
            }
        }

        @Override
        public void copy(String text) throws IOException {
            CompletableFuture<Void> done = new CompletableFuture<>();
            try {
                // The clipboard is the window toolkit's, and is used on its thread.
                Platform.runLater(() -> {
                    try {
                        ClipboardContent content = new ClipboardContent();
                        content.putString(text);
                        if (!Clipboard.getSystemClipboard().setContent(content)) {
                            done.completeExceptionally(new Fault("the clipboard did not take it"));
                            return;
                        }
                        done.complete(null);
                    } catch (RuntimeException e) {
                        done.completeExceptionally(e);
                    }
                });
                done.get(3, TimeUnit.SECONDS);
            } catch (IllegalStateException e) {
                throw new Fault("there is no window, and so no clipboard", e);
            } catch (ExecutionException e) {
                throw new Fault(Failure.of(e.getCause()), e.getCause());
            } catch (TimeoutException e) {
                throw new Fault("the clipboard did not answer", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Fault(Failure.INTERRUPTED, e);
            }
        }
    };

    /**
     * The command that shows a file in its folder, for the system of the given name: the Explorer on Windows and the
     * Finder on macOS mark the file; elsewhere, and for a file that is not there, the folder is opened.
     *
     * @param exists whether the file is there; a file that is not cannot be marked
     */
    static List<String> revealCommand(String osName, Path file, boolean exists) {
        Path absolute = file.toAbsolutePath().normalize();
        Path folder = absolute.getParent() != null ? absolute.getParent() : absolute;
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return exists ? List.of("explorer.exe", "/select," + absolute) : List.of("explorer.exe", folder.toString());
        }
        if (os.contains("mac")) {
            return exists ? List.of("open", "-R", absolute.toString()) : List.of("open", folder.toString());
        }
        return List.of("xdg-open", folder.toString());
    }
}
