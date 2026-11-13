package net.ethug.tscatemfdh.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** {@link Remote} on top of the world's Drive folder. */
public final class DriveRemote implements Remote {
    private final DriveClient drive;

    public DriveRemote(DriveClient drive) {
        this.drive = drive;
    }

    private static File of(DriveClient.DriveFile f) {
        return new File(f.id(), f.name(), f.created(), f.modified());
    }

    @Override
    public List<File> list() throws IOException {
        return drive.list().stream().map(DriveRemote::of).toList();
    }

    @Override
    public File create(String name, Path content) throws IOException {
        return of(drive.create(name, content));
    }

    @Override
    public File update(String id, Path content) throws IOException {
        return of(drive.update(id, content));
    }

    @Override
    public void download(String id, Path dest) throws IOException {
        drive.download(id, dest);
    }

    @Override
    public void delete(String id) throws IOException {
        drive.delete(id);
    }

    @Override
    public byte[] read(String id) throws IOException {
        return drive.downloadBytes(id);
    }
}
