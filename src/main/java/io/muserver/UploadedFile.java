package io.muserver;

import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.FileUpload;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import static io.muserver.Mutils.notNull;

/**
 * A file uploaded by the user, for example with an <code>&lt;input type=&quot;file&quot; name=&quot;name&quot;&gt;</code>
 * input field in a multipart form.
 */
public interface UploadedFile {

    Path asPath();

    /**
     * Gets a copy of the file. This has been uploaded to the server and saved locally.
     * @return Returns a File object pointing to the uploaded file.
     * @throws IOException If an error while saving file.
     */
    File asFile() throws IOException;

    /**
     * Returns the contents of the file as a String, decoded using UTF-8
     * @return The string contents of the file.
     * @throws IOException If an error reading the file.
     */
    String asString() throws IOException;

    /**
     * Gets the file contents as a byte array.
     * @return The bytes in the file.
     * @throws IOException If any error reading the bytes.
     */
    byte[] asBytes() throws IOException;

    /**
     * Gets the media type of the file as specified by the client, for example <code>image/jpeg</code>
     * @return The media type of the file.
     */
    String contentType();

    /**
     * Gets the original name of the file on the client's computer. Does not include the path.
     * @return A file name with extension
     */
    String filename();

    /**
     * Gets the extension of the file as it was on the client's computer.
     * @return A string such as "jpg" or an empty string if there was no extension.
     */
    String extension();

    /**
     * Saves the file to the specified destination. Parent directories will be created if they do not exist.
     * @param dest The destination to save to.
     * @throws IOException If there is an error saving the file.
     */
    void saveTo(File dest) throws IOException;

    void saveTo(Path dest) throws IOException;

    /**
     * Gets the size of the file.
     * @return The file size.
     */
    long size();

    /**
     * Gets the uploaded file as a stream.
     * @return The file stream.
     * @throws IOException If there is an error reading the file.
     */
    InputStream asStream() throws IOException;
}
class MuUploadedFile implements UploadedFile {
    private final FileUpload fu;
    private File file;

    MuUploadedFile(FileUpload fileUpload) {
        fu = fileUpload;
    }

    @Override
    public Path asPath() {
        return file.toPath();
    }

    @Override
    public File asFile() throws IOException {
        if (file == null) {
            if (fu.isInMemory()) {
                File dir = DiskFileUpload.baseDirectory == null ? null : new File(DiskFileUpload.baseDirectory);
                File tmpFile = File.createTempFile("mufu", ".tmp", dir);
                tmpFile.deleteOnExit();
                fu.renameTo(tmpFile);
                file = tmpFile;
            } else {
                file = fu.getFile();
            }
        }
        return file;
    }

    @Override
    public String asString() throws IOException {
        return fu.getString(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] asBytes() throws IOException {
        return fu.get();
    }

    @Override
    public String contentType() {
        return fu.getContentType();
    }

    @Override
    public String filename() {
        String n = fu.getFilename();
        int i = n.lastIndexOf('/');
        if (i > -1) {
            n = n.substring(i + 1);
        }
        i = n.lastIndexOf('\\');
        if (i > -1) {
            n = n .substring(i + 1);
        }
        return n;
    }

    @Override
    public String extension() {
        String n = filename();
        int i = n.lastIndexOf('.');
        if (i > -1) {
            return n.substring(i + 1);
        }
        return "";
    }

    @Override
    public void saveTo(File dest) throws IOException {
        notNull("dest", dest);
        dest.getParentFile().mkdirs();
        if (!fu.renameTo(dest)) {
            throw new IOException("Failed to save file to " + dest.getCanonicalPath());
        }
        this.file = dest;
    }

    @Override
    public void saveTo(Path dest) throws IOException {
        saveTo(dest.toFile());
    }

    @Override
    public long size() {
        return fu.length();
    }

    @Override
    public InputStream asStream() throws IOException {
        if (fu.isInMemory()) {
            return new ByteArrayInputStream(fu.get());
        } else {
            return new FileInputStream(fu.getFile());
        }
    }
}

class MuUploadedFile2 implements UploadedFile {
    private Path file;
    private boolean shouldDeleteOnClean = true;
    private final String contentType;
    private final String filename;

    MuUploadedFile2(Path file, String contentType, String filename) {
        this.file = file;
        this.contentType = contentType;
        this.filename = filename;
    }

    @Override
    public Path asPath() {
        return file;
    }

    @Override
    public File asFile() throws IOException {
        return file.toFile();
    }

    @Override
    public String asString() throws IOException {
        return new String(asBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] asBytes() throws IOException {
        try (var fis = asStream()) {
            return Mutils.toByteArray(fis, 8192);
        }
    }

    @Override
    public String contentType() {
        return contentType;
    }

    @Override
    public String filename() {
        String n = this.filename;
        int i = n.lastIndexOf('/');
        if (i > -1) {
            n = n.substring(i + 1);
        }
        i = n.lastIndexOf('\\');
        if (i > -1) {
            n = n.substring(i + 1);
        }
        return n;
    }

    @Override
    public String extension() {
        String n = filename();
        int i = n.lastIndexOf('.');
        if (i > -1) {
            return n.substring(i + 1);
        }
        return "";
    }

    @Override
    public void saveTo(File dest) throws IOException {
        notNull("dest", dest);
        saveTo(dest.toPath());
    }

    @Override
    public void saveTo(Path dest) throws IOException {
        notNull("dest", dest);
        Files.createDirectories(dest.getParent());
        var moved = Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
        shouldDeleteOnClean = false;
        this.file = moved;
    }

    @Override
    public long size() {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Error loading file size", e); // todo unchecked?
        }
    }

    @Override
    public InputStream asStream() throws IOException {
        return Files.newInputStream(file, StandardOpenOption.READ);
    }

    void deleteFile() {
        if (shouldDeleteOnClean) {
            tryToDelete(file);
        }
    }

    static void tryToDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            file.toFile().deleteOnExit();
        }
    }
}