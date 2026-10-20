package android.os;
import java.io.File;
public final class Environment {
    public static final String DIRECTORY_MOVIES = "Movies";
    public static final String MEDIA_MOUNTED = "mounted", MEDIA_MOUNTED_READ_ONLY = "mounted_ro";
    public static String state = MEDIA_MOUNTED;
    public static String getStorageState(File file) { return state; }
    public static boolean isExternalStorageRemovable(File file) { return true; }
}
