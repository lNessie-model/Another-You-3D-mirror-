package com.mirror.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses the production importer on real ZIPs in fresh isolated host stores; no device selection. */
public final class TripoImportPackageCheck {
    public static void main(String[] args) throws Exception {
        if(args.length<2)throw new IllegalArgumentException("Fresh output directory and ZIP paths required");
        Path output=Path.of(args[0]);
        if(Files.exists(output))throw new IllegalArgumentException("Preserve existing evidence");
        Files.createDirectories(output);
        JSONArray rows=new JSONArray();
        for(int i=1;i<args.length;i++) {
            Path archive=Path.of(args[i]);
            AvatarPackageStore store=AvatarPackageStore.open(output.resolve("store-"+i).toFile(),30);
            if(store.readCurrent()!=null)throw new AssertionError("Fresh store must use builtin");
            AvatarPackageStore.LoadedPackage loaded;
            try(var input=Files.newInputStream(archive)){loaded=store.importZip(input);}
            JSONObject manifest=new JSONObject(loaded.manifestJson);
            if(!loaded.ticket.modelSha256.equals(manifest.getString("modelSha256")))
                throw new AssertionError("Imported model digest changed");
            if(!loaded.rig.completeSourceCoverage())throw new AssertionError("Incomplete effective source coverage");
            AvatarPackageStore.LoadedPackage candidate=store.readCandidate();
            if(candidate==null||!candidate.ticket.packageId.equals(loaded.ticket.packageId))
                throw new AssertionError("Validated candidate not published");
            if(candidate.rig==loaded.rig)throw new AssertionError("Mutable rig must not be reused by cache");
            if(store.readCurrent()!=null)throw new AssertionError("Import must not select a candidate");
            rows.put(new JSONObject().put("archive",archive.toAbsolutePath().toString())
                    .put("modelSha256",loaded.ticket.modelSha256).put("vertices",loaded.asset.vertexCount())
                    .put("triangles",loaded.asset.triangleCount()).put("decodedBytes",loaded.asset.decodedBytes())
                    .put("coverageComplete",true).put("currentSelectionPreserved",true)
                    .put("freshMutableRigOnReload",true).put("artistAccepted",false).put("passed",true));
        }
        JSONObject report=new JSONObject().put("passed",true).put("packages",rows)
                .put("scope","Real ZIPs through production importer in isolated host directories; not Android SAF/UI, GPU or artist acceptance");
        Files.writeString(output.resolve("report.json"),report.toString(2));
        System.out.println(report.toString(2));
    }
}
