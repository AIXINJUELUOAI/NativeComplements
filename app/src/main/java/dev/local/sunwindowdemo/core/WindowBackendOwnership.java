package dev.local.sunwindowdemo.core;

import java.util.Properties;

/** Select only in system_server; Properties shares strings across module ClassLoaders. */
public final class WindowBackendOwnership {
    private static final String KEY="dev.nativecomplements.window.backend.owner.v1";
    private static final String SELECTION="dev.nativecomplements.window.backend.selection.v1";
    private WindowBackendOwnership() {}
    public static String claim(String product) { return claim(System.getProperties(),product); }
    public static String select(String product) { return select(System.getProperties(),product); }
    static String select(Properties properties,String product) {
        if(!product.isEmpty())ModuleIdentity.forPackage(product);
        synchronized(properties) {
            String selected=properties.getProperty(SELECTION);
            if(selected==null){properties.setProperty(SELECTION,product);return product;}
            return selected;
        }
    }
    static String claim(Properties properties,String product) {
        ModuleIdentity.forPackage(product); // Reject unknown identities before mutating shared process state.
        synchronized(properties) {
            String owner=properties.getProperty(KEY);
            if(owner==null) { properties.setProperty(KEY,product); return product; }
            return owner;
        }
    }
}
