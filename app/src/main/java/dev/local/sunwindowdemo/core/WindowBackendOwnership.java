package dev.local.sunwindowdemo.core;

import java.util.Properties;

/** Select only in system_server; Properties shares strings across module ClassLoaders. */
public final class WindowBackendOwnership {
    private static final String KEY="dev.nativecomplements.window.backend.owner.v1";
    private WindowBackendOwnership() {}
    public static String claim(String product) { return claim(System.getProperties(),product); }
    static String claim(Properties properties,String product) {
        ModuleIdentity.forPackage(product); // Reject unknown identities before mutating shared process state.
        synchronized(properties) {
            String owner=properties.getProperty(KEY);
            if(owner==null) { properties.setProperty(KEY,product); return product; }
            return owner;
        }
    }
}
