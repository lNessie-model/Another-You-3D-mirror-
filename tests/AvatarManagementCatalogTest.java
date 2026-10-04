package com.mirror.bench;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Calls the Activity's real static catalog adapter; no Activity construction or Android stub calls. */
public final class AvatarManagementCatalogTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).resolve(UUID.randomUUID().toString());
        AvatarPackageStore store=AvatarPackageStore.open(root.toFile(),30);
        Method archive=AvatarPackageStoreTest.class.getDeclaredMethod("archive",String.class);archive.setAccessible(true);
        var healthy=store.importZip(new ByteArrayInputStream((byte[])archive.invoke(null,"healthy")));store.activate(healthy.ticket);
        var broken=store.importZip(new ByteArrayInputStream((byte[])archive.invoke(null,"broken-summary")));
        Method read=AvatarManagementActivity.class.getDeclaredMethod("readCatalog",AvatarPackageStore.class);read.setAccessible(true);
        Object good=read.invoke(null,store);
        check(value(good,"catalog")!=null&&value(good,"warning").equals(""),"healthy summaries available");
        Files.writeString(root.resolve("packages").resolve(broken.ticket.packageId).resolve("validation.json"),"{}");
        try{store.readCatalog();throw new AssertionError("bad candidate summary must reproduce original failure");}catch(IOException expected){checks++;}
        Object bad=read.invoke(null,store);
        check(value(bad,"catalog")==null,"corrupt optional summaries explicitly unknown");
        check(!((String)value(bad,"warning")).isEmpty(),"failure is a maintenance warning, not hidden");
        check(store.readCurrent().ticket.packageId.equals(healthy.ticket.packageId),"healthy selected package still validates independently");
        check(Files.readString(root.resolve("packages").resolve(broken.ticket.packageId).resolve("validation.json")).equals("{}"),"catalog adapter does not rewrite corrupt metadata");
        System.out.println("AvatarManagementCatalogTest: "+checks+" checks passed; actual Store ZIP/parser and Activity adapter");
    }
    private static Object value(Object record,String name)throws Exception {Method getter=record.getClass().getDeclaredMethod(name);getter.setAccessible(true);return getter.invoke(record);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
