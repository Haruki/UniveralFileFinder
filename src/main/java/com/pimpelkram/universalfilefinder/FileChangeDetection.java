package com.pimpelkram.universalfilefinder;

import com.pimpelkram.universalfilefinder.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.LinkedBlockingQueue;

enum FileEvent {
    MODIFIED("Modified", "c"), CREATED("New", "+"), DELETED("Deleted", "-");

    private String nameLong;
    private String symbol;

    private FileEvent(String nameLong, String symbol) {
        this.nameLong = nameLong;
        this.symbol = symbol;
    }

    public String getSymbol() {
        return this.symbol;
    }

    public String getNameLong() {
        return this.nameLong;
    }
}

class FileChange {
    private Path path;
    private FileEvent event;
    private String[] splits;

    public FileChange(Path path, FileEvent event) {
        this.path = path;
        this.event = event;
        this.splits = path.toString().split("\\\\");
    }

    public String getShortString() {
        int lowIndex = this.splits.length - 3;
        int useIndex = (lowIndex) < 0 ? 0 : lowIndex;
        return "(" + this.splits[useIndex] + ") " + this.splits[this.splits.length - 1];
    }

    public FileEvent getFileEvent() {
        return this.event;
    }

    public Path getPath() {
        return this.path;
    }

    @Override
    public boolean equals(Object other) {
        return getShortString().equals(((FileChange) other).getShortString());
    }

    @Override
    public int hashCode() {
        return getShortString().hashCode();
    }
}

public class FileChangeDetection implements Runnable {

    private final Logger logger = LoggerFactory.getLogger(FileChangeDetection.class);
    private Path targetPath;
    private LinkedBlockingQueue<FileChange> queue;

    public FileChangeDetection(LinkedBlockingQueue<FileChange> queue, Path targetPath, Settings settings) {
        this.queue = queue;
        this.targetPath = targetPath;
        this.settings = settings;
    }

    Settings settings;

    @Override
    public void run() {
        logger.debug("Starting FileChange Detection!");
        WatchService watcher = null;
        WatchKey key;
        try {
            watcher = FileSystems.getDefault().newWatchService();
            Path path = this.targetPath;

            path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            this.logger.error(e.getMessage());
        }
        //while (!Thread.currentThread().isInterrupted()) {
        while (true) {
            try {
                logger.debug("FileChangeDetection next loop");
                key = watcher.take();
            } catch (InterruptedException x) {
                logger.debug("FileWatcher Thread terminating. " + Thread.currentThread().getName());
                return;
            }

            eventloop:
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                @SuppressWarnings("unchecked")
                Path file = ((Path) key.watchable()).resolve(((WatchEvent<Path>) event).context());

                //do not allow files that match a global regex filter to be added to the list:
                if (this.settings.getExcludeRegex() != null) {
                    for (String rx : this.settings.getExcludeRegex()) {
                        if (file.toString().matches(rx)) {
                            continue eventloop;
                        }
                    }
                }
                // this.logger.debug("New Event: " + file.toString() + " " +
                // file.toAbsolutePath().toString());
                switch (kind.name()) {
                    case "ENTRY_OVERFLOW":
                        continue;
                    case "ENTRY_MODIFY":
                        this.logger.debug("New Event: Modify - " + file.toString());
                        this.queue.offer(new FileChange(file, FileEvent.MODIFIED));
                        break;
                    case "ENTRY_CREATE":
                        this.logger.debug("New Event: Create - " + file.toString());
                        this.queue.offer(new FileChange(file, FileEvent.CREATED));
                        break;
                    case "ENTRY_DELETE":
                        this.logger.debug("New Event: Delete - " + file.toString());
                        this.queue.offer(new FileChange(file, FileEvent.DELETED));
                        break;
                    default:
                        break;

                }
            }

            // Reset the key -- this step is critical if you want to
            // receive further watch events. If the key is no longer valid,
            // the directory is inaccessible so exit the loop.
            boolean valid = key.reset();
            if (!valid) {
                break;
            }
        }
        this.logger.debug("Executor Thread EXIT! : " + Thread.currentThread().getName());

    }

}
