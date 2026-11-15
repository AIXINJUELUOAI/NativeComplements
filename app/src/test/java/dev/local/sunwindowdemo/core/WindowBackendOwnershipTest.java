package dev.local.sunwindowdemo.core;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class WindowBackendOwnershipTest {
    @Test public void oneProcessKeepsOneOwnerRegardlessOfRepeatedClaims() {
        Properties shared=new Properties();
        assertEquals(ModuleIdentity.FULL_PACKAGE,WindowBackendOwnership.claim(shared,ModuleIdentity.FULL_PACKAGE));
        assertEquals(ModuleIdentity.FULL_PACKAGE,WindowBackendOwnership.claim(shared,ModuleIdentity.WINDOW_PACKAGE));
        assertEquals(ModuleIdentity.FULL_PACKAGE,WindowBackendOwnership.claim(shared,ModuleIdentity.FULL_PACKAGE));
        assertEquals(ModuleIdentity.WINDOW_PACKAGE,WindowBackendOwnership.claim(new Properties(),ModuleIdentity.WINDOW_PACKAGE));
    }
    @Test public void simultaneousProductsObserveTheSameWinner() throws Exception {
        Properties shared=new Properties();CountDownLatch start=new CountDownLatch(1);
        AtomicReference<String> full=new AtomicReference<>(),window=new AtomicReference<>();
        Thread a=new Thread(()->{try{start.await();full.set(WindowBackendOwnership.claim(shared,ModuleIdentity.FULL_PACKAGE));}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        Thread b=new Thread(()->{try{start.await();window.set(WindowBackendOwnership.claim(shared,ModuleIdentity.WINDOW_PACKAGE));}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        a.start();b.start();start.countDown();a.join();b.join();
        assertNotNull(full.get());assertEquals(full.get(),window.get());
    }
    @Test public void separateModuleClassLoadersUseTheSameProcessOwner() throws Exception {
        Properties shared=System.getProperties();String key="dev.nativecomplements.window.backend.owner.v1";
        synchronized(shared) {
            String original=shared.getProperty(key);shared.remove(key);
            try {
                var location=WindowBackendOwnership.class.getProtectionDomain().getCodeSource().getLocation();
                try(var a=new java.net.URLClassLoader(new java.net.URL[]{location},ClassLoader.getSystemClassLoader().getParent());
                    var b=new java.net.URLClassLoader(new java.net.URL[]{location},ClassLoader.getSystemClassLoader().getParent())) {
                    Class<?> first=Class.forName(WindowBackendOwnership.class.getName(),true,a);
                    Class<?> second=Class.forName(WindowBackendOwnership.class.getName(),true,b);
                    assertNotSame(first,second);
                    assertEquals(ModuleIdentity.FULL_PACKAGE,first.getMethod("claim",String.class).invoke(null,ModuleIdentity.FULL_PACKAGE));
                    assertEquals(ModuleIdentity.FULL_PACKAGE,second.getMethod("claim",String.class).invoke(null,ModuleIdentity.WINDOW_PACKAGE));
                }
            } finally { if(original==null)shared.remove(key);else shared.setProperty(key,original); }
        }
    }
    @Test public void explicitSelectionIsFrozenAndDoesNotClaimOrTransferAnOwner() {
        Properties shared=new Properties();
        assertEquals(ModuleIdentity.WINDOW_PACKAGE,WindowBackendOwnership.select(shared,ModuleIdentity.WINDOW_PACKAGE));
        assertEquals(ModuleIdentity.WINDOW_PACKAGE,WindowBackendOwnership.select(shared,ModuleIdentity.FULL_PACKAGE));
        assertEquals(ModuleIdentity.WINDOW_PACKAGE,WindowBackendOwnership.claim(shared,ModuleIdentity.WINDOW_PACKAGE));
        assertEquals(ModuleIdentity.WINDOW_PACKAGE,WindowBackendOwnership.claim(shared,ModuleIdentity.FULL_PACKAGE));
        Properties automatic=new Properties();
        assertEquals("",WindowBackendOwnership.select(automatic,""));
        assertEquals("",WindowBackendOwnership.select(automatic,ModuleIdentity.WINDOW_PACKAGE));
        assertEquals(ModuleIdentity.FULL_PACKAGE,WindowBackendOwnership.claim(automatic,ModuleIdentity.FULL_PACKAGE));
    }
    @Test public void unknownProductCannotAcquireOwnership() {
        Properties shared=new Properties();
        try{WindowBackendOwnership.claim(shared,"other.module");fail();}catch(IllegalArgumentException expected){}
        assertTrue(shared.isEmpty());
    }
}
