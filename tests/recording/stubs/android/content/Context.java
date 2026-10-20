package android.content;
import java.io.File;
public class Context {
    private final File root;
    private final File[] external;
    public Context(File root) { this(root, new File[0]); }
    public Context(File root, File[] external) { this.root = root; this.external = external; }
    public Context getApplicationContext() { return this; }
    public File getFilesDir() { return root; }
    public File[] getExternalFilesDirs(String type) { return external; }
}
